package com.lagradost.cloudstream3.network

import android.content.DesktopContextProvider
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestsHelperTest {

    @Test
    fun `buildDefaultClient creates client with 50MiB cache and redirects`() {
        val client = buildDefaultClient(DesktopContextProvider.context, ignoreSSL = false)
        assertNotNull(client)
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
        assertNotNull(client.cache)
        assertEquals(50L * 1024L * 1024L, client.cache?.maxSize())
    }

    @Test
    fun `buildDefaultClient headless overload succeeds`() {
        val client = buildDefaultClient()
        assertNotNull(client)
        assertNotNull(client.cache)
    }

    @Test
    fun `Requests initClient initializes baseClient`() {
        val requests = Requests()
        requests.initClient(DesktopContextProvider.context)
        assertNotNull(requests.baseClient)
        assertEquals(50L * 1024L * 1024L, requests.baseClient.cache?.maxSize())

        val headlessRequests = Requests()
        headlessRequests.initClient()
        assertNotNull(headlessRequests.baseClient)
    }

    @Test
    fun `getHeaders includes default user-agent and merges custom headers and cookies`() {
        val headers = getHeaders(
            headers = mapOf("Accept" to "application/json", "X-Custom" to "test-val"),
            cookie = mapOf("session" to "abc123", "theme" to "dark")
        )

        assertEquals(USER_AGENT, headers["user-agent"])
        assertEquals("application/json", headers["Accept"])
        assertEquals("test-val", headers["X-Custom"])
        assertEquals("session=abc123; theme=dark;", headers["Cookie"])
    }

    @Test
    fun `getHeaders allows custom user-agent override`() {
        val customUa = "CustomBrowser/1.0"
        val headers = getHeaders(
            headers = mapOf("user-agent" to customUa),
            cookie = emptyMap()
        )
        assertEquals(customUa, headers["user-agent"])
    }

    @Test
    fun `toCookieString and parseCookieMap serialize and deserialize accurately`() {
        val cookies = mapOf("cf_clearance" to "token_123", "logged_in" to "true")
        val cookieHeader = toCookieString(cookies)
        assertEquals("cf_clearance=token_123; logged_in=true;", cookieHeader)

        val parsed = parseCookieMap("cf_clearance=token_123; logged_in=true; ")
        assertEquals(cookies, parsed)

        assertTrue(parseCookieMap("").isEmpty())
        assertTrue(parseCookieMap("   ").isEmpty())
    }

    @Test
    fun `DoH provider extension functions configure builder DNS without throwing`() {
        val googleClient = OkHttpClient.Builder().addGoogleDns().build()
        assertNotNull(googleClient.dns)

        val cfClient = OkHttpClient.Builder().addCloudFlareDns().build()
        assertNotNull(cfClient.dns)

        val adguardClient = OkHttpClient.Builder().addAdGuardDns().build()
        assertNotNull(adguardClient.dns)

        val quad9Client = OkHttpClient.Builder().addQuad9Dns().build()
        assertNotNull(quad9Client.dns)
    }
}
