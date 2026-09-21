// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.network

import android.util.Log
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI

@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }

        private fun WebViewResolver.Companion.getWebViewUserAgent(): String? = webViewUserAgent
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    init {
        // Needs to clear cookies between sessions to generate new cookies.
        savedCookies.clear()
    }

    /**
     * Normalizes headers and merges cookie map into standard Cookie header format.
     */
    fun normalizeHeaders(
        headers: Map<String, String>,
        cookie: Map<String, String>
    ): Headers {
        return getHeaders(headers, cookie)
    }

    private fun getHeaders(
        headers: Map<String, String>,
        cookie: Map<String, String>
    ): Headers {
        val cookieMap =
            if (cookie.isNotEmpty()) mapOf(
                "Cookie" to cookie.entries.joinToString(" ") {
                    "${it.key}=${it.value};"
                }
            ) else emptyMap()
        val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
        return tempHeaders.toHeaders()
    }

    /**
     * Gets the headers with cookies, webview user agent included!
     */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }
            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    @PlatformQuarantine(
        reason = "Android CookieManager is not available on desktop; headless Chromium/curl-impersonate gerekiyor",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt:79",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    private fun getWebViewCookie(url: String): String? {
        val host = safe { URI(url).host } ?: return null
        return savedCookies[host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * Returns true if the cf cookies were successfully fetched from the CookieManager
     * Also saves the cookies.
     */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        // Not sure if this takes expiration into account
        val hostCookies = savedCookies[request.url.host]
        if (hostCookies != null && hostCookies.containsKey("cf_clearance")) {
            return true
        }
        val cookieHeader = request.header("Cookie")
        if (cookieHeader != null && cookieHeader.contains("cf_clearance")) {
            val parsed = parseCookieMap(cookieHeader)
            savedCookies[request.url.host] = parsed
            return true
        }
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers =
            getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    @PlatformQuarantine(
        reason = "WebView challenge solving requires desktop alternative; headless Chromium/curl-impersonate gerekiyor",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/network/CloudflareKiller.kt:112",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        // If no cookies then try to get them
        // Remove this if statement if cookies expire
        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
            try {
                WebViewResolver(
                    // Never exit based on url
                    Regex(".^"),
                    // Cloudflare needs default user agent
                    userAgent = null,
                    // Cannot use okhttp (i think intercepting cookies fails which causes the issues)
                    useOkhttp = false,
                    // Match every url for the requestCallBack
                    additionalUrls = listOf(Regex("."))
                ).resolveUsingWebView(
                    url
                ) {
                    trySolveWithSavedCookies(request)
                }
            } catch (e: NotImplementedError) {
                Log.e(TAG, "WebViewResolver.resolveUsingWebView is not yet implemented on JVM (requires headless Chromium/curl-impersonate): ${e.message}", e)
                throw IOException("Cloudflare challenge cannot be solved on desktop without headless browser: ${request.url}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed resolving cloudflare challenge via webview: ${e.message}", e)
                throw IOException("Cloudflare challenge failed: ${request.url}", e)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}
