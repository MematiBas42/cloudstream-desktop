package com.lagradost.common.storage

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Represents watch progress and state for a media item (movie or TV episode).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchHistory(
    @JsonProperty("url")
    @JsonAlias("showUrl")
    val url: String,

    @JsonProperty("parentId")
    val parentId: String? = null,

    @JsonProperty("episodeId")
    val episodeId: String? = null,

    @JsonProperty("position")
    val position: Long = 0L,

    @JsonProperty("duration")
    val duration: Long = 0L,

    @JsonProperty("updateTime")
    val updateTime: Long = System.currentTimeMillis(),

    @JsonProperty("isCompleted")
    val isCompleted: Boolean = false,

    @JsonProperty("title")
    @JsonAlias("showName", "name")
    val title: String? = null,

    @JsonProperty("episode")
    val episode: Int? = null,

    @JsonProperty("season")
    val season: Int? = null,

    @JsonProperty("posterUrl")
    val posterUrl: String? = null,

    @JsonProperty("apiName")
    val apiName: String? = null
) {
    /**
     * Backward-compatible secondary constructor for legacy callers.
     */
    constructor(
        parentId: String,
        showName: String,
        showUrl: String,
        apiName: String? = null,
        posterUrl: String? = null,
        episode: Int? = null,
        season: Int? = null,
        episodeId: String? = null,
        position: Long = 0L,
        duration: Long = 0L,
        updateTime: Long = System.currentTimeMillis(),
        isCompleted: Boolean = false
    ) : this(
        url = showUrl,
        parentId = parentId,
        episodeId = episodeId,
        position = position,
        duration = duration,
        updateTime = updateTime,
        isCompleted = isCompleted,
        title = showName,
        episode = episode,
        season = season,
        posterUrl = posterUrl,
        apiName = apiName
    )

    @get:JsonIgnore
    val showName: String?
        get() = title

    @get:JsonIgnore
    val showUrl: String
        get() = url

    @get:JsonIgnore
    val progressPercentage: Float
        get() = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    @get:JsonIgnore
    val isWatched: Boolean
        get() = isCompleted || (duration > 0L && position >= (duration * 0.9).toLong())
}

/**
 * Saved bookmark for quick library navigation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Bookmark(
    @JsonProperty("id")
    val id: String,

    @JsonProperty("title")
    @JsonAlias("name")
    val title: String,

    @JsonProperty("posterUrl")
    val posterUrl: String? = null,

    @JsonProperty("url")
    val url: String? = null,

    @JsonProperty("apiName")
    val apiName: String? = null,

    @JsonProperty("createdAt")
    val createdAt: Long = System.currentTimeMillis()
) {
    constructor(
        id: String,
        name: String,
        url: String,
        apiName: String,
        posterUrl: String?
    ) : this(
        id = id,
        title = name,
        posterUrl = posterUrl,
        url = url,
        apiName = apiName,
        createdAt = System.currentTimeMillis()
    )

    @get:JsonIgnore
    val name: String
        get() = title
}

/**
 * Alias for legacy DesktopBookmark references.
 */
typealias DesktopBookmark = Bookmark

/**
 * Application-wide settings for hardware acceleration, rendering, subtitles, and DoH.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AppSettings(
    @JsonProperty("hwdec")
    val hwdec: String = "auto-safe",

    @JsonProperty("vo")
    val vo: String = "gpu",

    @JsonProperty("subSize")
    val subSize: Int = 45,

    @JsonProperty("subColor")
    val subColor: String = "#FFFFFF",

    @JsonProperty("dohProvider")
    val dohProvider: String = "cloudflare"
)

/**
 * Record for tracking installed or updated plugin versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PluginUpdateRecord(
    @JsonProperty("pluginName")
    val pluginName: String,

    @JsonProperty("version")
    val version: Int,

    @JsonProperty("iconUrl")
    val iconUrl: String? = null,

    @JsonProperty("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)
