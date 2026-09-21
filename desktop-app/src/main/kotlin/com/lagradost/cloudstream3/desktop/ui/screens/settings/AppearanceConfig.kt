package com.lagradost.cloudstream3.desktop.ui.screens.settings

import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.flow.MutableStateFlow

object AppearanceConfig {
    private const val PREF_THEME_ACCENT = "pref_theme_accent"
    private const val PREF_AMOLED_MODE = "pref_amoled_mode"
    private const val PREF_LIGHT_MODE = "pref_light_mode"
    private const val PREF_GRID_SCALE = "pref_grid_scale"

    val themeAccent = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_THEME_ACCENT) ?: "Blue")
    val amoledMode = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_AMOLED_MODE) ?: true)
    val isLightMode = MutableStateFlow(DesktopDataStore.getKey<Boolean>(PREF_LIGHT_MODE) ?: false)
    val gridScale = MutableStateFlow(DesktopDataStore.getKey<String>(PREF_GRID_SCALE) ?: "Normal")

    fun setThemeAccent(colorName: String) {
        themeAccent.value = colorName
        DesktopDataStore.setKey(PREF_THEME_ACCENT, colorName)
    }

    fun setAmoledMode(enabled: Boolean) {
        amoledMode.value = enabled
        DesktopDataStore.setKey(PREF_AMOLED_MODE, enabled)
    }

    fun setLightMode(enabled: Boolean) {
        isLightMode.value = enabled
        DesktopDataStore.setKey(PREF_LIGHT_MODE, enabled)
    }

    fun setGridScale(scale: String) {
        gridScale.value = scale
        DesktopDataStore.setKey(PREF_GRID_SCALE, scale)
    }
}
