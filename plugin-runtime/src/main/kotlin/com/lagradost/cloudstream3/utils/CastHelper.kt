// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/CastHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import androidx.media3.common.MimeTypes
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.actions.temp.fcast.FcastManager
import com.lagradost.cloudstream3.actions.temp.fcast.FcastSession
import com.lagradost.cloudstream3.actions.temp.fcast.Opcode
import com.lagradost.cloudstream3.actions.temp.fcast.PlayMessage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.MetadataHolder
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.common.logging.AppLogger
import kotlin.jvm.JvmName

/**
 * Image reference abstraction matching Google Cast WebImage contract for desktop casting.
 */
data class WebImage(val url: String) {
    constructor(uri: Any) : this(uri.toString())
    override fun toString(): String = url
}

/**
 * Metadata container matching Google Cast MediaMetadata contract for DLNA and FCast desktop streaming.
 */
data class MediaMetadata(
    val mediaType: Int = MEDIA_TYPE_MOVIE,
    val strings: MutableMap<String, String> = mutableMapOf(),
    val images: MutableList<WebImage> = mutableListOf()
) {
    companion object {
        const val MEDIA_TYPE_MOVIE = 1
        const val KEY_TITLE = "com.google.android.gms.cast.metadata.TITLE"
        const val KEY_SUBTITLE = "com.google.android.gms.cast.metadata.SUBTITLE"
    }

    fun putString(key: String, value: String) {
        strings[key] = value
    }

    fun getString(key: String): String? = strings[key]

    fun addImage(image: WebImage) {
        images.add(image)
    }

    fun addImage(uri: String) {
        images.add(WebImage(uri))
    }

    val title: String? get() = strings[KEY_TITLE] ?: strings["title"]
    val subtitle: String? get() = strings[KEY_SUBTITLE] ?: strings["subtitle"]
    val poster: String? get() = images.firstOrNull()?.url
}

/**
 * Subtitle and media track container matching Google Cast MediaTrack contract.
 */
data class MediaTrack(
    val id: Long,
    val type: Int,
    val name: String,
    val subtype: Int,
    val contentId: String,
    val language: String? = null
) {
    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_AUDIO = 2
        const val TYPE_VIDEO = 3
        const val SUBTYPE_SUBTITLES = 1
    }

    class Builder(private val id: Long, private val type: Int) {
        private var name: String = ""
        private var subtype: Int = SUBTYPE_SUBTITLES
        private var contentId: String = ""
        private var language: String? = null

        fun setName(name: String) = apply { this.name = name }
        fun setSubtype(subtype: Int) = apply { this.subtype = subtype }
        fun setContentId(contentId: String) = apply { this.contentId = contentId }
        fun setLanguage(language: String?) = apply { this.language = language }
        fun build() = MediaTrack(id, type, name, subtype, contentId, language)
    }
}

/**
 * Complete media description and payload container matching Google Cast MediaInfo
 * and providing DLNA / UPnP DIDL-Lite XML and FCast JSON serialization.
 */
data class MediaInfo(
    val url: String,
    val streamType: Int = STREAM_TYPE_BUFFERED,
    val contentType: String = MimeTypes.VIDEO_MP4,
    val metadata: MediaMetadata? = null,
    val mediaTracks: List<MediaTrack> = emptyList(),
    val customData: Any? = null
) {
    companion object {
        const val STREAM_TYPE_BUFFERED = 1
        const val STREAM_TYPE_LIVE = 2
    }

    class Builder(private val url: String) {
        private var streamType: Int = STREAM_TYPE_BUFFERED
        private var contentType: String = MimeTypes.VIDEO_MP4
        private var metadata: MediaMetadata? = null
        private var mediaTracks: List<MediaTrack> = emptyList()
        private var customData: Any? = null

        fun setStreamType(streamType: Int) = apply { this.streamType = streamType }
        fun setContentType(contentType: String) = apply { this.contentType = contentType }
        fun setMetadata(metadata: MediaMetadata?) = apply { this.metadata = metadata }
        fun setMediaTracks(mediaTracks: List<MediaTrack>) = apply { this.mediaTracks = mediaTracks }
        fun setCustomData(customData: Any?) = apply { this.customData = customData }
        fun build() = MediaInfo(url, streamType, contentType, metadata, mediaTracks, customData)
    }

    /**
     * Serializes this media info to a standard DIDL-Lite XML metadata payload
     * for DLNA / UPnP AVTransport streaming to Smart TVs.
     */
    fun toDidlLiteXml(): String {
        val title = metadata?.title ?: "Video"
        val subtitle = metadata?.subtitle
        val displayTitle = if (subtitle != null) "$title - $subtitle" else title
        val poster = metadata?.poster
        val escapedTitle = escapeXml(displayTitle)
        val escapedUrl = escapeXml(url)
        val escapedPoster = poster?.let { escapeXml(it) }

        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        sb.append("xmlns:sec=\"http://www.sec.co.kr/\">\n")
        sb.append("  <item id=\"0\" parentID=\"-1\" restricted=\"1\">\n")
        sb.append("    <dc:title>").append(escapedTitle).append("</dc:title>\n")
        sb.append("    <upnp:class>object.item.videoItem</upnp:class>\n")
        if (escapedPoster != null) {
            sb.append("    <upnp:albumArtURI>").append(escapedPoster).append("</upnp:albumArtURI>\n")
        }
        sb.append("    <res protocolInfo=\"http-get:*:").append(contentType).append(":*\">")
            .append(escapedUrl).append("</res>\n")
        for (track in mediaTracks) {
            sb.append("    <sec:CaptionInfo sec:type=\"srt\">")
                .append(escapeXml(track.contentId)).append("</sec:CaptionInfo>\n")
        }
        sb.append("  </item>\n")
        sb.append("</DIDL-Lite>")
        return sb.toString()
    }

    /**
     * Serializes this media info to a structured JSON payload for FCast / smart TV streaming.
     */
    fun toJsonPayload(): String {
        val map = mutableMapOf<String, Any?>()
        map["url"] = url
        map["contentType"] = contentType
        map["streamType"] = streamType
        metadata?.let { meta ->
            map["title"] = meta.title
            map["subtitle"] = meta.subtitle
            map["poster"] = meta.poster
        }
        if (mediaTracks.isNotEmpty()) {
            map["subtitles"] = mediaTracks.map { track ->
                mapOf(
                    "id" to track.id,
                    "name" to track.name,
                    "url" to track.contentId,
                    "language" to track.language
                )
            }
        }
        if (customData != null) {
            map["customData"] = customData
        }
        return map.toJson()
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

object CastHelper {
    private const val TAG = "CastHelper"

    fun getCastSession(): FcastSession? {
        return FcastManager.currentSession
    }

    /**
     * Constructs and populates 1:1 MediaInfo metadata payload matching upstream Google Cast contract,
     * fully equipped for DLNA DIDL-Lite and FCast smart TV streaming.
     */
    fun getMediaInfo(
        epData: ResultEpisode,
        holder: MetadataHolder,
        index: Int,
        data: Any?,
        subtitles: List<SubtitleData>
    ): MediaInfo {
        val link = holder.currentLinks[index]
        val movieMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        movieMetadata.putString(
            MediaMetadata.KEY_SUBTITLE,
            if (holder.isMovie)
                "${link.name} ${Qualities.getStringByInt(link.quality)}"
            else
                (epData.name ?: "Episode ${epData.episode}") + " - ${link.name} ${Qualities.getStringByInt(link.quality)}"
        )

        holder.title?.let {
            movieMetadata.putString(MediaMetadata.KEY_TITLE, it)
        }

        val srcPoster = epData.poster ?: holder.poster
        if (srcPoster != null) {
            movieMetadata.addImage(WebImage(srcPoster))
        }

        var subIndex = 0
        val tracks = subtitles.map {
            MediaTrack.Builder(subIndex++.toLong(), MediaTrack.TYPE_TEXT)
                .setName(it.name)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(it.url)
                .build()
        }

        val builder = MediaInfo.Builder(link.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(when (link.type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.VIDEO_MP4
            })
            .setMetadata(movieMetadata)
            .setMediaTracks(tracks)
        data?.let {
            builder.setCustomData(data)
        }

        return builder.build()
    }

    @JvmName("getMediaInfoAny")
    fun getMediaInfo(
        epData: ResultEpisode,
        holder: Any?,
        index: Int,
        data: Any?,
        subtitles: List<SubtitleData>
    ): MediaInfo? {
        return if (holder is MetadataHolder) {
            getMediaInfo(epData, holder, index, data, subtitles)
        } else {
            null
        }
    }

    fun awaitLinks(
        pending: Any?,
        callback: (Boolean) -> Unit
    ) {
        if (pending == null) return
        if (pending is Boolean) {
            callback.invoke(!pending)
        } else {
            callback.invoke(false)
        }
    }

    fun FcastSession?.startCast(
        apiName: String,
        isMovie: Boolean,
        title: String?,
        poster: String?,
        currentEpisodeIndex: Int,
        episodes: List<ResultEpisode>,
        currentLinks: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
        startIndex: Int? = null,
        startTime: Long? = null,
    ): Boolean {
        try {
            val session = this ?: getCastSession()
            if (session == null) {
                AppLogger.w(TAG, "startCast failed: No active FCast session")
                CommonActivity.showToast("No active cast device connected")
                return false
            }
            if (episodes.isEmpty()) {
                AppLogger.w(TAG, "startCast failed: Episode list is empty")
                CommonActivity.showToast("No episodes available to cast")
                return false
            }
            if (currentEpisodeIndex >= episodes.size) {
                AppLogger.w(TAG, "startCast failed: Episode index out of bounds: $currentEpisodeIndex >= ${episodes.size}")
                return false
            }

            val epData = episodes[currentEpisodeIndex]
            val index = if (startIndex == null || startIndex < 0) 0 else startIndex
            val link = currentLinks.getOrNull(index)
            if (link == null) {
                AppLogger.w(TAG, "startCast failed: No link available at index $index")
                CommonActivity.showToast("No stream link available for casting")
                return false
            }

            val holder = MetadataHolder(
                apiName = apiName,
                isMovie = isMovie,
                title = title,
                poster = poster,
                currentEpisodeIndex = currentEpisodeIndex,
                episodes = episodes,
                currentLinks = currentLinks,
                currentSubtitles = subtitles
            )

            // Populate 1:1 media info metadata payload for DLNA / smart TV / FCast streaming
            val mediaItem = getMediaInfo(epData, holder, index, holder.toJson(), subtitles)

            AppLogger.i(TAG, "Starting cast playback for ${epData.name ?: title} at link: ${link.url}")
            session.sendMessage(
                Opcode.Play,
                PlayMessage(
                    container = link.type.getMimeType(),
                    url = link.url,
                    content = mediaItem.toJsonPayload(),
                    time = (startTime ?: 0L) / 1000.0,
                    speed = 1.0,
                    headers = mapOf(
                        "referer" to link.referer,
                        "user-agent" to USER_AGENT
                    ) + link.headers
                )
            )

            awaitLinks(true) { failed ->
                if (failed && currentLinks.size > index + 1) {
                    startCast(
                        apiName,
                        isMovie,
                        title,
                        poster,
                        currentEpisodeIndex,
                        episodes,
                        currentLinks,
                        subtitles,
                        index + 1,
                        startTime
                    )
                }
            }

            CommonActivity.showToast("Casting to FCast receiver: ${title ?: epData.name}")
            return true
        } catch (e: Exception) {
            logError(e)
            AppLogger.e(TAG, "startCast failed with exception", e)
            CommonActivity.showToast("Cast error: ${e.message}")
            return false
        }
    }

    @JvmName("startCastAny")
    fun Any?.startCast(
        apiName: String,
        isMovie: Boolean,
        title: String?,
        poster: String?,
        currentEpisodeIndex: Int,
        episodes: List<ResultEpisode>,
        currentLinks: List<ExtractorLink>,
        subtitles: List<SubtitleData>,
        startIndex: Int? = null,
        startTime: Long? = null,
    ): Boolean {
        return (this as? FcastSession ?: getCastSession()).startCast(
            apiName,
            isMovie,
            title,
            poster,
            currentEpisodeIndex,
            episodes,
            currentLinks,
            subtitles,
            startIndex,
            startTime
        )
    }
}
