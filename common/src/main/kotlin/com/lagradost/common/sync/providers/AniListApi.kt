package com.lagradost.common.sync.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AniList GraphQL API v2 implementation supporting RFC 8628 TV Device Code flow / polling,
 * viewer profile resolution, and SaveMediaListEntry GraphQL scrobbler mutations.
 */
class AniListApi(
    var graphqlUrl: String = "https://graphql.anilist.co",
    var oauthBaseUrl: String = "https://anilist.co/api/v2/oauth",
    var clientId: String = DEFAULT_CLIENT_ID,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val tokenProvider: () -> String? = { null }
) : SyncAPI() {

    override val name: String = "AniList"
    override val idPrefix: String = "anilist"
    override val requiresLogin: Boolean = true

    companion object {
        const val DEFAULT_CLIENT_ID = "cloudstream_tv_anilist"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * RFC 8628 Device Authorization Request.
     * POST /oauth/device/code
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", "read write")
            .build()

        val request = Request.Builder()
            .url("$oauthBaseUrl/device/code")
            .post(form)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("AniList device code request failed: HTTP ${response.code} - $body")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val deviceCode = json.path("device_code").asText()
        val userCode = json.path("user_code").asText()
        val verificationUrl = json.path("verification_uri").asText("https://anilist.co/activate")
        val verificationComplete = json.path("verification_uri_complete").asText("$verificationUrl?user_code=$userCode")
        val expiresIn = json.path("expires_in").asInt(900)
        val interval = json.path("interval").asInt(5).coerceAtLeast(3)

        DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            verificationUriComplete = verificationComplete,
            expiresIn = expiresIn,
            interval = interval
        )
    }

    /**
     * Polls the device code endpoint for authorization.
     * POST /oauth/token
     */
    suspend fun pollDeviceCode(deviceCode: String): DevicePollingResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", deviceCode)
            .add("client_id", clientId)
            .build()

        val request = Request.Builder()
            .url("$oauthBaseUrl/token")
            .post(form)
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            val json = try { DesktopDataStore.mapper.readTree(body) } catch (_: Exception) { null }
            val error = json?.path("error")?.asText(body) ?: body

            return@withContext when {
                error.contains("authorization_pending", ignoreCase = true) -> DevicePollingResult.Pending
                error.contains("slow_down", ignoreCase = true) -> DevicePollingResult.SlowDown
                error.contains("expired", ignoreCase = true) -> DevicePollingResult.Expired
                error.contains("access_denied", ignoreCase = true) -> DevicePollingResult.AccessDenied
                else -> DevicePollingResult.Failure("HTTP ${response.code}: $error")
            }
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        if (json.has("access_token")) {
            val accessToken = json.path("access_token").asText()
            val refreshToken = json.path("refresh_token").asText(null)
            val expiresIn = json.path("expires_in").asLong(31536000L)
            return@withContext DevicePollingResult.TokenReceived(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSec = expiresIn
            )
        }

        DevicePollingResult.Pending
    }

    /**
     * Fetches the Viewer's profile using GraphQL.
     */
    suspend fun fetchUserProfile(accessToken: String): SyncAccount = withContext(Dispatchers.IO) {
        val query = """
            query {
                Viewer {
                    id
                    name
                    avatar {
                        large
                    }
                }
            }
        """.trimIndent()

        val payload = DesktopDataStore.mapper.createObjectNode().apply {
            put("query", query)
        }

        val request = Request.Builder()
            .url(graphqlUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(DesktopDataStore.mapper.writeValueAsString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch AniList profile: HTTP ${response.code}")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val viewer = json.path("data").path("Viewer")
        val id = viewer.path("id").asText("0")
        val name = viewer.path("name").asText("AniListUser")
        val avatar = viewer.path("avatar").path("large").asText(null)

        SyncAccount(
            id = id,
            username = name,
            avatarUrl = avatar,
            providerPrefix = idPrefix,
            accessToken = accessToken,
            refreshToken = null,
            expiresAtSec = (System.currentTimeMillis() / 1000L) + 31536000L // 1 year
        )
    }

    /**
     * Executes the SaveMediaListEntry GraphQL mutation to scrobble progress and rating.
     */
    suspend fun scrobble(
        accessToken: String,
        mediaId: Int,
        progress: Int,
        status: SyncWatchType = SyncWatchType.WATCHING,
        scoreRaw: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val anilistStatus = when (status) {
            SyncWatchType.WATCHING -> "CURRENT"
            SyncWatchType.COMPLETED -> "COMPLETED"
            SyncWatchType.ONHOLD -> "PAUSED"
            SyncWatchType.DROPPED -> "DROPPED"
            SyncWatchType.PLANTOWATCH -> "PLANNING"
            SyncWatchType.REWATCHING -> "REPEATING"
            SyncWatchType.NONE -> "PLANNING"
        }

        val mutation = """
            mutation UpdateMediaList(${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}scoreRaw: Int) {
                SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, scoreRaw: ${'$'}scoreRaw) {
                    id
                    mediaId
                    status
                    progress
                    score(format: POINT_100)
                    updatedAt
                }
            }
        """.trimIndent()

        val variablesNode = DesktopDataStore.mapper.createObjectNode().apply {
            put("mediaId", mediaId)
            put("status", anilistStatus)
            put("progress", progress)
            if (scoreRaw != null) {
                put("scoreRaw", scoreRaw)
            }
        }

        val rootNode = DesktopDataStore.mapper.createObjectNode().apply {
            put("query", mutation)
            set<JsonNode>("variables", variablesNode)
        }

        val payload = DesktopDataStore.mapper.writeValueAsString(rootNode)
        val request = Request.Builder()
            .url(graphqlUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            AppLogger.e("AniListApi", "AniList scrobble failed: HTTP ${response.code} - $body")
            throw IllegalStateException("AniList scrobble failed with HTTP ${response.code}")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        if (json.has("errors") && json.path("errors").size() > 0) {
            val errorMsg = json.path("errors").get(0).path("message").asText("GraphQL error")
            AppLogger.e("AniListApi", "AniList GraphQL error: $errorMsg")
            throw IllegalStateException("AniList GraphQL error: $errorMsg")
        }

        AppLogger.i("AniListApi", "AniList scrobble success: Media $mediaId Ep $progress (Status: $anilistStatus)")
        true
    }

    override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean {
        val token = tokenProvider() ?: return false
        val mediaId = id.toIntOrNull() ?: return false
        val progress = newStatus.watchedEpisodes ?: 1
        val score = newStatus.score
        return scrobble(
            accessToken = token,
            mediaId = mediaId,
            progress = progress,
            status = newStatus.status,
            scoreRaw = score
        )
    }
}
