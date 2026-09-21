package com.lagradost.player.impl.proxy

import com.lagradost.cloudstream3.app
import com.lagradost.common.logging.AppLogger
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.header
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.UUID

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (t: Throwable) {
            AppLogger.d("LocalStreamProxy: Call cancellation ignored: ${t.message}")
        }
    }
}

object LocalStreamProxy {
    private fun ApplicationCall.corsHeaders() {
        val origin = request.headers["Origin"] ?: "*"
        response.header("Access-Control-Allow-Origin", origin)
        response.header("Access-Control-Allow-Credentials", "true")
        response.header("Access-Control-Allow-Methods", "GET, OPTIONS")
        response.header("Access-Control-Allow-Headers", "Content-Type, Range, Authorization, Origin")
    }

    private val ProxyIoDispatcher = kotlinx.coroutines.Dispatchers.IO

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    var port: Int = 0
        private set

    data class ProxySession(
        val headers: Map<String, String>,
        val drmKey: ByteArray? = null,
        val kidHex: String? = null,
    ) {
        /** Encrypted DASH init segments, required by Bento4/decryptors to decrypt standalone fragments. */
        val fragmentInfo = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProxySession) return false
            return headers == other.headers && drmKey.contentEquals(other.drmKey) && kidHex == other.kidHex
        }

        override fun hashCode(): Int {
            return 31 * (31 * headers.hashCode() + (drmKey?.contentHashCode() ?: 0)) + (kidHex?.hashCode() ?: 0)
        }
    }

    private val sessions = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, ProxySession>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ProxySession>): Boolean {
                return size > 100
            }
        }
    )

    private val proxyClient by lazy {
        app.baseClient.newBuilder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 1000
                    maxRequestsPerHost = 500
                },
            )
            .build()
    }

    fun start() {
        if (server != null) return
        server = embeddedServer(Netty, port = 0, host = "0.0.0.0") {
            routing {
                options("/proxy") {
                    call.corsHeaders()
                    call.respond(HttpStatusCode.OK)
                }
                get("/proxy") {
                    handleRequest(call)
                }
                options("/dash/{s}/{b}/{path...}") {
                    call.corsHeaders()
                    call.respond(HttpStatusCode.OK)
                }
                get("/dash/{s}/{b}/{path...}") {
                    handleDashResource(call)
                }
            }
        }.start(wait = false)

        port = kotlinx.coroutines.runBlocking {
            server?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: 0
        }
        AppLogger.i("LocalStreamProxy started on port $port")
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        sessions.clear()
    }

    fun registerSession(headers: Map<String, String>, drmKey: ByteArray? = null, kidHex: String? = null): String {
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = ProxySession(headers, drmKey, kidHex)
        return sessionId
    }

    fun getSession(sessionId: String): ProxySession? = sessions[sessionId]

    fun buildProxyUrl(sessionId: String, url: String): String {
        val encodedUrl = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
        return "http://localhost:$port/proxy?s=$sessionId&u=$encodedUrl"
    }

    private fun buildDashBaseUrl(sessionId: String, baseUrl: String): String {
        val encodedBase = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(baseUrl.toByteArray(Charsets.UTF_8))
        return "http://localhost:$port/dash/$sessionId/$encodedBase/"
    }

    private suspend fun handleDashResource(call: ApplicationCall) {
        val sessionId = call.parameters["s"] ?: return call.respond(HttpStatusCode.NotFound)
        val encodedBase = call.parameters["b"] ?: return call.respond(HttpStatusCode.NotFound)
        val session = sessions[sessionId] ?: return call.respond(HttpStatusCode.NotFound)
        val base = try {
            String(Base64.getUrlDecoder().decode(encodedBase), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return call.respond(HttpStatusCode.BadRequest)
        }
        val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
        val target = URI(base).resolve(path).toString() +
            call.request.queryString().takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        proxyRemoteResource(call, target, session)
    }

    private suspend fun proxyRemoteResource(
        call: ApplicationCall,
        url: String,
        session: ProxySession,
    ) {
        call.corsHeaders()
        val headers = session.headers.toMutableMap().apply {
            keys.filter { it.equals("Accept-Encoding", true) }.forEach { remove(it) }
            call.request.headers["Range"]?.let { put("Range", it) }
        }
        val request = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        val response = try {
            proxyClient.newCall(request).await()
        } catch (e: Exception) {
            AppLogger.e("DASH proxy request failed: $url: ${e.message}")
            return call.respond(HttpStatusCode.BadGateway)
        }
        response.use {
            if (!it.isSuccessful) return call.respond(HttpStatusCode.fromValue(it.code))
            it.header("Content-Range")?.let { value -> call.response.header("Content-Range", value) }
            it.header("Accept-Ranges")?.let { value -> call.response.header("Accept-Ranges", value) }
            val bytes = withContext(ProxyIoDispatcher) { it.body?.bytes() ?: ByteArray(0) }
            val outputBytes = decryptSegmentIfNeeded(bytes, session, it.header("Content-Type").orEmpty(), url)
            val type = try {
                ContentType.parse(it.header("Content-Type") ?: "application/octet-stream")
            } catch (_: Exception) {
                ContentType.Application.OctetStream
            }
            call.respondBytes(outputBytes, type, HttpStatusCode.fromValue(it.code))
        }
    }

    private suspend fun handleRequest(call: ApplicationCall) {
        try {
            call.corsHeaders()
            val sessionId = call.request.queryParameters["s"]
            val encodedUrl = call.request.queryParameters["u"]

            if (sessionId == null || encodedUrl == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val url = String(Base64.getUrlDecoder().decode(encodedUrl), Charsets.UTF_8)
            val session = sessions[sessionId]

            if (session == null) {
                call.respond(HttpStatusCode.NotFound)
                return
            }

            val mergedHeaders = session.headers.toMutableMap()

            val keysToRemove = mergedHeaders.keys.filter { it.equals("Accept-Encoding", ignoreCase = true) }
            keysToRemove.forEach { mergedHeaders.remove(it) }

            call.request.headers["Range"]?.let {
                mergedHeaders["Range"] = it
            }

            val requestBuilder = okhttp3.Request.Builder().url(url)
            mergedHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = try {
                proxyClient.newCall(requestBuilder.build()).await()
            } catch (e: Exception) {
                AppLogger.e("LocalStreamProxy Request Failed (Async)! URL: $url Error: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError)
                return
            }

            if (!response.isSuccessful) {
                AppLogger.e("LocalStreamProxy Request Failed! Code: ${response.code} URL: $url")
                val errorBody = response.body?.string() ?: response.message
                call.respondText(errorBody, ContentType.Text.Plain, HttpStatusCode.fromValue(response.code))
                return
            }

            val contentTypeStr = response.header("Content-Type") ?: "application/octet-stream"
            val isM3u8 = url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".m3u", ignoreCase = true) ||
                contentTypeStr.contains("mpegurl", ignoreCase = true) ||
                contentTypeStr.contains("x-mpegURL", ignoreCase = true) ||
                withContext(ProxyIoDispatcher) {
                    try {
                        val s = response.body?.source()
                        s != null && s.request(7) && s.peek().readUtf8(7) == "#EXTM3U"
                    } catch (_: Exception) {
                        false
                    }
                }
            val isMpd = url.contains(".mpd", ignoreCase = true) ||
                contentTypeStr.contains("dash+xml", ignoreCase = true)

            if (isM3u8) {
                val m3u8Content = withContext(ProxyIoDispatcher) {
                    response.body?.string() ?: ""
                }
                val finalUrl = response.request.url.toString()

                val rewritten = rewriteM3u8(m3u8Content, finalUrl, sessionId)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)

                call.response.header("Content-Type", "application/vnd.apple.mpegurl")
                call.respondBytes(bytes, status = HttpStatusCode.OK)
            } else if (isMpd) {
                val mpd = withContext(ProxyIoDispatcher) { response.body?.string() ?: "" }
                val rewritten = rewriteMpd(mpd, response.request.url.toString(), sessionId)
                call.respondBytes(
                    rewritten.toByteArray(Charsets.UTF_8),
                    ContentType.parse("application/dash+xml"),
                    HttpStatusCode.OK,
                )
            } else {
                val drmKey = session.drmKey
                val isEncryptedSegment = drmKey != null &&
                    (contentTypeStr.contains("mp4", ignoreCase = true) ||
                     contentTypeStr.contains("octet-stream", ignoreCase = true) ||
                     contentTypeStr.contains("binary", ignoreCase = true))

                if (isEncryptedSegment) {
                    val rawBytes = withContext(ProxyIoDispatcher) {
                        response.body?.bytes() ?: ByteArray(0)
                    }
                    val finalBytes = decryptSegmentIfNeeded(rawBytes, session, contentTypeStr, url)
                    val parsedContentType = try {
                        ContentType.parse(contentTypeStr)
                    } catch (_: Exception) {
                        ContentType.Application.OctetStream
                    }
                    call.respondBytes(finalBytes, parsedContentType, HttpStatusCode.fromValue(response.code))
                } else {
                    response.header("Content-Range")?.let { call.response.header("Content-Range", it) }
                    response.header("Accept-Ranges")?.let { call.response.header("Accept-Ranges", it) }

                    val cl = response.body?.contentLength() ?: -1L
                    val contentLengthParam = if (cl >= 0) cl else null

                    val parsedContentType = try {
                        ContentType.parse(contentTypeStr)
                    } catch (_: Exception) {
                        ContentType.Application.OctetStream
                    }

                    call.respondBytesWriter(
                        contentType = parsedContentType,
                        status = HttpStatusCode.fromValue(response.code),
                        contentLength = contentLengthParam,
                    ) {
                        val streamSource = response.body?.source() ?: return@respondBytesWriter
                        val buffer = ByteArray(16384)
                        try {
                            while (!isClosedForWrite) {
                                val bytesRead = withContext(ProxyIoDispatcher) {
                                    streamSource.read(buffer)
                                }
                                if (bytesRead == -1) break
                                writeFully(buffer, 0, bytesRead)
                                flush()
                            }
                        } catch (_: Exception) {
                            // Client disconnected, e.g. user seeking
                        } finally {
                            withContext(ProxyIoDispatcher) {
                                response.body?.close()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("LocalStreamProxy error: ${e.message}")
            try {
                call.respond(HttpStatusCode.InternalServerError)
            } catch (ignored: Exception) {
                AppLogger.d("LocalStreamProxy: Failed to respond with 500: ${ignored.message}")
            }
        }
    }

    private fun rewriteM3u8(content: String, baseUrl: String, sessionId: String): String {
        val lines = content.split("\n")
        val rewritten = buildString {
            for (line in lines) {
                val trim = line.trim()
                if (trim.isEmpty()) continue
                if (trim.startsWith("#")) {
                    if (trim.contains("URI=\"")) {
                        val uriRegex = Regex("""URI="([^"]+)"""")
                        val newLine = trim.replace(uriRegex) { result ->
                            val uri = result.groupValues[1]
                            val absolute = resolveUrl(baseUrl, uri)
                            "URI=\"${buildProxyUrl(sessionId, absolute)}\""
                        }
                        appendLine(newLine)
                    } else {
                        appendLine(trim)
                    }
                } else {
                    val absolute = resolveUrl(baseUrl, trim)
                    appendLine(buildProxyUrl(sessionId, absolute))
                }
            }
        }
        return rewritten
    }

    private fun rewriteMpd(content: String, manifestUrl: String, sessionId: String): String {
        val manifestBase = URI(manifestUrl).resolve(".").toString()
        val baseTag = Regex("""<BaseURL([^>]*)>([^<]+)</BaseURL>""", RegexOption.IGNORE_CASE)
        val withBases = if (baseTag.containsMatchIn(content)) {
            content.replace(baseTag) { match ->
                val absolute = resolveUrl(manifestUrl, match.groupValues[2].trim())
                "<BaseURL${match.groupValues[1]}>${buildDashBaseUrl(sessionId, absolute)}</BaseURL>"
            }
        } else {
            content.replaceFirst(
                Regex("""<MPD([^>]*)>""", RegexOption.IGNORE_CASE),
                "$0<BaseURL>${buildDashBaseUrl(sessionId, manifestBase)}</BaseURL>",
            )
        }
        return withBases
    }

    private var mp4DecryptPath: String? = null
    private var mp4DecryptSearched = false

    private fun findMp4Decrypt(): String? {
        if (mp4DecryptSearched) return mp4DecryptPath
        mp4DecryptSearched = true
        val candidates = listOf(
            "/usr/local/sbin/mp4decrypt",
            "/usr/bin/mp4decrypt",
            "/usr/local/bin/mp4decrypt",
        ) + (System.getenv("PATH")?.split(File.pathSeparator)?.map { "$it/mp4decrypt" } ?: emptyList())
        for (path in candidates) {
            val f = File(path)
            if (f.isFile && f.canExecute()) {
                mp4DecryptPath = f.absolutePath
                AppLogger.i("LocalStreamProxy: found mp4decrypt at ${f.absolutePath}")
                return mp4DecryptPath
            }
        }
        AppLogger.w("LocalStreamProxy: mp4decrypt not found — CENC decryption will fall back")
        return null
    }

    private suspend fun decryptWithMp4Decrypt(
        data: ByteArray,
        kidHex: String,
        keyBytes: ByteArray,
        fragmentsInfo: ByteArray? = null,
    ): ByteArray {
        val executable = findMp4Decrypt()
            ?: return CencDecryptor(data, keyBytes).decrypt()

        val keyHex = keyBytes.joinToString("") { "%02x".format(it) }
        val inputFile = File.createTempFile("cenc_in_", ".m4s").apply { deleteOnExit() }
        val outputFile = File.createTempFile("cenc_out_", ".m4s").apply { deleteOnExit() }
        val fragmentsInfoFile = fragmentsInfo?.let {
            File.createTempFile("cenc_init_", ".mp4").apply { deleteOnExit() }
        }

        return withContext(ProxyIoDispatcher) {
            try {
                inputFile.writeBytes(data)
                fragmentsInfo?.let { fragmentsInfoFile?.writeBytes(it) }
                val arguments = mutableListOf(executable, "--key", "$kidHex:$keyHex")
                fragmentsInfoFile?.let { arguments += listOf("--fragments-info", it.absolutePath) }
                arguments += listOf(inputFile.absolutePath, outputFile.absolutePath)
                val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
                process.waitFor()
                if (process.exitValue() != 0) {
                    val err = process.inputStream.bufferedReader().readText()
                    AppLogger.w("mp4decrypt failed (exit ${process.exitValue()}): $err")
                    CencDecryptor(data, keyBytes).decrypt()
                } else {
                    if (outputFile.isFile && outputFile.length() > 0) {
                        outputFile.readBytes()
                    } else {
                        AppLogger.w("mp4decrypt produced empty output, falling back")
                        CencDecryptor(data, keyBytes).decrypt()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("mp4decrypt error: ${e.message}, falling back")
                CencDecryptor(data, keyBytes).decrypt()
            } finally {
                inputFile.delete()
                outputFile.delete()
                fragmentsInfoFile?.delete()
            }
        }
    }

    private suspend fun decryptSegmentIfNeeded(
        data: ByteArray,
        session: ProxySession,
        contentType: String,
        sourceUrl: String,
    ): ByteArray {
        val key = session.drmKey ?: return data
        val canBeMp4 = contentType.contains("mp4", true) ||
            contentType.contains("octet-stream", true) || contentType.isBlank()
        if (!canBeMp4) return data
        val hasMoov = containsMp4Box(data, "moov")
        val hasMoof = containsMp4Box(data, "moof")
        if (!hasMoov && !hasMoof) return data

        val fragmentKey = dashFragmentKey(sourceUrl)
        if (hasMoov) session.fragmentInfo[fragmentKey] = data

        return if (session.kidHex != null) {
            val fragmentsInfo = if (hasMoof) session.fragmentInfo[fragmentKey] else null
            if (hasMoof && fragmentsInfo == null) {
                AppLogger.w("LocalStreamProxy: DASH fragment arrived before its init segment; returning encrypted data")
                return data
            }
            AppLogger.i("LocalStreamProxy: CENC ${if (hasMoov) "init" else "media"} segment detected, decrypting")
            decryptWithMp4Decrypt(data, session.kidHex, key, fragmentsInfo)
        } else {
            AppLogger.w("LocalStreamProxy: CENC segment has no KID, using built-in decryptor")
            CencDecryptor(data, key).decrypt()
        }
    }

    private fun dashFragmentKey(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path ?: url.substringBefore('?')
        return path.replace(Regex("-\\d+(?=\\.[^./]+$)"), "")
    }

    internal fun containsMp4Box(data: ByteArray, type: String): Boolean {
        if (type.length != 4) return false
        val marker = type.toByteArray(Charsets.US_ASCII)
        for (index in 4..data.size - 4) {
            if (data[index] == marker[0] && data[index + 1] == marker[1] &&
                data[index + 2] == marker[2] && data[index + 3] == marker[3]
            ) return true
        }
        return false
    }

    private fun resolveUrl(base: String, uri: String): String {
        if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
            return uri
        }
        val baseUri = URI(base)
        val resolved = baseUri.resolve(uri).toString()

        if (baseUri.query != null && !resolved.contains("?")) {
            return "$resolved?${baseUri.query}"
        }

        return resolved
    }
}
