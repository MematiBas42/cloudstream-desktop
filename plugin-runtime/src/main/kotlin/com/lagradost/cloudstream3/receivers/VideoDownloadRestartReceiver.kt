// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/receivers/VideoDownloadRestartReceiver.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.receivers

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android Video Download Restart BroadcastReceiver
 *
 * Quarantined because Android BroadcastReceiver and startForegroundService are mobile-specific.
 * As specified in CLAUDE.md Section 5.10, Linux desktop replaces Android boot/service restart receivers
 * with systemd user services and daemon timer background management (W2-08).
 */
@PlatformQuarantine(
    reason = "Android BroadcastReceiver; masaüstünde systemd user service / daemon timer kullanılır (CLAUDE.md 5.10)",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/receivers/VideoDownloadRestartReceiver.kt",
    status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
)
class VideoDownloadRestartReceiver
