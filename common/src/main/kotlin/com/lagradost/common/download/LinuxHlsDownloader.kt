package com.lagradost.common.download

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS progressive stream recorder (.m3u8 demuxing and sequential .ts segment appending).
 *
 * Implements:
 * - Master playlist demuxing with automatic stream variant resolution.
 * - Media playlist parsing and relative URI resolution.
 * - Sequential append into Freedesktop XDG-compliant .part storage.
 * - Segment-level resume checkpointing via extraInfo index.
 */
class LinuxHlsDownloader(
    private val client: OkHttpClient,
    private val targetFile: File,
    private val link: DownloadLink,
    private val initialSegmentIndex: Int = 0,
    private val onProgress: (bytesDownloaded: Long, totalBytes: Long, bytesPerSec: Long, currentSegment: Int, totalSegments: Int) -> Unit
) {
    val partFile: File = LinuxDownloadStorage.resolvePartFile(targetFile)
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    val downloadedBytes = AtomicLong(0L)
    val currentSegmentIndex = AtomicInteger(initialSegmentIndex)

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val initialStart = if (partFile.exists()) partFile.length() else 0L
        downloadedBytes.set(initialStart)

        val targetParent = targetFile.parentFile
        if (targetParent != null && !targetParent.exists()) {
            targetParent.mkdirs()
        }

        // 1. Fetch playlist and resolve segments
        val segments = try {
            resolveHlsSegments(link.url, link.getAllHeaders())
        } catch (e: Exception) {
            AppLogger.e("LinuxHlsDownloader", "Failed to resolve HLS playlist for ${link.url}", e)
            return@withContext false
        }

        if (segments.isEmpty()) {
            AppLogger.w("LinuxHlsDownloader", "HLS playlist contained 0 segments: ${link.url}")
            return@withContext false
        }

        val totalSegments = segments.size
        val startIdx = currentSegmentIndex.get().coerceIn(0, totalSegments)

        val speedTrackerJob = launch {
            var lastBytes = downloadedBytes.get()
            var lastTime = System.currentTimeMillis()
            while (isActive && !isCancelled.get() && !isPaused.get()) {
                delay(1000)
                val now = System.currentTimeMillis()
                val current = downloadedBytes.get()
                val elapsedSec = ((now - lastTime) / 1000.0).coerceAtLeast(0.001)
                val speed = ((current - lastBytes) / elapsedSec).toLong().coerceAtLeast(0L)
                lastBytes = current
                lastTime = now
                onProgress(current, -1L, speed, currentSegmentIndex.get(), totalSegments)
            }
        }

        try {
            FileOutputStream(partFile, true).use { outputStream ->
                for (i in startIdx until totalSegments) {
                    if (isCancelled.get() || isPaused.get()) {
                        break
                    }

                    val segUrl = segments[i]
                    val segBytes = fetchSegmentBytes(segUrl, link.getAllHeaders())
                    if (segBytes == null) {
                        if (!isCancelled.get() && !isPaused.get()) {
                            AppLogger.e("LinuxHlsDownloader", "Failed to download segment $i/$totalSegments from $segUrl")
                            return@withContext false
                        }
                        break
                    }

                    outputStream.write(segBytes)
                    outputStream.flush()

                    downloadedBytes.addAndGet(segBytes.size.toLong())
                    currentSegmentIndex.set(i + 1)
                }
            }

            if (isCancelled.get()) {
                partFile.delete()
                return@withContext false
            }

            if (isPaused.get()) {
                return@withContext false
            }

            if (currentSegmentIndex.get() >= totalSegments) {
                LinuxDownloadStorage.commitPartFile(partFile, targetFile)
                val finalLen = targetFile.length()
                onProgress(finalLen, finalLen, 0L, totalSegments, totalSegments)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.e("LinuxHlsDownloader", "HLS download error", e)
            false
        } finally {
            speedTrackerJob.cancel()
        }
    }

    private suspend fun resolveHlsSegments(manifestUrl: String, headers: Map<String, String>): List<String> {
        val reqBuilder = Request.Builder().url(manifestUrl)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        val bodyText = client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} fetching manifest $manifestUrl")
            resp.body?.string() ?: throw Exception("Empty manifest body")
        }

        val lines = bodyText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Check for Master Playlist
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            var selectedMediaPlaylistUrl: String? = null
            var maxBandwidth = -1L

            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val uriLine = lines.getOrNull(i + 1)
                    if (uriLine != null && !uriLine.startsWith("#")) {
                        if (bandwidth >= maxBandwidth) {
                            maxBandwidth = bandwidth
                            selectedMediaPlaylistUrl = resolveUrl(manifestUrl, uriLine)
                        }
                    }
                }
            }

            val targetUrl = selectedMediaPlaylistUrl ?: throw Exception("Could not find variant stream in master playlist")
            return resolveHlsSegments(targetUrl, headers)
        }

        // Media Playlist: extract segment URLs
        val segmentUrls = mutableListOf<String>()
        var nextIsSegment = false
        for (line in lines) {
            if (line.startsWith("#EXTINF")) {
                nextIsSegment = true
            } else if (nextIsSegment && !line.startsWith("#")) {
                segmentUrls.add(resolveUrl(manifestUrl, line))
                nextIsSegment = false
            }
        }

        return segmentUrls
    }

    private fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(relativeOrAbsolute).toString()
        } catch (_: Exception) {
            if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
                relativeOrAbsolute
            } else {
                val prefix = baseUrl.substringBeforeLast('/', "")
                "$prefix/$relativeOrAbsolute"
            }
        }
    }

    private suspend fun fetchSegmentBytes(url: String, headers: Map<String, String>): ByteArray? {
        var retries = 3
        while (retries-- > 0 && !isCancelled.get() && !isPaused.get()) {
            try {
                val reqBuilder = Request.Builder().url(url)
                headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return resp.body?.bytes()
                    }
                }
            } catch (e: Exception) {
                delay(300)
            }
        }
        return null
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun cancel() {
        isCancelled.set(true)
    }
}
