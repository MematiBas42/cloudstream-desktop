// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/NextPlayerPackage.kt", upstreamCommit = "caeec18")
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

/** https://github.com/anilbeesetti/nextplayer */
@PlatformQuarantine(
    reason = "Android-özgü oynatıcı uygulamaları; Linux'ta VLC/MPV (W3-03, W3-04) kullanılır.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/NextPlayerPackage.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class NextPlayerPackage : OpenInAppAction(
    appName = txt("NextPlayer"),
    packageName = "dev.anilbeesetti.nextplayer",
    intentClass = "dev.anilbeesetti.nextplayer.feature.player.PlayerActivity"
) {
    override val sourceTypes: Set<ExtractorLinkType> =
        setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)

    override val oneSource: Boolean = true

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        // Quarantined: Not applicable to desktop environments
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
