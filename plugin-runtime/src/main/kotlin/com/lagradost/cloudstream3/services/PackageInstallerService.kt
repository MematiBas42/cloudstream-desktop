// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/services/PackageInstallerService.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.services

import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus

/**
 * Android PackageInstaller Background Service
 *
 * Quarantined because Android foreground service and APK streaming pipeline
 * are Android-specific. Linux desktop updates are managed via GitHub releases
 * and system package managers.
 */
@PlatformQuarantine(
    reason = "Android PackageInstaller foreground service is mobile-specific; Linux desktop updates are handled via GitHub releases / package managers",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/services/PackageInstallerService.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class PackageInstallerService {
    companion object {
        private const val EXTRA_URL = "EXTRA_URL"

        const val UPDATE_CHANNEL_ID = "cloudstream3.updates"
        const val UPDATE_CHANNEL_NAME = "App Updates"
        const val UPDATE_CHANNEL_DESCRIPTION = "App updates notification channel"
        const val UPDATE_NOTIFICATION_ID = -68454136

        fun getIntent(
            context: Context,
            url: String,
        ): Intent {
            return Intent(context, PackageInstallerService::class.java)
                .putExtra(EXTRA_URL, url)
        }
    }
}
