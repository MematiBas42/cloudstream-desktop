// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/OnlyPlayer.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.txt

/** https://github.com/Kindness-Kismet/only_player/tree/main
 * https://github.com/Kindness-Kismet/only_player/blob/main/feature/player/src/main/java/one/only/player/feature/player/PlayerActivity.kt */
@PlatformQuarantine(
    reason = "Android-özgü oynatıcı uygulamaları; Linux'ta VLC/MPV (W3-03, W3-04) kullanılır.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/OnlyPlayer.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class OnlyPlayer : OpenInAppAction(
    appName = txt("Only Player"),
    packageName = "one.only.player",
    intentClass = "one.only.player.feature.player.PlayerActivity"
) {
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
        intent.apply {
            setData(link.url.toUri())

            putExtra("headers", Bundle().apply {
                for ((key, value) in link.headers) {
                    putExtra(key, value)
                }
            })
        }
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
