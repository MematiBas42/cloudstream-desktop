// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/OpenInAppAction.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultFragment
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.AppContextUtils.isAppInstalled
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.io.File

fun updateDurationAndPosition(position: Long, duration: Long) {
    if (position <= 0 || duration <= 0) return
    val episode = getKey<ResultEpisode>("last_opened") ?: return
    DataStoreHelper.setViewPosAndResume(episode.id, position, duration, episode, null)
    ResultFragment.updateUI()
}

/**
 * Util method that may be helpful for creating intents for apps that support m3u8 files.
 * All sources are written to a temporary m3u8 file, which is then sent to the app.
 */
fun makeTempM3U8Intent(
    context: Context,
    intent: Intent,
    result: LinkLoadingResult
) {
    if (result.links.size == 1) {
        intent.setDataAndType(result.links.first().url.toUri(), "video/*")
        return
    }

    intent.apply {
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    val outputFile = File.createTempFile("mirrorlist", ".m3u8", context.cacheDir)
    var text = "#EXTM3U\n#EXT-X-VERSION:3"

    result.links.forEach { link ->
        text += "\n#EXTINF:0,${link.name}\n${link.url}"
    }

    //With subtitles it doesn't work for no reason :(
    /*for (sub in result.subs) {
        val normalizedName = sub.name.replace("[^a-zA-Z0-9 ]".toRegex(), "")
        text += "\n#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"${normalizedName}\",DEFAULT=NO,AUTOSELECT=NO,FORCED=NO,LANGUAGE=\"${sub.languageCode}\",URI=\"${sub.url}\""
    }*/

    text += "\n#EXT-X-ENDLIST"
    outputFile.writeText(text)

    intent.setDataAndType(
        FileProvider.getUriForFile(
            context,
            context.applicationContext.packageName + ".provider",
            outputFile
        ), "application/x-mpegURL"
    )
}

abstract class OpenInAppAction(
    open val appName: UiText,
    open val packageName: String,
    open val intentClass: String? = null,
    open val action: String = Intent.ACTION_VIEW
) : VideoClickAction() {
    override val name: UiText
        get() = txt(R.string.episode_action_play_in_format, appName)

    override val isPlayer = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) =
        if (packageName == BuildConfig.APPLICATION_ID || packageName.startsWith("com.lagradost.cloudstream3")) {
            true
        } else {
            context?.isAppInstalled(packageName) != false
        }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val intent = Intent(action)
        intent.setPackage(packageName)
        val cls = intentClass
        if (cls != null) {
            intent.component = ComponentName(packageName, cls)
        }
        putExtra(context, intent, video, result, index)

        // Ensure in-app embedded player intents retain all critical playback parameters without data loss
        if (packageName == BuildConfig.APPLICATION_ID || packageName.startsWith("com.lagradost.cloudstream3")) {
            if (!intent.hasExtra(CloudStreamPackage.LINKS_EXTRA) && result.links.isNotEmpty()) {
                intent.putExtra(
                    CloudStreamPackage.LINKS_EXTRA,
                    result.links.filter { it !is ExtractorLinkPlayList && it !is DrmExtractorLink }
                        .map { CloudStreamPackage.MinimalVideoLink.fromExtractor(it).toJson() }.toTypedArray()
                )
            }
            if (!intent.hasExtra(CloudStreamPackage.SUBTITLE_EXTRA) && result.subs.isNotEmpty()) {
                intent.putExtra(
                    CloudStreamPackage.SUBTITLE_EXTRA,
                    result.subs.map { CloudStreamPackage.MinimalSubtitleLink.fromSubtitle(it).toJson() }.toTypedArray()
                )
            }
            if (!intent.hasExtra(CloudStreamPackage.TITLE_EXTRA) && !video.name.isNullOrBlank()) {
                intent.putExtra(CloudStreamPackage.TITLE_EXTRA, video.name)
            }
            if (!intent.hasExtra(CloudStreamPackage.ID_EXTRA)) {
                intent.putExtra(CloudStreamPackage.ID_EXTRA, video.id)
            }
            if (!intent.hasExtra(CloudStreamPackage.POSITION_EXTRA)) {
                val position = DataStoreHelper.getViewPos(video.id)?.position
                if (position != null) intent.putExtra(CloudStreamPackage.POSITION_EXTRA, position)
            }
        }

        setKey("last_opened", video)
        launchResult(intent)
    }

    /**
     * Before intent is sent, this function is called to put extra data into the intent.
     * @see VideoClickAction.runAction
     * */
    @Throws
    abstract suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    )

    /**
     * This function is called when the app is opened again after the intent was sent.
     * You can use it to for example update duration and position.
     * @see updateDurationAndPosition
     */
    @Throws
    abstract fun onResult(activity: Activity, intent: Intent?)

    /** Safe version of onResult, we don't trust extension devs to not crash the app */
    fun onResultSafe(activity: Activity, intent: Intent?) {
        try {
            onResult(activity, intent)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
