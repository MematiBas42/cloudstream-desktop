// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.actions.temp.fcast.FcastManager
import com.lagradost.cloudstream3.actions.temp.fcast.FcastSession

import android.app.Activity
import android.content.Context
import android.content.DesktopContextProvider
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.schabi.newpipe.extractor.NewPipe
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class FocusDirection {
    Start,
    End,
    Up,
    Down,
}

data class ToastData(
    val message: String,
    val duration: Int = Toast.LENGTH_SHORT
)

object CommonActivity {
    const val TAG = "COMPACT"

    private var _activity: WeakReference<Activity>? = null
    var activity: Activity?
        get() = _activity?.get() ?: (DesktopContextProvider.context as? Activity)
        set(value) {
            _activity = if (value != null) WeakReference(value) else null
        }

    @MainThread
    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    @MainThread
    fun Activity?.getCastSession(): FcastSession? {
        return FcastManager.currentSession
    }

    private fun getScreenDimension(): Dimension {
        return try {
            if (!GraphicsEnvironment.isHeadless()) {
                Toolkit.getDefaultToolkit().screenSize
            } else {
                Dimension(1920, 1080)
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to query AWT screen size: ${e.message}")
            Dimension(1920, 1080)
        }
    }

    val displayMetrics: DisplayMetrics
        get() {
            val metrics = DisplayMetrics()
            val dim = getScreenDimension()
            metrics.widthPixels = dim.width
            metrics.heightPixels = dim.height
            return metrics
        }

    // screenWidth and screenHeight does always
    // refer to the screen while in landscape mode
    val screenWidth: Int
        get() {
            val dim = getScreenDimension()
            return max(dim.width, dim.height)
        }

    val screenHeight: Int
        get() {
            val size = getScreenDimension()
            return min(size.width, size.height)
        }

    val screenWidthWithOrientation: Int
        get() = getScreenDimension().width

    val screenHeightWithOrientation: Int
        get() = getScreenDimension().height

    var isPipDesired: Boolean = false
    var isInPIPMode: Boolean = false

    val onColorSelectedEvent = Event<Pair<Int, Int>>()
    val onDialogDismissedEvent = Event<Int>()

    var keyEventListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null
    var appliedTheme: Int = 0
    var appliedColor: Int = 0

    private var currentToast: Toast? = null

    // Toast bus for desktop UI consumption
    private val _toastSharedFlow = MutableSharedFlow<ToastData>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastSharedFlow: SharedFlow<ToastData> = _toastSharedFlow.asSharedFlow()
    val toastEvent = Event<ToastData>()

    fun showToast(@StringRes message: Int, duration: Int? = null) {
        val act = activity ?: (DesktopContextProvider.context as? Activity)
        val text = act?.getString(message) ?: DesktopContextProvider.context?.getString(message) ?: "res_$message"
        showToast(act, text, duration)
    }

    fun showToast(message: String?, duration: Int? = null) {
        val act = activity ?: (DesktopContextProvider.context as? Activity)
        showToast(act, message, duration)
    }

    fun showToast(message: UiText?, duration: Int? = null) {
        if (message == null) return
        val act = activity ?: (DesktopContextProvider.context as? Activity)
        val text = message.asStringNull(act ?: DesktopContextProvider.context) ?: message.toString()
        showToast(act, text, duration)
    }

    @MainThread
    fun showToast(act: Activity?, text: UiText, duration: Int) {
        val str = text.asStringNull(act ?: DesktopContextProvider.context) ?: text.toString()
        showToast(act, str, duration)
    }

    /** duration is Toast.LENGTH_SHORT if null*/
    @MainThread
    fun showToast(act: Activity?, @StringRes message: Int, duration: Int? = null) {
        val text = act?.getString(message) ?: DesktopContextProvider.context?.getString(message) ?: "res_$message"
        showToast(act, text, duration)
    }

    /** duration is Toast.LENGTH_SHORT if null*/
    @MainThread
    fun showToast(act: Activity?, message: String?, duration: Int? = null) {
        if (message.isNullOrBlank()) {
            Log.w(TAG, "invalid showToast act = $act message = $message")
            return
        }
        val cleanMsg = message.trim()
        val dur = duration ?: Toast.LENGTH_SHORT
        Log.i(TAG, "showToast = $cleanMsg")
        AppLogger.i(TAG, "Toast: $cleanMsg (duration=$dur)")

        val toastData = ToastData(cleanMsg, dur)
        _toastSharedFlow.tryEmit(toastData)
        try {
            toastEvent(toastData)
        } catch (e: Exception) {
            logError(e)
        }

        try {
            val toast = Toast(act ?: DesktopContextProvider.context)
            toast.setText(cleanMsg)
            toast.setDuration(dur)
            currentToast = toast
            toast.show()
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun setLocale(context: Context?, languageTag: String?) {
        if (context == null || languageTag == null) return
        try {
            val locale = Locale.forLanguageTag(languageTag)
            Locale.setDefault(locale)
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to set locale $languageTag: ${e.message}")
        }
    }

    fun Context.updateLocale() {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val localeKey = try {
            getString(R.string.locale_key)
        } catch (_: Throwable) {
            "app_locale"
        }
        val localeCode = settingsManager.getString(localeKey, null)
            ?: settingsManager.getString("app_locale", null)
            ?: settingsManager.getString("app_language", null)
        setLocale(this, localeCode)
    }

    fun init(act: Activity) {
        setActivityInstance(act)
        ioSafe { Torrent.deleteAllFiles() }
        act.updateLocale()
        try {
            AccountManager.initMainAPI()
        } catch (e: Throwable) {
            logError(e)
        }

        try {
            DownloaderTestImpl.getInstance()?.let { downloader ->
                NewPipe.init(downloader)
            }
        } catch (e: Throwable) {
            logError(e)
        }
        AppLogger.i(TAG, "CommonActivity initialized")
    }

    @PlatformQuarantine(
        reason = "Mobile picture-in-picture mode is handled by window managers on desktop (X11/Wayland always-on-top or floating player)",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:287",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.enterPIPMode() {
        if (!isPipDesired) return
        AppLogger.d(TAG, "enterPIPMode requested on desktop - activating mini-player floating state")
        com.lagradost.cloudstream3.ui.player.PlayerPipHelper.enterMiniPlayer()
    }

    @PlatformQuarantine(
        reason = "User leave hint is Android activity lifecycle specific; desktop uses window focus/minimize listeners",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:310",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun onUserLeaveHint(act: Activity) {
        act.enterPIPMode()
    }

    @PlatformQuarantine(
        reason = "Android Activity theme styling is replaced by Compose Desktop theming",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:316",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun updateTheme(act: Activity) {
        AppLogger.d(TAG, "updateTheme: Android View theme engine not used on Compose Desktop")
    }

    @PlatformQuarantine(
        reason = "Android system UI mode night mask mapped to R.style; desktop uses DesktopAppSettings or system dark theme",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:326",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun mapSystemTheme(act: Activity): Int {
        return 0
    }

    @PlatformQuarantine(
        reason = "Android Activity theme styling is replaced by Compose Desktop theming",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:339",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun loadThemes(act: Activity?) {
        if (act == null) return
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(act)

        val currentTheme =
            when (settingsManager.getString(act.getString(R.string.app_theme_key), "AmoledLight")) {
                "System" -> mapSystemTheme(act)
                "Black" -> 1
                "Light" -> 2
                "Amoled" -> 3
                "AmoledLight" -> 4
                "Monet" -> 5
                "Dracula" -> 6
                "Lavender" -> 7
                "SilentBlue" -> 8
                else -> 1
            }

        val currentOverlayTheme =
            when (settingsManager.getString(act.getString(R.string.primary_color_key), "Normal")) {
                "Normal" -> 101
                "DandelionYellow" -> 102
                "CarnationPink" -> 103
                "Orange" -> 104
                "DarkGreen" -> 105
                "Maroon" -> 106
                "NavyBlue" -> 107
                "Grey" -> 108
                "White" -> 109
                "CoolBlue" -> 110
                "Brown" -> 111
                "Purple" -> 112
                "Green" -> 113
                "GreenApple" -> 114
                "Red" -> 115
                "Banana" -> 116
                "Party" -> 117
                "Pink" -> 118
                "Lavender" -> 119
                "Monet" -> 120
                "Monet2" -> 121
                else -> 101
            }

        appliedTheme = currentTheme
        appliedColor = currentOverlayTheme
    }

    @PlatformQuarantine(
        reason = "Android View hierarchy navigation; desktop Compose uses FocusRequester and TvFocusManager",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:404",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    private fun localLook(from: View, id: Int): View? {
        if (id == View.NO_ID) return null
        var currentLook: View = from
        for (i in 0..15) {
            currentLook.findViewById<View>(id)?.let { return it }
            currentLook = (currentLook.parent as? View) ?: break
        }
        return null
    }

    @PlatformQuarantine(
        reason = "Android View content check; desktop Compose uses StateFlow and LayoutNode hierarchy",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:424",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    private fun View.hasContent(): Boolean {
        return isShown && when (this) {
            is ViewGroup -> this.isNotEmpty()
            else -> true
        }
    }

    @PlatformQuarantine(
        reason = "Android View focus search; desktop Compose uses KeyNavigationInterceptor and TvFocusManager",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:432",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun continueGetNextFocus(
        root: Any?,
        view: View,
        direction: FocusDirection,
        nextId: Int,
        depth: Int = 0
    ): View? {
        if (nextId == View.NO_ID) return null

        var next =
            when (root) {
                is Activity -> root.findViewById<View>(nextId)
                is View -> root.rootView.findViewById<View>(nextId)
                else -> null
            } ?: return null

        next = localLook(view, nextId) ?: next
        val shown = next.hasContent()

        val hasChildrenThatWantsFocus = (next as? ViewGroup)?.let { parent ->
            parent.descendantFocusability == ViewGroup.FOCUS_AFTER_DESCENDANTS && parent.isNotEmpty()
        } ?: false
        if (!next.isFocusable && shown && !hasChildrenThatWantsFocus) return null

        if (!shown) {
            if (next == view) return null
            return getNextFocus(root, next, direction, depth + 1)
        }

        return next
    }

    @PlatformQuarantine(
        reason = "Android View focus search; desktop Compose uses KeyNavigationInterceptor and TvFocusManager",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/CommonActivity.kt:488",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun getNextFocus(
        root: Any?,
        view: View?,
        direction: FocusDirection,
        depth: Int = 0
    ): View? {
        if (view == null || depth >= 10 || root == null) {
            return null
        }

        var nextId = when (direction) {
            FocusDirection.Start -> {
                if (view.isRtl())
                    view.nextFocusRightId
                else
                    view.nextFocusLeftId
            }

            FocusDirection.Up -> {
                view.nextFocusUpId
            }

            FocusDirection.End -> {
                if (view.isRtl())
                    view.nextFocusLeftId
                else
                    view.nextFocusRightId
            }

            FocusDirection.Down -> {
                view.nextFocusDownId
            }
        }

        if (nextId == View.NO_ID) {
            nextId = view.nextFocusForwardId
            if (nextId == View.NO_ID)
                return null
        }
        return continueGetNextFocus(root, view, direction, nextId, depth)
    }

    fun onKeyDown(act: Activity?, keyCode: Int, event: KeyEvent?): Boolean? {
        if (keyEventListener?.invoke(Pair(event, false)) == true) {
            return true
        }
        return null
    }

    /** overrides focus and custom key events */
    fun dispatchKeyEvent(act: Activity?, event: KeyEvent?): Boolean? {
        val currentAct = act ?: activity
        val currentFocus = currentAct?.currentFocus

        event?.keyCode?.let { keyCode ->
            if (currentAct != null && currentFocus != null && event.action == KeyEvent.ACTION_DOWN) {
                val nextView = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> getNextFocus(
                        currentAct,
                        currentFocus,
                        FocusDirection.Start
                    )

                    KeyEvent.KEYCODE_DPAD_RIGHT -> getNextFocus(
                        currentAct,
                        currentFocus,
                        FocusDirection.End
                    )

                    KeyEvent.KEYCODE_DPAD_UP -> getNextFocus(
                        currentAct,
                        currentFocus,
                        FocusDirection.Up
                    )

                    KeyEvent.KEYCODE_DPAD_DOWN -> getNextFocus(
                        currentAct,
                        currentFocus,
                        FocusDirection.Down
                    )

                    else -> null
                }

                if (nextView != null) {
                    nextView.requestFocus()
                    keyEventListener?.invoke(Pair(event, true))
                    return true
                }
            }
        }

        // if someone else want to override the focus then don't handle the event as it is already
        // consumed. used in video player (handles media keys like KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_NEXT, etc.)
        if (keyEventListener?.invoke(Pair(event, false)) == true) {
            return true
        }
        return null
    }
}
