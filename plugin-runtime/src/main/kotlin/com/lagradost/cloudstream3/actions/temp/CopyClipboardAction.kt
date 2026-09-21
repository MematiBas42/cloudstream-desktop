// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/CopyClipboardAction.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.logging.AppLogger
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyClipboardAction : VideoClickAction() {
    override val name = txt("Copy to clipboard")

    override val oneSource = true

    override fun shouldShow(context: Context?, video: ResultEpisode?) = true

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (index == null) return
        val link = result.links.getOrNull(index) ?: return
        clipboardHelper(txt(link.name), link.url)
    }

    companion object {
        fun clipboardHelper(label: UiText, text: CharSequence) {
            try {
                val selection = StringSelection(text.toString())
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                CommonActivity.showToast("Copied to clipboard")
            } catch (t: Throwable) {
                AppLogger.e("CopyClipboardAction", "Failed to copy to clipboard via AWT", t)
                try {
                    val process = ProcessBuilder("wl-copy").start()
                    process.outputStream.bufferedWriter().use { it.write(text.toString()) }
                    val exit = process.waitFor()
                    if (exit == 0) {
                        CommonActivity.showToast("Copied to clipboard")
                    } else {
                        throw RuntimeException("wl-copy exited with $exit")
                    }
                } catch (t2: Throwable) {
                    try {
                        val process = ProcessBuilder("xclip", "-selection", "clipboard").start()
                        process.outputStream.bufferedWriter().use { it.write(text.toString()) }
                        val exit = process.waitFor()
                        if (exit == 0) {
                            CommonActivity.showToast("Copied to clipboard")
                        } else {
                            throw RuntimeException("xclip exited with $exit")
                        }
                    } catch (t3: Throwable) {
                        AppLogger.e("CopyClipboardAction", "All clipboard copy methods failed", t3)
                        CommonActivity.showToast("Failed to copy to clipboard")
                    }
                }
            }
        }
    }
}
