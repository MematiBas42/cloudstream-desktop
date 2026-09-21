package com.lagradost.common.download

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance multi-connection segmented byte-range downloader.
 *
 * Concurrency & Reassembly Architecture:
 * - Splits remote HTTP media streams into 10 MiB byte ranges.
 * - Dispatches parallel worker coroutines across Dispatchers.IO.
 * - Enforces sequential file writes via RAM reassembly queue with thread-safe Mutex lock.
 * - RAM backpressure throttle: stalls worker threads if downloaded bytes exceed written bytes by > 50 MiB.
 * - Supports seamless byte-offset resumption from existing .part files.
 */
class LinuxStreamDownloader(
    private val client: OkHttpClient,
    private val targetFile: File,
    private val link: DownloadLink,
    private val parallelConnections: Int = 3,
    private val chunkSize: Long = 10L * 1024L * 1024L, // 10 MiB
    private val onProgress: (bytesDownloaded: Long, totalBytes: Long, bytesPerSec: Long) -> Unit
) {
    val partFile: File = LinuxDownloadStorage.resolvePartFile(targetFile)
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    val downloadedBytes = AtomicLong(0L)
    val writtenBytes = AtomicLong(0L)
    val totalStreamLength = AtomicLong(-1L)

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val initialStart = if (partFile.exists()) partFile.length() else 0L
        downloadedBytes.set(initialStart)
        writtenBytes.set(initialStart)

        // 1. Inspect remote link via HEAD request
        val headReqBuilder = Request.Builder()
            .url(link.url)
            .head()
        link.getAllHeaders().forEach { (k, v) -> headReqBuilder.addHeader(k, v) }

        var totalLength = -1L
        var supportsRange = false

        try {
            client.newCall(headReqBuilder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val lenStr = response.header("Content-Length")
                    totalLength = lenStr?.toLongOrNull() ?: -1L
                    val acceptRanges = response.header("Accept-Ranges")
                    supportsRange = acceptRanges?.trim()?.equals("bytes", ignoreCase = true) == true
                }
            }
        } catch (e: Exception) {
            AppLogger.w("LinuxStreamDownloader", "HEAD request failed for ${link.url}: ${e.message}")
        }

        // 2. Speculative range probe if HEAD didn't disclose range capability
        if (!supportsRange) {
            val probeReqBuilder = Request.Builder()
                .url(link.url)
                .addHeader("Range", "bytes=0-1023")
            link.getAllHeaders().forEach { (k, v) -> probeReqBuilder.addHeader(k, v) }

            try {
                client.newCall(probeReqBuilder.build()).execute().use { response ->
                    if (response.code == 206) {
                        supportsRange = true
                        val cr = response.header("Content-Range")
                        if (totalLength <= 0L && cr != null && cr.contains("/")) {
                            totalLength = cr.substringAfter("/").trim().toLongOrNull() ?: -1L
                        }
                    } else if (response.isSuccessful && totalLength <= 0L) {
                        totalLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("LinuxStreamDownloader", "Speculative range probe failed: ${e.message}")
            }
        }

        totalStreamLength.set(totalLength)

        val targetParent = targetFile.parentFile
        if (targetParent != null && !targetParent.exists()) {
            targetParent.mkdirs()
        }

        val raf = RandomAccessFile(partFile, "rw")
        raf.seek(initialStart)

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
                onProgress(current, totalStreamLength.get(), speed)
            }
        }

        try {
            val remainingBytes = if (totalLength > 0L) totalLength - initialStart else -1L
            val shouldChunk = supportsRange && totalLength > 0L && remainingBytes >= (chunkSize * 2)

            if (!shouldChunk) {
                // Single stream download
                val success = downloadSingleStream(raf, initialStart, totalLength)
                if (!success) {
                    raf.close()
                    return@withContext false
                }
            } else {
                // Multi-chunk parallel segmented download
                val chunkCount = ((remainingBytes + chunkSize - 1) / chunkSize).toInt()
                val chunkOffsets = LongArray(chunkCount) { idx -> initialStart + (idx * chunkSize) }

                val chunkIndex = AtomicLong(0)
                val pendingChunks = ConcurrentHashMap<Long, ByteArray>()
                val writeMutex = Mutex()

                val workers = (0 until parallelConnections).map {
                    launch {
                        while (isActive && !isCancelled.get() && !isPaused.get()) {
                            // RAM backpressure throttling: delay if downloaded is > 50 MB ahead of written
                            if (downloadedBytes.get() - writtenBytes.get() > 50L * 1024L * 1024L) {
                                delay(250)
                                continue
                            }

                            val idx = chunkIndex.getAndIncrement().toInt()
                            if (idx >= chunkOffsets.size) break

                            val start = chunkOffsets[idx]
                            val end = if (idx == chunkOffsets.lastIndex) totalLength - 1L else chunkOffsets[idx + 1] - 1L

                            val chunkData = fetchChunkBytes(start, end)
                            if (chunkData == null) {
                                if (!isCancelled.get() && !isPaused.get()) {
                                    throw IOException("Chunk $idx [$start-$end] failed after retries")
                                }
                                break
                            }

                            downloadedBytes.addAndGet(chunkData.size.toLong())

                            // Out-of-order reassembly write
                            writeMutex.withLock {
                                if (start == writtenBytes.get()) {
                                    raf.seek(start)
                                    raf.write(chunkData)
                                    writtenBytes.addAndGet(chunkData.size.toLong())

                                    // Drain consecutive pending chunks
                                    while (true) {
                                        val nextData = pendingChunks.remove(writtenBytes.get()) ?: break
                                        raf.seek(writtenBytes.get())
                                        raf.write(nextData)
                                        writtenBytes.addAndGet(nextData.size.toLong())
                                    }
                                } else {
                                    pendingChunks[start] = chunkData
                                }
                            }
                        }
                    }
                }

                workers.joinAll()
            }

            if (isCancelled.get()) {
                raf.close()
                partFile.delete()
                return@withContext false
            }

            if (isPaused.get()) {
                raf.close()
                return@withContext false
            }

            raf.close()
            LinuxDownloadStorage.commitPartFile(partFile, targetFile)
            val finalLength = targetFile.length()
            onProgress(finalLength, finalLength, 0L)
            true
        } catch (e: Exception) {
            AppLogger.e("LinuxStreamDownloader", "Download failed for ${link.url}", e)
            try {
                raf.close()
            } catch (t: Throwable) {
                AppLogger.w("LinuxStreamDownloader", "Failed to close RandomAccessFile: ${t.message}")
            }
            false
        } finally {
            speedTrackerJob.cancel()
        }
    }

    private suspend fun fetchChunkBytes(startByte: Long, endByte: Long): ByteArray? {
        var retries = 3
        while (retries-- > 0 && !isCancelled.get() && !isPaused.get()) {
            try {
                val reqBuilder = Request.Builder()
                    .url(link.url)
                    .addHeader("Range", "bytes=$startByte-$endByte")
                link.getAllHeaders().forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code != 206 && resp.code != 200) {
                        return@use
                    }
                    val body = resp.body ?: return@use
                    val bytes = body.bytes()
                    val expectedSize = (endByte - startByte + 1).toInt()
                    if (bytes.size == expectedSize || resp.code == 200) {
                        return bytes
                    }
                }
            } catch (e: Exception) {
                delay(300)
            }
        }
        return null
    }

    private fun downloadSingleStream(raf: RandomAccessFile, startAt: Long, totalLength: Long): Boolean {
        val reqBuilder = Request.Builder().url(link.url)
        if (startAt > 0L) {
            reqBuilder.addHeader("Range", "bytes=$startAt-")
        }
        link.getAllHeaders().forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val stream = resp.body?.byteStream() ?: return false
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                if (isCancelled.get() || isPaused.get()) return false
                raf.write(buffer, 0, read)
                downloadedBytes.addAndGet(read.toLong())
                writtenBytes.addAndGet(read.toLong())
            }
            return true
        }
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
