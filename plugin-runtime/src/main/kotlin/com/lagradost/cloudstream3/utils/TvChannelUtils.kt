// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/TvChannelUtils.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android TV Leanback Home Channels (TvContractCompat)
 *
 * Quarantined because Android TV Leanback Home Channels and TvContractCompat
 * are Android TV-specific APIs. As specified in CLAUDE.md Section 5.10, Linux desktop
 * replaces this with Freedesktop .desktop QuickList actions or D-Bus actions.
 */
@PlatformQuarantine(
    reason = "Android TV Leanback channels (TvContractCompat) are Android TV-specific; Linux desktop uses Freedesktop .desktop QuickList actions (CLAUDE.md 5.10)",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/TvChannelUtils.kt",
    status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
)
object TvChannelUtils
