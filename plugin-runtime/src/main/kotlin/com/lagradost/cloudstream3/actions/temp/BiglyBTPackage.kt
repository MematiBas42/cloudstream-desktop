// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/BiglyBTPackage.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

/** https://github.com/BiglySoftware/BiglyBT-Android */
@PlatformQuarantine(
    reason = "Android intent tabanlı istemciler; Linux'ta xdg-open/transmission/qbittorrent ve FCast (W3-10) kullanılır.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/BiglyBTPackage.kt",
    status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
)
class BiglyBTPackage : OpenInAppAction(
    appName = txt("BiglyBT"),
    packageName = "com.biglybt.android.client",
    intentClass = "com.biglybt.android.client.activity.IntentHandler"
) {
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.MAGNET, ExtractorLinkType.TORRENT)

    override val oneSource: Boolean = true

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        // Quarantined: Android intent-based client not applicable on Linux desktop
        return false
    }

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val link = result.links.getOrNull(index ?: 0) ?: return
        intent.data = link.url.toUri()
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
