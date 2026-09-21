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
import java.util.concurrent.TimeUnit

/**
 * Kitsu JSON:API 1.0 implementation for scrobbling and library management.
 */
class KitsuApi(
    var baseUrl: String = "https://kitsu.io/api/edge",
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val tokenProvider: () -> String? = { null }
) : SyncAPI() {

    override val name: String = "Kitsu"
    override val idPrefix: String = "kitsu"
    override val requiresLogin: Boolean = true

    companion object {
        private val JSON_API_MEDIA_TYPE = "application/vnd.api+json".toMediaType()
    }

    suspend fun scrobble(
        accessToken: String,
        mediaId: String,
        progress: Int,
        status: SyncWatchType = SyncWatchType.WATCHING,
        score: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val kitsuStatus = when (status) {
            SyncWatchType.WATCHING -> "current"
            SyncWatchType.COMPLETED -> "completed"
            SyncWatchType.ONHOLD -> "on_hold"
            SyncWatchType.DROPPED -> "dropped"
            SyncWatchType.PLANTOWATCH -> "planned"
            SyncWatchType.REWATCHING -> "current"
            SyncWatchType.NONE -> "planned"
        }

        val attributesNode = DesktopDataStore.mapper.createObjectNode().apply {
            put("status", kitsuStatus)
            put("progress", progress)
            if (score != null) {
                put("ratingTwenty", score * 2)
            }
        }

        val dataNode = DesktopDataStore.mapper.createObjectNode().apply {
            put("type", "libraryEntries")
            put("id", mediaId)
            set<JsonNode>("attributes", attributesNode)
        }

        val rootNode = DesktopDataStore.mapper.createObjectNode().apply {
            set<JsonNode>("data", dataNode)
        }

        val payload = DesktopDataStore.mapper.writeValueAsString(rootNode)
        val request = Request.Builder()
            .url("$baseUrl/library-entries/$mediaId")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/vnd.api+json")
            .addHeader("Accept", "application/vnd.api+json")
            .patch(payload.toRequestBody(JSON_API_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            AppLogger.e("KitsuApi", "Kitsu scrobble failed: HTTP ${response.code} - $body")
            throw IllegalStateException("Kitsu scrobble failed with HTTP ${response.code}")
        }

        AppLogger.i("KitsuApi", "Kitsu scrobble success: Entry $mediaId Progress $progress")
        true
    }

    override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean {
        val token = tokenProvider() ?: return false
        val progress = newStatus.watchedEpisodes ?: 1
        return scrobble(
            accessToken = token,
            mediaId = id,
            progress = progress,
            status = newStatus.status,
            score = newStatus.score
        )
    }
}
