// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/VideoClickAction.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DesktopContextProvider
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.common.platform.DesktopPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Callable

abstract class VideoClickAction {
    abstract val name: UiText

    /** if true, the app will show dialog to select source - result.links[index] */
    open val oneSource: Boolean = false

    /** if true, this action could be selected as default player (one press action) in settings */
    open val isPlayer: Boolean = false

    /** Which type of sources this action can handle. */
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    /** Determines which plugin a given provider is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null

    /** Even if VideoClickAction should not run any UI code, startActivity requires it,
     * this is a wrapper for Dispatchers.Main in a suspended safe context that bubble up exceptions */
    @Throws
    suspend fun <T> uiThread(callable: Callable<T>): T? {
        return try {
            withContext(Dispatchers.Main) {
                callable.call()
            }
        } catch (_: Throwable) {
            callable.call()
        }
    }

    internal fun launchProcess(intent: Intent) {
        val target = intent.data?.toString() ?: intent.getStringExtra("data") ?: intent.action
        val pkg = intent.component?.packageName ?: intent.getStringExtra("package")
        val cls = intent.component?.className

        // 1. In-App Embedded Player Interception:
        // When an action targets CloudStream itself (e.g. CloudStreamPackage or DownloadedPlayerActivity)
        // or provides playback links, route directly to the embedded player preserving all original parameters
        // (url, title, headers, subtitles, resume position) without data loss.
        val isEmbeddedPlayer = pkg == BuildConfig.APPLICATION_ID ||
            pkg == "com.lagradost.cloudstream3" ||
            pkg == "com.lagradost.cloudstream3.prerelease" ||
            pkg == "com.lagradost.cloudstream3.debug" ||
            cls?.contains("DownloadedPlayerActivity") == true ||
            intent.hasExtra(CloudStreamPackage.LINKS_EXTRA)

        if (isEmbeddedPlayer) {
            val activity = CommonActivity.activity ?: Activity()
            if (OfflinePlaybackHelper.playIntent(activity, intent)) {
                return
            }
            val uri = intent.data
            if (uri != null) {
                OfflinePlaybackHelper.playUri(activity, uri)
                return
            }
            if (!target.isNullOrBlank()) {
                OfflinePlaybackHelper.playLink(activity, target)
                return
            }
            return
        }

        // 2. External player / application resolution
        val binName = if (!pkg.isNullOrBlank()) {
            when {
                pkg.contains("vlc", ignoreCase = true) -> "vlc"
                pkg.contains("mpv", ignoreCase = true) -> "mpv"
                pkg.contains("celluloid", ignoreCase = true) -> "celluloid"
                pkg.contains("totem", ignoreCase = true) -> "totem"
                pkg.contains("smplayer", ignoreCase = true) -> "smplayer"
                pkg.contains("kodi", ignoreCase = true) -> "kodi"
                pkg.contains("firefox", ignoreCase = true) -> "firefox"
                pkg.contains("chrome", ignoreCase = true) -> "google-chrome"
                pkg.contains("chromium", ignoreCase = true) -> "chromium"
                pkg.contains("brave", ignoreCase = true) -> "brave"
                else -> pkg.substringAfterLast('.').lowercase()
            }
        } else null

        if (binName != null) {
            val resolvedBin = DesktopPlatform.resolveExecutable(binName)
            val cmd = mutableListOf(resolvedBin)
            if (!target.isNullOrBlank()) {
                cmd.add(target)
            }
            try {
                ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (t: Throwable) {
                // Cross-platform fallback when direct binary launch fails
                if (!target.isNullOrBlank()) {
                    DesktopPlatform.openUrl(target)
                } else {
                    throw t
                }
            }
        } else if (!target.isNullOrBlank()) {
            DesktopPlatform.openUrl(target)
        }
    }

    /** Internally uses activityResultLauncher,
     * use this when the activity has a result like watched position */
    @Throws
    suspend fun launchResult(intent: Intent?, options: ActivityOptionsCompat? = null) {
        if (intent == null) {
            return
        }

        uiThread {
            launchProcess(intent)
        }
    }

    /** Internally uses startActivity, use this when you don't
     * have any result that needs to be stored when exiting the activity */
    @Throws
    suspend fun launch(intent: Intent?, bundle: Bundle? = null) {
        if (intent == null) {
            return
        }

        uiThread {
            launchProcess(intent)
        }
    }

    fun uniqueId() = "$sourcePlugin:${this::class.qualifiedName}"

    @Throws
    abstract fun shouldShow(context: Context?, video: ResultEpisode?): Boolean

    /** Safe version of shouldShow, as we don't trust extension devs to handle exceptions,
     * however no dev *should* throw in shouldShow */
    fun shouldShowSafe(context: Context?, video: ResultEpisode?): Boolean {
        return try {
            shouldShow(context, video)
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    /**
     *  This function is called when the action is clicked.
     *  @param context The current activity
     *  @param video The episode/movie that was clicked
     *  @param result The result of the link loading, contains video & subtitle links
     *  @param index if oneSource is true, this is the index of the selected source
     */
    @Throws
    abstract suspend fun runAction(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?)

    /** Safe version of runAction, as we don't trust extension devs to handle exceptions */
    fun runActionSafe(context: Context?, video: ResultEpisode, result: LinkLoadingResult, index: Int?) = ioSafe {
        try {
            runAction(context, video, result, index)
        } catch (_: NotImplementedError) {
            val actionName = name.asStringNull(context)
                ?: name.asStringNull(DesktopContextProvider.context)
                ?: name.toString()
            CommonActivity.showToast(
                "runAction has not been implemented for $actionName, please contact the extension developer of $sourcePlugin",
                Toast.LENGTH_LONG
            )
        } catch (error: ErrorLoadingException) {
            CommonActivity.showToast(error.message, Toast.LENGTH_LONG)
        } catch (_: ActivityNotFoundException) {
            CommonActivity.showToast(R.string.app_not_found_error, Toast.LENGTH_LONG)
        } catch (_: IOException) {
            CommonActivity.showToast(R.string.app_not_found_error, Toast.LENGTH_LONG)
        } catch (t: Throwable) {
            logError(t)
            CommonActivity.showToast(t.toString(), Toast.LENGTH_LONG)
        }
    }
}
