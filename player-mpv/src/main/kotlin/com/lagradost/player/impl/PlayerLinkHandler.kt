package com.lagradost.player.impl

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.io.File

/**
 * Normalizes ExtractorLink data for external players (MPV / VLC).
 * Mirrors Android CS3IPlayer header handling: referer + custom headers on every request.
 */
object PlayerLinkHandler {

    enum class PlayerBackend { MPV, VLC, KODI }

    data class PlaybackSupport(val supported: Boolean, val reason: String? = null)

    const val MIN_DURATION_TO_SAVE_SECONDS = 30L
    const val RESUME_RESET_PERCENT = 95L
    const val RESUME_MIN_PERCENT = 1L

    data class DrmInfo(
        val kid: String?,
        val key: String?,
        val uuid: String?,
        val licenseUrl: String?,
    )

    data class ValidatedLink(
        val url: String,
        val displayTitle: String,
        val headers: Map<String, String>,
        val streamKind: StreamKind,
        val useUrlFile: Boolean,
        val audioTracks: List<com.lagradost.cloudstream3.AudioFile> = emptyList(),
        val proxySessionId: String? = null,
        val drmInfo: DrmInfo? = null,
        val lastKnownPositionMs: Long = 0L,
    ) {
        val startPositionSeconds: Double
            get() = if (lastKnownPositionMs > 0L) lastKnownPositionMs / 1000.0 else 0.0

        fun toLoadFileCommand(mode: String = "replace"): List<Any> {
            return buildLoadFileCommand(url, mode, lastKnownPositionMs)
        }
    }

    enum class StreamKind {
        HLS,
        DASH,
        PROGRESSIVE,
    }

    fun validate(
        link: ExtractorLink,
        explicitTitle: String? = null,
        useLocalProxy: Boolean = false,
        lastKnownPositionMs: Long = 0L,
    ): Result<ValidatedLink> {
        // Check for DRM (ClearKey/Widevine) - only ClearKey is playable on desktop
        val drmInfo = if (link is com.lagradost.cloudstream3.utils.DrmExtractorLink) {
            @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
            DrmInfo(
                kid = link.kid,
                key = link.key,
                uuid = link.uuid.toString(),
                licenseUrl = link.licenseUrl,
            )
        } else {
            null
        }

        // --- Handle ExtractorLinkPlayList (concatenated chunk streams) ---
        // These have url="" but provide a list of chunk URLs + durations.
        // We generate an MPV EDL (Edit Decision List) file to play them seamlessly.
        if (link is ExtractorLinkPlayList) {
            if (link.playlist.isEmpty()) {
                return Result.failure(IllegalArgumentException("ExtractorLinkPlayList has empty playlist."))
            }
            val headers = buildHeaderMap(link)
            val display = sanitizeDisplayTitle(explicitTitle?.takeIf { it.isNotBlank() } ?: link.name)
            val edlFile = writeEdlFile(link.playlist, headers)
            return Result.success(
                ValidatedLink(
                    url = edlFile.absolutePath,
                    displayTitle = display,
                    headers = headers,
                    streamKind = StreamKind.PROGRESSIVE,
                    useUrlFile = false,
                    drmInfo = drmInfo,
                    lastKnownPositionMs = lastKnownPositionMs.coerceAtLeast(0L),
                ),
            )
        }

        val url = link.url.trim()
        if (url.isBlank()) {
            return Result.failure(IllegalArgumentException("Stream URL is empty."))
        }
        val isLocalFile = url.startsWith("file://", ignoreCase = true) ||
            url.startsWith("/") ||
            (url.length > 2 && url[1] == ':' && (url[2] == '\\' || url[2] == '/'))

        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true) &&
            !isLocalFile
        ) {
            return Result.failure(IllegalArgumentException("Unsupported stream URL scheme: ${url.take(12)}..."))
        }

        val headers = buildHeaderMap(link)
        if (headers.values.any { it.isBlank() }) {
            return Result.failure(IllegalArgumentException("Stream headers contain invalid empty values."))
        }

        val kind = when {
            link.isM3u8 || link.type == ExtractorLinkType.M3U8 -> StreamKind.HLS
            link.isDash || link.type == ExtractorLinkType.DASH -> StreamKind.DASH
            else -> StreamKind.PROGRESSIVE
        }

        // For HLS streams, route through the LocalStreamProxy.
        // The proxy fetches the .m3u8 manifest, rewrites all segment URLs to go through
        // localhost:8080, and injects the correct auth headers on every segment request.
        // This makes the stream appear as a seamless local HLS feed to MPV.
        val useProxy = !isLocalFile && useLocalProxy &&
            (kind == StreamKind.HLS ||
                (kind == StreamKind.DASH && (headers.isNotEmpty() || drmInfo?.key?.isNotBlank() == true)))
        var finalSessionId: String? = null
        val resolvedUrl = if (url.startsWith("file://", ignoreCase = true)) {
            val stripped = url.substring(7)
            if (stripped.length > 3 && stripped.startsWith("/") && stripped[2] == ':' && (stripped[3] == '/' || stripped[3] == '\\')) {
                stripped.substring(1)
            } else {
                stripped
            }
        } else {
            url
        }
        val finalUrl = if (useProxy) {
            val drmKeyBytes = drmInfo?.key?.let(::decodeDrmBase64)
            val kidHex = drmInfo?.kid?.let(::decodeDrmBase64)
                ?.joinToString("") { "%02x".format(it) }
            val sessionId = com.lagradost.player.impl.proxy.LocalStreamProxy.registerSession(headers, drmKeyBytes, kidHex)
            finalSessionId = sessionId
            com.lagradost.player.impl.proxy.LocalStreamProxy.buildProxyUrl(sessionId, resolvedUrl)
        } else {
            resolvedUrl
        }

        val finalHeaders = if (useProxy) emptyMap() else headers

        val display = sanitizeDisplayTitle(explicitTitle?.takeIf { it.isNotBlank() } ?: link.name)

        val finalAudioTracks = if (useProxy && finalSessionId != null) {
            link.audioTracks.map { audio ->
                try {
                    val ctor = com.lagradost.cloudstream3.AudioFile::class.java.declaredConstructors.firstOrNull {
                        it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java
                    } ?: com.lagradost.cloudstream3.AudioFile::class.java.declaredConstructors.first()
                    ctor.isAccessible = true
                    val proxiedUrl = com.lagradost.player.impl.proxy.LocalStreamProxy.buildProxyUrl(finalSessionId, audio.url)
                    if (ctor.parameterTypes.size == 2) {
                        ctor.newInstance(proxiedUrl, audio.headers) as com.lagradost.cloudstream3.AudioFile
                    } else {
                        audio
                    }
                } catch (_: Exception) {
                    audio
                }
            }
        } else {
            link.audioTracks
        }

        return Result.success(
            ValidatedLink(
                url = finalUrl,
                displayTitle = display,
                headers = finalHeaders,
                streamKind = kind,
                useUrlFile = !isLocalFile && !useProxy && (finalUrl.length > 1800 || finalUrl.count { it == '&' } > 8),
                audioTracks = finalAudioTracks,
                proxySessionId = finalSessionId,
                drmInfo = drmInfo,
                lastKnownPositionMs = lastKnownPositionMs.coerceAtLeast(0L),
            ),
        )
    }

    /**
     * Loads and validates an ExtractorLink for player playback.
     * Supports both online streams (HTTP/HTTPS) and offline media files (file://, local paths).
     * Optionally accepts [lastKnownPositionMs] to maintain playback continuity across link switches.
     */
    fun loadLink(
        link: ExtractorLink,
        explicitTitle: String? = null,
        lastKnownPositionMs: Long = 0L,
    ): Result<ValidatedLink> {
        return validate(
            link = link,
            explicitTitle = explicitTitle,
            useLocalProxy = false,
            lastKnownPositionMs = lastKnownPositionMs,
        )
    }

    /**
     * Prepares in-flight mirror fallback for a backup candidate mirror, strictly preserving
     * [lastKnownPositionMs] across a seamless `loadfile replace` transition.
     *
     * In-flight mirror fallback contract:
     * - Preserves the exact millisecond timestamp [lastKnownPositionMs] (no 5% truncation or reset to 0)
     * - Validates candidate mirror URL, stream headers, and DRM attributes
     * - Emits a [ValidatedLink] carrying the preserved position and ready for `loadfile <url> replace`
     */
    fun prepareMirrorFallback(
        candidateLink: ExtractorLink,
        lastKnownPositionMs: Long,
        explicitTitle: String? = null,
        useLocalProxy: Boolean = false,
    ): Result<ValidatedLink> {
        val safePosition = lastKnownPositionMs.coerceAtLeast(0L)
        return validate(
            link = candidateLink,
            explicitTitle = explicitTitle,
            useLocalProxy = useLocalProxy,
            lastKnownPositionMs = safePosition,
        )
    }

    /**
     * Resolves the playback position to use during player loading or mirror fallback.
     *
     * Behavioral parity with upstream GeneratorPlayer / CS3IPlayer:
     * - When [sameEpisode] is true (mirror fallback or mirror switch within the same media):
     *   The video MUST resume at the exact timestamp [lastKnownPositionMs], bypassing the
     *   5% intro and 95% completion reset thresholds of [resumeStartSeconds].
     *   If [startPositionMs] is explicitly provided (> 0), it takes precedence.
     * - When [sameEpisode] is false (new media or episode):
     *   Uses [startPositionMs] if provided, else 0L.
     */
    fun resolveFallbackPosition(
        sameEpisode: Boolean,
        lastKnownPositionMs: Long,
        startPositionMs: Long? = null,
    ): Long {
        if (sameEpisode) {
            return when {
                startPositionMs != null && startPositionMs > 0L -> startPositionMs
                lastKnownPositionMs > 0L -> lastKnownPositionMs
                else -> 0L
            }
        }
        return (startPositionMs ?: 0L).coerceAtLeast(0L)
    }

    /**
     * Formats an MPV start option string ("start=%.3f") with millisecond precision.
     * Used by `loadfile` IPC command options so MPV seeks immediately on file load.
     */
    fun formatMpvStartOption(positionMs: Long): String {
        if (positionMs <= 0L) return "start=0"
        val seconds = positionMs / 1000.0
        return "start=%.3f".format(java.util.Locale.US, seconds)
    }

    /**
     * Builds the MPV JSON-RPC command array for `loadfile`.
     *
     * Syntax:
     * - Without position (positionMs <= 0): `["loadfile", url, mode]`
     * - With positionMs > 0: `["loadfile", url, mode, "start=%.3f"]`
     *
     * In MPV IPC, passing `"start=..."` as the options argument guarantees that playback
     * resumes at the exact timestamp without a perceptible pause or reset to 0.
     */
    fun buildLoadFileCommand(
        url: String,
        mode: String = "replace",
        positionMs: Long = 0L,
    ): List<Any> {
        return if (positionMs > 0L) {
            listOf("loadfile", url, mode, formatMpvStartOption(positionMs))
        } else {
            listOf("loadfile", url, mode)
        }
    }

    /**
     * Builds the command-line or IPC arguments list for `loadfile replace`.
     */
    fun buildLoadFileArgs(
        url: String,
        positionMs: Long = 0L,
        mode: String = "replace",
    ): List<String> {
        return if (positionMs > 0L) {
            listOf("loadfile", url, mode, formatMpvStartOption(positionMs))
        } else {
            listOf("loadfile", url, mode)
        }
    }

    private fun decodeDrmBase64(value: String): ByteArray? {
        val normalized = value.trim().padEnd((value.trim().length + 3) / 4 * 4, '=')
        return runCatching { java.util.Base64.getUrlDecoder().decode(normalized) }.getOrElse {
            runCatching { java.util.Base64.getDecoder().decode(normalized) }.getOrNull()
        }
    }

    fun buildHeaderMap(link: ExtractorLink): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        link.getAllHeaders().forEach { (key, value) ->
            val cleaned = value.replace("\r", "").replace("\n", "").trim()
            if (key.isNotBlank() && cleaned.isNotEmpty()) {
                merged[key] = cleaned
            }
        }
        if (merged.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            merged["User-Agent"] = com.lagradost.cloudstream3.USER_AGENT
        }
        return merged
    }

    /** Capability gate shared by the UI and player implementations. */
    fun playbackSupport(link: ExtractorLink, backend: PlayerBackend): PlaybackSupport {
        val drmHeader = link.getAllHeaders().keys.any { it.equals("x-drm", ignoreCase = true) }
        val drm = link as? com.lagradost.cloudstream3.utils.DrmExtractorLink
        val hasLicenseDrm = drmHeader || !drm?.licenseUrl.isNullOrBlank()
        val hasClearKey = drm?.kid?.isNotBlank() == true && drm.key?.isNotBlank() == true

        if (hasLicenseDrm) {
            return PlaybackSupport(
                false,
                "This stream uses license-server DRM. $backend cannot play it through the desktop app; use a legally configured DRM-capable browser or Kodi inputstream.adaptive integration.",
            )
        }
        if (drm != null && !hasClearKey) {
            return PlaybackSupport(false, "This stream contains unsupported or incomplete DRM metadata.")
        }
        if (hasClearKey && backend != PlayerBackend.MPV) {
            return PlaybackSupport(false, "ClearKey streams are only supported by the embedded MPV backend.")
        }
        return PlaybackSupport(true)
    }

    fun sanitizeDisplayTitle(raw: String): String {
        return raw
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(",", " ")
            .trim()
            .take(120)
            .ifBlank { "CloudStream" }
    }

    /**
     * Resume position in seconds for MPV/VLC --start.
     * Aligns with Android [getPos] and WatchHistoryRepository:
     * - Normalizes input to milliseconds if needed
     * - Returns 0 if < 30s duration, <= 5% watched (intro/recap), or >= 95% watched (credits/completed)
     * - Returns position converted to seconds
     */
    fun resumeStartSeconds(position: Long, duration: Long): Long {
        if (position <= 0) return 0
        val normalizedDur = if (duration in 1 until 30_000L) duration * 1000L else duration
        val normalizedPos = if (duration in 1 until 30_000L) position * 1000L else position
        if (normalizedDur < 30_000L) return 0
        val percent = normalizedPos * 100 / normalizedDur
        if (percent >= 95L || percent <= 5L) return 0
        return (normalizedPos / 1000L).coerceAtLeast(0)
    }

    fun isCompleted(position: Long, duration: Long): Boolean {
        if (position <= 0 || duration <= 0) return false
        val percent = position * 100 / duration
        return percent >= RESUME_RESET_PERCENT
    }

    private object PlayerCacheManager {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "cloudstream_player_cache")
        init {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            cacheDir.listFiles()?.forEach { it.delete() }
        }
        fun getTempFile(prefix: String, suffix: String): File {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            return File(cacheDir, "${prefix}_${System.nanoTime()}$suffix")
        }
    }

    /** Plain URL list file — not a fake HLS manifest (which breaks MKV/MP4/DASH). */
    fun writeUrlListFile(prefix: String, title: String, url: String): File {
        val file = PlayerCacheManager.getTempFile(prefix, ".m3u")
        file.deleteOnExit()
        file.writeText(
            buildString {
                appendLine("#EXTM3U")
                appendLine("#EXTINF:-1,$title")
                appendLine(url)
            },
        )
        return file
    }

    fun writeMpvConfig(
        headers: Map<String, String>,
        subtitles: List<String>,
        audioTracks: List<com.lagradost.cloudstream3.AudioFile> = emptyList(),
    ): File {
        val file = PlayerCacheManager.getTempFile("mpv_stream_", ".conf")
        file.deleteOnExit()
        file.writeText(buildMpvConfigContent(headers, subtitles, audioTracks))
        return file
    }

    fun buildMpvConfigContent(
        headers: Map<String, String>,
        subtitles: List<String>,
        audioTracks: List<com.lagradost.cloudstream3.AudioFile> = emptyList(),
    ): String {
        return buildString {
            appendLine("# Generated by CloudStream Desktop")
            appendLine("hr-seek=yes")
            appendLine("cache=yes")
            appendLine("demuxer-max-bytes=64M")
            appendLine("demuxer-max-back-bytes=32M")
            appendLine("demuxer-lavf-o-append=reconnect=1")
            appendLine("demuxer-lavf-o-append=reconnect_streamed=1")
            appendLine("demuxer-lavf-o-append=reconnect_on_http_error=403,404,429,500,503")
            appendLine("demuxer-lavf-o-append=reconnect_delay_max=4")
            appendLine("stream-lavf-o-append=reconnect=1")
            appendLine("stream-lavf-o-append=reconnect_streamed=1")

            headers.forEach { (key, value) ->
                when {
                    key.equals("user-agent", ignoreCase = true) -> {
                        appendLine("user-agent=\"${escapeMpvValue(value)}\"")
                    }
                    key.equals("referer", ignoreCase = true) || key.equals("referrer", ignoreCase = true) -> {
                        appendLine("referrer=\"${escapeMpvValue(value)}\"")
                    }
                }
            }

            subtitles.forEach { sub ->
                if (sub.isNotBlank()) {
                    appendLine("sub-files-append=\"${escapeMpvValue(sub)}\"")
                }
            }

            audioTracks.forEach { audio ->
                if (audio.url.isNotBlank()) {
                    appendLine("audio-file-append=\"${escapeMpvValue(audio.url)}\"")
                }
            }
        }
    }

    /**
     * Builds the headers as a single MPV CLI argument.
     */
    fun buildHeadersCliArg(headers: Map<String, String>): List<String> {
        if (headers.isEmpty()) return emptyList()
        val extraHeaders = headers.filter { (key, _) ->
            !key.equals("user-agent", ignoreCase = true) &&
            !key.equals("referer", ignoreCase = true) &&
            !key.equals("referrer", ignoreCase = true)
        }
        if (extraHeaders.isEmpty()) return emptyList()

        val httpHeaderFields = extraHeaders.entries.joinToString(separator = ",") { (k, v) ->
            val cleanV = v.replace("\r", "").replace("\n", "").replace(",", "\\,")
            "$k: $cleanV"
        }
        return listOf("--http-header-fields=$httpHeaderFields")
    }

    /**
     * Generates an MPV EDL (Edit Decision List) file for [ExtractorLinkPlayList] streams.
     */
    fun writeEdlFile(
        playlist: List<com.lagradost.cloudstream3.utils.PlayListItem>,
        headers: Map<String, String>,
    ): File {
        val file = PlayerCacheManager.getTempFile("cloudstream_playlist_", ".edl")
        file.deleteOnExit()

        val content = buildString {
            appendLine("# mpv EDL v0")
            for (item in playlist) {
                if (item.url.isBlank()) continue
                val url = item.url.trim()
                if (item.durationUs > 0) {
                    append("%${item.durationUs}%")
                }
                appendLine(url)
            }
        }
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun buildVlcExtraHeaders(headers: Map<String, String>): String {
        return headers.entries.joinToString(separator = "\r\n") { (k, v) ->
            "$k: ${v.replace("\r", "").replace("\n", "")}"
        }
    }

    private fun escapeMpvValue(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace(",", "\\,")
    }
}
