// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/PackageInstaller.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android PackageInstaller APK Streaming Session
 *
 * Quarantined because Android PackageInstaller session streaming is mobile/APK-specific.
 * Linux desktop distribution updates are handled via AppImage, Flatpak, native packages
 * (deb/rpm/arch), or GitHub release assets.
 */
@PlatformQuarantine(
    reason = "Android PackageInstaller APK streaming session is mobile-specific; Linux desktop updates are handled via GitHub releases / package managers",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/PackageInstaller.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class ApkInstaller(val service: Any? = null) {

    companion object {
        var delayedInstaller: DelayedInstaller? = null
        private const val TAG = "ApkInstaller"
    }

    class DelayedInstaller {
        fun startInstallation(): Boolean {
            delayedInstaller = null
            return false
        }
    }

    enum class InstallProgressStatus {
        Preparing,
        Downloading,
        Installing,
        Failed,
    }
}
