// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/subtitles/SubtitlesFragment.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.subtitles

import android.content.Context
import android.graphics.Color
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SubtitlesFragment {
    companion object {
        const val SUBTITLE_KEY = "subtitle_settings"
        const val SUBTITLE_AUTO_SELECT_KEY = "subs_auto_select"
        const val SUBTITLE_DOWNLOAD_KEY = "subs_auto_download"
        const val DEF_SUBS_ELEVATION = 20

        const val autoSelectSubtitlesKey = SUBTITLE_AUTO_SELECT_KEY
        const val autoDownloadSubtitlesKey = SUBTITLE_DOWNLOAD_KEY

        val applyStyleEvent = Event<SaveCaptionStyle>()
        val captionRegex = Regex("""(-\s?|)[\[({][\S\s]*?[])}]\s*""")

        private fun getDefColor(id: Int): Int {
            return when (id) {
                0 -> Color.WHITE       // -1
                1 -> Color.BLACK       // -16777216
                2 -> Color.TRANSPARENT // 0
                3 -> Color.TRANSPARENT // 0
                else -> Color.TRANSPARENT
            }
        }

        val defaultSubtitleStyle = SaveCaptionStyle(
            foregroundColor = getDefColor(0),
            backgroundColor = getDefColor(2),
            windowColor = getDefColor(3),
            edgeType = androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            edgeColor = getDefColor(1),
            font = null,
            typefaceFilePath = null,
            elevation = DEF_SUBS_ELEVATION,
            fixedTextSize = null,
            edgeSize = null,
            removeCaptions = false,
            removeBloat = true,
            upperCase = false,
            bold = false,
            italic = false,
            backgroundRadius = null,
            alignment = null,
        )

        private val _subtitleStyleState = MutableStateFlow(
            runCatching { DataStore.getKey<SaveCaptionStyle>(SUBTITLE_KEY) }.getOrNull() ?: defaultSubtitleStyle
        )
        val subtitleStyleState: StateFlow<SaveCaptionStyle> = _subtitleStyleState.asStateFlow()

        fun getCurrentSavedStyle(): SaveCaptionStyle {
            return _subtitleStyleState.value
        }

        fun getSubtitleStyle(context: Context? = null): SaveCaptionStyle {
            val style = if (context != null) {
                with(DataStore) { context.getKey<SaveCaptionStyle>(SUBTITLE_KEY) }
            } else {
                DataStore.getKey<SaveCaptionStyle>(SUBTITLE_KEY)
            } ?: defaultSubtitleStyle
            _subtitleStyleState.value = style
            return style
        }

        fun setSubtitleStyle(context: Context? = null, style: SaveCaptionStyle) {
            _subtitleStyleState.value = style
            if (context != null) {
                with(DataStore) { context.setKey(SUBTITLE_KEY, style) }
            } else {
                DataStore.setKey(SUBTITLE_KEY, style)
            }
            applyStyleEvent.invoke(style)
        }

        fun Context.saveStyle(style: SaveCaptionStyle) {
            setSubtitleStyle(this, style)
        }

        @JvmName("getSubtitleStyleFromContext")
        fun Context.getSubtitleStyle(): SaveCaptionStyle {
            return SubtitlesFragment.getSubtitleStyle(this)
        }

        fun getDownloadSubsLanguageTagIETF(): List<String> {
            return DataStore.getKey<List<String>>(SUBTITLE_DOWNLOAD_KEY) ?: listOf("en")
        }

        fun getAutoSelectLanguageTagIETF(): String {
            return DataStore.getKey<String>(SUBTITLE_AUTO_SELECT_KEY) ?: "en"
        }

        fun setDownloadSubsLanguageTagIETF(tags: List<String>) {
            DataStore.setKey(SUBTITLE_DOWNLOAD_KEY, tags)
        }

        fun setAutoDownloadSubtitlesIETF(tags: List<String>) {
            setDownloadSubsLanguageTagIETF(tags)
        }

        fun setAutoSelectLanguageTagIETF(tag: String) {
            DataStore.setKey(SUBTITLE_AUTO_SELECT_KEY, tag)
        }

        var autoSelectSubtitles: String
            get() = getAutoSelectLanguageTagIETF()
            set(value) = setAutoSelectLanguageTagIETF(value)

        var autoDownloadSubtitles: List<String>
            get() = getDownloadSubsLanguageTagIETF()
            set(value) = setDownloadSubsLanguageTagIETF(value)

        fun getSavedFonts(context: Context? = null): List<File> {
            val baseDir = try {
                context?.filesDir ?: PlatformPaths.dataDir.toFile()
            } catch (_: Exception) {
                PlatformPaths.dataDir.toFile()
            }
            val fontDir = File(baseDir, "Fonts").also { it.mkdirs() }
            return fontDir.listFiles()?.filter { file ->
                file.isFile && (file.extension.equals("ttf", ignoreCase = true) ||
                        file.extension.equals("otf", ignoreCase = true) ||
                        file.extension.equals("woff", ignoreCase = true) ||
                        file.extension.equals("woff2", ignoreCase = true))
            } ?: emptyList()
        }
    }
}
