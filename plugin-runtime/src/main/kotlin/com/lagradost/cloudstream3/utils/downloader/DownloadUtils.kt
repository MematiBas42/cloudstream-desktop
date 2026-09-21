// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadUtils.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.graphics.Bitmap
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ExtractorSubtitleLink
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFileName
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFolder
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.notifications.FreedesktopNotificationManager
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Separate object with helper functions for the downloader */
object DownloadUtils {
    private const val TAG = "DownloadUtils"
    private val cachedBitmaps = ConcurrentHashMap<String, Bitmap>()

    internal fun Context.getImageBitmapFromUrl(
        url: String,
        headers: Map<String, String>? = null
    ): Bitmap? = safe {
        cachedBitmaps[url]?.let {
            return@safe it
        }

        // 0. Handle local file paths and file: URIs directly
        if (url.startsWith("file:") || url.startsWith("/")) {
            val localPath = if (url.startsWith("file://")) {
                url.removePrefix("file://")
            } else if (url.startsWith("file:")) {
                url.removePrefix("file:")
            } else {
                url
            }
            val localFile = File(localPath)
            if (localFile.exists() && localFile.isFile) {
                val image = try {
                    ImageIO.read(localFile)
                } catch (t: Throwable) {
                    logError(t)
                    null
                }
                val bitmap = Bitmap(image = image, file = localFile, filePath = localFile.absolutePath)
                cachedBitmaps.putIfAbsent(url, bitmap)
                return@safe bitmap
            }
        }

        // 1. Try Coil ImageLoader via disk cache / execution if available on classpath
        val coilBitmap = tryLoadWithCoil(url, headers)
        if (coilBitmap != null) {
            cachedBitmaps.putIfAbsent(url, coilBitmap)
            return@safe coilBitmap
        }

        // 2. Headless / Desktop image loading via FreedesktopNotificationManager poster resolver & ImageIO
        val resolvedPath = FreedesktopNotificationManager.resolvePosterPath(url, headers)
        if (resolvedPath != null) {
            val file = File(resolvedPath)
            if (file.exists() && file.isFile && file.length() > 0) {
                val image = try {
                    ImageIO.read(file)
                } catch (t: Throwable) {
                    logError(t)
                    null
                }
                val bitmap = Bitmap(image = image, file = file, filePath = file.absolutePath)
                cachedBitmaps.putIfAbsent(url, bitmap)
                return@safe bitmap
            }
        }

        // 3. Fallback: direct ImageIO URL fetch and cache to disk
        val fallbackBitmap = tryLoadDirectImageIO(url, headers)
        if (fallbackBitmap != null) {
            cachedBitmaps.putIfAbsent(url, fallbackBitmap)
            return@safe fallbackBitmap
        }

        return@safe null
    }

    private fun tryLoadWithCoil(url: String, headers: Map<String, String>?): Bitmap? {
        return try {
            val singletonLoaderClass = Class.forName("coil3.SingletonImageLoader")
            val platformContextClass = Class.forName("coil3.PlatformContext")
            val platformContextInstance = platformContextClass.getField("INSTANCE").get(null)
            val getMethod = singletonLoaderClass.getMethod("get", platformContextClass)
            val imageLoader = getMethod.invoke(null, platformContextInstance) ?: return null

            val getDiskCacheMethod = imageLoader.javaClass.getMethod("getDiskCache")
            val diskCache = getDiskCacheMethod.invoke(imageLoader)
            if (diskCache != null) {
                val openSnapshotMethod = diskCache.javaClass.getMethod("openSnapshot", String::class.java)
                val snapshot = openSnapshotMethod.invoke(diskCache, url) as? AutoCloseable
                if (snapshot != null) {
                    snapshot.use {
                        val getDataMethod = snapshot.javaClass.getMethod("getData")
                        val okioPath = getDataMethod.invoke(snapshot)
                        val toFileMethod = okioPath.javaClass.getMethod("toFile")
                        val file = toFileMethod.invoke(okioPath) as? File
                        if (file != null && file.exists() && file.length() > 0) {
                            val image = try {
                                ImageIO.read(file)
                            } catch (t: Throwable) {
                                logError(t)
                                null
                            }
                            return Bitmap(image = image, file = file, filePath = file.absolutePath)
                        }
                    }
                }
            }

            val imageRequestBuilderClass = Class.forName("coil3.request.ImageRequest\$Builder")
            val builder = imageRequestBuilderClass.getConstructor(platformContextClass).newInstance(platformContextInstance)
            val dataMethod = imageRequestBuilderClass.getMethod("data", Any::class.java)
            dataMethod.invoke(builder, url)

            val buildMethod = imageRequestBuilderClass.getMethod("build")
            val request = buildMethod.invoke(builder)

            try {
                val nonJsClass = Class.forName("coil3.ImageLoaders_nonJsCommonKt")
                val executeBlockingMethod = nonJsClass.getMethod(
                    "executeBlocking",
                    Class.forName("coil3.ImageLoader"),
                    Class.forName("coil3.request.ImageRequest")
                )
                executeBlockingMethod.invoke(null, imageLoader, request)
            } catch (t: Throwable) {
                AppLogger.d(TAG, "executeBlocking failed: ${t.message}")
            }

            if (diskCache != null) {
                val openSnapshotMethod = diskCache.javaClass.getMethod("openSnapshot", String::class.java)
                val snapshot = openSnapshotMethod.invoke(diskCache, url) as? AutoCloseable
                if (snapshot != null) {
                    snapshot.use {
                        val getDataMethod = snapshot.javaClass.getMethod("getData")
                        val okioPath = getDataMethod.invoke(snapshot)
                        val toFileMethod = okioPath.javaClass.getMethod("toFile")
                        val file = toFileMethod.invoke(okioPath) as? File
                        if (file != null && file.exists() && file.length() > 0) {
                            val image = try {
                                ImageIO.read(file)
                            } catch (t: Throwable) {
                                logError(t)
                                null
                            }
                            return Bitmap(image = image, file = file, filePath = file.absolutePath)
                        }
                    }
                }
            }
            null
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Coil loading not available or failed for $url: ${t.message}")
            null
        }
    }

    private fun tryLoadDirectImageIO(url: String, headers: Map<String, String>?): Bitmap? {
        return try {
            val u = java.net.URI.create(url).toURL()
            val connection = u.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection is java.net.HttpURLConnection) {
                headers?.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            }
            val image = connection.getInputStream().use { stream ->
                ImageIO.read(stream)
            } ?: return null
            val cacheDir = PlatformPaths.imageCacheDir.toFile().apply { mkdirs() }
            val hash = sha256Hex(url)
            val destFile = File(cacheDir, "img_$hash.png")
            ImageIO.write(image, "png", destFile)
            Bitmap(image = image, file = destFile, filePath = destFile.absolutePath)
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    //calculate the time
    internal fun getEstimatedTimeLeft(
        context: Context,
        bytesPerSecond: Long,
        progress: Long,
        total: Long
    ): String {
        if (bytesPerSecond <= 0) return ""
        val timeInSec = (total - progress) / bytesPerSecond
        val hrs = timeInSec / 3600
        val mins = (timeInSec % 3600) / 60
        val secs = timeInSec % 60
        val timeFormated: UiText? = when {
            hrs > 0 -> txt(
                R.string.download_time_left_hour_min_sec_format,
                hrs,
                mins,
                secs
            )

            mins > 0 -> txt(
                R.string.download_time_left_min_sec_format,
                mins,
                secs
            )

            secs > 0 -> txt(
                R.string.download_time_left_sec_format,
                secs
            )

            else -> null
        }
        return timeFormated?.asString(context) ?: ""
    }

    internal fun downloadSubtitle(
        context: Context?,
        link: ExtractorSubtitleLink,
        fileName: String,
        folder: String
    ) {
        ioSafe {
            VideoDownloadManager.downloadThing(
                context ?: return@ioSafe,
                link,
                "$fileName ${link.name}",
                folder,
                if (link.url.contains(".srt")) "srt" else "vtt",
                false,
                null, createNotificationCallback = {}
            )
        }
    }

    fun downloadSubtitle(
        context: Context?,
        link: SubtitleData,
        meta: DownloadObjects.DownloadEpisodeMetadata,
    ) {
        context?.let { ctx ->
            val fileName = getFileName(ctx, meta)
            val folder = getFolder(meta.type ?: return, meta.mainName)
            downloadSubtitle(
                ctx,
                ExtractorSubtitleLink(link.name, link.url, "", link.headers),
                fileName,
                folder
            )
        }
    }

    /** Helper function to make sure duplicate attributes don't get overridden or inserted without lowercase cmp
     * example: map("a" to 1) appendAndDontOverride map("A" to 2, "a" to 3, "c" to 4) = map("a" to 1, "c" to 4)
     * */
    internal fun <V> Map<String, V>.appendAndDontOverride(rhs: Map<String, V>): Map<String, V> {
        val out = this.toMutableMap()
        val current = this.keys.map { it.lowercase() }
        for ((key, value) in rhs) {
            if (current.contains(key.lowercase())) continue
            out[key] = value
        }
        return out
    }

    internal fun List<Job>.cancel() {
        forEach { job ->
            try {
                job.cancel()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }

    internal suspend fun List<Job>.join() {
        forEach { job ->
            try {
                job.join()
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }
}
