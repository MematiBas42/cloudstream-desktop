// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/Aria2Package.kt", upstreamCommit = "caeec18")
// Desktop adaptation: replaced Android Aria2Android intent stub with Linux native aria2c ProcessBuilder/JSON-RPC integration.
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.platform.PlatformPaths
import java.io.File

/**
 * Linux desktop adaptation of Aria2Package.
 * Replaces the unworkable Android Aria2Android intent stub with native Linux aria2c integration,
 * supporting both JSON-RPC daemon dispatch and direct ProcessBuilder execution.
 */
@Suppress("unused")
class Aria2Package : OpenInAppAction(
    appName = txt("Aria2"),
    packageName = "com.gianlu.aria2android",
    intentClass = "com.gianlu.aria2android.MainActivity"
) {
    override val oneSource: Boolean = true

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        return isAria2Available()
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val selectedIndex = index ?: 0
        val link = result.links.getOrNull(selectedIndex) ?: run {
            CommonActivity.showToast("No link available for Aria2 download")
            return
        }

        val downloadDir = getDownloadDir()
        val url = link.url
        val headers = link.headers
        val referer = link.referer

        // 1. Try sending to a running aria2 JSON-RPC daemon (default: http://127.0.0.1:6800/jsonrpc)
        val rpcSuccess = trySendRpc(url, downloadDir.absolutePath, headers, referer)
        if (rpcSuccess) {
            CommonActivity.showToast("Aria2: Download enqueued via RPC")
            return
        }

        // 2. Fallback: Launch aria2c process directly via ProcessBuilder
        val processStarted = startAria2Process(url, downloadDir, headers, referer)
        if (processStarted) {
            CommonActivity.showToast("Aria2: Download started in ${downloadDir.name}")
        } else {
            CommonActivity.showToast("Aria2: Failed to start aria2c process")
        }
    }

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val selectedIndex = index ?: 0
        val link = result.links.getOrNull(selectedIndex) ?: return
        intent.putExtra("url", link.url)
        intent.putExtra("dir", getDownloadDir().absolutePath)
        if (link.referer.isNotBlank()) {
            intent.putExtra("referer", link.referer)
        }
    }

    @PlatformQuarantine(
        reason = "Aria2 on Linux runs as a background process/RPC daemon; Android Activity result is not applicable",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/Aria2Package.kt:29",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    override fun onResult(activity: Activity, intent: Intent?) {
        // Desktop handles downloads asynchronously via aria2c daemon/process
    }

    companion object {
        private const val DEFAULT_RPC_URL = "http://127.0.0.1:6800/jsonrpc"
        private const val PREF_DOWNLOAD_PATH = "download_path_key"

        /**
         * Checks if the aria2c binary exists in PATH and is executable.
         */
        fun isAria2Available(): Boolean {
            val pathEnv = System.getenv("PATH") ?: return false
            return pathEnv.split(File.pathSeparator).any { dir ->
                val file = File(dir, "aria2c")
                file.isFile && file.canExecute()
            }
        }

        /**
         * Resolves the download directory from user settings (DataStore) or PlatformPaths.downloadsDir.
         */
        fun getDownloadDir(): File {
            val configuredPath = DataStore.getKey<String>(PREF_DOWNLOAD_PATH)?.ifBlank { null }
            val dir = if (configuredPath != null) {
                File(configuredPath)
            } else {
                PlatformPaths.downloadsDir.toFile()
            }
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        /**
         * Attempts to send a download request to a running aria2 JSON-RPC daemon.
         */
        suspend fun trySendRpc(
            url: String,
            downloadDirPath: String,
            headers: Map<String, String>,
            referer: String
        ): Boolean {
            return try {
                val headerList = mutableListOf<String>()
                if (referer.isNotBlank()) {
                    headerList.add("Referer: $referer")
                }
                headers.forEach { (k, v) ->
                    headerList.add("$k: $v")
                }

                val options = mutableMapOf<String, Any>(
                    "dir" to downloadDirPath
                )
                if (headerList.isNotEmpty()) {
                    options["header"] = headerList
                }

                val rpcPayload = mapOf(
                    "jsonrpc" to "2.0",
                    "id" to "cloudstream-${System.currentTimeMillis()}",
                    "method" to "aria2.addUri",
                    "params" to listOf(
                        listOf(url),
                        options
                    )
                )

                val response = app.post(
                    DEFAULT_RPC_URL,
                    json = rpcPayload,
                    timeout = 2L
                )
                response.isSuccessful
            } catch (t: Throwable) {
                logError(t)
                false
            }
        }

        /**
         * Launches aria2c via ProcessBuilder in background with appropriate headers and download directory.
         */
        fun startAria2Process(
            url: String,
            downloadDir: File,
            headers: Map<String, String>,
            referer: String
        ): Boolean {
            return try {
                val command = mutableListOf(
                    "aria2c",
                    url,
                    "--dir=${downloadDir.absolutePath}",
                    "--summary-interval=0"
                )
                if (referer.isNotBlank()) {
                    command.add("--referer=$referer")
                }
                headers.forEach { (k, v) ->
                    command.add("--header=$k: $v")
                }

                val processBuilder = ProcessBuilder(command)
                processBuilder.directory(downloadDir)

                val logDir = PlatformPaths.logsDir.toFile().apply { mkdirs() }
                val logFile = File(logDir, "aria2.log")
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile))

                processBuilder.start()
                true
            } catch (e: Exception) {
                logError(e)
                false
            }
        }
    }
}
