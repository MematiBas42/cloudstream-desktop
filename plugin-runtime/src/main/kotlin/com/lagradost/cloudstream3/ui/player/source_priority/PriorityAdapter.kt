// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/source_priority/PriorityAdapter.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player.source_priority

/**
 * Priority item representing a data item (e.g. quality or mirror link),
 * its display name, and its integer priority score.
 */
data class SourcePriority<T>(
    val data: T,
    val name: String,
    var priority: Int
)
