// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/MpvRxPackage.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.txt

/** https://github.com/Riteshp2001/mpvRx
 *
 * https://github.com/Riteshp2001/mpvRx/blob/00e0c5e803ab53e5757426cbf2248448ba1f49bf/app/src/main/java/app/gyrolet/mpvrx/utils/media/MediaUtils.kt#L132
 * https://github.com/Riteshp2001/mpvRx/blob/00e0c5e803ab53e5757426cbf2248448ba1f49bf/app/src/main/java/app/gyrolet/mpvrx/utils/media/MediaUtils.kt#L56
 * */
@PlatformQuarantine(
    reason = "Android-özgü MPV fork; Linux'ta kanonik mpv ikilisi MpvPackage ile kullanılır",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/MpvRxPackage.kt:21",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class MpvRxPackage : OpenInAppAction(
    appName = txt("mpvRx"),
    packageName = "app.gyrolet.mpvrx",
    intentClass = "app.gyrolet.mpvrx.ui.player.PlayerActivity"
) {
    override val oneSource = true

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
        intent.apply {
            putExtra("title", video.name)
            val link = (if (index != null && index in result.links.indices) {
                result.links[index]
            } else {
                result.links.firstOrNull()
            }) ?: return
            val headers = link.headers

            setData(link.url.toUri())
            if (headers.isNotEmpty()) {
                // PlayerActivity expects a flat array: [key1, value1, key2, value2, ...]
                val flat = headers.entries.flatMap { listOf(it.key, it.value) }.toTypedArray()
                intent.putExtra("headers", flat)
            }

            if (video.tvType.isEpisodeBased()) {
                video.season?.let { intent.putExtra("introdb_season", it) }
                video.episode.let { intent.putExtra("introdb_episode", it) }
            }

            val position = getViewPos(video.id)?.position
            if (position != null)
                putExtra("position", position.toInt())
        }
    }

    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        Log.d("MPV", "Position: $position, Duration: $duration")
        updateDurationAndPosition(position.toLong(), duration.toLong())
    }
}
