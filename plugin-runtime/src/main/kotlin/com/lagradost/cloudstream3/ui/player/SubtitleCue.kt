// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/SubtitleOffsetItemAdapter.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

data class SubtitleCue(val startTimeMs: Long, val durationMs: Long, val text: List<String>)
