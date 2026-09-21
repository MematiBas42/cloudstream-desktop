// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/ControllerActivity.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MetadataHolder(
    @JsonProperty("apiName") @SerialName("apiName") val apiName: String,
    @JsonProperty("isMovie") @SerialName("isMovie") val isMovie: Boolean,
    @JsonProperty("title") @SerialName("title") val title: String?,
    @JsonProperty("poster") @SerialName("poster") val poster: String?,
    @JsonProperty("currentEpisodeIndex") @SerialName("currentEpisodeIndex") val currentEpisodeIndex: Int,
    @JsonProperty("episodes") @SerialName("episodes") val episodes: List<ResultEpisode>,
    @JsonProperty("currentLinks") @SerialName("currentLinks") val currentLinks: List<ExtractorLink>,
    @JsonProperty("currentSubtitles") @SerialName("currentSubtitles") val currentSubtitles: List<SubtitleData>,
)
