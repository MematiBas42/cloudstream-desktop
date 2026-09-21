package com.lagradost.cloudstream3.desktop.ui.player

import com.lagradost.cloudstream3.app
import com.lagradost.common.logging.AppLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Embedded local HTTP streaming proxy for Compose Desktop media playback.
 * Intercepts local player requests and injects mandatory upstream headers (e.g. Referer, User-Agent, Cookies)
 * before streaming bytes directly to native decoders (GStreamer / MediaFoundation), preventing HTTP 400/403 rejections.
 */
object LocalStreamProxy {
    private var server: HttpServer? = null
    val port: Int get() = server?.address?.port ?: 0

    data class StreamTarget(
        val url: String,
        val headers: Map<String, String>,
    )

    private val targetStore = ConcurrentHashMap<String, StreamTarget>()

    @Synchronized
    fun start() {
        if (server != null) return
        try {
            val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            s.createContext("/stream") { exchange ->
                handleStream(exchange)
            }
            s.executor = Executors.newCachedThreadPool { r ->
                Thread(r, "LocalStreamProxy-Worker").apply { isDaemon = true }
            }
            s.start()
            server = s
            AppLogger.i("LocalStreamProxy", "LocalStreamProxy running on port ${s.address.port}")
        } catch (t: Throwable) {
            AppLogger.e("LocalStreamProxy", "Failed to start LocalStreamProxy: ${t.message}")
        }
    }

    /**
     * Registers an extractor stream URL along with its required headers and returns a loopback streaming URL.
     */
    fun registerStream(url: String, headers: Map<String, String>): String {
        start()
        val token = UUID.randomUUID().toString()
        targetStore[token] = StreamTarget(url, headers)
        return "http://127.0.0.1:$port/stream?id=$token"
    }

    private fun handleStream(exchange: HttpExchange) {
        val query = exchange.requestURI.query ?: ""
        val token = query.split("&").firstOrNull { it.startsWith("id=") }?.substringAfter("id=")
        val target = if (token != null) targetStore[token] else null

        if (target == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }

        val method = exchange.requestMethod
        val rangeHeader = exchange.requestHeaders.getFirst("Range")

        val reqBuilder = Request.Builder()
            .url(target.url)

        // Inject headers required by web extractors (Referer, User-Agent, etc.)
        for ((k, v) in target.headers) {
            reqBuilder.header(k, v)
        }

        // Forward Range requests from player for video seeking
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        if (method.equals("HEAD", ignoreCase = true)) {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        try {
            val response: Response = app.baseClient.newCall(reqBuilder.build()).execute()
            val code = response.code
            val body = response.body

            val responseHeaders = exchange.responseHeaders
            response.header("Content-Type")?.let { responseHeaders.set("Content-Type", it) }
            response.header("Accept-Ranges")?.let { responseHeaders.set("Accept-Ranges", it) }
            response.header("Content-Range")?.let { responseHeaders.set("Content-Range", it) }

            val contentLength = body?.contentLength() ?: -1L

            val responseLength = when {
                method.equals("HEAD", ignoreCase = true) -> -1L
                contentLength > 0L -> contentLength
                else -> 0L
            }

            exchange.sendResponseHeaders(code, responseLength)

            if (!method.equals("HEAD", ignoreCase = true) && body != null) {
                body.byteStream().use { input ->
                    exchange.responseBody.use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            }
            response.close()
        } catch (t: Throwable) {
            AppLogger.e("LocalStreamProxy", "Stream proxy error: ${t.message}")
            try {
                exchange.sendResponseHeaders(502, -1)
            } catch (t2: Throwable) {
                AppLogger.d("LocalStreamProxy", "Failed to send 502 response: ${t2.message}")
            }
        } finally {
            exchange.close()
        }
    }
}
