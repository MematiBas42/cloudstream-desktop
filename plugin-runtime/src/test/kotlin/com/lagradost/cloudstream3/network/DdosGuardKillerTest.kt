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

class DdosGuardKillerTest {

    private val client = OkHttpClient()

    @Test
    fun testNon403ResponsePassesThrough() {
        val killer = DdosGuardKiller(alwaysBypass = false)
        val request = Request.Builder().url("https://normal-server.com/api").build()

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
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
    fun testNon403ErrorPassesThroughWhenAlwaysBypassIsFalse() {
        val killer = DdosGuardKiller(alwaysBypass = false)
        val request = Request.Builder().url("https://normal-server.com/notfound").build()

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("Not Found".toResponseBody())
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
        assertEquals(404, response.code)
        assertEquals("Not Found", response.message)
    }

    @Test
    fun testSavedCookiesMapCachesAndRetrieves() {
        val killer = DdosGuardKiller(alwaysBypass = false)
        val host = "erairaws.com"
        val cookies = mapOf("__ddg1_" to "cookie_val_1", "__ddg2_" to "cookie_val_2")

        killer.savedCookiesMap[host] = cookies

        assertEquals(cookies, killer.savedCookiesMap[host])
        assertEquals("cookie_val_1", killer.savedCookiesMap[host]?.get("__ddg1_"))
        assertEquals("cookie_val_2", killer.savedCookiesMap[host]?.get("__ddg2_"))
    }
}
