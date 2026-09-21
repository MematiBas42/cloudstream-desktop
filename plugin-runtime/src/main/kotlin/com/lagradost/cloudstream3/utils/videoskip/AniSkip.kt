// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/videoskip/AniSkip.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils.videoskip

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getMalId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class AniSkip : SkipAPI() {
    override val name: String = "AniSkip"
    override val supportedTypes: Set<TvType> = setOf(TvType.Anime, TvType.OVA)

    override suspend fun stamps(
        data: LoadResponse,
        episode: ResultEpisode,
        episodeDurationMs: Long
    ): List<SkipStamp>? {
        if (data !is AnimeLoadResponse) return null // Filter actual anime

        val malId = data.getMalId()?.toIntOrNull() ?: return null
        val url =
            "https://api.aniskip.com/v2/skip-times/$malId/${episode.episode}?types[]=ed&types[]=mixed-ed&types[]=mixed-op&types[]=op&types[]=recap&episodeLength=${episodeDurationMs / 1000L}"

        val response = app.get(url).parsed<AniSkipResponse>()

        return response.results?.mapNotNull { stamp ->
            val skipType = when (stamp.skipType) {
                "op" -> SkipType.Opening
                "ed" -> SkipType.Ending
                "recap" -> SkipType.Recap
                "mixed-ed" -> SkipType.MixedEnding
                "mixed-op" -> SkipType.MixedOpening
                else -> null
            } ?: return@mapNotNull null
            val end = (stamp.interval.endTime * 1000.0).toLong()
            val start = (stamp.interval.startTime * 1000.0).toLong()
            SkipStamp(
                type = skipType,
                startMs = start,
                endMs = end,
            )
        }
    }

    @Serializable
    data class AniSkipResponse(
        @JsonProperty("found") @SerialName("found") val found: Boolean = false,
        @JsonProperty("results") @SerialName("results") val results: List<Stamp>? = null,
        @JsonProperty("message") @SerialName("message") val message: String? = null,
        @JsonProperty("statusCode") @SerialName("statusCode") val statusCode: Int = 200,
    )

    @Serializable
    data class Stamp(
        @JsonProperty("interval") @SerialName("interval") val interval: AniSkipInterval = AniSkipInterval(),
        @JsonProperty("skipType") @SerialName("skipType") val skipType: String = "",
        @JsonProperty("skipId") @SerialName("skipId") val skipId: String = "",
        @JsonProperty("episodeLength") @SerialName("episodeLength") val episodeLength: Double = 0.0,
    )

    @Serializable
    data class AniSkipInterval(
        @JsonProperty("startTime") @SerialName("startTime") val startTime: Double = 0.0,
        @JsonProperty("endTime") @SerialName("endTime") val endTime: Double = 0.0,
    )
}
