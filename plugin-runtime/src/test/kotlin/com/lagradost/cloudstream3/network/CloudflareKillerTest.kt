package com.lagradost.cloudstream3.network

import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class CloudflareKillerTest {

    private val client = OkHttpClient()

    @Test
    fun testParseCookieMap() {
        val cookieString = "cf_clearance=abc123xyz; __cf_bm=bm456; session=active"
        val parsed = CloudflareKiller.parseCookieMap(cookieString)

        assertEquals("abc123xyz", parsed["cf_clearance"])
        assertEquals("bm456", parsed["__cf_bm"])
        assertEquals("active", parsed["session"])

        // Empty and blank strings
        assertTrue(CloudflareKiller.parseCookieMap("").isEmpty())
        assertTrue(CloudflareKiller.parseCookieMap("   ;  ; = ; ").isEmpty())

        // Single cookie
        val single = CloudflareKiller.parseCookieMap("cf_clearance=testValue")
        assertEquals(mapOf("cf_clearance" to "testValue"), single)
    }

    @Test
    fun testNormalizeHeadersAndGetCookieHeaders() {
        val killer = CloudflareKiller()
        val url = "https://example.com/test"

        // Without saved cookies
        val headersWithoutCookies = killer.getCookieHeaders(url)
        assertNotNull(headersWithoutCookies["user-agent"])
        assertNull(headersWithoutCookies["Cookie"])

        // With saved cookies
        killer.savedCookies["example.com"] = mapOf("cf_clearance" to "cf_token_val")
        val headersWithCookies = killer.getCookieHeaders(url)
        assertEquals("cf_clearance=cf_token_val;", headersWithCookies["Cookie"])

        // Test normalizeHeaders helper directly
        val normalized = killer.normalizeHeaders(
            headers = mapOf("X-Custom" to "CustomValue"),
            cookie = mapOf("session_id" to "12345")
        )
        assertEquals("CustomValue", normalized["X-Custom"])
        assertEquals("session_id=12345;", normalized["Cookie"])
        assertNotNull(normalized["user-agent"])
    }

    @Test
    fun testNonCloudflareResponsePassesThrough() {
        val killer = CloudflareKiller()
        val request = Request.Builder().url("https://normal-server.com/api").build()

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Server", "nginx")
                    .body("{\"status\":\"ok\"}".toResponseBody())
                    .build()
            }
            override fun connection() = null
            override fun call(): Call = client.newCall(request)
            override fun connectTimeoutMillis() = 10_000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 10_000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 10_000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val response = killer.intercept(mockChain)
        assertEquals(200, response.code)
        assertEquals("OK", response.message)
    }

    @Test
    fun testNonCloudflareErrorPassesThrough() {
        val killer = CloudflareKiller()
        val request = Request.Builder().url("https://normal-server.com/forbidden").build()

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Server", "apache")
                    .body("Forbidden".toResponseBody())
                    .build()
            }
            override fun connection() = null
            override fun call(): Call = client.newCall(request)
            override fun connectTimeoutMillis() = 10_000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 10_000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 10_000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        val response = killer.intercept(mockChain)
        assertEquals(403, response.code)
        assertEquals("Forbidden", response.message)
    }

    @Test
    fun testCloudflareChallengeWithoutDesktopAlternativeThrowsIOException() {
        val killer = CloudflareKiller()
        val request = Request.Builder().url("https://cf-protected.com/data").build()

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(403)
                    .message("Forbidden")
                    .header("Server", "cloudflare")
                    .body("Just a moment...".toResponseBody())
                    .build()
            }
            override fun connection() = null
            override fun call(): Call = client.newCall(request)
            override fun connectTimeoutMillis() = 10_000
            override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun readTimeoutMillis() = 10_000
            override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
            override fun writeTimeoutMillis() = 10_000
            override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        }

        // When Cloudflare challenge cannot be solved without WebView/headless Chromium,
        // it must throw IOException and not pretend success (Zero-Fake-Success)
        assertThrows(IOException::class.java) {
            killer.intercept(mockChain)
        }
    }
}
