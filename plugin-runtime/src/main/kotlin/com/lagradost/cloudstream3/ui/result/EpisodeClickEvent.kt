// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/EpisodeAdapter.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

const val ACTION_PLAY_EPISODE_IN_PLAYER = 1
const val ACTION_CHROME_CAST_EPISODE = 4
const val ACTION_CHROME_CAST_MIRROR = 5

const val ACTION_DOWNLOAD_EPISODE = 6
const val ACTION_DOWNLOAD_MIRROR = 7

const val ACTION_RELOAD_EPISODE = 8

const val ACTION_SHOW_OPTIONS = 10

const val ACTION_CLICK_DEFAULT = 11
const val ACTION_SHOW_TOAST = 12
const val ACTION_SHOW_DESCRIPTION = 15

const val ACTION_DOWNLOAD_EPISODE_SUBTITLE = 13
const val ACTION_DOWNLOAD_EPISODE_SUBTITLE_MIRROR = 14

const val ACTION_MARK_AS_WATCHED = 18
const val ACTION_MARK_WATCHED_UP_TO_THIS_EPISODE = 19

const val START_ACTION_RESUME_LATEST = 1
const val START_ACTION_LOAD_EP = 2

data class EpisodeClickEvent(val position: Int?, val action: Int, val data: ResultEpisode) {
    constructor(action: Int, data: ResultEpisode) : this(null, action, data)
}
