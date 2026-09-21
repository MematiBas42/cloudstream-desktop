package com.lagradost.cloudstream3.desktop.ui.navigation

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.storage.WatchHistory

/**
 * Encapsulates playback launch parameters, metadata, and lifecycle callbacks for embedded MPV playback.
 */
data class VideoLaunchData(
    val links: List<ExtractorLink> = emptyList(),
    val initialIndex: Int = 0,
    val title: String? = null,
    val subtitles: List<SubtitleFile> = emptyList(),
    val startPositionMs: Long = 0L,
    val history: WatchHistory,
    val episodes: List<ResultEpisode> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val provider: MainAPI? = null,
    val dataUrl: String? = null,
    val useLocalProxy: Boolean = false,
    val onError: ((String) -> Unit)? = null,
    val onClosed: (() -> Unit)? = null,
    val onNextEpisode: (() -> Unit)? = null,
)
