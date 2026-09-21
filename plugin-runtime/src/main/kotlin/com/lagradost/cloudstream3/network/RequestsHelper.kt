// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/network/RequestsHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.network

import android.content.Context
import android.content.DesktopContextProvider
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing*/
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(context, ignoreSSL)
}

/** Headless desktop overload */
fun Requests.initClient(ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(DesktopContextProvider.context, ignoreSSL)
}

// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

/** Headless desktop overload */
fun buildDefaultClient(): OkHttpClient {
    return buildDefaultClient(DesktopContextProvider.context, false)
}

/** Only use ignoreSSL if you know what you are doing*/
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    safe { Security.insertProviderAt(Conscrypt.newProvider(), 1) }

    val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
    val dnsKey = context.getString(R.string.dns_key)
    val dns = settingsManager.getInt(dnsKey, 0).let {
        if (it == 0) DesktopDataStore.getKey<Int>(dnsKey) ?: 0 else it
    }
    val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(context.cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
            }
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}

/**
 * Serializes a cookie map to HTTP 'Cookie' header format: key1=value1; key2=value2;
 */
fun toCookieString(cookies: Map<String, String>): String =
    cookies.entries.joinToString(" ") { "${it.key}=${it.value};" }

/**
 * Parses a semicolon-separated cookie header string into a key-value map.
 */
fun parseCookieMap(cookieHeader: String): Map<String, String> {
    if (cookieHeader.isBlank()) return emptyMap()
    return cookieHeader.split(";")
        .mapNotNull {
            val trimmed = it.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = trimmed.split("=", limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }
        .toMap()
}
