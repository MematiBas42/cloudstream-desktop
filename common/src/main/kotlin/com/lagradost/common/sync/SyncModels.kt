package com.lagradost.common.sync

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.security.MessageDigest
import java.util.UUID

/**
 * Unified watch type enum matching upstream SyncWatchType and DesktopWatchType.
 * Unifies local bookmark status and third-party scrobbler categories.
 */
enum class SyncWatchType(val internalId: Int, val displayName: String) {
    NONE(-1, "None"),
    WATCHING(0, "Watching"),
    COMPLETED(1, "Completed"),
    ONHOLD(2, "On Hold"),
    DROPPED(3, "Dropped"),
    PLANTOWATCH(4, "Plan to Watch"),
    REWATCHING(5, "Re-watching");

    companion object {
        fun fromInternalId(id: Int?): SyncWatchType =
            entries.find { it.internalId == id } ?: NONE

        fun fromLegacyWatchType(id: Int?): SyncWatchType = when (id) {
            0 -> NONE
            1 -> COMPLETED
            2 -> WATCHING
            3 -> PLANTOWATCH
            4 -> ONHOLD
            5 -> DROPPED
            else -> NONE
        }

        fun fromString(name: String?): SyncWatchType {
            if (name.isNullOrBlank()) return NONE
            val clean = name.trim().replace(" ", "").replace("-", "").replace("_", "").lowercase()
            return entries.find {
                it.name.replace("_", "").lowercase() == clean ||
                it.displayName.replace(" ", "").replace("-", "").lowercase() == clean
            } ?: NONE
        }
    }
}

typealias ScrobbleStatus = SyncWatchType

/**
 * Sorting criteria for library shelves.
 */
enum class ListSorting(val displayName: String) {
    Query("Relevance"),
    RatingHigh("Rating (High to Low)"),
    RatingLow("Rating (Low to High)"),
    UpdatedNew("Recently Updated"),
    UpdatedOld("Oldest Updated"),
    AlphabeticalA("Alphabetical (A-Z)"),
    AlphabeticalZ("Alphabetical (Z-A)"),
    ReleaseDateNew("Release Date (Newest)"),
    ReleaseDateOld("Release Date (Oldest)")
}

/**
 * Watch status payload across sync providers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncStatus(
    @param:JsonProperty("status") val status: SyncWatchType = SyncWatchType.NONE,
    @param:JsonProperty("score") val score: Int? = null,
    @param:JsonProperty("watchedEpisodes") val watchedEpisodes: Int? = null,
    @param:JsonProperty("maxEpisodes") val maxEpisodes: Int? = null,
    @param:JsonProperty("isFavorite") val isFavorite: Boolean? = null,
    @param:JsonProperty("updatedAt") val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Connected third-party or local user account.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncAccount(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("username") @param:JsonAlias("name") val username: String,
    @param:JsonProperty("avatarUrl") val avatarUrl: String? = null,
    @param:JsonProperty("providerPrefix") val providerPrefix: String,
    @param:JsonProperty("accessToken") val accessToken: String? = null,
    @param:JsonProperty("refreshToken") val refreshToken: String? = null,
    @param:JsonProperty("expiresAtSec") val expiresAtSec: Long? = null,
    @param:JsonProperty("tokenType") val tokenType: String = "Bearer",
    @param:JsonProperty("customData") val customData: Map<String, String> = emptyMap()
) {
    fun isTokenExpired(marginSec: Long = 60L): Boolean {
        if (expiresAtSec == null) return false
        val nowSec = System.currentTimeMillis() / 1000L
        return nowSec + marginSec >= expiresAtSec
    }
}

/**
 * Playback progress and scrobbler calculation model.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScrobbleProgress(
    @param:JsonProperty("mediaId") val mediaId: String,
    @param:JsonProperty("episodeNumber") val episodeNumber: Int,
    @param:JsonProperty("seasonNumber") val seasonNumber: Int? = 1,
    @param:JsonProperty("positionSec") val positionSec: Double,
    @param:JsonProperty("durationSec") val durationSec: Double,
    @param:JsonProperty("isCompleted") val isCompleted: Boolean = false,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("providerPrefix") val providerPrefix: String? = null
) {
    val progressRatio: Double
        get() = if (durationSec > 0.0) positionSec / durationSec else 0.0

    fun calculateRatio(): Double = progressRatio

    /**
     * Threshold rule: duration >= 120s (ignore previews/clips)
     * and (progressRatio >= 0.85 or isCompleted == true).
     */
    fun isScrobbleThresholdReached(): Boolean {
        if (durationSec < 120.0) return false
        return progressRatio >= 0.85 || isCompleted
    }

    fun shouldScrobble(): Boolean = isScrobbleThresholdReached()

    /**
     * Deduplication session key: SHA-256(mediaId:season:episode).
     */
    fun sessionKey(): String {
        val raw = "$mediaId:${seasonNumber ?: 1}:$episodeNumber"
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * RFC 8628 TV Device Authorization response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DeviceCodeResponse(
    @param:JsonProperty("device_code")
    @param:JsonAlias("deviceCode")
    val deviceCode: String,

    @param:JsonProperty("user_code")
    @param:JsonAlias("userCode")
    val userCode: String,

    @param:JsonProperty("verification_url")
    @param:JsonAlias("verification_uri", "verificationUri", "verificationUrl")
    val verificationUrl: String,

    @param:JsonProperty("verification_uri_complete")
    @param:JsonAlias("verificationUriComplete")
    val verificationUriComplete: String? = null,

    @param:JsonProperty("expires_in")
    @param:JsonAlias("expiresIn")
    val expiresIn: Int = 900,

    @param:JsonProperty("interval")
    val interval: Int = 5
)

/**
 * Canonical synchronized media entry (bookmark / library entry).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncEntry(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("name") @param:JsonAlias("title") val name: String,
    @param:JsonProperty("url") val url: String = "",
    @param:JsonProperty("apiName") val apiName: String = "",
    @param:JsonProperty("status") val status: SyncWatchType = SyncWatchType.NONE,
    @param:JsonProperty("watchedEpisodes") val watchedEpisodes: Int = 0,
    @param:JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    @param:JsonProperty("score") val score: Int? = null,
    @param:JsonProperty("isFavorite") val isFavorite: Boolean = false,
    @param:JsonProperty("posterUrl") val posterUrl: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("year") val year: Int? = null,
    @param:JsonProperty("plot") val plot: String? = null,
    @param:JsonProperty("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @param:JsonProperty("syncMappings") val syncMappings: Map<String, String> = emptyMap()
) {
    fun toLibraryItem(): LibraryItem = LibraryItem(
        id = id,
        name = name,
        url = url,
        apiName = apiName,
        status = status,
        watchedEpisodes = watchedEpisodes,
        totalEpisodes = totalEpisodes,
        score = score,
        isFavorite = isFavorite,
        posterUrl = posterUrl,
        type = type,
        year = year,
        plot = plot,
        updatedAt = updatedAt
    )
}

typealias LocalMediaBookmark = SyncEntry

@JsonIgnoreProperties(ignoreUnknown = true)
data class LibraryItem(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("url") val url: String = "",
    @param:JsonProperty("apiName") val apiName: String = "",
    @param:JsonProperty("status") val status: SyncWatchType = SyncWatchType.NONE,
    @param:JsonProperty("watchedEpisodes") val watchedEpisodes: Int = 0,
    @param:JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
    @param:JsonProperty("score") val score: Int? = null,
    @param:JsonProperty("isFavorite") val isFavorite: Boolean = false,
    @param:JsonProperty("posterUrl") val posterUrl: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("year") val year: Int? = null,
    @param:JsonProperty("plot") val plot: String? = null,
    @param:JsonProperty("updatedAt") val updatedAt: Long = System.currentTimeMillis()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LibraryList(
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("items") val items: List<LibraryItem>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LibraryMetadata(
    @param:JsonProperty("allLibraryLists") val allLibraryLists: List<LibraryList>,
    @param:JsonProperty("supportedListSorting") val supportedListSorting: Set<ListSorting> = emptySet()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QueuedScrobble(
    @param:JsonProperty("id") val id: String = UUID.randomUUID().toString(),
    @param:JsonProperty("providerPrefix") val providerPrefix: String,
    @param:JsonProperty("mediaSyncId") val mediaSyncId: String,
    @param:JsonProperty("episodeNumber") val episodeNumber: Int,
    @param:JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @param:JsonProperty("status") val status: SyncWatchType = SyncWatchType.WATCHING,
    @param:JsonProperty("score") val score: Int? = null,
    @param:JsonProperty("queuedTimestampMs") val queuedTimestampMs: Long = System.currentTimeMillis(),
    @param:JsonProperty("retryCount") val retryCount: Int = 0
)

typealias OutboxMutation = QueuedScrobble

/**
 * State machine for RFC 8628 TV Device Code Authentication.
 */
sealed class DeviceAuthState {
    data object Idle : DeviceAuthState()
    data class DisplayCode(
        val userCode: String,
        val verificationUrl: String,
        val qrCodePayload: String,
        val expiresInSec: Int,
        val intervalSec: Int = 5,
        val isLocalCompanion: Boolean = false
    ) : DeviceAuthState()
    data class Polling(val userCode: String, val attempt: Int) : DeviceAuthState()
    data class Success(val account: SyncAccount) : DeviceAuthState()
    data class Error(val message: String, val canRetry: Boolean = true) : DeviceAuthState()
}

/**
 * Result of polling an RFC 8628 device code.
 */
sealed class DevicePollingResult {
    data class TokenReceived(val accessToken: String, val refreshToken: String? = null, val expiresInSec: Long? = null) : DevicePollingResult()
    data object Pending : DevicePollingResult()
    data object SlowDown : DevicePollingResult()
    data object Expired : DevicePollingResult()
    data object AccessDenied : DevicePollingResult()
    data class Failure(val error: String) : DevicePollingResult()
}
