// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/MpvPackage.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.makeTempM3U8Intent
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// https://github.com/mpv-android/mpv-android/blob/0eb3cdc6f1632636b9c30d52ec50e4b017661980/app/src/main/java/is/xyz/mpv/MPVActivity.kt#L904
// https://mpv-android.github.io/mpv-android/intent.html

// https://github.com/marlboro-advance/mpvEx
class MpvExPackage : MpvPackage("mpvEx", "app.marlboroadvance.mpvex", "app.marlboroadvance.mpvex.ui.player.PlayerActivity")

class MpvYTDLPackage : MpvPackage("MPV YTDL", "is.xyz.mpv.ytdl") {
    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8
    )
}

open class MpvPackage(
    appName: String = "MPV",
    packageName: String = "is.xyz.mpv",
    intentClass: String = "is.xyz.mpv.MPVActivity"
) : OpenInAppAction(
    txt(appName),
    packageName,
    intentClass
) {
    open val executableName: String = "mpv"

    override val oneSource = true // mpv has poor playlist support on TV

    companion object {
        fun isBinaryInPath(
            binary: String = "mpv",
            osName: String = System.getProperty("os.name") ?: "",
            pathEnv: String? = System.getenv("PATH"),
            env: Map<String, String> = System.getenv()
        ): Boolean {
            return resolveExecutable(binary, osName, pathEnv, env) != null
        }

        fun resolveExecutable(
            binary: String = "mpv",
            osName: String = System.getProperty("os.name") ?: "",
            pathEnv: String? = System.getenv("PATH"),
            env: Map<String, String> = System.getenv()
        ): File? {
            return VlcPackage.resolveExecutable(binary, osName, pathEnv, env)
        }

        fun resolveExecutablePath(
            binary: String = "mpv",
            osName: String = System.getProperty("os.name") ?: "",
            pathEnv: String? = System.getenv("PATH"),
            env: Map<String, String> = System.getenv()
        ): String? {
            return resolveExecutable(binary, osName, pathEnv, env)?.absolutePath
        }
    }

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        return isBinaryInPath(executableName)
    }

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        intent.apply {
            putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
            putExtra("title", video.name)

            if (index != null) {
                setDataAndType((result.links.getOrNull(index)?.url ?: return).toUri(), "video/*")
            } else {
                makeTempM3U8Intent(context, this, result)
            }

            val position = getViewPos(video.id)?.position
            if (position != null)
                putExtra("position", position.toInt())

            putExtra("secure_uri", true)
        }
    }

    /**
     * Builds command-line arguments for launching MPV externally, mapping upstream extras:
     * - HTTP headers -> --http-header-fields=
     * - Subtitle URLs -> --sub-file=
     * - Resume position -> --start=
     * - Media title -> --force-media-title=
     * - Referer -> --referrer=
     * - User-Agent -> --user-agent=
     */
    fun buildMpvArgs(
        executable: String = resolveExecutable(executableName)?.absolutePath ?: executableName,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int? = null,
        positionMs: Long? = null,
        subsLang: String? = null
    ): List<String> {
        val selectedLink = if (index != null && index in result.links.indices) {
            result.links[index]
        } else {
            result.links.firstOrNull()
        } ?: return emptyList()

        val url = selectedLink.url
        val args = mutableListOf(executable, url)

        // Title matching upstream putExtra("title", video.name)
        if (!video.name.isNullOrBlank()) {
            args.add("--force-media-title=${video.name}")
        }

        // Start position matching upstream putExtra("position", position.toInt())
        val effectivePositionMs = positionMs ?: getViewPos(video.id)?.position ?: 0L
        val positionSec = (effectivePositionMs / 1000L).coerceAtLeast(0L)
        if (positionSec > 0) {
            args.add("--start=$positionSec")
        }

        // Subtitle files matching upstream putExtra("subs", result.subs.map { it.url.toUri() }.toTypedArray())
        val distinctSubs = result.subs.filter { it.url.isNotBlank() }.distinctBy { it.url }
        for (sub in distinctSubs) {
            args.add("--sub-file=${sub.url}")
        }

        val effectiveSubsLang = subsLang ?: getKey<String>(SUBTITLE_AUTO_SELECT_KEY) ?: "en"
        if (distinctSubs.isNotEmpty() && !effectiveSubsLang.isNullOrBlank()) {
            args.add("--slang=$effectiveSubsLang")
        }

        // HTTP Headers mapping to --referrer, --user-agent, and --http-header-fields
        val allHeaders = linkedMapOf<String, String>()

        val referer = selectedLink.referer.ifBlank {
            selectedLink.headers["referer"] ?: selectedLink.headers["Referer"]
        }
        if (!referer.isNullOrBlank()) {
            allHeaders["Referer"] = referer
            args.add("--referrer=$referer")
        }

        val userAgent = selectedLink.headers["user-agent"] ?: selectedLink.headers["User-Agent"]
        if (!userAgent.isNullOrBlank()) {
            allHeaders["User-Agent"] = userAgent
            args.add("--user-agent=$userAgent")
        }

        for ((key, value) in selectedLink.headers) {
            if (allHeaders.keys.none { it.equals(key, ignoreCase = true) }) {
                allHeaders[key] = value
            }
        }

        val headerFields = allHeaders.map { "${it.key}: ${it.value}" }
        if (headerFields.isNotEmpty()) {
            args.add("--http-header-fields=${headerFields.joinToString(",")}")
        }

        return args
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        setKey("last_opened", video)
        val args = buildMpvArgs(
            video = video,
            result = result,
            index = index
        )
        if (args.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                ProcessBuilder(args)
                    .inheritIO()
                    .start()
            } catch (t: Throwable) {
                Log.e("MPV", "Failed to launch MPV process: ${t.message}")
            }
        }
    }

    @PlatformQuarantine(
        reason = "MPV CLI process on Linux does not report playback position/duration back via Intent result",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/MpvPackage.kt:66",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1) ?: -1
        val duration = intent?.getIntExtra("duration", -1) ?: -1
        Log.d("MPV", "Position: $position, Duration: $duration")
        updateDurationAndPosition(position.toLong(), duration.toLong())
    }
}
