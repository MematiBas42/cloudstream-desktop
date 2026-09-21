package com.lagradost.common.download

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Lifecycle execution states for media downloads.
 */
enum class DownloadStatus {
    Pending,
    Downloading,
    Paused,
    Completed,
    Failed,
    Stopped;

    @get:JsonIgnore
    val isTerminal: Boolean
        get() = this == Completed || this == Failed || this == Stopped

    companion object {
        val PENDING: DownloadStatus get() = Pending
        val DOWNLOADING: DownloadStatus get() = Downloading
        val PAUSED: DownloadStatus get() = Paused
        val COMPLETED: DownloadStatus get() = Completed
        val FAILED: DownloadStatus get() = Failed
        val STOPPED: DownloadStatus get() = Stopped
    }
}

/**
 * Backward-compatibility alias for the specification's DownloadState.
 */
typealias DownloadState = DownloadStatus

/**
 * Requested user actions dispatched to active downloaders.
 */
enum class DownloadAction {
    PAUSE,
    RESUME,
    CANCEL
}

/**
 * Descriptor of a remote media stream link or mirror candidate.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadLink(
    @param:JsonProperty("url") val url: String,
    @param:JsonProperty("name") val name: String = "Default",
    @param:JsonProperty("source") val source: String = "",
    @param:JsonProperty("referer") val referer: String = "",
    @param:JsonProperty("quality") val quality: Int = 0,
    @param:JsonProperty("type") val type: String = "video",
    @param:JsonProperty("headers") val headers: Map<String, String> = emptyMap(),
    @param:JsonProperty("extractorData") val extractorData: String? = null
) {
    @get:JsonIgnore
    val isM3u8: Boolean
        get() = type.equals("m3u8", ignoreCase = true) ||
                url.contains(".m3u8", ignoreCase = true) ||
                name.contains("m3u8", ignoreCase = true)

    @JsonIgnore
    fun getAllHeaders(): Map<String, String> {
        if (referer.isBlank()) return headers
        if (headers.keys.none { it.equals("referer", ignoreCase = true) }) {
            return headers + mapOf("referer" to referer)
        }
        return headers
    }
}

/**
 * Domain alias for extractor links within the common download subsystem.
 */
typealias ExtractorLink = DownloadLink

/**
 * Subtitle track information associated with a downloaded video.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleDownloadItem(
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("url") val url: String,
    @param:JsonProperty("languageCode") val languageCode: String,
    @param:JsonProperty("headers") val headers: Map<String, String> = emptyMap()
)

/**
 * Episode-level or media metadata matching upstream DownloadEpisodeMetadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadEpisodeMetadata(
    @param:JsonProperty("id") val id: Int,
    @param:JsonProperty("parentId") val parentId: Int = 0,
    @param:JsonProperty("mainName") val mainName: String,
    @param:JsonProperty("sourceApiName") val sourceApiName: String? = null,
    @param:JsonProperty("poster") val poster: String? = null,
    @param:JsonProperty("name") val name: String? = null,
    @param:JsonProperty("season") val season: Int? = null,
    @param:JsonProperty("episode") val episode: Int? = null,
    @param:JsonProperty("type") val type: String? = null
)

/**
 * Canonical media download descriptor matching upstream DownloadItem.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadItem(
    @param:JsonProperty("source") val source: String? = null,
    @param:JsonProperty("folder") val folder: String? = null,
    @param:JsonProperty("ep") val ep: DownloadEpisodeMetadata,
    @param:JsonProperty("links") val links: List<DownloadLink> = emptyList(),
    @param:JsonProperty("subtitles") val subtitles: List<SubtitleDownloadItem> = emptyList()
) {
    @get:JsonIgnore
    val id: Int get() = ep.id

    @get:JsonIgnore
    val parentId: Int get() = ep.parentId

    @get:JsonIgnore
    val titleName: String get() = ep.mainName

    @get:JsonIgnore
    val episodeName: String? get() = ep.name

    /**
     * Secondary constructor for direct creation without manual DownloadEpisodeMetadata nesting.
     */
    constructor(
        id: Int,
        parentId: Int = 0,
        titleName: String,
        episodeName: String? = null,
        season: Int? = null,
        episode: Int? = null,
        links: List<DownloadLink> = emptyList(),
        folder: String? = null,
        poster: String? = null,
        sourceApiName: String? = null,
        type: String? = null,
        subtitles: List<SubtitleDownloadItem> = emptyList()
    ) : this(
        source = sourceApiName,
        folder = folder,
        ep = DownloadEpisodeMetadata(
            id = id,
            parentId = parentId,
            mainName = titleName,
            sourceApiName = sourceApiName,
            poster = poster,
            name = episodeName,
            season = season,
            episode = episode,
            type = type
        ),
        links = links,
        subtitles = subtitles
    )

    fun toDescriptor(): DownloadItemDescriptor = DownloadItemDescriptor(
        id = ep.id,
        parentId = ep.parentId,
        titleName = ep.mainName,
        episodeName = ep.name,
        season = ep.season,
        episode = ep.episode,
        tvType = ep.type,
        isMovie = ep.season == null && ep.episode == null,
        posterUrl = ep.poster,
        apiName = ep.sourceApiName ?: "",
        sourceUrl = source ?: "",
        links = links,
        subtitles = subtitles
    )
}

/**
 * Resume checkpoint matching upstream DownloadResumePackage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadResumePackage(
    @param:JsonProperty("item") val item: DownloadItem,
    @param:JsonProperty("linkIndex") val linkIndex: Int? = 0,
    @param:JsonProperty("bytesDownloaded") val bytesDownloaded: Long = 0L,
    @param:JsonProperty("totalBytes") val totalBytes: Long = 0L,
    @param:JsonProperty("extraInfo") val extraInfo: String? = null
) {
    fun toWrapper(): DownloadQueueWrapper = DownloadQueueWrapper(resumePackage = this)
}

/**
 * Backward-compatibility alias for the specification's DownloadResumeState.
 */
typealias DownloadResumeState = DownloadResumePackage

/**
 * Flat descriptor for simplified queue interaction matching Section 4.2 of the specification.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadItemDescriptor(
    @param:JsonProperty("id") val id: Int,
    @param:JsonProperty("parentId") val parentId: Int = 0,
    @param:JsonProperty("titleName") val titleName: String,
    @param:JsonProperty("episodeName") val episodeName: String? = null,
    @param:JsonProperty("season") val season: Int? = null,
    @param:JsonProperty("episode") val episode: Int? = null,
    @param:JsonProperty("tvType") val tvType: String? = null,
    @param:JsonProperty("isMovie") val isMovie: Boolean = false,
    @param:JsonProperty("posterUrl") val posterUrl: String? = null,
    @param:JsonProperty("apiName") val apiName: String = "",
    @param:JsonProperty("sourceUrl") val sourceUrl: String = "",
    @param:JsonProperty("links") val links: List<DownloadLink> = emptyList(),
    @param:JsonProperty("subtitles") val subtitles: List<SubtitleDownloadItem> = emptyList()
) {
    fun toItem(): DownloadItem = DownloadItem(
        id = id,
        parentId = parentId,
        titleName = titleName,
        episodeName = episodeName,
        season = season,
        episode = episode,
        links = links,
        folder = null,
        poster = posterUrl,
        sourceApiName = apiName,
        type = tvType,
        subtitles = subtitles
    )
}

/**
 * Queue item wrapper matching upstream DownloadQueueWrapper.
 * Holds either an existing DownloadResumePackage or a fresh DownloadItem / DownloadItemDescriptor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DownloadQueueWrapper(
    @param:JsonProperty("resumePackage") val resumePackage: DownloadResumePackage? = null,
    @param:JsonProperty("downloadItem") val downloadItem: DownloadItem? = null,
    @param:JsonProperty("descriptor") val descriptor: DownloadItemDescriptor? = null
) {
    init {
        require(resumePackage != null || downloadItem != null || descriptor != null) {
            "DownloadQueueWrapper must contain a resumePackage, downloadItem, or descriptor"
        }
    }

    @JsonProperty("id")
    val id: Int = resumePackage?.item?.id ?: downloadItem?.id ?: descriptor?.id ?: 0

    @JsonProperty("parentId")
    val parentId: Int = resumePackage?.item?.parentId ?: downloadItem?.parentId ?: descriptor?.parentId ?: 0

    fun toItem(): DownloadItem = resumePackage?.item ?: downloadItem ?: descriptor!!.toItem()
}

/**
 * Storage record for completed local media files.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalDownloadedFileInfo(
    @param:JsonProperty("id") val id: Int,
    @param:JsonProperty("parentId") val parentId: Int = 0,
    @param:JsonProperty("absoluteFilePath") val absoluteFilePath: String,
    @param:JsonProperty("displayName") val displayName: String,
    @param:JsonProperty("totalBytes") val totalBytes: Long,
    @param:JsonProperty("completedTime") val completedTime: Long = System.currentTimeMillis(),
    @param:JsonProperty("extraInfo") val extraInfo: String? = null
)

/**
 * Real-time progress update event for UI StateFlow bindings.
 */
data class DownloadProgress(
    val id: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val state: DownloadStatus,
    val errorMessage: String? = null,
    val activeMirrorIndex: Int = 0,
    val totalMirrors: Int = 1
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val progressPercentage: Int
        get() = (progressFraction * 100).toInt()
}
