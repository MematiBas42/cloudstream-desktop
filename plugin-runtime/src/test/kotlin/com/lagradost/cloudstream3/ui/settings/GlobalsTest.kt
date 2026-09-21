package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GlobalsTest {

    private lateinit var context: Context

    @BeforeEach
    fun setup() {
        context = CloudStreamApp.context ?: android.content.DesktopContextProvider.context
        Globals.windowDimensionProvider = null
        // Clear layout setting
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("app_layout_key").apply()
        with(Globals) {
            context.updateTv()
        }
    }

    @AfterEach
    fun tearDown() {
        Globals.windowDimensionProvider = null
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("app_layout_key").apply()
        with(Globals) {
            context.updateTv()
        }
    }

    @Test
    fun `test default constants and beneneCount`() {
        assertEquals(0b00001, Globals.PHONE)
        assertEquals(0b00010, Globals.TV)
        assertEquals(0b00100, Globals.EMULATOR)

        Globals.beneneCount = 5
        assertEquals(5, Globals.beneneCount)
        Globals.beneneCount = 0
    }

    @Test
    fun `test isLandscape dynamic window responsive query`() {
        // Mock vertical tiled window (e.g. Hyprland/Sway 50% vertical split)
        Globals.windowDimensionProvider = { Pair(600, 1080) }
        assertFalse(
            Globals.isLandscape(),
            "Vertical tiled window (width < height) must report portrait (isLandscape = false)"
        )

        // Mock horizontal/landscape window
        Globals.windowDimensionProvider = { Pair(1280, 720) }
        assertTrue(
            Globals.isLandscape(),
            "Landscape window (width > height) must report landscape (isLandscape = true)"
        )

        // Mock square window
        Globals.windowDimensionProvider = { Pair(1000, 1000) }
        assertFalse(
            Globals.isLandscape(),
            "Square window (width == height) must not be landscape"
        )
    }

    @Test
    fun `test isLayout flags and updateTv with preferences`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // 1. Phone layout (0)
        prefs.edit().putInt("app_layout_key", 0).apply()
        with(Globals) {
            context.updateTv()
        }
        assertTrue(Globals.isLayout(Globals.PHONE))
        assertFalse(Globals.isLayout(Globals.TV))
        assertFalse(Globals.isLayout(Globals.EMULATOR))

        // 2. TV layout (1)
        prefs.edit().putInt("app_layout_key", 1).apply()
        with(Globals) {
            context.updateTv()
        }
        assertFalse(Globals.isLayout(Globals.PHONE))
        assertTrue(Globals.isLayout(Globals.TV))
        assertFalse(Globals.isLayout(Globals.EMULATOR))
        assertTrue(Globals.isLayout(Globals.TV or Globals.EMULATOR))

        // TV layout forces isLandscape to true even in portrait window dimensions
        Globals.windowDimensionProvider = { Pair(500, 1000) }
        assertTrue(
            Globals.isLandscape(),
            "TV layout must always report isLandscape = true even if window dimensions are portrait"
        )

        // 3. Emulator layout (2)
        prefs.edit().putInt("app_layout_key", 2).apply()
        with(Globals) {
            context.updateTv()
        }
        assertFalse(Globals.isLayout(Globals.PHONE))
        assertFalse(Globals.isLayout(Globals.TV))
        assertTrue(Globals.isLayout(Globals.EMULATOR))
        assertTrue(Globals.isLayout(Globals.TV or Globals.EMULATOR))

        // Emulator layout also forces isLandscape = true
        Globals.windowDimensionProvider = { Pair(500, 1000) }
        assertTrue(
            Globals.isLandscape(),
            "Emulator layout must always report isLandscape = true"
        )
    }

    @Test
    fun `test isAutoTv on desktop returns false`() {
        assertFalse(Globals.isAutoTv(context))
    }

    @Test
    fun `test default layout on desktop without preference`() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("app_layout_key").apply()
        with(Globals) {
            context.updateTv()
        }
        // Default on desktop is PHONE (desktop/laptop mode, not TV)
        assertTrue(Globals.isLayout(Globals.PHONE))
        assertFalse(Globals.isLayout(Globals.TV))
    }
}
