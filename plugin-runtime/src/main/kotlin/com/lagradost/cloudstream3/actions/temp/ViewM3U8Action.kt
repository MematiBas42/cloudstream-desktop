// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/ViewM3U8Action.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.logging.AppLogger
import java.io.File

class ViewM3U8Action : VideoClickAction() {
    override val name = txt(R.string.episode_action_play_in_format, "m3u8 player")

    override val isPlayer = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val i = Intent(Intent.ACTION_VIEW)
        makeTempM3U8Intent(context, i, result)

        val link = index?.let { result.links.getOrNull(it) } ?: result.links.firstOrNull()
        val m3u8Content: String = try {
            if (link != null && (link.url.contains(".m3u8") || link.url.contains("/m3u8"))) {
                app.get(link.url).text
            } else {
                buildM3U8Playlist(result)
            }
        } catch (t: Throwable) {
            AppLogger.e("ViewM3U8Action", "Failed to fetch m3u8 content, generating playlist", t)
            buildM3U8Playlist(result)
        }

        val cacheDir = context.cacheDir ?: File(System.getProperty("java.io.tmpdir"))
        val outputFile = File.createTempFile("mirrorlist", ".m3u8", cacheDir)
        outputFile.writeText(m3u8Content)

        try {
            ProcessBuilder("xdg-open", outputFile.absolutePath).start()
            AppLogger.i("ViewM3U8Action", "Opened m3u8 playlist with xdg-open: ${outputFile.absolutePath}")
        } catch (t: Throwable) {
            AppLogger.e("ViewM3U8Action", "Failed to launch xdg-open for m3u8 file", t)
        }
    }

    companion object {
        fun buildM3U8Playlist(result: LinkLoadingResult): String {
            val sb = StringBuilder()
            sb.append("#EXTM3U\n#EXT-X-VERSION:3")
            result.links.forEach { link ->
                sb.append("\n#EXTINF:0,${link.name}\n${link.url}")
            }
            sb.append("\n#EXT-X-ENDLIST")
            return sb.toString()
        }

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

            val cacheDir = context.cacheDir ?: File(System.getProperty("java.io.tmpdir"))
            val outputFile = File.createTempFile("mirrorlist", ".m3u8", cacheDir)
            outputFile.writeText(buildM3U8Playlist(result))

            intent.setDataAndType(
                outputFile.toURI().toString().toUri(),
                "application/x-mpegURL"
            )
        }
    }
}
