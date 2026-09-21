// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/PowerManagerAPI.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android PowerManager & Battery Optimization Checker
 *
 * Quarantined because Android battery optimization whitelist (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
 * and WakeLock APIs are mobile-specific hardware constraints not applicable to Linux desktop.
 * Power inhibition during playback and downloads is handled via Freedesktop D-Bus systemd-inhibit.
 */
@PlatformQuarantine(
    reason = "Android battery optimization whitelist and WakeLock APIs are mobile hardware constraints not applicable to Linux desktop; desktop utilizes D-Bus systemd-inhibit",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/PowerManagerAPI.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
object BatteryOptimizationChecker {
    private const val TAG = "PowerManagerAPI"

    fun isAppRestricted(context: Context? = null): Boolean = false

    fun openBatteryOptimizationSettings(context: Context? = null) {
        // Mobile battery optimization settings are not applicable to desktop
    }

    fun Context.showBatteryOptimizationDialog() {
        // Mobile battery optimization dialogs are not applicable to desktop
    }

    fun Context.showRequestIgnoreBatteryOptDialog() {
        // Mobile battery optimization intent dialogs are not applicable to desktop
    }
}
