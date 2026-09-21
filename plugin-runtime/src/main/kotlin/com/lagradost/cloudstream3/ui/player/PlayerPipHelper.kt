// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/PlayerPipHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.DesktopContextProvider
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import kotlin.math.roundToInt

object PlayerPipHelper {
    private const val TAG = "PlayerPipHelper"

    // =========================================================================
    // 1:1 Upstream Android Picture-in-Picture Contract
    // =========================================================================

    /** Is pip (Player in Player) supported, and enabled? */
    fun Context.isPIPPossible(): Boolean {
        return try {
            this.hasPIPEnabled() && this.hasPIPFeature()
        } catch (t: Throwable) {
            // While both hasPIPEnabled and hasPIPFeature should never throw, this catches it just in case
            logError(t)
            false
        }
    }

    /** Is pip enabled in app settings? */
    private fun Context.hasPIPEnabled(): Boolean {
        return try {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
            settingsManager?.getBoolean(
                getString(R.string.pip_enabled_key),
                true
            ) ?: true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    /**
     * Is pip supported by the OS?
     *
     * Source:
     * https://stackoverflow.com/questions/52594181/how-to-know-if-user-has-disabled-picture-in-picture-feature-permission
     * https://developer.android.com/guide/topics/ui/picture-in-picture
     */
    private fun Context.hasPIPFeature(): Boolean =
        // OS Support
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                // Might have the feature, but OS blocked due to power drain
                this.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                // Might have been disabled by the user
                this.hasPIPPermission()

    /** Is pip enabled in the OS settings? */
    private fun Context.hasPIPPermission(): Boolean {
        val appOps =
            getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                android.os.Process.myUid(),
                packageName
            ) == AppOpsManager.MODE_ALLOWED
        } else true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPen(activity: Activity, code: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            activity,
            code,
            Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, code),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getRemoteAction(
        activity: Activity,
        id: Int,
        @StringRes title: Int,
        event: CSPlayerEvent
    ): RemoteAction {
        val text = activity.getString(title)
        return RemoteAction(
            Icon.createWithResource(activity, id),
            text,
            text,
            getPen(activity, event.value)
        )
    }

    fun updatePIPModeActions(
        activity: Activity?,
        status: CSPlayerLoading,
        pipEnabled: Boolean,
        aspectRatio: Rational?
    ) {
        // Is it even desired to enter pip mode right now if we ignore all settings?
        // This does not check for isPIPPossible as that is deferred to later
        val isPipDesired = when (status) {
            CSPlayerLoading.IsBuffering, CSPlayerLoading.IsPlaying -> pipEnabled
            else -> false
        }

        // On lower api ver setPictureInPictureParams is not supported,
        // so we enter pip manually in onUserLeaveHint
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            CommonActivity.isPipDesired = isPipDesired
            return
        }

        if (activity == null) return

        val actions: ArrayList<RemoteAction> = ArrayList()
        actions.add(
            getRemoteAction(
                activity,
                R.drawable.baseline_headphones_24,
                R.string.audio_singular,
                CSPlayerEvent.PlayAsAudio
            )
        )
        /*actions.add(
            getRemoteAction(
                activity,
                R.drawable.go_back_30,
                R.string.go_back_30,
                CSPlayerEvent.SeekBack
            )
        )*/

        if (status == CSPlayerLoading.IsPlaying) {
            actions.add(
                getRemoteAction(
                    activity,
                    R.drawable.netflix_pause,
                    R.string.pause,
                    CSPlayerEvent.Pause
                )
            )
        } else {
            actions.add(
                getRemoteAction(
                    activity,
                    R.drawable.ic_baseline_play_arrow_24,
                    R.string.pause,
                    CSPlayerEvent.Play
                )
            )
        }

        actions.add(
            getRemoteAction(
                activity,
                R.drawable.go_forward_30,
                R.string.go_forward_30,
                CSPlayerEvent.SeekForward
            )
        )

        // Necessary to prevent crashing.
        val mixAspectRatio = 0.41841f // ~1/2.39
        val maxAspectRatio = 2.39f // widescreen standard
        val ratioAccuracy = 100000 // To convert the float to int

        // java.lang.IllegalArgumentException: setPictureInPictureParams: Aspect ratio is too extreme
        // (must be between 0.418410 and 2.390000)
        val fixedRational =
            aspectRatio?.toFloat()?.coerceIn(mixAspectRatio, maxAspectRatio)?.let {
                Rational((it * ratioAccuracy).roundToInt(), ratioAccuracy)
            }

        safe {
            activity.setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setSeamlessResizeEnabled(true)
                            setAutoEnterEnabled(isPipDesired && activity.isPIPPossible())
                        } else {
                            // We enter pip manually in onUserLeaveHint as the smooth transition
                            // is not supported yet
                            CommonActivity.isPipDesired = isPipDesired
                        }
                    }
                    .setAspectRatio(fixedRational)
                    .setActions(actions)
                    .build()
            )
        }

        // Keep CommonActivity.isPipDesired synchronized for desktop window manager awareness
        CommonActivity.isPipDesired = isPipDesired

        // Dynamically reflect aspect ratio into active desktop mini-player window
        if (isInMiniPlayerMode && fixedRational != null) {
            updateMiniPlayerAspectRatio(fixedRational)
        }
    }

    // =========================================================================
    // Desktop Mini-Player / Always-On-Top Floating State Alternative
    // =========================================================================

    data class MiniPlayerState(
        val isMiniPlayer: Boolean = false,
        val isAlwaysOnTop: Boolean = false,
        val aspectRatio: Rational? = null,
        val originalBounds: Rectangle? = null,
        val miniPlayerBounds: Rectangle? = null
    )

    private val _miniPlayerState = MutableStateFlow(MiniPlayerState())
    val miniPlayerState: StateFlow<MiniPlayerState> = _miniPlayerState.asStateFlow()

    val isInMiniPlayerMode: Boolean
        get() = _miniPlayerState.value.isMiniPlayer

    val isAlwaysOnTopActive: Boolean
        get() = _miniPlayerState.value.isAlwaysOnTop

    private var savedBounds: Rectangle? = null
    private var savedAlwaysOnTop: Boolean = false

    /**
     * Verifies if always-on-top window placement is supported by the desktop window manager.
     */
    fun isAlwaysOnTopSupported(window: Window? = DesktopContextProvider.currentWindow): Boolean {
        return try {
            if (!GraphicsEnvironment.isHeadless()) {
                window?.isAlwaysOnTopSupported ?: true
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sets always-on-top state on the target desktop window and tracks it in [miniPlayerState].
     */
    fun setAlwaysOnTop(window: Window? = DesktopContextProvider.currentWindow, enable: Boolean): Boolean {
        return try {
            val win = window ?: DesktopContextProvider.currentWindow
            if (win != null && !GraphicsEnvironment.isHeadless() && win.isAlwaysOnTopSupported) {
                win.isAlwaysOnTop = enable
            }
            _miniPlayerState.value = _miniPlayerState.value.copy(isAlwaysOnTop = enable)
            AppLogger.d(TAG, "Desktop window always-on-top set to: $enable")
            true
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed to set always-on-top: ${t.message}")
            false
        }
    }

    /**
     * Current always-on-top state.
     */
    fun isAlwaysOnTop(window: Window? = DesktopContextProvider.currentWindow): Boolean {
        val win = window ?: DesktopContextProvider.currentWindow
        return if (win != null && !GraphicsEnvironment.isHeadless()) {
            win.isAlwaysOnTop
        } else {
            _miniPlayerState.value.isAlwaysOnTop
        }
    }

    /**
     * Enters desktop mini-player floating state:
     * 1. Preserves original window bounds and always-on-top configuration.
     * 2. Calculates compact floating dimensions preserving video aspect ratio (default 480px width).
     * 3. Repositions window to bottom-right corner with margin.
     * 4. Engages always-on-top mode.
     * 5. Updates [CommonActivity.isInPIPMode] and [_miniPlayerState].
     */
    fun enterMiniPlayer(
        window: Window? = DesktopContextProvider.currentWindow,
        aspectRatio: Rational? = null,
        targetWidth: Int = 480
    ): Boolean {
        return try {
            val win = window ?: DesktopContextProvider.currentWindow
            val currentBounds = win?.bounds ?: Rectangle(100, 100, 1280, 720)

            if (!isInMiniPlayerMode) {
                savedBounds = Rectangle(currentBounds)
                savedAlwaysOnTop = if (win != null && !GraphicsEnvironment.isHeadless()) {
                    win.isAlwaysOnTop
                } else {
                    false
                }
            }

            val ratio = aspectRatio?.let {
                val r = it.toFloat()
                if (r > 0f) r else 16f / 9f
            } ?: (16f / 9f)

            val width = targetWidth.coerceIn(320, 800)
            val height = (width / ratio).roundToInt().coerceIn(180, 600)

            val screenDim = try {
                if (!GraphicsEnvironment.isHeadless()) {
                    Toolkit.getDefaultToolkit().screenSize
                } else {
                    Dimension(1920, 1080)
                }
            } catch (_: Throwable) {
                Dimension(1920, 1080)
            }

            val margin = 24
            val x = (screenDim.width - width - margin).coerceAtLeast(0)
            val y = (screenDim.height - height - margin).coerceAtLeast(0)
            val miniBounds = Rectangle(x, y, width, height)

            if (win != null && !GraphicsEnvironment.isHeadless()) {
                win.bounds = miniBounds
                if (win.isAlwaysOnTopSupported) {
                    win.isAlwaysOnTop = true
                }
            }

            _miniPlayerState.value = MiniPlayerState(
                isMiniPlayer = true,
                isAlwaysOnTop = true,
                aspectRatio = aspectRatio,
                originalBounds = savedBounds,
                miniPlayerBounds = miniBounds
            )
            CommonActivity.isInPIPMode = true

            AppLogger.i(TAG, "Entered desktop mini-player mode: bounds=$miniBounds, alwaysOnTop=true")
            true
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    /**
     * Exits desktop mini-player floating state:
     * Restores previous window bounds and always-on-top setting.
     */
    fun exitMiniPlayer(window: Window? = DesktopContextProvider.currentWindow): Boolean {
        if (!isInMiniPlayerMode) return false

        return try {
            val win = window ?: DesktopContextProvider.currentWindow
            val restoreBounds = savedBounds

            if (win != null && !GraphicsEnvironment.isHeadless()) {
                if (restoreBounds != null) {
                    win.bounds = restoreBounds
                }
                if (win.isAlwaysOnTopSupported) {
                    win.isAlwaysOnTop = savedAlwaysOnTop
                }
            }

            _miniPlayerState.value = MiniPlayerState(
                isMiniPlayer = false,
                isAlwaysOnTop = savedAlwaysOnTop,
                aspectRatio = null,
                originalBounds = null,
                miniPlayerBounds = null
            )
            CommonActivity.isInPIPMode = false
            savedBounds = null

            AppLogger.i(TAG, "Exited desktop mini-player mode: restoredBounds=$restoreBounds")
            true
        } catch (t: Throwable) {
            logError(t)
            false
        }
    }

    /**
     * Toggles between desktop mini-player floating state and standard windowed state.
     */
    fun toggleMiniPlayer(
        window: Window? = DesktopContextProvider.currentWindow,
        aspectRatio: Rational? = null
    ): Boolean {
        return if (isInMiniPlayerMode) {
            exitMiniPlayer(window)
        } else {
            enterMiniPlayer(window, aspectRatio)
        }
    }

    /**
     * Dynamically updates mini-player dimensions when video aspect ratio changes during playback.
     */
    fun updateMiniPlayerAspectRatio(
        aspectRatio: Rational,
        window: Window? = DesktopContextProvider.currentWindow
    ) {
        if (!isInMiniPlayerMode) return
        val current = _miniPlayerState.value.miniPlayerBounds ?: return
        val ratio = aspectRatio.toFloat()
        if (ratio <= 0f) return

        val newHeight = (current.width / ratio).roundToInt().coerceIn(180, 600)
        val win = window ?: DesktopContextProvider.currentWindow

        val screenHeight = try {
            if (!GraphicsEnvironment.isHeadless()) Toolkit.getDefaultToolkit().screenSize.height else 1080
        } catch (_: Throwable) {
            1080
        }

        val margin = 24
        val newY = (screenHeight - newHeight - margin).coerceAtLeast(0)
        val updatedBounds = Rectangle(current.x, newY, current.width, newHeight)

        if (win != null && !GraphicsEnvironment.isHeadless()) {
            win.bounds = updatedBounds
        }

        _miniPlayerState.value = _miniPlayerState.value.copy(
            aspectRatio = aspectRatio,
            miniPlayerBounds = updatedBounds
        )
    }

    /**
     * Resets mini-player state to initial default. Useful for test teardowns.
     */
    fun resetMiniPlayerState() {
        savedBounds = null
        savedAlwaysOnTop = false
        _miniPlayerState.value = MiniPlayerState()
        CommonActivity.isInPIPMode = false
        CommonActivity.isPipDesired = false
    }
}
