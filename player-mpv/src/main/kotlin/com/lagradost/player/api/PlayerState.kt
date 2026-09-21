package com.lagradost.player.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class TrackInfo(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("type") val type: String = "", // "video", "audio", "sub"
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("selected") val selected: Boolean = false,
    @JsonProperty("codec") val codec: String? = null,
    @JsonProperty("demux-channels") val demuxChannels: String? = null,
    @JsonProperty("audio-channels") val audioChannels: Int? = null,
    @JsonProperty("demux-channel-count") val demuxChannelCount: Int? = null,
    @JsonProperty("external") val external: Boolean = false,
    @JsonProperty("external-filename") val externalFilename: String? = null,
    @JsonProperty("default") val default: Boolean = false,
    @JsonProperty("forced") val forced: Boolean = false,
)

typealias PlayerTrack = TrackInfo

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayerState(
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val duration: Long = 0L,
    val position: Long = 0L,
    val isFinished: Boolean = false,
    val error: String? = null,
    val isLoading: Boolean = false,
    val currentUrl: String? = null,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val tracks: List<TrackInfo> = emptyList(),
    val isCompleted: Boolean = false,
    val subtitleDelaySec: Double = 0.0,
    val activeAudioTrackId: Int? = null,
    val activeSubtitleTrackId: Int? = null,
    val videoAspect: Double? = null,
    val dwidth: Int? = null,
    val dheight: Int? = null,
    val currentSubText: String? = null,
) {
    val isFinishedOrCompleted: Boolean
        get() = isFinished || isCompleted

    val audioTracks: List<TrackInfo>
        get() = tracks.filter { it.type == "audio" }

    val subtitleTracks: List<TrackInfo>
        get() = tracks.filter { it.type == "sub" }
}
