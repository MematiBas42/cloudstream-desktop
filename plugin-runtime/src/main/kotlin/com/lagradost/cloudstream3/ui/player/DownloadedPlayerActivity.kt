// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/DownloadedPlayerActivity.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper.playLink
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper.playUri
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat
import java.io.File

/**
 * 1:1 Architectural Parity Port of upstream DownloadedPlayerActivity with Desktop CLI & File Launcher Bridge.
 *
 * Responsibilities:
 * 1. Android Activity Lifecycle & Intent Routing:
 *    - Handles ACTION_VIEW, ACTION_SEND, ACTION_OPEN_DOCUMENT and CloudStreamPackage intents.
 *    - Dispatches to [OfflinePlaybackHelper.playUri] / [OfflinePlaybackHelper.playLink].
 *    - Guards against duplicate reloads via [isSameIntent].
 * 2. Desktop CLI & File Association Bridge:
 *    - Parses command-line arguments and external file manager requests (paths, file://, magnet:).
 *    - Routes directly to [playUri] on desktop.
 */
class DownloadedPlayerActivity : AppCompatActivity() {
    companion object {
        const val TAG = "DownloadedPlayerActivity"

        /**
         * Connects CLI file arguments or external file open requests directly to [playUri].
         * Supports:
         * 1. Absolute / relative local filesystem file paths (e.g. "/path/to/video.mp4", "C:\videos\movie.mkv")
         * 2. file:// URIs (e.g. "file:///home/user/video.mp4")
         * 3. content:// URIs
         * 4. magnet:? and http(s):// direct media links
         *
         * @param fileOrUri Path, URI or URL from CLI arguments or system file association.
         * @param activity Target activity (defaults to active Activity or creates a [DownloadedPlayerActivity] instance).
         * @return true if the argument was recognized as a playable media target and dispatched.
         */
        fun handleFileArgument(fileOrUri: String?, activity: Activity? = null): Boolean {
            if (fileOrUri.isNullOrBlank()) return false
            val trimmed = fileOrUri.trim().trim('"', '\'')
            if (trimmed.isEmpty()) return false

            val act = activity ?: CommonActivity.activity ?: DownloadedPlayerActivity()

            // 1. Magnet URI
            if (trimmed.startsWith("magnet:", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                playUri(act, uri)
                return true
            }

            // 2. Explicit file:// or content:// URI
            if (trimmed.startsWith("file://", ignoreCase = true) || trimmed.startsWith("content://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                playUri(act, uri)
                return true
            }

            // 3. HTTP / HTTPS stream or media link
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                playLink(act, trimmed)
                return true
            }

            // 4. Local filesystem path (e.g. /home/user/video.mp4, C:\Videos\movie.mkv)
            val file = File(trimmed)
            if (file.exists() && file.isFile) {
                val uri = Uri.fromFile(file)
                playUri(act, uri)
                return true
            }

            // 5. Recognized media file extensions (video & playlist formats)
            val knownMediaExtensions = listOf(
                ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".wmv", ".m4v",
                ".3gp", ".ts", ".m3u8", ".mpd"
            )
            if (knownMediaExtensions.any { trimmed.endsWith(it, ignoreCase = true) }) {
                val uri = Uri.fromFile(file.absoluteFile)
                playUri(act, uri)
                return true
            }

            return false
        }

        /**
         * Creates an [Intent] from a file path or URI string with [Intent.ACTION_VIEW].
         */
        fun createViewIntent(fileOrUri: String): Intent {
            val trimmed = fileOrUri.trim().trim('"', '\'')
            val uri = if (trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("magnet:", ignoreCase = true)
            ) {
                Uri.parse(trimmed)
            } else {
                Uri.fromFile(File(trimmed).absoluteFile)
            }
            return Intent(Intent.ACTION_VIEW).apply {
                data = uri
            }
        }

        /**
         * Dispatches an external [Intent] to [DownloadedPlayerActivity] routing.
         */
        fun handleIntent(intent: Intent, activity: Activity? = null): Boolean {
            val act = (activity as? DownloadedPlayerActivity) ?: DownloadedPlayerActivity()
            act.handleIntent(intent)
            return true
        }

        /**
         * Convenience delegation matching PlayerActivity.playUri / OfflinePlaybackHelper.playUri contract.
         */
        fun playUri(activity: Activity, uri: Uri) {
            OfflinePlaybackHelper.playUri(activity, uri)
        }

        /**
         * Convenience delegation matching PlayerActivity.playLink / OfflinePlaybackHelper.playLink contract.
         */
        fun playLink(activity: Activity, url: String) {
            OfflinePlaybackHelper.playLink(activity, url)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        CommonActivity.onKeyDown(this, keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        CommonActivity.onUserLeaveHint(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ignore same intent so the player doesnt totally
        // reload if you are playing the same thing.
        if (isSameIntent(intent)) return
        this.intent = intent
        Log.i(TAG, "onNewIntent")
        handleIntent(intent)
    }

    fun isSameIntent(newIntent: Intent): Boolean {
        val old = intent ?: return false
        // Compare URIs first
        val oldUri = old.data ?: old.clipData?.getItemAt(0)?.uri
        val newUri = newIntent.data ?: newIntent.clipData?.getItemAt(0)?.uri
        if (oldUri != null && oldUri == newUri) return true
        // Fall back to comparing EXTRA_TEXT links
        val oldText = safe { old.getStringExtra(Intent.EXTRA_TEXT) }
        val newText = safe { newIntent.getStringExtra(Intent.EXTRA_TEXT) }
        return oldText != null && oldText == newText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.loadThemes(this)
        CommonActivity.init(this)
        enableEdgeToEdgeCompat()
        setContentView(R.layout.empty_layout)
        Log.i(TAG, "onCreate")
        intent?.let { handleIntent(it) }

        /**
         * Use moveTaskToBack instead of finish() so there is always exactly one task
         * entry in recents, always reflecting the current file.
         *
         * finish() destroys the Activity but may leave the task in recents. Each new file
         * open can create a new task entry, so recents accumulates stale entries for old
         * files. The user then taps a stale entry and gets the wrong file.
         *
         * moveTaskToBack keeps the Activity alive in the background. There is only ever
         * one task entry in recents. New files opened from the file manager arrive via
         * onNewIntent on the live instance, updating the player immediately. The single
         * recents entry always reflects the current state, ensuring we load the
         * correct file.
         */
        attachBackPressedCallback("DownloadedPlayerActivity") { moveTaskToBack(true) }
    }

    fun handleIntent(intent: Intent) {
        val data = intent.data
        if (OfflinePlaybackHelper.playIntent(activity = this, intent = intent)) {
            return
        }

        if (
            intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_OPEN_DOCUMENT ||
            intent.action == Intent.ACTION_VIEW
        ) {
            val extraText = safe { intent.getStringExtra(Intent.EXTRA_TEXT) }
            val cd = intent.clipData
            val item = if (cd != null && cd.itemCount > 0) cd.getItemAt(0) else null
            val url = item?.text?.toString()
            when {
                item?.uri != null -> playUri(this, item.uri!!)
                url != null -> playLink(this, url)
                data != null -> playUri(this, data)
                extraText != null -> playLink(this, extraText)
                else -> finishAndRemoveTask()
            }
        } else if (data?.scheme == "content" || data?.scheme == "file") {
            playUri(this, data)
        } else finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        CommonActivity.setActivityInstance(this)
    }
}
