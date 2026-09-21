package com.lagradost.cloudstream3.desktop.observability

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.logging.DiagnosticOrgan
import com.lagradost.common.logging.SystemDiagnostics
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Global OkHttp interceptor that automatically traces every network request and response
 * across all plugins, scrapers, and metadata providers without modifying any plugin code.
 */
class NetworkObservabilityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val urlStr = request.url.toString()
        val method = request.method
        val startNs = System.nanoTime()

        SystemDiagnostics.record(
            organ = DiagnosticOrgan.NETWORK,
            level = AppLogger.Level.DEBUG,
            tag = "OkHttp",
            message = "--> $method $urlStr",
            details = "Headers: ${request.headers.names().joinToString { "$it=${request.header(it)}" }}",
        )

        return try {
            val response = chain.proceed(request)
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            val code = response.code
            val level = if (code >= 400) AppLogger.Level.WARN else AppLogger.Level.DEBUG
            val contentLength = response.body?.contentLength() ?: -1L
            val sizeDesc = if (contentLength >= 0) "$contentLength B" else "stream"

            SystemDiagnostics.record(
                organ = DiagnosticOrgan.NETWORK,
                level = level,
                tag = "OkHttp",
                message = "<-- $code $method $urlStr (${durationMs}ms, $sizeDesc)",
                details = "Content-Type: ${response.header("Content-Type") ?: "unknown"}",
            )
            response
        } catch (e: Throwable) {
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            SystemDiagnostics.record(
                organ = DiagnosticOrgan.NETWORK,
                level = AppLogger.Level.ERROR,
                tag = "OkHttp",
                message = "<-- FAILED $method $urlStr (${durationMs}ms)",
                error = "${e::class.simpleName}: ${e.message}",
            )
            throw e
        }
    }
}
