package android.provider

import android.content.ContentResolver

object Settings {
    object System {
        const val SCREEN_BRIGHTNESS = "screen_brightness"
        const val SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"
        const val SCREEN_BRIGHTNESS_MODE_MANUAL = 0
        const val SCREEN_BRIGHTNESS_MODE_AUTOMATIC = 1

        private val values = mutableMapOf<String, Int>()

        fun getInt(resolver: ContentResolver?, name: String): Int {
            return values[name] ?: 128
        }

        fun putInt(resolver: ContentResolver?, name: String, value: Int): Boolean {
            values[name] = value
            return true
        }
    }
}
