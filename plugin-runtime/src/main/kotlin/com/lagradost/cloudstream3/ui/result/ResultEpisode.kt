// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragment.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class VideoWatchState {
    None,
    Watched
}

@Serializable
data class ResultEpisode(
    @param:JsonProperty("headerName") @SerialName("headerName") val headerName: String,
    @param:JsonProperty("name") @SerialName("name") val name: String?,
    @param:JsonProperty("poster") @SerialName("poster") val poster: String?,
    @param:JsonProperty("episode") @SerialName("episode") val episode: Int,
    @param:JsonProperty("seasonIndex") @SerialName("seasonIndex") val seasonIndex: Int? = null,
    @param:JsonProperty("season") @SerialName("season") val season: Int? = null,
    @param:JsonProperty("data") @SerialName("data") val data: String = "",
    @param:JsonProperty("apiName") @SerialName("apiName") val apiName: String = "",
    @param:JsonProperty("id") @SerialName("id") val id: Int = 0,
    @param:JsonProperty("index") @SerialName("index") val index: Int = 0,
    @param:JsonProperty("position") @SerialName("position") val position: Long = 0L,
    @param:JsonProperty("duration") @SerialName("duration") val duration: Long = 0L,
    @param:JsonProperty("score") @SerialName("score") val score: Score? = null,
    @param:JsonProperty("description") @SerialName("description") val description: String? = null,
    @param:JsonProperty("isFiller") @SerialName("isFiller") val isFiller: Boolean? = null,
    @param:JsonProperty("tvType") @SerialName("tvType") val tvType: TvType = TvType.TvSeries,
    @param:JsonProperty("parentId") @SerialName("parentId") val parentId: Int = 0,
    @param:JsonProperty("videoWatchState") @SerialName("videoWatchState") val videoWatchState: VideoWatchState = VideoWatchState.None,
    @param:JsonProperty("totalEpisodeIndex") @SerialName("totalEpisodeIndex") val totalEpisodeIndex: Int? = null,
    @param:JsonProperty("airDate") @SerialName("airDate") val airDate: Long? = null,
    @param:JsonProperty("runTime") @SerialName("runTime") val runTime: Int? = null,
    @param:JsonProperty("seasonData") @SerialName("seasonData") val seasonData: com.lagradost.cloudstream3.SeasonData? = null,
)

fun ResultEpisode.getRealPosition(): Long {
    if (duration <= 0) return 0
    val percentage = position * 100 / duration
    if (percentage <= 5 || percentage >= 95) return 0
    return position
}

fun ResultEpisode.getDisplayPosition(): Long {
    if (duration <= 0) return 0
    val percentage = position * 100 / duration
    if (percentage <= 1) return 0
    if (percentage <= 5) return 5 * duration / 100
    if (percentage >= 95) return duration
    return position
}

fun ResultEpisode.getWatchProgress(): Float {
    return (getDisplayPosition() / 1000).toFloat() / (duration / 1000).toFloat()
}

fun buildResultEpisode(
    headerName: String,
    name: String? = null,
    poster: String? = null,
    episode: Int,
    seasonIndex: Int? = null,
    season: Int? = null,
    data: String,
    apiName: String,
    id: Int,
    index: Int,
    rating: Score? = null,
    description: String? = null,
    isFiller: Boolean? = null,
    tvType: TvType,
    parentId: Int,
    totalEpisodeIndex: Int? = null,
    airDate: Long? = null,
    runTime: Int? = null,
    seasonData: com.lagradost.cloudstream3.SeasonData? = null,
): ResultEpisode {
    val posDur = com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos(id)
    val videoWatchState = com.lagradost.cloudstream3.utils.DataStoreHelper.getVideoWatchState(id) ?: VideoWatchState.None
    return ResultEpisode(
        headerName = headerName,
        name = name,
        poster = poster,
        episode = episode,
        seasonIndex = seasonIndex,
        season = season,
        data = data,
        apiName = apiName,
        id = id,
        index = index,
        position = posDur?.position ?: 0,
        duration = posDur?.duration ?: 0,
        score = rating,
        description = description,
        isFiller = isFiller,
        tvType = tvType,
        parentId = parentId,
        videoWatchState = videoWatchState,
        totalEpisodeIndex = totalEpisodeIndex,
        airDate = airDate,
        runTime = runTime,
        seasonData = seasonData
    )
}

fun com.lagradost.cloudstream3.LoadResponse.getId(): Int {
    return (if (this is ResultViewModel2.LoadResponseFromSearch) this.id else null)
        ?: getLoadResponseIdFromUrl(uniqueUrl, apiName)
}

fun getLoadResponseIdFromUrl(url: String, apiName: String): Int {
    return url.replace(com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(apiName)?.mainUrl ?: "", "").replace("/", "")
        .hashCode()
}
