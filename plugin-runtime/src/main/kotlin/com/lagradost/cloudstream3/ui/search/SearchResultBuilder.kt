// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchResultBuilder.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android RecyclerView SearchResult Card ViewBinder
 *
 * Quarantined because Android View, ImageView, ProgressBar, and CardView binding
 * are Android-specific UI components (CLAUDE.md Group F). On Linux desktop,
 * this is replaced by Compose Multiplatform TvCard and SearchScreen components.
 */
@PlatformQuarantine(
    reason = "Replaced by Compose Multiplatform TvCard and SearchScreen (CLAUDE.md Group F)",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchResultBuilder.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
object SearchResultBuilder
