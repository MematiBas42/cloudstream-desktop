package unit

import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.subtitles.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class SubtitlePreProcessorTest {

    @BeforeEach
    fun setup() {
        DesktopDataStore.init()
    }

    @Test
    fun testTurkishWindows1254Transcoding() {
        // Turkish phrase with distinctive characters: Ş (0xDE), ğ (0xF0), ı (0xFD), İ (0xDD), etc.
        val turkishText = "Şiir, Çağrı, Işık ve Ağaç. Çıkış burada."
        val turkishCharset = Charset.forName("windows-1254")
        val turkishBytes = turkishText.toByteArray(turkishCharset)

        val detectedCharsetName = SubtitleCharsetDetector.detectCharsetName(turkishBytes)
        assertEquals("windows-1254", detectedCharsetName)

        val (decoded, resolvedCharset) = SubtitleCharsetDetector.decodeToString(turkishBytes)
        assertEquals(turkishText, decoded)
        assertEquals("windows-1254", resolvedCharset.name().lowercase())
        assertFalse(decoded.contains("�"), "Decoded Turkish text must not contain unicode replacement character")

        // Test single word "Çıkış"
        val cikisBytes = "Çıkış".toByteArray(turkishCharset)
        val (cikisDecoded, _) = SubtitleCharsetDetector.decodeToString(cikisBytes)
        assertEquals("Çıkış", cikisDecoded)
    }

    @Test
    fun testArabicWindows1256Transcoding() {
        // Spec payload for Arabic phrase encoded in Windows-1256
        val arabicBytes = byteArrayOf(
            0xED.toByte(), 0xD1.toByte(), 0xCD.toByte(), 0xC8.toByte(), 0xC7.toByte(), 0x20.toByte(),
            0xC8.toByte(), 0xDF.toByte(), 0xE3.toByte(), 0x20.toByte(), 0xDD.toByte(), 0xED.toByte(),
            0x20.toByte(), 0xC7.toByte(), 0xE1.toByte(), 0xC7.toByte(), 0xCE.toByte(), 0xCA.toByte(),
            0xC8.toByte(), 0xC7.toByte(), 0xD1.toByte()
        )

        val detectedCharsetName = SubtitleCharsetDetector.detectCharsetName(arabicBytes)
        assertEquals("windows-1256", detectedCharsetName)

        val (decoded, resolvedCharset) = SubtitleCharsetDetector.decodeToString(arabicBytes)
        assertEquals("windows-1256", resolvedCharset.name().lowercase())
        assertFalse(decoded.contains("�"), "Decoded text must not contain unicode replacement character")
        // Check Arabic characters are present in decoded text
        val hasArabic = decoded.any { it in '؀'..'ۿ' }
        assertTrue(hasArabic, "Decoded text must contain Arabic characters")
    }

    @Test
    fun testRussianWindows1251Transcoding() {
        val russianText = "Привет, мир! Как дела?"
        val russianCharset = Charset.forName("windows-1251")
        val russianBytes = russianText.toByteArray(russianCharset)

        val detectedCharsetName = SubtitleCharsetDetector.detectCharsetName(russianBytes)
        assertEquals("windows-1251", detectedCharsetName)

        val (decoded, resolvedCharset) = SubtitleCharsetDetector.decodeToString(russianBytes)
        assertEquals(russianText, decoded)
        assertEquals("windows-1251", resolvedCharset.name().lowercase())
        assertFalse(decoded.contains("�"), "Decoded text must not contain unicode replacement character")
    }

    @Test
    fun testOpenSubtitlesVipAdRemoval() {
        val dirtySrt = """
            1
            00:00:01,000 --> 00:00:04,000
            Support us and become VIP member to remove all ads from OpenSubtitles.org

            2
            00:00:05,000 --> 00:00:08,000
            Hello, Detective Miller.

            3
            00:00:09,000 --> 00:00:12,000
            Please rate this subtitle at www.opensubtitles.org Help other users to choose the best subtitles
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val cleaned = SubtitleFormatParser.processSrtOrVtt(dirtySrt, isVtt = false, config)

        assertFalse(cleaned.contains("VIP member"), "VIP advertisement must be stripped")
        assertFalse(cleaned.contains("Please rate this subtitle"), "Rating prompt must be stripped")
        assertTrue(cleaned.contains("Hello, Detective Miller."), "Dialogue line must be preserved")

        // Verify cue was re-sequenced as index 1
        val expectedCleaned = """
            1
            00:00:05,000 --> 00:00:08,000
            Hello, Detective Miller.
        """.trimIndent()

        assertEquals(expectedCleaned.trim(), cleaned.trim())
    }

    @Test
    fun testSdhAuditoryTagStrippingPreservingAn8Tags() {
        val sdhSrt = """
            1
            00:01:10,000 --> 00:01:14,000
            {\an8}- [DOOR OPENS]
            {\an8}- Who is there?

            2
            00:01:15,000 --> 00:01:18,000
            (SUSPENSEFUL MUSIC PLAYING)

            3
            00:01:20,000 --> 00:01:23,000
            It's me. (whispering) Don't shoot!
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = true)
        val cleaned = SubtitleFormatParser.processSrtOrVtt(sdhSrt, isVtt = false, config)

        // Positioning tag {\an8} must be preserved
        assertTrue(cleaned.contains("{\\an8}"), "Alignment tag {\\an8} must be preserved")
        // Auditory cues must be stripped
        assertFalse(cleaned.contains("DOOR OPENS"), "[DOOR OPENS] must be stripped")
        assertFalse(cleaned.contains("SUSPENSEFUL MUSIC PLAYING"), "Soundtrack cues must be stripped")
        assertFalse(cleaned.contains("whispering"), "(whispering) must be stripped")

        val expected = """
            1
            00:01:10,000 --> 00:01:14,000
            {\an8}- Who is there?

            2
            00:01:20,000 --> 00:01:23,000
            It's me. Don't shoot!
        """.trimIndent()

        assertEquals(expected.trim(), cleaned.trim())
    }

    @Test
    fun testAtomicCacheWriting() {
        val testSrtContent = """
            1
            00:00:01,000 --> 00:00:04,000
            Support us and become VIP member to remove all ads from OpenSubtitles.org

            2
            00:00:05,000 --> 00:00:08,000
            [THUNDER RUMBLES]
            {\an8}It is raining heavily.
        """.trimIndent()

        val tempSource = File.createTempFile("test_sub_", ".srt")
        try {
            tempSource.writeText(testSrtContent, StandardCharsets.UTF_8)

            val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = true)
            val cachedPath = runBlocking {
                SubtitlePreProcessor.processSingle(tempSource.absolutePath, config)
            }

            assertNotNull(cachedPath)
            assertTrue(cachedPath.startsWith("/tmp/cloudstream/subtitles/"), "Cache path must be in /tmp/cloudstream/subtitles/")

            val cachedFile = File(cachedPath)
            assertTrue(cachedFile.exists(), "Cached file must exist on disk")
            assertTrue(cachedFile.length() > 0, "Cached file must not be empty")
            assertTrue(cachedFile.name.matches(Regex("""sub_[0-9a-f]{64}\.srt""")), "Filename must contain SHA-256 hash")

            val fileContent = cachedFile.readText(StandardCharsets.UTF_8)
            assertFalse(fileContent.contains("VIP member"))
            assertFalse(fileContent.contains("THUNDER RUMBLES"))
            assertTrue(fileContent.contains("{\\an8}It is raining heavily."))
            assertTrue(fileContent.startsWith("1\n00:00:05,000 --> 00:00:08,000"))
        } finally {
            tempSource.delete()
        }
    }

    @Test
    fun testTextNormalizationAndBomStripping() {
        // String containing UTF-8 BOM, zero-width space, and non-breaking space
        val raw = "﻿​Hello World Subtitle​"
        val normalized = SubtitleSanitizer.normalizeWhitespaceAndInvisibleChars(raw)
        assertEquals("Hello World Subtitle", normalized.trim())
    }

    @Test
    fun testForcedEncodingOverride() {
        val turkishText = "Şiir, Çağrı, Işık ve Ağaç"
        val bytes = turkishText.toByteArray(Charset.forName("windows-1254"))

        val (decoded, charset) = SubtitleCharsetDetector.decodeToString(bytes, forcedEncoding = "windows-1254")
        assertEquals(turkishText, decoded)
        assertEquals("windows-1254", charset.name().lowercase())
    }

    @Test
    fun testAssDialogueSanitization() {
        val assContent = """
            [Script Info]
            Title: Sample ASS
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Support us and become VIP member to remove all ads from OpenSubtitles.org
            Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,{\an8}[DOOR OPENS]
            Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,{\an8}Who is there?
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = true)
        val cleaned = SubtitleFormatParser.processAss(assContent, config)

        assertTrue(cleaned.contains("[Script Info]"), "Header must be preserved")
        assertTrue(cleaned.contains("[V4+ Styles]"), "Styles must be preserved")
        assertFalse(cleaned.contains("VIP member"), "Bloat dialogue must be removed")
        assertFalse(cleaned.contains("DOOR OPENS"), "Auditory caption line must be removed")
        assertTrue(cleaned.contains("Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,{\\an8}Who is there?"))
    }

    @Test
    fun testSubtitleSanitizerConfigFromDataStore() {
        DesktopDataStore.setKey(SubtitleSanitizerConfig.PREF_SUB_ENCODING, "windows-1254")
        DesktopDataStore.setKey(SubtitleSanitizerConfig.PREF_SUB_REMOVE_BLOAT, false)
        DesktopDataStore.setKey(SubtitleSanitizerConfig.PREF_SUB_REMOVE_CAPTIONS, true)
        DesktopDataStore.setKey(SubtitleSanitizerConfig.PREF_SUB_UPPERCASE, true)

        val config = SubtitleSanitizerConfig.fromDataStore()
        assertEquals("windows-1254", config.forcedEncoding)
        assertFalse(config.removeBloat)
        assertTrue(config.removeCaptions)
        assertTrue(config.upperCase)

        // Clean up
        DesktopDataStore.removeKey(SubtitleSanitizerConfig.PREF_SUB_ENCODING)
        DesktopDataStore.removeKey(SubtitleSanitizerConfig.PREF_SUB_REMOVE_BLOAT)
        DesktopDataStore.removeKey(SubtitleSanitizerConfig.PREF_SUB_REMOVE_CAPTIONS)
        DesktopDataStore.removeKey(SubtitleSanitizerConfig.PREF_SUB_UPPERCASE)
    }
}
