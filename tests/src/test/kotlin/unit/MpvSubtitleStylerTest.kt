package unit

import androidx.media3.ui.CaptionStyleCompat
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitleFont
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import com.lagradost.player.subtitles.MpvSubtitleStyler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MpvSubtitleStylerTest {

    @Test
    fun `test colorToMpvHex conversion`() {
        assertEquals("#FFFFFFFF", MpvSubtitleStyler.colorToMpvHex(-1))
        assertEquals("#FF000000", MpvSubtitleStyler.colorToMpvHex(-16777216))
        assertEquals("#00000000", MpvSubtitleStyler.colorToMpvHex(0))
        assertEquals("#80FF0000", MpvSubtitleStyler.colorToMpvHex(0x80FF0000.toInt()))
        assertEquals("#FFFF0000", MpvSubtitleStyler.colorToMpvHex(0xFFFF0000.toInt()))
        assertEquals("#FF00FF00", MpvSubtitleStyler.colorToMpvHex(0xFF00FF00.toInt()))
        assertEquals("#FF0000FF", MpvSubtitleStyler.colorToMpvHex(0xFF0000FF.toInt()))
    }

    @Test
    fun `test resolveFont with various style inputs`() {
        // 1. Default style
        val defaultStyle = SubtitlesFragment.defaultSubtitleStyle
        assertEquals("sans-serif", MpvSubtitleStyler.resolveFont(defaultStyle))

        // 2. SubtitleFont enum
        val trebuchetStyle = defaultStyle.copy(font = SubtitleFont.Trebuchet)
        assertEquals(SubtitleFont.Trebuchet.desktopFontFamily, MpvSubtitleStyler.resolveFont(trebuchetStyle))

        // 3. Custom typeface takes precedence over enum font
        val customStyle = defaultStyle.copy(
            font = SubtitleFont.Open,
            typefaceFilePath = "/home/user/.fonts/myfont.ttf"
        )
        assertEquals("/home/user/.fonts/myfont.ttf", MpvSubtitleStyler.resolveFont(customStyle))
    }

    @Test
    fun `test calculateFontSize scaling`() {
        assertEquals(38.0, MpvSubtitleStyler.calculateFontSize(null), 0.001)
        assertEquals(38.0, MpvSubtitleStyler.calculateFontSize(25.0f), 0.001)
        assertEquals(45.6, MpvSubtitleStyler.calculateFontSize(30.0f), 0.001)
        assertEquals(18.24, MpvSubtitleStyler.calculateFontSize(12.0f), 0.001)
    }

    @Test
    fun `test calculateBorderSize and calculateShadowOffset across edge types`() {
        // EDGE_TYPE_NONE
        assertEquals(0.0, MpvSubtitleStyler.calculateBorderSize(CaptionStyleCompat.EDGE_TYPE_NONE, 5f))
        assertEquals(0.0, MpvSubtitleStyler.calculateShadowOffset(CaptionStyleCompat.EDGE_TYPE_NONE, 5f))

        // EDGE_TYPE_OUTLINE
        assertEquals(3.0, MpvSubtitleStyler.calculateBorderSize(CaptionStyleCompat.EDGE_TYPE_OUTLINE, null))
        assertEquals(4.5, MpvSubtitleStyler.calculateBorderSize(CaptionStyleCompat.EDGE_TYPE_OUTLINE, 4.5f))
        assertEquals(0.0, MpvSubtitleStyler.calculateShadowOffset(CaptionStyleCompat.EDGE_TYPE_OUTLINE, null))

        // EDGE_TYPE_DROP_SHADOW
        assertEquals(0.0, MpvSubtitleStyler.calculateBorderSize(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, null))
        assertEquals(3.0, MpvSubtitleStyler.calculateShadowOffset(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, null))
        assertEquals(6.0, MpvSubtitleStyler.calculateShadowOffset(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 6f))

        // EDGE_TYPE_RAISED
        assertEquals(2.0, MpvSubtitleStyler.calculateBorderSize(CaptionStyleCompat.EDGE_TYPE_RAISED, null))
        assertEquals(2.0, MpvSubtitleStyler.calculateShadowOffset(CaptionStyleCompat.EDGE_TYPE_RAISED, null))

        // EDGE_TYPE_DEPRESSED
        assertEquals(2.0, MpvSubtitleStyler.calculateBorderSize(CaptionStyleCompat.EDGE_TYPE_DEPRESSED, null))
        assertEquals(-2.0, MpvSubtitleStyler.calculateShadowOffset(CaptionStyleCompat.EDGE_TYPE_DEPRESSED, null))
    }

    @Test
    fun `test calculateSubPos with elevation and alignment`() {
        // Bottom alignment (default)
        assertEquals(100.0, MpvSubtitleStyler.calculateSubPos(elevation = 0, alignment = null), 0.001)
        assertEquals(98.0, MpvSubtitleStyler.calculateSubPos(elevation = 20, alignment = null), 0.001)
        assertEquals(90.0, MpvSubtitleStyler.calculateSubPos(elevation = 100, alignment = 2), 0.001)

        // Middle alignment (4, 5, 6)
        assertEquals(50.0, MpvSubtitleStyler.calculateSubPos(elevation = 50, alignment = 5), 0.001)

        // Top alignment (7, 8, 9)
        assertEquals(11.0, MpvSubtitleStyler.calculateSubPos(elevation = 20, alignment = 8), 0.001)
    }

    @Test
    fun `test translateToProperties guarantees all 9 core MPV properties`() {
        val style = SaveCaptionStyle(
            foregroundColor = 0xFFFFFF00.toInt(),
            backgroundColor = 0x80000000.toInt(),
            windowColor = 0,
            edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            edgeColor = 0xFF111111.toInt(),
            font = SubtitleFont.Netflix,
            elevation = 30,
            fixedTextSize = 25.0f,
            edgeSize = 3.5f,
            bold = true,
            italic = false,
        )

        val props = MpvSubtitleStyler.translateToProperties(style, delaySec = 1.25)

        // 1. sub-font
        assertEquals(SubtitleFont.Netflix.desktopFontFamily, props[MpvSubtitleStyler.PROP_SUB_FONT])

        // 2. sub-font-size
        assertEquals(38.0, (props[MpvSubtitleStyler.PROP_SUB_FONT_SIZE] as Number).toDouble(), 0.001)

        // 3. sub-color
        assertEquals("#FFFFFF00", props[MpvSubtitleStyler.PROP_SUB_COLOR])

        // 4. sub-back-color
        assertEquals("#80000000", props[MpvSubtitleStyler.PROP_SUB_BACK_COLOR])

        // 5. sub-border-color
        assertEquals("#FF111111", props[MpvSubtitleStyler.PROP_SUB_BORDER_COLOR])

        // 6. sub-border-size
        assertEquals(3.5, (props[MpvSubtitleStyler.PROP_SUB_BORDER_SIZE] as Number).toDouble(), 0.001)

        // 7. sub-shadow-offset
        assertEquals(0.0, (props[MpvSubtitleStyler.PROP_SUB_SHADOW_OFFSET] as Number).toDouble(), 0.001)

        // 8. sub-pos
        assertEquals(97.0, (props[MpvSubtitleStyler.PROP_SUB_POS] as Number).toDouble(), 0.001)

        // 9. sub-delay
        assertEquals(1.25, (props[MpvSubtitleStyler.PROP_SUB_DELAY] as Number).toDouble(), 0.001)

        // Flags
        assertEquals(true, props[MpvSubtitleStyler.PROP_SUB_BOLD])
        assertEquals(false, props[MpvSubtitleStyler.PROP_SUB_ITALIC])
    }

    @Test
    fun `test buildSetPropertyCommands and JSON-RPC formatting`() {
        val style = SubtitlesFragment.defaultSubtitleStyle
        val commands = MpvSubtitleStyler.buildSetPropertyCommands(style, delaySec = 0.5)

        assertTrue(commands.isNotEmpty())
        for (cmd in commands) {
            assertEquals("set_property", cmd[0])
            assertTrue(cmd.size == 3)
        }

        val jsonLines = MpvSubtitleStyler.buildJsonRpcCommands(style, delaySec = 0.5)
        assertEquals(commands.size, jsonLines.size)

        for (json in jsonLines) {
            val event = MpvJsonRpcProtocol.mapper.readTree(json)
            assertTrue(event.has("command"))
            val cmdArray = event.get("command")
            assertEquals("set_property", cmdArray.get(0).asText())
        }
    }

    @Test
    fun `test MpvSubtitleStyler instance state and reapply`() {
        val styler = MpvSubtitleStyler(ipcClient = null)

        assertEquals(SubtitlesFragment.defaultSubtitleStyle, styler.currentStyle)

        val newStyle = SubtitlesFragment.defaultSubtitleStyle.copy(
            elevation = 60,
            fixedTextSize = 34f,
            font = SubtitleFont.Google
        )

        // applyStyle without connected client returns false but updates currentStyle
        val success = styler.applyStyle(newStyle)
        assertFalse(success)
        assertEquals(newStyle, styler.currentStyle)

        assertFalse(styler.reapplyCurrentStyle())
    }
}
