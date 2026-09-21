// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/PreviewGenerator.kt", upstreamCommit = "caeec18")
package com.lagradost.player.preview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.log2

const val MAX_LOD = 6
const val MIN_LOD = 3

data class ImageParams(
    val width: Int,
    val height: Int,
) {
    companion object {
        val DEFAULT = ImageParams(200, 320)
        fun new16by9(width: Int): ImageParams {
            if (width < 100) {
                return DEFAULT
            }
            return ImageParams(
                width / 4,
                (width * 9) / (4 * 16)
            )
        }
    }

    init {
        assert(width > 0 && height > 0)
    }
}

interface IPreviewGenerator {
    fun hasPreview(): Boolean
    fun getPreviewImage(fraction: Float): Bitmap?
    fun release()

    var params: ImageParams

    var durationMs: Long
    var loadedImages: Int

    companion object {
        fun new(): IPreviewGenerator {
            val userDisabled = CloudStreamApp.context?.let { ctx ->
                PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(
                    ctx.getString(R.string.preview_seekbar_key), true
                ) == false
            } ?: false
            /** because TV has low ram + not show we disable this for now */
            return if (isLayout(TV) || userDisabled) {
                empty()
            } else {
                PreviewGenerator()
            }
        }

        fun empty(): IPreviewGenerator {
            return NoPreviewGenerator()
        }
    }
}

fun Bitmap.scale(width: Int, height: Int): Bitmap {
    val src = this.image ?: return this
    val targetW = width.coerceAtLeast(1)
    val targetH = height.coerceAtLeast(1)
    val resized = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB)
    val g = resized.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(src, 0, 0, targetW, targetH, null)
    } finally {
        g.dispose()
    }
    return Bitmap(resized)
}

fun Bitmap.recycle() {
    // GC manages BufferedImage memory on desktop JVM
}

private fun rescale(image: Bitmap, params: ImageParams): Bitmap {
    if (image.width <= params.width && image.height <= params.height) return image
    val new = image.scale(params.width, params.height)
    // throw away the old image
    if (new != image) {
        image.recycle()
    }
    return new
}

class MediaMetadataRetriever {
    companion object {
        const val METADATA_KEY_DURATION = 9
        const val METADATA_KEY_VIDEO_WIDTH = 18
        const val METADATA_KEY_VIDEO_HEIGHT = 19
        const val OPTION_CLOSEST_SYNC = 2
        private const val TAG = "MediaMetadataRetriever"
        private val FFMPEG_PATH by lazy {
            listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg").firstOrNull { path ->
                try {
                    val p = ProcessBuilder(path, "-version").redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
                } catch (t: Throwable) {
                    Log.w(TAG, "FFmpeg probe error: ${t.message}")
                    false
                }
            } ?: "ffmpeg"
        }
        private val FFPROBE_PATH by lazy {
            listOf("/usr/bin/ffprobe", "/usr/local/bin/ffprobe", "ffprobe").firstOrNull { path ->
                try {
                    val p = ProcessBuilder(path, "-version").redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0
                } catch (t: Throwable) {
                    Log.w(TAG, "FFprobe probe error: ${t.message}")
                    false
                }
            } ?: "ffprobe"
        }
    }

    private var dataSource: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var cachedDurationMs: Long? = null

    fun setDataSource(url: String, headers: Map<String, String> = emptyMap()) {
        this.dataSource = url
        this.headers = headers
        this.cachedDurationMs = null
    }

    fun setDataSource(context: Context, uri: Uri) {
        val path = uri.path ?: uri.toString().removePrefix("file://")
        this.dataSource = path
        this.headers = emptyMap()
        this.cachedDurationMs = null
    }

    fun extractMetadata(keyCode: Int): String? {
        val source = dataSource ?: return null
        return when (keyCode) {
            METADATA_KEY_DURATION -> {
                cachedDurationMs?.toString() ?: getDurationMs(source)?.also { cachedDurationMs = it }?.toString()
            }
            else -> null
        }
    }

    private fun getDurationMs(source: String): Long? {
        try {
            val cmd = mutableListOf(FFPROBE_PATH, "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1")
            if (headers.isNotEmpty()) {
                val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd.add("-headers")
                cmd.add(headerStr)
            }
            cmd.add(source)

            val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS)
            if (proc.exitValue() == 0) {
                val sec = output.toDoubleOrNull()
                if (sec != null && sec > 0) {
                    return (sec * 1000.0).toLong()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ffprobe duration error: ${t.message}")
        }

        try {
            val cmd = mutableListOf(FFMPEG_PATH)
            if (headers.isNotEmpty()) {
                val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd.add("-headers")
                cmd.add(headerStr)
            }
            cmd.addAll(listOf("-i", source))

            val pb = ProcessBuilder(cmd)
            val proc = pb.start()
            val stderr = proc.errorStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)

            val durationRegex = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
            val match = durationRegex.find(stderr)
            if (match != null) {
                val h = match.groupValues[1].toLong()
                val m = match.groupValues[2].toLong()
                val s = match.groupValues[3].toDouble()
                return ((h * 3600 + m * 60 + s) * 1000.0).toLong()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ffmpeg duration fallback error: ${t.message}")
        }
        return null
    }

    fun getScaledFrameAtTime(timeUs: Long, option: Int, width: Int, height: Int): Bitmap? {
        val source = dataSource ?: return null
        val timeSec = (timeUs.toDouble() / 1_000_000.0).coerceAtLeast(0.0)
        return try {
            val cmd = mutableListOf(
                FFMPEG_PATH,
                "-ss", String.format(java.util.Locale.US, "%.3f", timeSec)
            )
            if (headers.isNotEmpty()) {
                val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd.add("-headers")
                cmd.add(headerStr)
            }
            cmd.addAll(listOf(
                "-i", source,
                "-vf", "scale=${width}:${height}:force_original_aspect_ratio=decrease",
                "-frames:v", "1",
                "-f", "image2pipe",
                "-vcodec", "mjpeg",
                "-"
            ))

            val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
            val proc = pb.start()
            val bufferedImage = ImageIO.read(proc.inputStream)
            proc.waitFor(5, TimeUnit.SECONDS)
            if (bufferedImage != null) {
                Bitmap(bufferedImage)
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to getScaledFrameAtTime: ${t.message}")
            null
        }
    }

    fun getFrameAtTime(timeUs: Long): Bitmap? {
        val source = dataSource ?: return null
        val timeSec = (timeUs.toDouble() / 1_000_000.0).coerceAtLeast(0.0)
        return try {
            val cmd = mutableListOf(
                FFMPEG_PATH,
                "-ss", String.format(java.util.Locale.US, "%.3f", timeSec)
            )
            if (headers.isNotEmpty()) {
                val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n"
                cmd.add("-headers")
                cmd.add(headerStr)
            }
            cmd.addAll(listOf(
                "-i", source,
                "-frames:v", "1",
                "-f", "image2pipe",
                "-vcodec", "mjpeg",
                "-"
            ))

            val pb = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD)
            val proc = pb.start()
            val bufferedImage = ImageIO.read(proc.inputStream)
            proc.waitFor(5, TimeUnit.SECONDS)
            if (bufferedImage != null) {
                Bitmap(bufferedImage)
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to getFrameAtTime: ${t.message}")
            null
        }
    }

    fun release() {
        dataSource = null
        headers = emptyMap()
        cachedDurationMs = null
    }
}

/** rescale to not take up as much memory */
private fun MediaMetadataRetriever.image(timeUs: Long, params: ImageParams): Bitmap? {
    val scaled = this.getScaledFrameAtTime(
        timeUs,
        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        params.width,
        params.height
    )
    if (scaled != null) {
        return scaled
    }
    val raw = this.getFrameAtTime(timeUs) ?: return null
    return rescale(raw, params)
}

/** PreviewGenerator that hides the implementation details of the sub generators that is used, used for source switch cache */
class PreviewGenerator : IPreviewGenerator {

    /** the most up to date generator, will always mirror the actual source in the player */
    private var currentGenerator: IPreviewGenerator = NoPreviewGenerator()

    /** the longest generated preview of the same episode */
    private var lastGenerator: IPreviewGenerator = NoPreviewGenerator()

    /** always NoPreviewGenerator, used as a cache for nothing */
    private val dummy: IPreviewGenerator = NoPreviewGenerator()

    /** if the current generator is the same as the last by checking time */
    private fun isSameLength(): Boolean {
        return currentGenerator.durationMs.minus(lastGenerator.durationMs).absoluteValue < 10_000L
    }

    /** use the backup if the current generator is init or if they have the same length */
    private val backupGenerator: IPreviewGenerator
        get() {
            if (currentGenerator.durationMs == 0L || isSameLength()) {
                return lastGenerator
            }
            return dummy
        }

    override fun hasPreview(): Boolean {
        return currentGenerator.hasPreview() || backupGenerator.hasPreview()
    }

    override fun getPreviewImage(fraction: Float): Bitmap? {
        return try {
            currentGenerator.getPreviewImage(fraction) ?: backupGenerator.getPreviewImage(fraction)
        } catch (t: Throwable) {
            logError(t)
            null
        }
    }

    override fun release() {
        lastGenerator.release()
        currentGenerator.release()
        lastGenerator = NoPreviewGenerator()
        currentGenerator = NoPreviewGenerator()
    }

    override var params: ImageParams = ImageParams.DEFAULT
        set(value) {
            field = value
            lastGenerator.params = value
            backupGenerator.params = value
            currentGenerator.params = value
        }

    override var durationMs: Long
        get() = currentGenerator.durationMs
        set(_) {}
    override var loadedImages: Int
        get() = currentGenerator.loadedImages
        set(_) {}

    fun clear(keepCache: Boolean) {
        if (keepCache) {
            if (!isSameLength() || currentGenerator.loadedImages >= lastGenerator.loadedImages || lastGenerator.durationMs == 0L) {
                // the current generator is better than the last generator, therefore keep the current
                // or the lengths are not the same, therefore favoring the more recent selection

                // if they are the same we favor the current generator
                lastGenerator.release()
                lastGenerator = currentGenerator
            } else {
                // otherwise just keep the last generator and throw away the current generator
                currentGenerator.release()
            }
        } else {
            // we switched the episode, therefore keep nothing
            lastGenerator.release()
            lastGenerator = NoPreviewGenerator()
            currentGenerator.release()
            // we assume that we set currentGenerator right after this, so currentGenerator != NoPreviewGenerator
        }
    }

    fun load(link: ExtractorLink, keepCache: Boolean) {
        clear(keepCache)

        when (link.type) {
            ExtractorLinkType.M3U8 -> {
                currentGenerator = M3u8PreviewGenerator(params).apply {
                    load(url = link.url, headers = link.getAllHeaders())
                }
            }

            ExtractorLinkType.VIDEO -> {
                currentGenerator = Mp4PreviewGenerator(params).apply {
                    load(url = link.url, headers = link.getAllHeaders())
                }
            }

            else -> {
                Log.i("PreviewImg", "unsupported format for $link")
            }
        }
    }

    fun load(context: Context, link: ExtractorUri, keepCache: Boolean) {
        clear(keepCache)
        currentGenerator = Mp4PreviewGenerator(params).apply {
            load(keepCache = keepCache, context = context, uri = link.uri)
        }
    }

    fun loadVtt(url: String, headers: Map<String, String> = emptyMap(), keepCache: Boolean = false) {
        clear(keepCache)
        currentGenerator = WebVttPreviewGenerator(params).apply {
            load(url = url, headers = headers)
        }
    }
}

private class NoPreviewGenerator : IPreviewGenerator {
    override fun hasPreview(): Boolean {
        return false
    }

    override fun getPreviewImage(fraction: Float): Bitmap? {
        return null
    }

    override fun release() {
        // No-op for empty preview generator
    }

    override var params: ImageParams
        get() = ImageParams.DEFAULT
        set(_) {}
    override var durationMs: Long = 0L
    override var loadedImages: Int = 0
}

class WebVttPreviewGenerator(override var params: ImageParams) : IPreviewGenerator {
    companion object {
        private const val TAG = "PreviewImgVtt"
    }

    data class VttThumbnailCue(
        val startMs: Long,
        val endMs: Long,
        val imageUrl: String,
        val x: Int = 0,
        val y: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
    )

    private var cues: List<VttThumbnailCue> = emptyList()
    private val spriteCache = ConcurrentHashMap<String, BufferedImage>()
    private val tileCache = ConcurrentHashMap<VttThumbnailCue, Bitmap>()
    private var currentJob: Job? = null
    private var headers: Map<String, String> = emptyMap()

    override var durationMs: Long = 0L
    override var loadedImages: Int = 0

    override fun hasPreview(): Boolean {
        return cues.isNotEmpty()
    }

    override fun getPreviewImage(fraction: Float): Bitmap? {
        if (cues.isEmpty() || durationMs <= 0L) return null
        val targetMs = (fraction * durationMs).toLong().coerceIn(0L, durationMs)

        var low = 0
        var high = cues.size - 1
        var bestCue: VttThumbnailCue? = null

        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            if (targetMs < cue.startMs) {
                high = mid - 1
            } else if (targetMs >= cue.endMs) {
                low = mid + 1
            } else {
                bestCue = cue
                break
            }
        }
        if (bestCue == null) {
            bestCue = cues.minByOrNull { cue ->
                if (targetMs < cue.startMs) cue.startMs - targetMs else targetMs - cue.endMs
            }
        }
        val cue = bestCue ?: return null

        tileCache[cue]?.let { return it }

        val spriteSheet = spriteCache[cue.imageUrl] ?: loadSpriteSheet(cue.imageUrl) ?: return null
        spriteCache[cue.imageUrl] = spriteSheet

        val tile = if (cue.width > 0 && cue.height > 0) {
            val safeX = cue.x.coerceIn(0, (spriteSheet.width - 1).coerceAtLeast(0))
            val safeY = cue.y.coerceIn(0, (spriteSheet.height - 1).coerceAtLeast(0))
            val safeW = cue.width.coerceAtMost(spriteSheet.width - safeX).coerceAtLeast(1)
            val safeH = cue.height.coerceAtMost(spriteSheet.height - safeY).coerceAtLeast(1)
            spriteSheet.getSubimage(safeX, safeY, safeW, safeH)
        } else {
            spriteSheet
        }

        val bitmap = rescale(Bitmap(tile), params)
        tileCache[cue] = bitmap
        loadedImages = tileCache.size
        return bitmap
    }

    private fun loadSpriteSheet(imageUrl: String): BufferedImage? {
        return try {
            if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                val url = java.net.URI(imageUrl).toURL()
                val conn = url.openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.getInputStream().use { ImageIO.read(it) }
            } else {
                val file = File(imageUrl)
                if (file.exists()) {
                    ImageIO.read(file)
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load sprite sheet from $imageUrl: ${t.message}")
            null
        }
    }

    fun load(url: String, headers: Map<String, String> = emptyMap()) {
        clear()
        this.headers = headers
        currentJob?.cancel()
        currentJob = ioSafe {
            withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URI(url).toURL().openConnection()
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    val content = conn.getInputStream().bufferedReader().readText()
                    val baseUrl = url.substringBeforeLast('/') + "/"
                    parseVttContent(content, baseUrl)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load VTT from $url: ${t.message}")
                }
            }
        }
    }

    fun parseVttContent(content: String, baseUrl: String) {
        val parsedCues = parseVttCues(content, baseUrl)
        cues = parsedCues
        durationMs = cues.maxOfOrNull { it.endMs } ?: 0L
        loadedImages = 0
    }

    private fun parseVttCues(content: String, baseUrl: String): List<VttThumbnailCue> {
        val result = mutableListOf<VttThumbnailCue>()
        val lines = content.lines()
        val timeRegex = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})""")

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val match = timeRegex.find(line)
            if (match != null) {
                val startMs = parseTime(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
                val endMs = parseTime(match.groupValues[5], match.groupValues[6], match.groupValues[7], match.groupValues[8])

                var payload = ""
                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) {
                    j++
                }
                if (j < lines.size) {
                    payload = lines[j].trim()
                }

                if (payload.isNotEmpty() && !payload.startsWith("NOTE")) {
                    val cue = parseCuePayload(payload, baseUrl, startMs, endMs)
                    if (cue != null) {
                        result.add(cue)
                    }
                }
                i = j
            }
            i++
        }
        return result
    }

    private fun parseTime(h: String, m: String, s: String, ms: String): Long {
        val hours = if (h.isNotEmpty()) h.toLong() else 0L
        val minutes = m.toLong()
        val seconds = s.toLong()
        val millis = ms.toLong()
        return hours * 3600000L + minutes * 60000L + seconds * 1000L + millis
    }

    private fun parseCuePayload(payload: String, baseUrl: String, startMs: Long, endMs: Long): VttThumbnailCue? {
        val rawUrl = payload.substringBefore('#').trim()
        val resolvedUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            baseUrl + rawUrl
        }

        val fragment = payload.substringAfter('#', "")
        var x = 0
        var y = 0
        var w = 0
        var h = 0

        if (fragment.startsWith("xywh=")) {
            val parts = fragment.removePrefix("xywh=").split(',')
            if (parts.size == 4) {
                x = parts[0].toIntOrNull() ?: 0
                y = parts[1].toIntOrNull() ?: 0
                w = parts[2].toIntOrNull() ?: 0
                h = parts[3].toIntOrNull() ?: 0
            }
        }

        return VttThumbnailCue(startMs, endMs, resolvedUrl, x, y, w, h)
    }

    private fun clear() {
        currentJob?.cancel()
        cues = emptyList()
        tileCache.clear()
        spriteCache.clear()
        durationMs = 0L
        loadedImages = 0
    }

    override fun release() {
        clear()
    }
}

private class M3u8PreviewGenerator(override var params: ImageParams) : IPreviewGenerator {
    // generated images 1:1 to idx of hsl
    private var images: Array<Bitmap?> = arrayOf()

    companion object {
        private const val TAG = "PreviewImgM3u8"
    }

    // prefixSum[i] = sum(hsl.ts[0..i].time)
    // where [0] = 0, [1] = hsl.ts[0].time aka time at start of segment, do [b] - [a] for range a,b
    private var prefixSum: Array<Double> = arrayOf()

    // how many images has been generated
    override var loadedImages: Int = 0

    // how many images we can generate in total, == hsl.size ?: 0
    private var totalImages: Int = 0

    private var vttGenerator: WebVttPreviewGenerator? = null

    override fun hasPreview(): Boolean {
        return vttGenerator?.hasPreview() == true || (totalImages > 0 && loadedImages >= minOf(totalImages, 4))
    }

    override fun getPreviewImage(fraction: Float): Bitmap? {
        if (vttGenerator?.hasPreview() == true) {
            val vttImg = vttGenerator?.getPreviewImage(fraction)
            if (vttImg != null) return vttImg
        }

        var bestIdx = -1
        var bestDiff = Double.MAX_VALUE
        synchronized(images) {
            // just find the best one in a for loop, we don't care about bin searching rn
            for (i in images.indices) {
                if (i >= prefixSum.size) break
                val diff = prefixSum[i].minus(fraction).absoluteValue
                if (diff > bestDiff) {
                    break
                }
                if (images[i] != null) {
                    bestIdx = i
                    bestDiff = diff
                }
            }
            return images.getOrNull(bestIdx)
        }
    }

    private fun clear() {
        synchronized(images) {
            currentJob?.cancel()
            vttGenerator?.release()
            vttGenerator = null
            images = arrayOf()
            prefixSum = arrayOf()
            loadedImages = 0
            totalImages = 0
        }
    }

    override fun release() {
        clear()
        images = arrayOf()
    }

    override var durationMs: Long = 0L

    private var currentJob: Job? = null
    fun load(url: String, headers: Map<String, String>) {
        clear()
        currentJob?.cancel()
        currentJob = ioSafe {
            withContext(Dispatchers.IO) {
                Log.i(TAG, "Loading with url = $url headers = $headers")

                // Probe for HLS WebVTT image stream tile playlist
                try {
                    val rawPlaylist = try {
                        val conn = java.net.URI(url).toURL().openConnection()
                        conn.connectTimeout = 4000
                        conn.readTimeout = 6000
                        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        conn.getInputStream().bufferedReader().readText()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to read raw playlist for image stream: ${t.message}")
                        null
                    }
                    if (rawPlaylist != null) {
                        val imageStreamRegex = Regex("#EXT-X-IMAGE-STREAM-INF:[^\\r\\n]*URI=\"([^\"]+)\"")
                        val match = imageStreamRegex.find(rawPlaylist)
                        if (match != null) {
                            val vttRel = match.groupValues[1]
                            val vttUrl = if (vttRel.startsWith("http://") || vttRel.startsWith("https://")) {
                                vttRel
                            } else {
                                url.substringBeforeLast('/') + "/" + vttRel
                            }
                            val vttGen = WebVttPreviewGenerator(params)
                            vttGen.load(vttUrl, headers)
                            vttGenerator = vttGen
                            Log.i(TAG, "Discovered HLS image stream: $vttUrl")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "HLS image stream probe failed: ${t.message}")
                }

                val retriever = MediaMetadataRetriever()
                val hsl = M3u8Helper2.hslLazy(
                    M3u8Helper.M3u8Stream(
                        streamUrl = url,
                        headers = headers
                    ),
                    selectBest = false,
                    requireAudio = false,
                )

                // no support for encryption atm
                if (hsl.isEncrypted) {
                    Log.i(TAG, "m3u8 is encrypted")
                    totalImages = 0
                    return@withContext
                }

                // total duration of the entire m3u8 in seconds
                val duration = hsl.allTsLinks.sumOf { it.time ?: 0.0 }
                durationMs = (duration * 1000.0).toLong()
                val durationInv = 1.0 / duration

                // if the total duration is less then 10s then something is very wrong or
                // too short playback to matter
                if (duration <= 10.0) {
                    totalImages = 0
                    return@withContext
                }

                totalImages = hsl.allTsLinks.size

                // we cant init directly as it is no guarantee of in order
                prefixSum = Array(hsl.allTsLinks.size + 1) { 0.0 }
                var runningSum = 0.0
                for (i in hsl.allTsLinks.indices) {
                    runningSum += (hsl.allTsLinks[i].time ?: 0.0)
                    prefixSum[i + 1] = runningSum * durationInv
                }
                synchronized(images) {
                    images = Array(hsl.size) { null }
                    loadedImages = 0
                }

                val maxLod = ceil(log2(duration)).toInt().coerceIn(MIN_LOD, MAX_LOD)
                val count = hsl.allTsLinks.size
                for (l in 1..maxLod) {
                    val items = (1 shl (l - 1))
                    for (i in 0 until items) {
                        val index = (count.div(1 shl l) + (i * count) / items).coerceIn(0, (hsl.size - 1).coerceAtLeast(0))
                        if (synchronized(images) { images[index] } != null) {
                            continue
                        }
                        Log.i(TAG, "Generating preview for $index")

                        val ts = hsl.allTsLinks[index]
                        try {
                            retriever.setDataSource(ts.url, hsl.headers)
                            if (!isActive) {
                                return@withContext
                            }
                            val img = retriever.image(0, params)
                            if (!isActive) {
                                return@withContext
                            }
                            if (img == null || img.width <= 1 || img.height <= 1) continue
                            synchronized(images) {
                                images[index] = img
                                loadedImages += 1
                            }
                        } catch (t: Throwable) {
                            logError(t)
                            continue
                        }
                    }
                }
            }
        }
    }
}

private class Mp4PreviewGenerator(override var params: ImageParams) : IPreviewGenerator {
    // lod = level of detail where the number indicates how many ones there is
    // 2^(lod-1) = images
    private var loadedLod = 0
    override var loadedImages = 0
    private var images = Array<Bitmap?>((1 shl MAX_LOD) - 1) {
        null
    }

    companion object {
        private const val TAG = "PreviewImgMp4"
    }

    override fun hasPreview(): Boolean {
        synchronized(images) {
            return loadedLod >= MIN_LOD
        }
    }

    override fun getPreviewImage(fraction: Float): Bitmap? {
        synchronized(images) {
            if (loadedLod < MIN_LOD) {
                Log.i(TAG, "Requesting preview for $fraction but $loadedLod < $MIN_LOD")
                return null
            }
            Log.i(TAG, "Requesting preview for $fraction")

            var bestIdx = 0
            var bestDiff = 0.5f.minus(fraction).absoluteValue

            // this should be done mathematically, but for now we just loop all images
            for (l in 1..loadedLod + 1) {
                val items = (1 shl (l - 1))
                for (i in 0 until items) {
                    val idx = items - 1 + i
                    if (idx > loadedImages) {
                        break
                    }
                    if (images[idx] == null) {
                        continue
                    }
                    val currentFraction =
                        (1.0f.div((1 shl l).toFloat()) + i * 1.0f.div(items.toFloat()))
                    val diff = currentFraction.minus(fraction).absoluteValue
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestIdx = idx
                    }
                }
            }
            Log.i(TAG, "Best diff found at ${bestDiff * 100}% diff (${bestIdx})")
            return images[bestIdx]
        }
    }

    // also check out https://github.com/wseemann/FFmpegMediaMetadataRetriever
    private val retriever: MediaMetadataRetriever = MediaMetadataRetriever()

    private fun clear(keepCache: Boolean) {
        if (keepCache) return
        synchronized(images) {
            loadedLod = 0
            loadedImages = 0
            images.fill(null)
        }
    }

    private var currentJob: Job? = null
    fun load(url: String, headers: Map<String, String>) {
        currentJob?.cancel()
        currentJob = ioSafe {
            Log.i(TAG, "Loading with url = $url headers = $headers")
            clear(true)
            retriever.setDataSource(url, headers)
            start(this)
        }
    }

    fun load(keepCache: Boolean, context: Context, uri: Uri) {
        currentJob?.cancel()
        currentJob = ioSafe {
            Log.i(TAG, "Loading with uri = $uri")
            clear(keepCache)
            retriever.setDataSource(context, uri)
            start(this)
        }
    }

    override fun release() {
        currentJob?.cancel()
        clear(false)
        retriever.release()
    }

    override var durationMs: Long = 0L

    @Throws
    @WorkerThread
    private fun start(scope: CoroutineScope) {
        Log.i(TAG, "Started loading preview")

        val durationMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                ?: throw IllegalArgumentException("Bad video duration")
        this.durationMs = durationMs
        val durationUs = (durationMs * 1000L).toFloat()

        // log2 # 10s durations in the video ~= how many segments we have
        val maxLod = ceil(log2((durationMs / 10_000).toFloat())).toInt().coerceIn(MIN_LOD, MAX_LOD)

        for (l in 1..maxLod) {
            val items = (1 shl (l - 1))
            for (i in 0 until items) {
                val idx = items - 1 + i // as sum(prev) = cur-1
                // frame = 100 / 2^lod + i * 100 / 2^(lod-1) = duration % where lod is one indexed
                val fraction = (1.0f.div((1 shl l).toFloat()) + i * 1.0f.div(items.toFloat()))
                Log.i(TAG, "Generating preview for ${fraction * 100}%")
                val frame = durationUs * fraction
                val img = retriever.image(frame.toLong(), params)
                if (!scope.isActive) return
                if (img == null || img.width <= 1 || img.height <= 1) continue
                synchronized(images) {
                    images[idx] = img
                    loadedImages = maxOf(loadedImages, idx)
                }
            }

            synchronized(images) {
                loadedLod = maxOf(loadedLod, l)
            }
        }
    }
}
