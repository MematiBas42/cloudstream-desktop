// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/download/DownloadAdapter.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.download

import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

const val DOWNLOAD_ACTION_PLAY_FILE = 0
const val DOWNLOAD_ACTION_DELETE_FILE = 1
const val DOWNLOAD_ACTION_RESUME_DOWNLOAD = 2
const val DOWNLOAD_ACTION_PAUSE_DOWNLOAD = 3
const val DOWNLOAD_ACTION_DOWNLOAD = 4
const val DOWNLOAD_ACTION_LONG_CLICK = 5
const val DOWNLOAD_ACTION_CANCEL_PENDING = 6

const val DOWNLOAD_ACTION_GO_TO_CHILD = 0
const val DOWNLOAD_ACTION_LOAD_RESULT = 1

sealed class VisualDownloadCached {
    abstract val currentBytes: Long
    abstract val totalBytes: Long
    abstract val data: DownloadObjects.DownloadCached
    abstract var isSelected: Boolean

    data class Child(
        override val currentBytes: Long,
        override val totalBytes: Long,
        override val data: DownloadObjects.DownloadEpisodeCached,
        override var isSelected: Boolean,
    ) : VisualDownloadCached()

    data class Header(
        override val currentBytes: Long,
        override val totalBytes: Long,
        override val data: DownloadObjects.DownloadHeaderCached,
        override var isSelected: Boolean,
        val child: DownloadObjects.DownloadEpisodeCached?,
        val currentOngoingDownloads: Int,
        val totalDownloads: Int,
    ) : VisualDownloadCached()
}

data class DownloadClickEvent(
    val action: Int,
    val data: DownloadObjects.DownloadEpisodeCached
)

data class DownloadHeaderClickEvent(
    val action: Int,
    val data: DownloadObjects.DownloadHeaderCached
)
