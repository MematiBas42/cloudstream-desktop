package com.lagradost.player.subtitles

import androidx.media3.ui.CaptionStyleCompat
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitleFont
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.common.logging.AppLogger
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import com.lagradost.player.tracks.MpvTrackManager
import java.util.Locale

/**
 * Live subtitle styling engine for MPV.
 * Translates [SaveCaptionStyle] into live MPV JSON-RPC commands and applies them
 * during playback over Unix domain socket IPC without reloading the active video stream.
 */
class MpvSubtitleStyler(
    private val ipcClient: MpvIpcClient? = null,
) {
    private var _currentStyle: SaveCaptionStyle = SubtitlesFragment.defaultSubtitleStyle
    val currentStyle: SaveCaptionStyle get() = _currentStyle

    /**
     * Applies [style] to the internal IPC client without video reload.
     */
    fun applyStyle(style: SaveCaptionStyle, delaySec: Double? = null): Boolean {
        _currentStyle = style
        val client = ipcClient ?: return false
        return applyStyle(client, style, delaySec)
    }

    /**
     * Re-applies the currently configured subtitle style.
     */
    fun reapplyCurrentStyle(delaySec: Double? = null): Boolean {
        val client = ipcClient ?: return false
        return applyStyle(client, _currentStyle, delaySec)
    }

    companion object {
        const val PROP_SUB_FONT = "sub-font"
        const val PROP_SUB_FONT_SIZE = "sub-font-size"
        const val PROP_SUB_COLOR = "sub-color"
        const val PROP_SUB_BACK_COLOR = "sub-back-color"
        const val PROP_SUB_BORDER_COLOR = "sub-border-color"
        const val PROP_SUB_BORDER_SIZE = "sub-border-size"
        const val PROP_SUB_SHADOW_OFFSET = "sub-shadow-offset"
        const val PROP_SUB_POS = "sub-pos"
        const val PROP_SUB_DELAY = "sub-delay"
        const val PROP_SUB_BOLD = "sub-bold"
        const val PROP_SUB_ITALIC = "sub-italic"

        /**
         * Converts a 32-bit ARGB integer into MPV hex color format (#AARRGGBB).
         * MPV accepts hex color with alpha channel.
         */
        fun colorToMpvHex(colorInt: Int): String {
            val a = (colorInt ushr 24) and 0xFF
            val r = (colorInt ushr 16) and 0xFF
            val g = (colorInt ushr 8) and 0xFF
            val b = colorInt and 0xFF
            return String.format(Locale.US, "#%02X%02X%02X%02X", a, r, g, b)
        }

        /**
         * Resolves the font family string for MPV fontconfig matching.
         */
        fun resolveFont(style: SaveCaptionStyle): String {
            val typeface = style.typefaceFilePath
            if (!typeface.isNullOrBlank()) {
                return typeface
            }
            val font = style.font
            if (font != null) {
                return font.desktopFontFamily
            }
            return "sans-serif"
        }

        /**
         * Calculates MPV font size (Double) from Android sp or defaults to 38.0.
         */
        fun calculateFontSize(fixedTextSize: Float?): Double {
            if (fixedTextSize == null) return 38.0
            return (fixedTextSize * 1.52).coerceIn(12.0, 120.0)
        }

        /**
         * Calculates outline border size in pixels based on edgeType and edgeSize.
         */
        fun calculateBorderSize(edgeType: Int, edgeSize: Float?): Double {
            return when (edgeType) {
                CaptionStyleCompat.EDGE_TYPE_NONE -> 0.0
                CaptionStyleCompat.EDGE_TYPE_OUTLINE -> edgeSize?.toDouble() ?: 3.0
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> 0.0
                CaptionStyleCompat.EDGE_TYPE_RAISED -> edgeSize?.toDouble() ?: 2.0
                CaptionStyleCompat.EDGE_TYPE_DEPRESSED -> edgeSize?.toDouble() ?: 2.0
                else -> edgeSize?.toDouble() ?: 3.0
            }
        }

        /**
         * Calculates shadow offset in pixels based on edgeType and edgeSize.
         */
        fun calculateShadowOffset(edgeType: Int, edgeSize: Float?): Double {
            return when (edgeType) {
                CaptionStyleCompat.EDGE_TYPE_NONE -> 0.0
                CaptionStyleCompat.EDGE_TYPE_OUTLINE -> 0.0
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> edgeSize?.toDouble() ?: 3.0
                CaptionStyleCompat.EDGE_TYPE_RAISED -> 2.0
                CaptionStyleCompat.EDGE_TYPE_DEPRESSED -> -2.0
                else -> 0.0
            }
        }

        /**
         * Calculates vertical subtitle position (0.0 - 150.0, default 100.0) from elevation and alignment.
         */
        fun calculateSubPos(elevation: Int, alignment: Int? = null): Double {
            return when (alignment) {
                // Top alignments (7: Top-Left, 8: Top-Center, 9: Top-Right)
                7, 8, 9 -> {
                    val offset = (elevation * 0.05).coerceIn(0.0, 30.0)
                    (10.0 + offset).coerceIn(0.0, 50.0)
                }
                // Middle alignments (4: Middle-Left, 5: Middle-Center, 6: Middle-Right)
                4, 5, 6 -> 50.0
                // Bottom alignments or null (1: Bottom-Left, 2: Bottom-Center, 3: Bottom-Right)
                else -> {
                    val offset = (elevation * 0.1).coerceIn(0.0, 40.0)
                    (100.0 - offset).coerceIn(40.0, 100.0)
                }
            }
        }

        /**
         * Translates [SaveCaptionStyle] into an ordered map of MPV property names and values.
         * Guarantees inclusion of the 9 required core properties:
         * "sub-font", "sub-font-size", "sub-color", "sub-back-color", "sub-border-color",
         * "sub-border-size", "sub-shadow-offset", "sub-pos", "sub-delay".
         */
        fun translateToProperties(
            style: SaveCaptionStyle,
            delaySec: Double? = 0.0,
        ): Map<String, Any> {
            val properties = LinkedHashMap<String, Any>()

            // 1. Font
            properties[PROP_SUB_FONT] = resolveFont(style)

            // 2. Font size
            properties[PROP_SUB_FONT_SIZE] = calculateFontSize(style.fixedTextSize)

            // 3. Subtitle text color
            properties[PROP_SUB_COLOR] = colorToMpvHex(style.foregroundColor)

            // 4. Background / window color
            val backColor = when {
                style.backgroundColor != 0 -> colorToMpvHex(style.backgroundColor)
                style.windowColor != 0 -> colorToMpvHex(style.windowColor)
                else -> "#00000000"
            }
            properties[PROP_SUB_BACK_COLOR] = backColor

            // 5. Border / outline color
            properties[PROP_SUB_BORDER_COLOR] = colorToMpvHex(style.edgeColor)

            // 6. Border size
            properties[PROP_SUB_BORDER_SIZE] = calculateBorderSize(style.edgeType, style.edgeSize)

            // 7. Shadow offset
            properties[PROP_SUB_SHADOW_OFFSET] = calculateShadowOffset(style.edgeType, style.edgeSize)

            // 8. Vertical position
            properties[PROP_SUB_POS] = calculateSubPos(style.elevation, style.alignment)

            // 9. Subtitle delay
            val delay = if (delaySec != null) {
                MpvTrackManager.clampSubtitleDelay(delaySec)
            } else {
                0.0
            }
            properties[PROP_SUB_DELAY] = delay

            // Optional styling flags
            properties[PROP_SUB_BOLD] = style.bold
            properties[PROP_SUB_ITALIC] = style.italic

            return properties
        }

        /**
         * Builds list of low-level MPV JSON-RPC command arrays: ["set_property", key, value].
         */
        fun buildSetPropertyCommands(
            style: SaveCaptionStyle,
            delaySec: Double? = 0.0,
        ): List<List<Any>> {
            val props = translateToProperties(style, delaySec)
            return props.map { (key, value) ->
                listOf("set_property", key, value)
            }
        }

        /**
         * Builds serialized JSON-RPC strings ready to write directly to MPV Unix domain socket.
         */
        fun buildJsonRpcCommands(
            style: SaveCaptionStyle,
            delaySec: Double? = 0.0,
        ): List<String> {
            val commands = buildSetPropertyCommands(style, delaySec)
            return commands.map { cmd ->
                MpvJsonRpcProtocol.formatCommand(cmd)
            }
        }

        /**
         * Applies [style] to live MPV process via [client].
         * Executes JSON-RPC set_property commands in sequence without reloading video.
         */
        fun applyStyle(
            client: MpvIpcClient,
            style: SaveCaptionStyle,
            delaySec: Double? = null,
        ): Boolean {
            val commands = buildSetPropertyCommands(style, delaySec)
            var allSucceeded = true
            for (cmd in commands) {
                val sent = client.sendCommand(cmd)
                if (!sent) {
                    allSucceeded = false
                    AppLogger.w("MpvSubtitleStyler: Failed to send command $cmd to MPV IPC")
                }
            }
            return allSucceeded
        }
    }
}
