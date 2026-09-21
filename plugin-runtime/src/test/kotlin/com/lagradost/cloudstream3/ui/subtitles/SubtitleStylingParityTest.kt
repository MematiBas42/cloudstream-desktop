package com.lagradost.cloudstream3.ui.subtitles

import android.content.Context
import androidx.media3.ui.CaptionStyleCompat
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.autoDownloadSubtitles
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.autoSelectSubtitles
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.getAutoSelectLanguageTagIETF
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.getDownloadSubsLanguageTagIETF
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.getSubtitleStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.saveStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.setAutoDownloadSubtitlesIETF
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.setAutoSelectLanguageTagIETF
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.setDownloadSubsLanguageTagIETF
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.setSubtitleStyle
import com.lagradost.cloudstream3.utils.DataStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SubtitleStylingParityTest {

    private val testContext = Context()

    @BeforeEach
    fun setup() {
        DataStore.removeKey(SubtitlesFragment.SUBTITLE_KEY)
        DataStore.removeKey(SubtitlesFragment.SUBTITLE_AUTO_SELECT_KEY)
        DataStore.removeKey(SubtitlesFragment.SUBTITLE_DOWNLOAD_KEY)
    }

    @Test
    fun `test SubtitleFont enum definitions and desktop fallbacks`() {
        val requiredFonts = listOf(
            SubtitleFont.Trebuchet to "Trebuchet MS",
            SubtitleFont.Netflix to "Netflix Sans",
            SubtitleFont.Google to "Google Sans",
            SubtitleFont.Open to "Open Sans",
            SubtitleFont.Futura to "Futura",
            SubtitleFont.Consola to "Consola",
            SubtitleFont.Gotham to "Gotham",
            SubtitleFont.Lucida to "Lucida Grande",
            SubtitleFont.STIX to "STIX General",
            SubtitleFont.TimesNewRoman to "Times New Roman",
            SubtitleFont.Verdana to "Verdana",
            SubtitleFont.Ubuntu to "Ubuntu",
            SubtitleFont.Comic to "Comic Sans",
            SubtitleFont.Poppins to "Poppins",
            SubtitleFont.Custom to "Custom",
        )

        assertEquals(15, SubtitleFont.entries.size)

        for ((font, expectedLabel) in requiredFonts) {
            assertEquals(expectedLabel, font.label)
            assertEquals(expectedLabel, font.fontName)
            assertTrue(font.desktopFontFamily.isNotBlank(), "Desktop fallback for ${font.name} should not be blank")
        }

        // Check desktop font fallbacks contains generic font family fallback
        assertTrue(SubtitleFont.Trebuchet.desktopFontFamily.contains("sans-serif"))
        assertTrue(SubtitleFont.Consola.desktopFontFamily.contains("monospace"))
        assertTrue(SubtitleFont.TimesNewRoman.desktopFontFamily.contains("serif"))
        assertTrue(SubtitleFont.STIX.desktopFontFamily.contains("serif"))

        // Check custom path override
        val customPath = "/usr/share/fonts/custom.ttf"
        assertEquals(customPath, SubtitleFont.Trebuchet.getDesktopFont(customPath))
        assertEquals(SubtitleFont.Trebuchet.desktopFontFamily, SubtitleFont.Trebuchet.getDesktopFont(null))

        // Check fromLabel resolver
        assertEquals(SubtitleFont.Trebuchet, SubtitleFont.fromLabel("Trebuchet MS"))
        assertEquals(SubtitleFont.Netflix, SubtitleFont.fromLabel("Netflix"))
        assertNull(SubtitleFont.fromLabel("NonExistentFont"))
    }

    @Test
    fun `test SaveCaptionStyle defaults and fields parity`() {
        val defaultStyle = SubtitlesFragment.defaultSubtitleStyle

        assertEquals(-1, defaultStyle.foregroundColor)
        assertEquals(0, defaultStyle.backgroundColor)
        assertEquals(0, defaultStyle.windowColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_OUTLINE, defaultStyle.edgeType)
        assertEquals(-16777216, defaultStyle.edgeColor)
        assertNull(defaultStyle.font)
        assertNull(defaultStyle.typefaceFilePath)
        assertEquals(SubtitlesFragment.DEF_SUBS_ELEVATION, defaultStyle.elevation)
        assertNull(defaultStyle.fixedTextSize)
        assertNull(defaultStyle.edgeSize)
        assertFalse(defaultStyle.removeCaptions)
        assertTrue(defaultStyle.removeBloat)
        assertFalse(defaultStyle.upperCase)
        assertFalse(defaultStyle.bold)
        assertFalse(defaultStyle.italic)
        assertNull(defaultStyle.backgroundRadius)
        assertNull(defaultStyle.alignment)
        assertNull(defaultStyle.typeface)
    }

    @Test
    fun `test SaveCaptionStyle custom construction and typeface property`() {
        val customStyle = SaveCaptionStyle(
            foregroundColor = 0xFFFFFF00.toInt(),
            backgroundColor = 0x80000000.toInt(),
            windowColor = 0x40000000,
            edgeType = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            edgeColor = 0xFF111111.toInt(),
            typeface = "Trebuchet MS",
            elevation = 35,
            fixedTextSize = 28.0f,
        )

        assertEquals("Trebuchet MS", customStyle.typeface)
        assertEquals("Trebuchet MS", customStyle.typefaceFilePath)
        assertEquals(SubtitleFont.Trebuchet, customStyle.font)
        assertEquals(0xFFFFFF00.toInt(), customStyle.foregroundColor)
        assertEquals(35, customStyle.elevation)
        assertEquals(28.0f, customStyle.fixedTextSize)
    }

    @Test
    fun `test SaveCaptionStyle Jackson and DataStore serialization parity`() {
        val originalStyle = SaveCaptionStyle(
            foregroundColor = 0xFFFF0000.toInt(),
            backgroundColor = 0x80222222.toInt(),
            windowColor = 0,
            edgeType = CaptionStyleCompat.EDGE_TYPE_RAISED,
            edgeColor = 0xFF000000.toInt(),
            font = SubtitleFont.Netflix,
            typefaceFilePath = "/fonts/netflix.ttf",
            elevation = 40,
            fixedTextSize = 32.5f,
            edgeSize = 4.0f,
            removeCaptions = true,
            removeBloat = false,
            upperCase = true,
            bold = true,
            italic = true,
            backgroundRadius = 8.0f,
            alignment = 2,
        )

        // 1. JSON String round-trip
        val json = originalStyle.toJson()
        assertTrue(json.contains("foregroundColor"))
        assertTrue(json.contains("elevation"))

        val deserialized = SaveCaptionStyle.fromJson(json)
        assertNotNull(deserialized)
        assertEquals(originalStyle, deserialized)
        assertEquals(originalStyle.typeface, deserialized?.typeface)

        // 2. DataStore persistence round-trip
        DataStore.setKey(SubtitlesFragment.SUBTITLE_KEY, originalStyle)
        val loadedFromDataStore = DataStore.getKey<SaveCaptionStyle>(SubtitlesFragment.SUBTITLE_KEY)
        assertEquals(originalStyle, loadedFromDataStore)
    }

    @Test
    fun `test SubtitlesFragment companion getter and setter functions`() {
        var observedStyle: SaveCaptionStyle? = null
        val observer: (SaveCaptionStyle) -> Unit = { observedStyle = it }
        SubtitlesFragment.applyStyleEvent += observer

        try {
            // Initial style should match default
            val initial = getSubtitleStyle()
            assertEquals(SubtitlesFragment.defaultSubtitleStyle, initial)
            assertEquals(initial, SubtitlesFragment.getCurrentSavedStyle())
            assertEquals(initial, SubtitlesFragment.subtitleStyleState.value)

            // Update style
            val newStyle = SubtitlesFragment.defaultSubtitleStyle.copy(
                elevation = 50,
                fixedTextSize = 30.0f,
                upperCase = true,
                bold = true,
            )

            setSubtitleStyle(style = newStyle)

            assertEquals(newStyle, getSubtitleStyle())
            assertEquals(newStyle, SubtitlesFragment.getCurrentSavedStyle())
            assertEquals(newStyle, SubtitlesFragment.subtitleStyleState.value)
            assertEquals(newStyle, observedStyle)

            // Context-based extension functions
            val contextStyle = newStyle.copy(elevation = 70)
            with(SubtitlesFragment) {
                testContext.saveStyle(contextStyle)
                assertEquals(contextStyle, testContext.getSubtitleStyle())
            }
        } finally {
            SubtitlesFragment.applyStyleEvent -= observer
        }
    }

    @Test
    fun `test SubtitlesFragment language preferences and keys`() {
        assertEquals("subtitle_settings", SubtitlesFragment.SUBTITLE_KEY)
        assertEquals("subs_auto_select", SubtitlesFragment.SUBTITLE_AUTO_SELECT_KEY)
        assertEquals("subs_auto_download", SubtitlesFragment.SUBTITLE_DOWNLOAD_KEY)
        assertEquals("subs_auto_select", SubtitlesFragment.autoSelectSubtitlesKey)
        assertEquals("subs_auto_download", SubtitlesFragment.autoDownloadSubtitlesKey)

        // Default language values
        assertEquals("en", getAutoSelectLanguageTagIETF())
        assertEquals(listOf("en"), getDownloadSubsLanguageTagIETF())
        assertEquals("en", autoSelectSubtitles)
        assertEquals(listOf("en"), autoDownloadSubtitles)

        // Update auto-select
        setAutoSelectLanguageTagIETF("tr")
        assertEquals("tr", getAutoSelectLanguageTagIETF())
        assertEquals("tr", autoSelectSubtitles)

        autoSelectSubtitles = "es"
        assertEquals("es", getAutoSelectLanguageTagIETF())

        // Update auto-download
        val targetLangs = listOf("tr", "en", "de")
        setDownloadSubsLanguageTagIETF(targetLangs)
        assertEquals(targetLangs, getDownloadSubsLanguageTagIETF())
        assertEquals(targetLangs, autoDownloadSubtitles)

        autoDownloadSubtitles = listOf("ja", "fr")
        assertEquals(listOf("ja", "fr"), getDownloadSubsLanguageTagIETF())
    }

    @Test
    fun `test SubtitlesFragment captionRegex behavior`() {
        val regex = SubtitlesFragment.captionRegex

        val textWithCaptions = "Hello [Applause] World (Thunder rumbling) End"
        val stripped = regex.replace(textWithCaptions, "").trim()
        assertEquals("Hello World End", stripped.replace(Regex("\\s+"), " "))

        val matches = regex.findAll(textWithCaptions).map { it.value.trim() }.toList()
        assertEquals(2, matches.size)
        assertEquals("[Applause]", matches[0])
        assertEquals("(Thunder rumbling)", matches[1])
    }

    @Test
    fun `test SubtitlesFragment getSavedFonts directory and extension filtering`() {
        val fontDir = File(testContext.filesDir, "Fonts").also { it.mkdirs() }

        val validTtf = File(fontDir, "custom_font.ttf").apply { writeText("dummy ttf") }
        val validOtf = File(fontDir, "custom_font.otf").apply { writeText("dummy otf") }
        val invalidTxt = File(fontDir, "notes.txt").apply { writeText("not a font") }

        try {
            val savedFonts = SubtitlesFragment.getSavedFonts(testContext)
            val fontNames = savedFonts.map { it.name }

            assertTrue(fontNames.contains("custom_font.ttf"))
            assertTrue(fontNames.contains("custom_font.otf"))
            assertFalse(fontNames.contains("notes.txt"))
        } finally {
            validTtf.delete()
            validOtf.delete()
            invalidTxt.delete()
        }
    }
}
