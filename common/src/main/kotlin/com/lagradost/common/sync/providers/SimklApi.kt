package com.lagradost.common.sync.providers

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Simkl REST API implementation supporting RFC 8628 PIN/Device Code authentication,
 * profile retrieval, and watch history/status scrobbler mutations.
 */
class SimklApi(
    var baseUrl: String = "https://api.simkl.com",
    var clientId: String = DEFAULT_CLIENT_ID,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val tokenProvider: () -> String? = { null }
) : SyncAPI() {

    override val name: String = "Simkl"
    override val idPrefix: String = "simkl"
    override val requiresLogin: Boolean = true

    companion object {
        const val DEFAULT_CLIENT_ID = "087c53e839e5fa4726f1da457813a0784260212fcae9ba6c5dc3e06ef1cb95a9"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * Initiates the RFC 8628-style PIN flow with Simkl.
     * GET /oauth/pin?client_id={clientId}
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val url = "$baseUrl/oauth/pin?client_id=$clientId"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Simkl device code request failed: HTTP ${response.code} - $body")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val userCode = json.path("user_code").asText()
        val deviceCode = json.path("device_code").asText(userCode)
        val verificationUrl = json.path("verification_url").asText("https://simkl.com/pin")
        val expiresIn = json.path("expires_in").asInt(900)
        val interval = json.path("interval").asInt(5).coerceAtLeast(3)

        DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUrl = verificationUrl,
            verificationUriComplete = "$verificationUrl?pin=$userCode",
            expiresIn = expiresIn,
            interval = interval
        )
    }

    /**
     * Polls the device code endpoint for authorization.
     * GET /oauth/pin/{userCode}?client_id={clientId}
     */
    suspend fun pollDeviceCode(userCode: String): DevicePollingResult = withContext(Dispatchers.IO) {
        val url = "$baseUrl/oauth/pin/$userCode?client_id=$clientId"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            return@withContext DevicePollingResult.Failure("HTTP ${response.code}: $body")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val result = json.path("result").asText("")
        val message = json.path("message").asText("")

        if (json.has("access_token")) {
            val token = json.path("access_token").asText()
            if (token.isNotBlank()) {
                return@withContext DevicePollingResult.TokenReceived(
                    accessToken = token,
                    refreshToken = null,
                    expiresInSec = null
                )
            }
        }

        when {
            result.equals("OK", ignoreCase = true) && json.has("access_token") -> {
                DevicePollingResult.TokenReceived(json.path("access_token").asText())
            }
            message.contains("pending", ignoreCase = true) || result.contains("pending", ignoreCase = true) || message.contains("waiting", ignoreCase = true) -> {
                DevicePollingResult.Pending
            }
            message.contains("slow_down", ignoreCase = true) || message.contains("slowdown", ignoreCase = true) -> {
                DevicePollingResult.SlowDown
            }
            message.contains("expired", ignoreCase = true) -> {
                DevicePollingResult.Expired
            }
            message.contains("denied", ignoreCase = true) || message.contains("declined", ignoreCase = true) -> {
                DevicePollingResult.AccessDenied
            }
            else -> {
                // If not yet authorized
                DevicePollingResult.Pending
            }
        }
    }

    /**
     * Fetches the user profile for the authenticated account.
     */
    suspend fun fetchUserProfile(accessToken: String): SyncAccount = withContext(Dispatchers.IO) {
        val url = "$baseUrl/users/settings"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("simkl-api-key", clientId)
            .get()
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Failed to fetch Simkl user profile: HTTP ${response.code}")
        }

        val json: JsonNode = DesktopDataStore.mapper.readTree(body)
        val userNode = json.path("user")
        val accountNode = json.path("account")

        val id = accountNode.path("id").asText(userNode.path("name").asText("unknown"))
        val username = userNode.path("name").asText("SimklUser")
        val avatar = userNode.path("avatar").asText(null)

        SyncAccount(
            id = id,
            username = username,
            avatarUrl = avatar,
            providerPrefix = idPrefix,
            accessToken = accessToken,
            refreshToken = null,
            expiresAtSec = null
        )
    }

    /**
     * Scrobble watch progress to Simkl history.
     * POST /sync/history
     */
    suspend fun scrobble(
        accessToken: String,
        mediaId: String,
        episodeNumber: Int,
        seasonNumber: Int? = 1,
        status: SyncWatchType = SyncWatchType.WATCHING,
        score: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val watchedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val numericId = mediaId.toIntOrNull()

        val idsNode = DesktopDataStore.mapper.createObjectNode().apply {
            if (numericId != null) {
                put("simkl", numericId)
            } else {
                put("mal", mediaId)
            }
        }

        val episodeNode = DesktopDataStore.mapper.createObjectNode().apply {
            put("number", episodeNumber)
            put("watched_at", watchedAt)
        }

        val seasonNode = DesktopDataStore.mapper.createObjectNode().apply {
            put("number", seasonNumber ?: 1)
            set<JsonNode>("episodes", DesktopDataStore.mapper.createArrayNode().add(episodeNode))
        }

        val showNode = DesktopDataStore.mapper.createObjectNode().apply {
            set<JsonNode>("ids", idsNode)
            set<JsonNode>("seasons", DesktopDataStore.mapper.createArrayNode().add(seasonNode))
        }

        val rootNode = DesktopDataStore.mapper.createObjectNode().apply {
            set<JsonNode>("shows", DesktopDataStore.mapper.createArrayNode().add(showNode))
            set<JsonNode>("movies", DesktopDataStore.mapper.createArrayNode())
        }

        val payload = DesktopDataStore.mapper.writeValueAsString(rootNode)
        val request = Request.Builder()
            .url("$baseUrl/sync/history")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("simkl-api-key", clientId)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string().orEmpty()
            AppLogger.e("SimklApi", "Simkl scrobble failed: HTTP ${response.code} - $errBody")
            throw IllegalStateException("Simkl scrobble failed: HTTP ${response.code}")
        }

        AppLogger.i("SimklApi", "Successfully scrobbled $mediaId Ep $episodeNumber to Simkl")

        // Also update list status if appropriate
        if (status != SyncWatchType.NONE) {
            try {
                updateListStatus(accessToken, mediaId, status)
            } catch (e: Exception) {
                AppLogger.w("SimklApi", "Failed to update list status for $mediaId: ${e.message}")
            }
        }

        true
    }

    /**
     * Updates list status (e.g. watching, completed, plantowatch).
     * POST /sync/add-to-list
     */
    suspend fun updateListStatus(
        accessToken: String,
        mediaId: String,
        status: SyncWatchType
    ): Boolean = withContext(Dispatchers.IO) {
        val simklStatus = when (status) {
            SyncWatchType.WATCHING -> "watching"
            SyncWatchType.COMPLETED -> "completed"
            SyncWatchType.ONHOLD -> "hold"
            SyncWatchType.DROPPED -> "dropped"
            SyncWatchType.PLANTOWATCH -> "plantowatch"
            SyncWatchType.REWATCHING -> "watching"
            SyncWatchType.NONE -> return@withContext true
        }

        val numericId = mediaId.toIntOrNull()
        val idsNode = DesktopDataStore.mapper.createObjectNode().apply {
            if (numericId != null) put("simkl", numericId) else put("mal", mediaId)
        }

        val showNode = DesktopDataStore.mapper.createObjectNode().apply {
            set<JsonNode>("ids", idsNode)
            put("to", simklStatus)
        }

        val rootNode = DesktopDataStore.mapper.createObjectNode().apply {
            set<JsonNode>("shows", DesktopDataStore.mapper.createArrayNode().add(showNode))
            set<JsonNode>("movies", DesktopDataStore.mapper.createArrayNode())
        }

        val payload = DesktopDataStore.mapper.writeValueAsString(rootNode)
        val request = Request.Builder()
            .url("$baseUrl/sync/add-to-list")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("simkl-api-key", clientId)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        response.isSuccessful
    }

    override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean {
        val token = tokenProvider() ?: return false
        val ep = newStatus.watchedEpisodes ?: 1
        return scrobble(
            accessToken = token,
            mediaId = id,
            episodeNumber = ep,
            status = newStatus.status,
            score = newStatus.score
        )
    }
}
