// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/CastOptionsProvider.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Google Cast Framework OptionsProvider
 *
 * Quarantined because Google Play Services Cast SDK (com.google.android.gms.cast)
 * is proprietary Android infrastructure. Desktop casting uses FCast / DLNA protocols,
 * implemented in task W3-10.
 */
@PlatformQuarantine(
    reason = "Google Play Services Cast SDK is Android-specific; Linux desktop uses native FCast/DLNA protocols (implemented in W3-10)",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/CastOptionsProvider.kt",
    status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
)
class CastOptionsProvider
