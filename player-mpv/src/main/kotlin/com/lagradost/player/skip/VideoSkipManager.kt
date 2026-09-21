package com.lagradost.player.skip

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.videoskip.SkipAPI
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp

enum class SkipType {
    Intro,
    Outro,
    Recap,
    Mixed
}

data class SkipInterval(
    val startMs: Long,
    val endMs: Long,
    val type: SkipType
)

enum class AnimeIdType {
    MAL,
    AniList
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniSkipInterval(
    @param:JsonProperty("startTime") val startTime: Double = 0.0,
    @param:JsonProperty("endTime") val endTime: Double = 0.0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniSkipResultItem(
    @param:JsonProperty("interval") val interval: AniSkipInterval = AniSkipInterval(),
    @param:JsonProperty("skipType") val skipType: String = "",
    @param:JsonProperty("skipId") val skipId: String? = null,
    @param:JsonProperty("episodeLength") val episodeLength: Double = 0.0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AniSkipResponse(
    @param:JsonProperty("found") val found: Boolean = false,
    @param:JsonProperty("results") val results: List<AniSkipResultItem>? = null,
    @param:JsonProperty("message") val message: String? = null,
    @param:JsonProperty("statusCode") val statusCode: Int? = null
)

class AniSkipClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    companion object {
        const val ANISKIP_BASE_URL = "https://api.aniskip.com/v2/skip-times"
        const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
    }

    /**
     * Parses JSON response from AniSkip API into a list of SkipInterval.
     * Maps "op" to Intro, "ed" to Outro, "recap" to Recap, "mixed-op" / "mixed-ed" to Mixed.
     */
    fun parseResponse(json: String): List<SkipInterval> {
        if (json.isBlank()) return emptyList()
        val response = runCatching {
            mapper.readValue(json, AniSkipResponse::class.java)
        }.getOrNull() ?: return emptyList()

        if (!response.found || response.results.isNullOrEmpty()) {
            return emptyList()
        }

        return response.results.mapNotNull { item ->
            val type = when (item.skipType.lowercase()) {
                "op" -> SkipType.Intro
                "ed" -> SkipType.Outro
                "recap" -> SkipType.Recap
                "mixed-op", "mixed-ed", "mixed" -> SkipType.Mixed
                else -> null
            } ?: return@mapNotNull null

            val start = (item.interval.startTime * 1000.0).toLong()
            val end = (item.interval.endTime * 1000.0).toLong()
            if (start < end) {
                SkipInterval(startMs = start, endMs = end, type = type)
            } else {
                null
            }
        }
    }

    suspend fun resolveAniListToMalId(aniListId: Long): Long? = withContext(Dispatchers.IO) {
        val query = """
            query (${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) {
                idMal
              }
            }
        """.trimIndent()
        val payload = mapOf(
            "query" to query,
            "variables" to mapOf("id" to aniListId)
        )
        val jsonPayload = mapper.writeValueAsString(payload)
        val request = Request.Builder()
            .url(ANILIST_GRAPHQL_URL)
            .header("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bodyStr = resp.body.string()
                val root = mapper.readTree(bodyStr)
                val idMalNode = root.path("data").path("Media").path("idMal")
                if (!idMalNode.isMissingNode && !idMalNode.isNull) {
                    idMalNode.asLong()
                } else null
            }
        }.getOrNull()
    }

    suspend fun query(
        animeId: Long,
        episode: Int,
        episodeLengthSec: Long = 0L,
        idType: AnimeIdType = AnimeIdType.MAL
    ): List<SkipInterval> = withContext(Dispatchers.IO) {
        val malId = if (idType == AnimeIdType.AniList) {
            resolveAniListToMalId(animeId) ?: animeId
        } else {
            animeId
        }

        val url = "$ANISKIP_BASE_URL/$malId/$episode" +
                "?types[]=op&types[]=ed&types[]=mixed-op&types[]=mixed-ed&types[]=recap" +
                "&episodeLength=$episodeLengthSec"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                parseResponse(body)
            }
        }.getOrElse { emptyList() }
    }
}

/**
 * Manages video skip intervals, auto-skip evaluation, and AniSkip integration.
 */
class VideoSkipManager(
    var intervals: List<SkipInterval> = emptyList(),
    var autoSkipEnabled: Boolean = false,
    val aniSkipClient: AniSkipClient = AniSkipClient()
) {
    var onAutoSkipTriggered: ((SkipInterval) -> Unit)? = null
    var onAutoSkip: ((SkipInterval) -> Unit)?
        get() = onAutoSkipTriggered
        set(value) { onAutoSkipTriggered = value }

    var lastSkippedInterval: SkipInterval? = null

    fun loadIntervals(newIntervals: List<SkipInterval>) {
        intervals = newIntervals
    }

    /**
     * Bridges :plugin-runtime canonical SkipAPI stamps into VideoSkipManager intervals.
     */
    fun loadFromSkipStamps(stamps: List<VideoSkipStamp>) {
        intervals = stamps.map { stamp ->
            val type = when (stamp.timestamp.type) {
                com.lagradost.cloudstream3.utils.videoskip.SkipType.Opening,
                com.lagradost.cloudstream3.utils.videoskip.SkipType.Intro -> SkipType.Intro
                com.lagradost.cloudstream3.utils.videoskip.SkipType.Ending,
                com.lagradost.cloudstream3.utils.videoskip.SkipType.Credits -> SkipType.Outro
                com.lagradost.cloudstream3.utils.videoskip.SkipType.Recap -> SkipType.Recap
                com.lagradost.cloudstream3.utils.videoskip.SkipType.MixedOpening,
                com.lagradost.cloudstream3.utils.videoskip.SkipType.MixedEnding -> SkipType.Mixed
                com.lagradost.cloudstream3.utils.videoskip.SkipType.Preview -> SkipType.Intro
            }
            SkipInterval(
                startMs = stamp.timestamp.startMs,
                endMs = stamp.timestamp.endMs,
                type = type
            )
        }
    }

    /**
     * Loads skip intervals using the canonical multi-provider SkipAPI engine.
     */
    suspend fun loadFromSkipApi(
        data: LoadResponse,
        episode: ResultEpisode,
        episodeDurationMs: Long,
        hasNextEpisode: Boolean = false
    ): List<SkipInterval> {
        val stamps = SkipAPI.videoStamps(data, episode, episodeDurationMs, hasNextEpisode)
        loadFromSkipStamps(stamps)
        return intervals
    }

    suspend fun loadAniSkip(
        animeId: Long,
        episode: Int,
        episodeLengthSec: Long = 0L,
        idType: AnimeIdType = AnimeIdType.MAL
    ): List<SkipInterval> {
        val fetched = aniSkipClient.query(animeId, episode, episodeLengthSec, idType)
        intervals = fetched
        return fetched
    }

    /**
     * Checks if current position falls inside [startMs, endMs) for any loaded interval.
     * If autoSkipEnabled is true and an interval is active, triggers auto-skip callback.
     */
    fun checkSkip(currentPositionMs: Long): SkipInterval? {
        val active = intervals.firstOrNull { interval ->
            currentPositionMs >= interval.startMs && currentPositionMs < interval.endMs
        }
        if (active != null && autoSkipEnabled) {
            lastSkippedInterval = active
            onAutoSkipTriggered?.invoke(active)
        }
        return active
    }

    /**
     * Executes skip for active interval, returning destination timestamp (endMs).
     */
    fun executeSkip(currentPositionMs: Long): Long? {
        val active = checkSkip(currentPositionMs) ?: return null
        return active.endMs
    }
}
