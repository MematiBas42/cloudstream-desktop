// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/Globals.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.content.DesktopContextProvider
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.common.logging.AppLogger
import java.awt.GraphicsEnvironment
import java.awt.Window

object Globals {
    private const val TAG = "Globals"
    var beneneCount = 0

    const val PHONE: Int = 0b00001
    const val TV: Int = 0b00010
    const val EMULATOR: Int = 0b00100
    private const val INVALID = -1
    private var layoutId = INVALID

    @PlatformQuarantine(
        reason = "Android TV donanım ve UI modu tespiti (UiModeManager / FireTV / Leanback). Masaüstünde masaüstü/dizüstü birincil hedeftir.",
        upstreamRef = "upstream/shared/src/androidMain/kotlin/com/lagradost/cloudstream4/compose/Layout.kt:23",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun isAutoTv(context: Context): Boolean {
        return false
    }

    private fun Context.getLayoutInt(): Int {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        return try {
            settingsManager.getInt(this.getString(R.string.app_layout_key), -1)
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to read layout preference: ${e.message}")
            -1
        }
    }

    fun Context.updateTv() {
        layoutId = when (getLayoutInt()) {
            -1 -> if (isAutoTv(this)) TV else PHONE
            0 -> PHONE
            1 -> TV
            2 -> EMULATOR
            else -> PHONE
        }
    }

    fun updateTv() {
        val ctx = CloudStreamApp.context ?: CommonActivity.activity ?: DesktopContextProvider.context
        ctx.updateTv()
    }

    /**
     * Optional provider for Compose Desktop window size binding or test harnesses.
     * When set, takes precedence over AWT window inspection and screen dimension queries.
     */
    var windowDimensionProvider: (() -> Pair<Int, Int>)? = null

    /**
     * Dynamic window/screen dimension query for desktop responsive layouts.
     * Inspects active AWT/Compose window dimensions first, then falls back to CommonActivity.
     */
    private fun getDynamicDimensions(): Pair<Int, Int> {
        windowDimensionProvider?.let { provider ->
            try {
                return provider.invoke()
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Custom windowDimensionProvider failed: ${e.message}")
            }
        }

        try {
            if (!GraphicsEnvironment.isHeadless()) {
                val window = Window.getWindows()?.firstOrNull { it.isShowing && it.width > 0 && it.height > 0 }
                if (window != null) {
                    return Pair(window.width, window.height)
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to query AWT windows for layout dimensions: ${e.message}")
        }

        val w = CommonActivity.screenWidthWithOrientation
        val h = CommonActivity.screenHeightWithOrientation
        return Pair(w, h)
    }

    /** Returns true if the current orientation is landscape. */
    fun isLandscape(): Boolean {
        if (isLayout(TV or EMULATOR)) return true
        val (width, height) = getDynamicDimensions()
        return width > height
    }

    /** Returns true if the layout is any of the flags,
     * so isLayout(TV or EMULATOR) is a valid statement for checking if the layout is in the emulator
     * or tv. Auto will become the "TV" or the "PHONE" layout.
     *
     * Valid flags are: PHONE, TV, EMULATOR
     * */
    fun isLayout(flags: Int): Boolean {
        if (layoutId == INVALID) {
            val ctx = CloudStreamApp.context ?: CommonActivity.activity ?: DesktopContextProvider.context
            ctx.updateTv()
        }
        if (layoutId == INVALID) {
            return false
        }
        return (layoutId and flags) != 0
    }
}
