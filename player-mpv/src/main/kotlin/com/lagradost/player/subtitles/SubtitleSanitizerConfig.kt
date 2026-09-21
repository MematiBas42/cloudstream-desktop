package com.lagradost.player.subtitles

import com.lagradost.common.storage.DesktopDataStore

/**
 * Immutable configuration options governing subtitle sanitization,
 * encoding overrides, and text transformations.
 */
data class SubtitleSanitizerConfig(
    val forcedEncoding: String? = null,
    val removeBloat: Boolean = true,
    val removeCaptions: Boolean = false,
    val upperCase: Boolean = false,
) {
    companion object {
        const val PREF_SUB_ENCODING = "player_sub_encoding"
        const val PREF_SUB_REMOVE_BLOAT = "player_sub_remove_bloat"
        const val PREF_SUB_REMOVE_CAPTIONS = "player_sub_remove_captions"
        const val PREF_SUB_UPPERCASE = "player_sub_uppercase"

        fun fromDataStore(): SubtitleSanitizerConfig {
            val enc = DesktopDataStore.getKey<String>(PREF_SUB_ENCODING)?.takeIf { it.isNotBlank() }
            val bloat = DesktopDataStore.getKey<Boolean>(PREF_SUB_REMOVE_BLOAT) ?: true
            val captions = DesktopDataStore.getKey<Boolean>(PREF_SUB_REMOVE_CAPTIONS) ?: false
            val upper = DesktopDataStore.getKey<Boolean>(PREF_SUB_UPPERCASE) ?: false
            return SubtitleSanitizerConfig(
                forcedEncoding = enc,
                removeBloat = bloat,
                removeCaptions = captions,
                upperCase = upper,
            )
        }
    }
}
