// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DimenRes
import androidx.annotation.StyleRes
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.roundToInt

/**
 * Encapsulates a navigation request issued from business logic / plugin-runtime.
 * Deserialized and handled by the desktop UI shell (NavController).
 */
data class NavigationRequest(
    val navigationId: Int,
    val args: Any? = null,
    val navOptions: Any? = null
)

/**
 * Bridge for resolving theme colors in headless/plugin-runtime environments.
 * The UI layer (desktop-app Compose) can set [resolver] to provide colors from its active ColorScheme.
 */
object ThemeColorProvider {
    @Volatile
    var resolver: ((context: Context?, attribute: Int) -> Int)? = null

    // Standard Desktop / Material 3 theme colors
    const val COLOR_PRIMARY: Int = 0xFF2563EB.toInt() // Electric blue
    const val COLOR_ACCENT: Int = 0xFF3B82F6.toInt()
    const val COLOR_BACKGROUND: Int = 0xFF0B0F19.toInt()
    const val COLOR_SURFACE: Int = 0xFF1E293B.toInt()
    const val COLOR_TEXT_PRIMARY: Int = 0xFFF8FAFC.toInt()
    const val COLOR_TEXT_MUTED: Int = 0xFF94A3B8.toInt()

    @ColorInt
    fun getColor(context: Context?, @AttrRes attribute: Int): Int {
        resolver?.let { return it(context, attribute) }
        return when (attribute) {
            com.lagradost.cloudstream3.R.attr.colorPrimary -> COLOR_PRIMARY
            else -> COLOR_PRIMARY
        }
    }
}

object UIHelper {
    var displayDensity: Float = 1.0f

    val Int.toPx: Int get() = (this * displayDensity).roundToInt()
    val Float.toPx: Float get() = (this * displayDensity)
    val Int.toDp: Int get() = (this / displayDensity).roundToInt()
    val Float.toDp: Float get() = (this / displayDensity)

    const val CLIPBOARD_MAX_RETRIES = 5
    const val CLIPBOARD_RETRY_DELAY_MS = 25L

    @Volatile
    var systemClipboardProvider: ((text: String) -> Boolean)? = null

    @PlatformQuarantine(
        reason = "Android runtime storage permissions (WRITE_EXTERNAL_STORAGE) not applicable on POSIX desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:96",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Context.checkWrite(): Boolean {
        AppLogger.d("UIHelper", "checkWrite: POSIX filesystem permissions are used on desktop")
        return true
    }

    @PlatformQuarantine(
        reason = "Android Material ChipGroup view population replaced by Compose Desktop tags/chips in desktop-app",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:107",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun populateChips(
        view: Any?,
        tags: List<String>,
        @StyleRes style: Int = 0,
        @AttrRes textColor: Int? = null,
    ) {
        AppLogger.d("UIHelper", "populateChips: Android ChipGroup is not used on desktop")
    }

    @PlatformQuarantine(
        reason = "Android runtime permissions request (MANAGE_EXTERNAL_STORAGE) not applicable on POSIX desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:139",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.requestRW() {
        AppLogger.d("UIHelper", "requestRW: POSIX desktop does not require runtime storage permission dialog")
    }

    /**
     * Attempts to write text to system clipboard via AWT with retry mechanism.
     * Retries upon [IllegalStateException] (typically thrown when another process has locked the clipboard).
     */
    fun copyToClipboardAwt(
        text: String,
        maxRetries: Int = CLIPBOARD_MAX_RETRIES,
        retryDelayMs: Long = CLIPBOARD_RETRY_DELAY_MS,
        clipboardSupplier: (() -> java.awt.datatransfer.Clipboard)? = null
    ): Boolean {
        if (java.awt.GraphicsEnvironment.isHeadless() && clipboardSupplier == null) {
            return false
        }
        val selection = java.awt.datatransfer.StringSelection(text)
        var lastException: Throwable? = null
        for (attempt in 1..maxRetries) {
            try {
                val clipboard = clipboardSupplier?.invoke()
                    ?: java.awt.Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(selection, null)
                return true
            } catch (e: IllegalStateException) {
                // Clipboard busy or locked by another application
                lastException = e
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs * attempt)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } catch (t: Throwable) {
                lastException = t
                break
            }
        }
        AppLogger.w("UIHelper", "AWT clipboard copy failed after $maxRetries attempts: ${lastException?.message}")
        return false
    }

    /**
     * Fallback process-based clipboard writers for Linux (Wayland/X11) and Windows.
     */
    fun copyToClipboardProcess(text: String): Boolean {
        // Try wl-copy (Wayland)
        try {
            val process = ProcessBuilder("wl-copy").start()
            process.outputStream.bufferedWriter().use { it.write(text) }
            if (process.waitFor() == 0) return true
        } catch (_: Throwable) {}

        // Try xclip (X11)
        try {
            val process = ProcessBuilder("xclip", "-selection", "clipboard").start()
            process.outputStream.bufferedWriter().use { it.write(text) }
            if (process.waitFor() == 0) return true
        } catch (_: Throwable) {}

        // Try xsel (X11 alternative)
        try {
            val process = ProcessBuilder("xsel", "--clipboard", "--input").start()
            process.outputStream.bufferedWriter().use { it.write(text) }
            if (process.waitFor() == 0) return true
        } catch (_: Throwable) {}

        // Try clip (Windows)
        try {
            val process = ProcessBuilder("clip").start()
            process.outputStream.bufferedWriter().use { it.write(text) }
            if (process.waitFor() == 0) return true
        } catch (_: Throwable) {}

        return false
    }

    fun clipboardHelper(label: UiText, text: CharSequence) {
        val ctx = CloudStreamApp.context
        val labelStr = label.asStringNull(ctx) ?: label.toString()
        val textStr = text.toString()

        if (textStr.length > 5_000_000) {
            AppLogger.e("UIHelper", "Clipboard text too large: ${textStr.length} chars")
            CommonActivity.showToast(R.string.clipboard_too_large)
            return
        }

        try {
            val copied = systemClipboardProvider?.invoke(textStr)
                ?: (copyToClipboardAwt(textStr) || copyToClipboardProcess(textStr))

            if (copied) {
                val labelSuffix = txt(R.string.toast_copied).asStringNull(ctx) ?: "copied!"
                CommonActivity.showToast("$labelStr $labelSuffix")
            } else {
                throw IllegalStateException("All clipboard copy methods failed")
            }
        } catch (t: Throwable) {
            AppLogger.e("UIHelper", "ClipboardService error: $t", t)
            when (t) {
                is SecurityException -> {
                    CommonActivity.showToast(R.string.clipboard_permission_error)
                }
                is OutOfMemoryError -> {
                    CommonActivity.showToast(R.string.clipboard_too_large)
                }
                else -> {
                    CommonActivity.showToast(R.string.clipboard_unknown_error, android.widget.Toast.LENGTH_LONG)
                }
            }
        }
    }

    @PlatformQuarantine(
        reason = "Android ListView dynamic measurement not applicable to Compose Desktop LazyColumn",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:187",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun setListViewHeightBasedOnItems(listView: Any?) {
        AppLogger.d("UIHelper", "setListViewHeightBasedOnItems: Android ListView not used on desktop")
    }

    fun Context.getSpanCount(isHorizontal: Boolean = false): Int {
        val spanCountLandscape = if (isHorizontal) 3 else 6
        val spanCountPortrait = if (isHorizontal) 2 else 3
        val orientation = try {
            resources.configuration.orientation
        } catch (_: Throwable) {
            Configuration.ORIENTATION_LANDSCAPE
        }

        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            spanCountLandscape
        } else spanCountPortrait
    }

    @PlatformQuarantine(
        reason = "Virtual keyboard management is not applicable to Linux desktop physical keyboards",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:221",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun hideKeyboard(view: View?) {
        AppLogger.d("UIHelper", "hideKeyboard: physical keyboard in use on desktop")
    }

    @PlatformQuarantine(
        reason = "Android AppBarLayout scroll flags not applicable to Compose Desktop top bars",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:228",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun View?.setAppBarNoScrollFlagsOnTV() {
        AppLogger.d("UIHelper", "setAppBarNoScrollFlagsOnTV: AppBarLayout not used on desktop")
    }

    @PlatformQuarantine(
        reason = "androidx.fragment.app.Fragment is not used in single-window Compose Desktop; keyboard is physical",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:221",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun androidx.fragment.app.Fragment.hideKeyboard() {
        AppLogger.d("UIHelper", "Fragment.hideKeyboard: Fragments are not used on desktop; physical keyboard in use")
    }

    @PlatformQuarantine(
        reason = "Virtual keyboard management is not applicable to Linux desktop physical keyboards",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:236",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.hideKeyboard() {
        AppLogger.d("UIHelper", "hideKeyboard: physical keyboard in use on desktop")
    }

    private val _navigationRequests = MutableSharedFlow<NavigationRequest>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigationRequests: SharedFlow<NavigationRequest> = _navigationRequests.asSharedFlow()

    val navigateEvent = Event<NavigationRequest>()

    fun navigate(
        navigationId: Int,
        args: Any? = null,
        navOptions: Any? = null
    ) {
        AppLogger.i("UIHelper", "navigate: navigationId=$navigationId, args=$args")
        val request = NavigationRequest(navigationId, args, navOptions)
        _navigationRequests.tryEmit(request)
        try {
            navigateEvent(request)
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Activity?.navigate(
        navigationId: Int,
        args: Any? = null,
        navOptions: Any? = null
    ) {
        UIHelper.navigate(navigationId, args, navOptions)
    }

    fun Context?.navigate(
        navigationId: Int,
        args: Any? = null,
        navOptions: Any? = null
    ) {
        UIHelper.navigate(navigationId, args, navOptions)
    }

    @PlatformQuarantine(
        reason = "Android Activity intent navigation not applicable to single-window Compose Desktop application",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:265",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Context.openActivity(activity: Class<*>, args: Bundle? = null, baseIntent: Any? = null) {
        AppLogger.d("UIHelper", "openActivity: Android Activity navigation not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "Android FragmentActivity back stack pop replaced by NavController.goBack() in desktop-app",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:282",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.popCurrentPage(fromBackPressedCallback: String? = null) {
        AppLogger.d("UIHelper", "popCurrentPage: Fragment back stack is replaced by NavController on desktop")
    }

    @ColorInt
    fun Context.getResourceColor(@AttrRes resource: Int, alphaFactor: Float = 1f): Int {
        val color = colorFromAttribute(resource)
        return if (alphaFactor < 1f) adjustAlpha(color, alphaFactor) else color
    }

    @ColorInt
    fun Context?.colorFromAttribute(@AttrRes attribute: Int): Int {
        return ThemeColorProvider.getColor(this, attribute)
    }

    @ColorInt
    fun colorFromAttribute(@AttrRes attribute: Int): Int {
        return ThemeColorProvider.getColor(null, attribute)
    }

    @ColorInt
    fun adjustAlpha(@ColorInt color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).roundToInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    var createPaletteAsyncCache: HashMap<String, Any> = hashMapOf()

    @PlatformQuarantine(
        reason = "AndroidX Palette color extraction not applicable on desktop (handled by Compose or Coil)",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:333",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun createPaletteAsync(url: String, bitmap: Any, callback: (Any) -> Unit) {
        AppLogger.d("UIHelper", "createPaletteAsync: AndroidX Palette not used on desktop")
    }

    @PlatformQuarantine(
        reason = "Android system UI (status/navigation bars) fullscreen control not applicable to desktop windowing",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:346",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.hideSystemUI() {
        AppLogger.d("UIHelper", "hideSystemUI: Window fullscreen is handled by desktop window manager")
    }

    @PlatformQuarantine(
        reason = "Android edge-to-edge window insets not applicable to desktop windows",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:376",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.enableEdgeToEdgeCompat() {
        AppLogger.d("UIHelper", "enableEdgeToEdgeCompat: Edge-to-edge not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "Android navigation bar coloring not applicable to desktop windows",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:382",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.setNavigationBarColorCompat(@AttrRes resourceId: Int) {
        AppLogger.d("UIHelper", "setNavigationBarColorCompat: Navigation bar color not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "Android system status bar height not applicable on desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:390",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Context.getStatusBarHeight(): Int {
        AppLogger.d("UIHelper", "getStatusBarHeight: 0 on desktop")
        return 0
    }

    @PlatformQuarantine(
        reason = "Android status bar margin padding not applicable on desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:403",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun fixPaddingStatusbarMargin(v: View?) {
        AppLogger.d("UIHelper", "fixPaddingStatusbarMargin: Android status bar padding not used on desktop")
    }

    @PlatformQuarantine(
        reason = "Android status bar view padding not applicable on desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:419",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun fixPaddingStatusbarView(v: View?) {
        AppLogger.d("UIHelper", "fixPaddingStatusbarView: Android status bar view not used on desktop")
    }

    @PlatformQuarantine(
        reason = "Android WindowInsetsCompat system bars padding not applicable on desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:426",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun fixSystemBarsPadding(
        v: View,
        @DimenRes heightResId: Int? = null,
        @DimenRes widthResId: Int? = null,
        padTop: Boolean = true,
        padBottom: Boolean = true,
        padLeft: Boolean = true,
        padRight: Boolean = true,
        overlayCutout: Boolean = true,
        fixIme: Boolean = false
    ) {
        AppLogger.d("UIHelper", "fixSystemBarsPadding: System bars padding not used on desktop")
    }

    @PlatformQuarantine(
        reason = "Android system navigation bar height not applicable on desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:497",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Context.getNavigationBarHeight(): Int {
        AppLogger.d("UIHelper", "getNavigationBarHeight: 0 on desktop")
        return 0
    }

    fun Context?.isBottomLayout(): Boolean {
        if (this == null) return false
        val settingsManager = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val key = try { getString(R.string.bottom_title_key) } catch (_: Throwable) { "bottom_title_key" }
        return settingsManager.getBoolean(
            key,
            com.lagradost.common.storage.DesktopDataStore.getKey<Boolean>("bottom_title_key") ?: false
        )
    }

    @PlatformQuarantine(
        reason = "Android status bar state not applicable on desktop window managers",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:513",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.changeStatusBarState(hide: Boolean) {
        AppLogger.d("UIHelper", "changeStatusBarState: status bar not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "Android system UI visibility not applicable on desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:544",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.showSystemUI() {
        AppLogger.d("UIHelper", "showSystemUI: Android system UI not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "Virtual keyboard management is not applicable to Linux desktop physical keyboards",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:573",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun showInputMethod(view: View?) {
        AppLogger.d("UIHelper", "showInputMethod: physical keyboard in use on desktop")
    }

    fun Dialog?.dismissSafe(activity: Activity?) {
        if (this?.isShowing() == true && activity?.isFinishing() == false) {
            this.dismiss()
        }
    }

    fun Dialog?.dismissSafe() {
        if (this?.isShowing() == true && CommonActivity.activity?.isFinishing() != true) {
            this.dismiss()
        }
    }

    @PlatformQuarantine(
        reason = "MaterialButton progress indicator not applicable to Compose Desktop buttons",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:600",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.showProgress(@ColorInt tintColor: Int? = null) {
        AppLogger.d("UIHelper", "showProgress: MaterialButton progress not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "MaterialButton progress indicator not applicable to Compose Desktop buttons",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:624",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.hideProgress() {
        AppLogger.d("UIHelper", "hideProgress: MaterialButton progress not applicable on desktop")
    }

    @PlatformQuarantine(
        reason = "Android PopupMenu replaced by Compose Desktop context menus and dropdowns",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:632",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun View.popupMenuNoIcons(
        items: List<Pair<Int, Int>>,
        onMenuItemClick: (Int) -> Unit = {}
    ): Any? {
        AppLogger.d("UIHelper", "popupMenuNoIcons: Android PopupMenu not used on desktop")
        return null
    }

    @PlatformQuarantine(
        reason = "Android PopupMenu replaced by Compose Desktop context menus and dropdowns",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:662",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun View.popupMenuNoIconsAndNoStringRes(
        items: List<Pair<Int, String>>,
        onMenuItemClick: (Int) -> Unit = {}
    ): Any? {
        AppLogger.d("UIHelper", "popupMenuNoIconsAndNoStringRes: Android PopupMenu not used on desktop")
        return null
    }
}

@PlatformQuarantine(
    reason = "Android display cutout overlay drawable not applicable to desktop monitors",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt:692",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
private class CutoutOverlayDrawable(
    private val view: View,
    private val leftCutout: Int,
    private val rightCutout: Int,
) {
    fun draw(canvas: Any?) {
        AppLogger.d("CutoutOverlayDrawable", "draw: display cutout overlay not used on desktop")
    }

    fun setAlpha(alpha: Int) {
        AppLogger.d("CutoutOverlayDrawable", "setAlpha: alpha=$alpha")
    }

    fun setColorFilter(colorFilter: Any?) {
        AppLogger.d("CutoutOverlayDrawable", "setColorFilter")
    }

    fun getOpacity(): Int {
        return 0
    }
}
