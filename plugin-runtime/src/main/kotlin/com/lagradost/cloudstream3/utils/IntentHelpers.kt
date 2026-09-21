// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/IntentHelpers.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android Intent & BroadcastReceiver Flag Helpers
 *
 * Quarantined because Android Intent Parcelable extra extraction (TIRAMISU backwards compatibility)
 * and Context.registerReceiver flags are Android OS-specific IPC mechanisms.
 * Linux desktop utilizes in-memory type-safe objects, POSIX signals, and D-Bus IPC.
 */
@PlatformQuarantine(
    reason = "Android Intent Parcelable and BroadcastReceiver flags are mobile-specific; Linux desktop uses in-memory objects and D-Bus IPC",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/IntentHelpers.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
object IntentHelpers
