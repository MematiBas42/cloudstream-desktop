package com.lagradost.common.storage

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Categorized watch state matching upstream WatchType.kt.
 * Standardized on 6 canonical states:
 * NONE(0, "None"), COMPLETED(1, "Completed"), WATCHING(2, "Watching"),
 * PLANTOWATCH(3, "Plan to Watch"), ONHOLD(4, "On Hold"), DROPPED(5, "Dropped").
 */
enum class DesktopWatchType(val internalId: Int, val stringRes: String) {
    NONE(0, "None"),
    COMPLETED(1, "Completed"),
    WATCHING(2, "Watching"),
    PLANTOWATCH(3, "Plan to Watch"),
    ONHOLD(4, "On Hold"),
    DROPPED(5, "Dropped");

    val displayName: String get() = stringRes

    companion object {
        val PLAN_TO_WATCH: DesktopWatchType get() = PLANTOWATCH
        val ON_HOLD: DesktopWatchType get() = ONHOLD

        fun fromInternalId(id: Int?): DesktopWatchType =
            entries.find { it.internalId == id } ?: NONE

        fun fromStringRes(name: String?): DesktopWatchType =
            entries.find { it.stringRes.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

/**
 * Sorting criteria matching upstream ListSorting.kt.
 */
enum class DesktopListSorting(val displayName: String) {
    None("Default"),
    UpdatedNew("Recently Updated"),
    UpdatedOld("Oldest Updated"),
    AlphabeticalA("Alphabetical (A-Z)"),
    AlphabeticalZ("Alphabetical (Z-A)"),
    ReleaseDateNew("Release Date (Newest)"),
    ReleaseDateOld("Release Date (Oldest)");

    companion object {
        val Query: DesktopListSorting get() = None
        val RatingHigh: DesktopListSorting get() = None
        val RatingLow: DesktopListSorting get() = None
    }
}

/**
 * Categorized bookmark item matching upstream BookmarkedData.
 * Serialized under $currentAccount/result_watch_state_data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BookmarkedData(
    @param:JsonProperty("id")
    val id: String? = null,

    @param:JsonProperty("name")
    @param:JsonAlias("title")
    val name: String,

    @param:JsonProperty("url")
    val url: String,

    @param:JsonProperty("apiName")
    val apiName: String,

    @param:JsonProperty("type")
    val type: String? = null,

    @param:JsonProperty("posterUrl")
    val posterUrl: String? = null,

    @param:JsonProperty("year")
    val year: Int? = null,

    @param:JsonProperty("bookmarkedTime")
    val bookmarkedTime: Long = System.currentTimeMillis(),

    @param:JsonProperty("latestUpdatedTime")
    val latestUpdatedTime: Long = System.currentTimeMillis(),

    @param:JsonProperty("syncData")
    val syncData: Map<String, String>? = null,

    @param:JsonProperty("quality")
    val quality: String? = null,

    @param:JsonProperty("posterHeaders")
    val posterHeaders: Map<String, String>? = null,

    @param:JsonProperty("plot")
    val plot: String? = null,

    @param:JsonProperty("tags")
    val tags: List<String>? = null,
) {
    @get:JsonIgnore
    val title: String get() = name

    constructor(
        id: Int?,
        name: String,
        url: String,
        apiName: String,
        type: String? = null,
        posterUrl: String? = null,
        year: Int? = null,
        bookmarkedTime: Long = System.currentTimeMillis(),
        latestUpdatedTime: Long = System.currentTimeMillis(),
        syncData: Map<String, String>? = null,
        quality: String? = null,
        posterHeaders: Map<String, String>? = null,
        plot: String? = null,
        tags: List<String>? = null,
    ) : this(
        id = id?.toString(),
        name = name,
        url = url,
        apiName = apiName,
        type = type,
        posterUrl = posterUrl,
        year = year,
        bookmarkedTime = bookmarkedTime,
        latestUpdatedTime = latestUpdatedTime,
        syncData = syncData,
        quality = quality,
        posterHeaders = posterHeaders,
        plot = plot,
        tags = tags
    )
}

/**
 * Favorite item matching upstream FavoritesData.
 * Serialized under $currentAccount/result_favorites_state_data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FavoritesData(
    @param:JsonProperty("id")
    val id: String? = null,

    @param:JsonProperty("name")
    @param:JsonAlias("title")
    val name: String,

    @param:JsonProperty("url")
    val url: String,

    @param:JsonProperty("apiName")
    val apiName: String,

    @param:JsonProperty("type")
    val type: String? = null,

    @param:JsonProperty("posterUrl")
    val posterUrl: String? = null,

    @param:JsonProperty("year")
    val year: Int? = null,

    @param:JsonProperty("favoritesTime")
    val favoritesTime: Long = System.currentTimeMillis(),

    @param:JsonProperty("latestUpdatedTime")
    val latestUpdatedTime: Long = System.currentTimeMillis(),

    @param:JsonProperty("syncData")
    val syncData: Map<String, String>? = null,

    @param:JsonProperty("quality")
    val quality: String? = null,

    @param:JsonProperty("posterHeaders")
    val posterHeaders: Map<String, String>? = null,

    @param:JsonProperty("plot")
    val plot: String? = null,

    @param:JsonProperty("tags")
    val tags: List<String>? = null,
) {
    @get:JsonIgnore
    val title: String get() = name

    constructor(
        id: Int?,
        name: String,
        url: String,
        apiName: String,
        type: String? = null,
        posterUrl: String? = null,
        year: Int? = null,
        favoritesTime: Long = System.currentTimeMillis(),
        latestUpdatedTime: Long = System.currentTimeMillis(),
        syncData: Map<String, String>? = null,
        quality: String? = null,
        posterHeaders: Map<String, String>? = null,
        plot: String? = null,
        tags: List<String>? = null,
    ) : this(
        id = id?.toString(),
        name = name,
        url = url,
        apiName = apiName,
        type = type,
        posterUrl = posterUrl,
        year = year,
        favoritesTime = favoritesTime,
        latestUpdatedTime = latestUpdatedTime,
        syncData = syncData,
        quality = quality,
        posterHeaders = posterHeaders,
        plot = plot,
        tags = tags
    )
}

/**
 * Subscribed item with episode tracking matching upstream SubscribedData.
 * Serialized under $currentAccount/result_subscribed_state_data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SubscriptionData(
    @param:JsonProperty("id")
    val id: String? = null,

    @param:JsonProperty("name")
    @param:JsonAlias("title")
    val name: String,

    @param:JsonProperty("url")
    val url: String,

    @param:JsonProperty("apiName")
    val apiName: String,

    @param:JsonProperty("lastSeenEpisodeCount")
    val lastSeenEpisodeCount: Map<String, Int?> = emptyMap(),

    @param:JsonProperty("type")
    val type: String? = null,

    @param:JsonProperty("posterUrl")
    val posterUrl: String? = null,

    @param:JsonProperty("year")
    val year: Int? = null,

    @param:JsonProperty("subscribedTime")
    val subscribedTime: Long = System.currentTimeMillis(),

    @param:JsonProperty("latestUpdatedTime")
    val latestUpdatedTime: Long = System.currentTimeMillis(),

    @param:JsonProperty("syncData")
    val syncData: Map<String, String>? = null,

    @param:JsonProperty("quality")
    val quality: String? = null,

    @param:JsonProperty("posterHeaders")
    val posterHeaders: Map<String, String>? = null,

    @param:JsonProperty("plot")
    val plot: String? = null,

    @param:JsonProperty("tags")
    val tags: List<String>? = null,
) {
    @get:JsonIgnore
    val title: String get() = name

    constructor(
        id: Int?,
        name: String,
        url: String,
        apiName: String,
        lastSeenEpisodeCount: Map<String, Int?> = emptyMap(),
        type: String? = null,
        posterUrl: String? = null,
        year: Int? = null,
        subscribedTime: Long = System.currentTimeMillis(),
        latestUpdatedTime: Long = System.currentTimeMillis(),
        syncData: Map<String, String>? = null,
        quality: String? = null,
        posterHeaders: Map<String, String>? = null,
        plot: String? = null,
        tags: List<String>? = null,
    ) : this(
        id = id?.toString(),
        name = name,
        url = url,
        apiName = apiName,
        lastSeenEpisodeCount = lastSeenEpisodeCount,
        type = type,
        posterUrl = posterUrl,
        year = year,
        subscribedTime = subscribedTime,
        latestUpdatedTime = latestUpdatedTime,
        syncData = syncData,
        quality = quality,
        posterHeaders = posterHeaders,
        plot = plot,
        tags = tags
    )

    fun getSeenEpisodes(key: Any): Int? =
        lastSeenEpisodeCount[key.toString()] ?: lastSeenEpisodeCount[key]

    companion object {
        fun fromEpisodes(
            id: String? = null,
            name: String,
            url: String,
            apiName: String,
            episodeCounts: Map<*, Int?>,
            type: String? = null,
            posterUrl: String? = null,
            year: Int? = null,
            subscribedTime: Long = System.currentTimeMillis(),
            latestUpdatedTime: Long = System.currentTimeMillis(),
        ): SubscriptionData = SubscriptionData(
            id = id,
            name = name,
            url = url,
            apiName = apiName,
            lastSeenEpisodeCount = episodeCounts.entries.associate { (k, v) -> (k?.toString() ?: "") to v },
            type = type,
            posterUrl = posterUrl,
            year = year,
            subscribedTime = subscribedTime,
            latestUpdatedTime = latestUpdatedTime
        )
    }
}

// Backward-compatibility and architectural aliases
typealias DesktopBookmarkedData = BookmarkedData
typealias DesktopFavoritesData = FavoritesData
typealias DesktopSubscribedData = SubscriptionData
typealias SubscribedData = SubscriptionData
