// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/WebVideoCastPackage.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt

// https://www.webvideocaster.com/integrations

@PlatformQuarantine(
    reason = "Android intent tabanlı istemciler; Linux'ta xdg-open/transmission/qbittorrent ve FCast (W3-10) kullanılır.",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/WebVideoCastPackage.kt",
    status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
)
class WebVideoCastPackage : OpenInAppAction(
    appName = txt("Web Video Cast"),
    packageName = "com.instantbits.cast.webvideo"
) {
    override val oneSource = true

    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8
    )

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

        intent.apply {
            setDataAndType(link.url.toUri(), "video/*")

            val title = video.name ?: video.headerName

            putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
            putExtra("title", title)
            video.poster?.let { putExtra("poster", it) }
            val headers = Bundle().apply {
                if (link.referer.isNotBlank())
                    putString("Referer", link.referer)
                putString("User-Agent", USER_AGENT)
                for ((key, value) in link.headers) {
                    putString(key, value)
                }
            }
            putExtra("android.media.intent.extra.HTTP_HEADERS", headers)
            putExtra("secure_uri", true)
        }
    }

    override fun onResult(activity: Activity, intent: Intent?) = Unit
}
