package com.lagradost.common.sync.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * MyAnimeList REST API v2 implementation with OAuth2 token refresh and scrobbling.
 */
class MALApi(
    var apiBaseUrl: String = "https://api.myanimelist.net/v2",
    var oauthBaseUrl: String = "https://myanimelist.net/v1/oauth2",
    var clientId: String = DEFAULT_CLIENT_ID,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val tokenProvider: () -> String? = { null }
) : SyncAPI() {

    override val name: String = "MyAnimeList"
    override val idPrefix: String = "mal"
    override val requiresLogin: Boolean = true

    companion object {
        const val DEFAULT_CLIENT_ID = "cloudstream_desktop_mal"
    }

    /**
     * Refreshes an expired MAL access token using its refresh token.
     * Single-use refresh token exchange: returns new access token and new refresh token.
     */
    suspend fun refreshToken(refreshToken: String): SyncAccount? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("$oauthBaseUrl/token")
            .post(form)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            AppLogger.e("MALApi", "Token refresh failed: HTTP ${response.code} - $body")
            return@withContext null
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val accessToken = json.path("access_token").asText()
        val newRefreshToken = json.path("refresh_token").asText(refreshToken)
        val expiresIn = json.path("expires_in").asLong(2678400L) // 31 days default

        val profile = try {
            fetchUserProfile(accessToken)
        } catch (_: Exception) {
            null
        }

        SyncAccount(
            id = profile?.id ?: "mal_user",
            username = profile?.username ?: "MAL User",
            avatarUrl = profile?.avatarUrl,
            providerPrefix = idPrefix,
            accessToken = accessToken,
            refreshToken = newRefreshToken,
            expiresAtSec = (System.currentTimeMillis() / 1000L) + expiresIn
        )
    }

    /**
     * Fetches current authenticated user profile.
     * GET /v2/users/@me
     */
    suspend fun fetchUserProfile(accessToken: String): SyncAccount = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$apiBaseUrl/users/@me")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch MAL profile: HTTP ${response.code}")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val id = json.path("id").asText("0")
        val name = json.path("name").asText("MAL User")
        val picture = json.path("picture").asText(null)

        SyncAccount(
            id = id,
            username = name,
            avatarUrl = picture,
            providerPrefix = idPrefix,
            accessToken = accessToken
        )
    }

    /**
     * Scrobble watch progress and rating to MAL.
     * PUT /v2/anime/{anime_id}/my_list_status
     */
    suspend fun scrobble(
        accessToken: String,
        animeId: String,
        numWatchedEpisodes: Int,
        status: SyncWatchType = SyncWatchType.WATCHING,
        score: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val malStatus = when (status) {
            SyncWatchType.WATCHING -> "watching"
            SyncWatchType.COMPLETED -> "completed"
            SyncWatchType.ONHOLD -> "on_hold"
            SyncWatchType.DROPPED -> "dropped"
            SyncWatchType.PLANTOWATCH -> "plan_to_watch"
            SyncWatchType.REWATCHING -> "watching"
            SyncWatchType.NONE -> "plan_to_watch"
        }

        val formBuilder = FormBody.Builder()
            .add("status", malStatus)
            .add("num_watched_episodes", numWatchedEpisodes.toString())

        if (score != null) {
            formBuilder.add("score", score.coerceIn(0, 10).toString())
        }

        val request = Request.Builder()
            .url("$apiBaseUrl/anime/$animeId/my_list_status")
            .addHeader("Authorization", "Bearer $accessToken")
            .put(formBuilder.build())
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            AppLogger.e("MALApi", "MAL scrobble failed: HTTP ${response.code} - $body")
            throw IllegalStateException("MAL scrobble failed with HTTP ${response.code}")
        }

        AppLogger.i("MALApi", "MAL scrobble success: Anime $animeId Ep $numWatchedEpisodes")
        true
    }

    override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean {
        val token = tokenProvider() ?: return false
        val episodes = newStatus.watchedEpisodes ?: 1
        return scrobble(
            accessToken = token,
            animeId = id,
            numWatchedEpisodes = episodes,
            status = newStatus.status,
            score = newStatus.score
        )
    }
}
