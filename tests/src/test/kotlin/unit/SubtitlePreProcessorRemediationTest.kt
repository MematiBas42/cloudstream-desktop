package unit

import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.subtitles.SubtitleFormat
import com.lagradost.player.subtitles.SubtitleFormatParser
import com.lagradost.player.subtitles.SubtitlePreProcessor
import com.lagradost.player.subtitles.SubtitleSanitizer
import com.lagradost.player.subtitles.SubtitleSanitizerConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Industrial-grade JUnit 5 test suite verifying 1:1 upstream architectural parity,
 * zero-stub enforcement, and robust inner text preservation for SubtitlePreProcessor
 * and SubtitleSanitizer (Cluster C22_SubtitlePreProcessor):
 *
 * Specific defect addressed:
 * - L85: Preserve inner text when sanitizing malformed HTML font tags in subtitles
 *   rather than stripping whole elements.
 */
class SubtitlePreProcessorRemediationTest {

    @BeforeEach
    fun setup() {
        DesktopDataStore.init()
    }

    // =========================================================================
    // 1. Well-Formed HTML Font Tag Inner Text Preservation
    // =========================================================================

    @Test
    @DisplayName("Preserve inner text when sanitizing standard HTML font color tags")
    fun testPreserveInnerTextOnStandardFontTags() {
        val input = """<font color="#ffff00">Hello Detective Miller.</font>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Hello Detective Miller.", sanitized, "Inner text must be preserved after stripping font tags")

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Hello Detective Miller.", lineSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when font tag has multiple attributes (face, size, color)")
    fun testPreserveInnerTextOnMultiAttributeFontTags() {
        val input = """<font face="Arial" size="14" color="#00ff00">Styled dialogue line.</font>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Styled dialogue line.", sanitized)

        val config = SubtitleSanitizerConfig(removeBloat = false, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Styled dialogue line.", lineSanitized)
    }

    // =========================================================================
    // 2. Malformed HTML Font Tag Scenarios (L85 Defect Verification)
    // =========================================================================

    @Test
    @DisplayName("Preserve inner text when font tag is malformed with unclosed opening tag")
    fun testPreserveInnerTextOnMalformedUnclosedFontTag() {
        // Many web subtitles omit </font> at the end of the line
        val input = """<font color="#ff0000">Unclosed font tag with urgent message"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Unclosed font tag with urgent message", sanitized)

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Unclosed font tag with urgent message", lineSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when font tag has stray or lone closing tag")
    fun testPreserveInnerTextOnMalformedLoneClosingTag() {
        val input = """Dialogue line ending with stray closing tag</font>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Dialogue line ending with stray closing tag", sanitized)

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Dialogue line ending with stray closing tag", lineSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when malformed font tag lacks closing '>' before dialogue")
    fun testPreserveInnerTextOnMalformedMissingBracketFontTag() {
        val input = """<font color="#ff0000" Missing bracket dialogue text"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Missing bracket dialogue text", sanitized.trim())

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Missing bracket dialogue text", lineSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when malformed font tag has extra '>' bracket")
    fun testPreserveInnerTextOnMalformedExtraBracketFontTag() {
        val input = """<font color="#ffffff">>Extra bracket dialogue text</font>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Extra bracket dialogue text", sanitized)

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Extra bracket dialogue text", lineSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when font tag is self-closing (<font .../>)")
    fun testPreserveInnerTextOnSelfClosingFontTag() {
        val input = """<font color="#fff"/>Self-closing dialogue text"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Self-closing dialogue text", sanitized)

        val spacedInput = """<font color="#fff" />Spaced self-closing dialogue"""
        val spacedSanitized = SubtitleSanitizer.sanitizeHtmlFontTags(spacedInput)
        assertEquals("Spaced self-closing dialogue", spacedSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when font tag has unquoted or single-quoted attributes")
    fun testPreserveInnerTextOnUnquotedAndSingleQuotedAttributes() {
        val unquoted = """<font color=yellow size=3>Unquoted attribute text</font>"""
        assertEquals("Unquoted attribute text", SubtitleSanitizer.sanitizeHtmlFontTags(unquoted))

        val singleQuoted = """<font color='red' face='sans-serif'>Single quoted text</font>"""
        assertEquals("Single quoted text", SubtitleSanitizer.sanitizeHtmlFontTags(singleQuoted))

        val hexWithoutQuotes = """<font color=#123456>Hex color without quotes</font>"""
        assertEquals("Hex color without quotes", SubtitleSanitizer.sanitizeHtmlFontTags(hexWithoutQuotes))
    }

    @Test
    @DisplayName("Preserve inner text when font tag has no attributes (<font>...</font>)")
    fun testPreserveInnerTextOnBareFontTags() {
        val bare = """<font>Plain bare font tag</font>"""
        assertEquals("Plain bare font tag", SubtitleSanitizer.sanitizeHtmlFontTags(bare))

        val spacedClosing = """Bare font with spaced closing</ font >"""
        assertEquals("Bare font with spaced closing", SubtitleSanitizer.sanitizeHtmlFontTags(spacedClosing))

        val spacedOpening = """<font   >Whitespace in opening tag</font>"""
        assertEquals("Whitespace in opening tag", SubtitleSanitizer.sanitizeHtmlFontTags(spacedOpening))
    }

    @Test
    @DisplayName("Preserve inner text with case-insensitive font tags (<FONT>, <Font>)")
    fun testCaseInsensitiveFontTagPreservation() {
        val upper = """<FONT COLOR="#FFFF00">Uppercase FONT tag text</FONT>"""
        assertEquals("Uppercase FONT tag text", SubtitleSanitizer.sanitizeHtmlFontTags(upper))

        val mixed = """<Font Color="Cyan">Mixed case Font tag text</Font>"""
        assertEquals("Mixed case Font tag text", SubtitleSanitizer.sanitizeHtmlFontTags(mixed))

        val loneUpper = """<FONT COLOR="RED">Lone upper opening tag without closing"""
        assertEquals("Lone upper opening tag without closing", SubtitleSanitizer.sanitizeHtmlFontTags(loneUpper))
    }

    // =========================================================================
    // 3. Multi-Tag and Nested Tag Scenarios (Anti-Greedy Regex Proof)
    // =========================================================================

    @Test
    @DisplayName("Preserve all inner text when multiple font tags appear on the same line")
    fun testMultipleFontTagsOnSingleLine() {
        val input = """<font color="red">Alice:</font> Hello! <font color="blue">Bob:</font> Hi there!"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Alice: Hello! Bob: Hi there!", sanitized, "Dialogue between tags must NOT be wiped out")

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("Alice: Hello! Bob: Hi there!", lineSanitized)
    }

    @Test
    @DisplayName("Preserve inner text when font tags are nested")
    fun testNestedFontTagsPreservation() {
        val input = """<font color="red">Outer start <font color="blue">Inner nested</font> Outer end</font>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("Outer start Inner nested Outer end", sanitized)

        val tripleNested = """<font color="1"><font color="2"><font color="3">Deeply Nested Text</font></font></font>"""
        assertEquals("Deeply Nested Text", SubtitleSanitizer.sanitizeHtmlFontTags(tripleNested))
    }

    // =========================================================================
    // 4. Formatting Tag Preservation (Italics, Bold, Underline for libass)
    // =========================================================================

    @Test
    @DisplayName("Preserve SubRip formatting tags (<i>, <b>, <u>) while sanitizing font tags")
    fun testPreserveFormattingTagsWhileSanitizingFontTags() {
        val input = """<font color="#ffff00"><i>Italic dialogue</i> and <b>Bold dialogue</b></font>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlFontTags(input)
        assertEquals("<i>Italic dialogue</i> and <b>Bold dialogue</b>", sanitized)

        // With removeBloat = true, formatting tags must still be preserved for MPV
        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val lineSanitized = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("<i>Italic dialogue</i> and <b>Bold dialogue</b>", lineSanitized)
    }

    @Test
    @DisplayName("sanitizeHtmlArtifactTags strips span and div while preserving i, b, u")
    fun testSanitizeHtmlArtifactTagsPreservingFormatting() {
        val input = """<span style="color: yellow"><i>Italic dialogue</i> in span</span>"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlArtifactTags(input, preserveFormattingTags = true)
        assertEquals("<i>Italic dialogue</i> in span", sanitized)

        val fullStrip = SubtitleSanitizer.sanitizeHtmlArtifactTags(input, preserveFormattingTags = false)
        assertEquals("Italic dialogue in span", fullStrip)
    }

    @Test
    @DisplayName("Convert <br> to whitespace without merging adjacent words")
    fun testHtmlBrTagReplacement() {
        val input = """First line<br>Second line<br/>Third line"""
        val sanitized = SubtitleSanitizer.sanitizeHtmlArtifactTags(input, preserveFormattingTags = true)
        assertEquals("First line Second line Third line", sanitized.replace(Regex("""\s+"""), " ").trim())
    }

    // =========================================================================
    // 5. Interaction: Font Tags with Bloat Removal & SDH Captions
    // =========================================================================

    @Test
    @DisplayName("Promotional bloat wrapped in font tags is purged while dialogue in font tags is kept")
    fun testInteractionWithBloatRemoval() {
        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)

        val bloatWithFont = """<font color="#ffff00">Support us and become VIP member to remove all ads from OpenSubtitles.org</font>"""
        val bloatResult = SubtitleSanitizer.sanitizeLine(bloatWithFont, config)
        assertNull(bloatResult, "Bloat wrapped in font tags must be purged completely")

        val dialogueWithFont = """<font color="#ffffff">Hello, Detective Miller.</font>"""
        val dialogueResult = SubtitleSanitizer.sanitizeLine(dialogueWithFont, config)
        assertEquals("Hello, Detective Miller.", dialogueResult)
    }

    @Test
    @DisplayName("SDH captions inside font tags are stripped while keeping dialogue and ASS tags")
    fun testInteractionWithSdhCaptions() {
        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = true)

        // Pure auditory cue inside font tag
        val pureSdh = """<font color="#ffffff">(SUSPENSEFUL MUSIC PLAYING)</font>"""
        assertNull(SubtitleSanitizer.sanitizeLine(pureSdh, config))

        // Auditory cue + dialogue inside font tag
        val sdhWithDialogue = """<font color="#ffffff">{\an8}[DOOR OPENS] Who is there?</font>"""
        val result = SubtitleSanitizer.sanitizeLine(sdhWithDialogue, config)
        assertEquals("{\\an8}Who is there?", result)

        // Whispering caption stripped
        val whispering = """<font color="#ffffff">It's me. (whispering) Don't shoot!</font>"""
        val whisperResult = SubtitleSanitizer.sanitizeLine(whispering, config)
        assertEquals("It's me. Don't shoot!", whisperResult)
    }

    @Test
    @DisplayName("Uppercase configuration applies correctly to font-sanitized dialogue")
    fun testUppercaseTransformation() {
        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false, upperCase = true)
        val input = """<font color="#00ff00">whispering in the dark</font>"""
        val result = SubtitleSanitizer.sanitizeLine(input, config)
        assertEquals("WHISPERING IN THE DARK", result)
    }

    // =========================================================================
    // 6. End-to-End Subtitle Processing (SRT & ASS Documents)
    // =========================================================================

    @Test
    @DisplayName("End-to-end SRT processing sanitizes malformed font tags and preserves dialogue")
    fun testEndToEndSrtProcessingWithFontTags() {
        val dirtySrt = """
            1
            00:00:01,000 --> 00:00:04,000
            <font color="#ffff00">Support us and become VIP member to remove all ads from OpenSubtitles.org</font>

            2
            00:00:05,000 --> 00:00:08,000
            <font color="#ff0000">Unclosed font dialogue line

            3
            00:00:09,000 --> 00:00:12,000
            <font color="red">Speaker 1:</font> Hello! <font color="blue">Speaker 2:</font> Hi!

            4
            00:00:13,000 --> 00:00:16,000
            Dialogue with stray closing tag</font>
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val cleaned = SubtitlePreProcessor.processText(dirtySrt, config)

        assertFalse(cleaned.contains("VIP member"), "Bloat cue must be pruned")
        assertFalse(cleaned.contains("<font"), "All <font> tags must be removed")
        assertFalse(cleaned.contains("</font>"), "All </font> tags must be removed")

        assertTrue(cleaned.contains("Unclosed font dialogue line"), "Unclosed tag inner text must be preserved")
        assertTrue(cleaned.contains("Speaker 1: Hello! Speaker 2: Hi!"), "Multiple tags inner text must be preserved")
        assertTrue(cleaned.contains("Dialogue with stray closing tag"), "Stray closing tag inner text must be preserved")

        // Validate renumbered cues (1, 2, 3)
        assertTrue(cleaned.startsWith("1\n00:00:05,000 --> 00:00:08,000"))
        assertTrue(cleaned.contains("\n2\n00:00:09,000 --> 00:00:12,000"))
        assertTrue(cleaned.contains("\n3\n00:00:13,000 --> 00:00:16,000"))
    }

    @Test
    @DisplayName("End-to-end ASS processing sanitizes font tags in Dialogue lines while preserving headers")
    fun testEndToEndAssProcessingWithFontTags() {
        val assContent = """
            [Script Info]
            Title: Sample ASS with Font Tags
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,<font color="#ffff00">Support us and become VIP member to remove all ads from OpenSubtitles.org</font>
            Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,<font color="#ff0000">Important announcement</font>
            Dialogue: 0,0:00:09.00,0:00:12.00,Default,,0,0,0,,{\an8}<font color="cyan">Who is there?</font>
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val cleaned = SubtitlePreProcessor.processText(assContent, config)

        assertTrue(cleaned.contains("[Script Info]"), "ASS header must be preserved")
        assertTrue(cleaned.contains("[V4+ Styles]"), "ASS styles must be preserved")
        assertFalse(cleaned.contains("VIP member"), "Bloat dialogue must be removed")
        assertFalse(cleaned.contains("<font"), "Font tags must be sanitized from dialogue")
        assertFalse(cleaned.contains("</font>"), "Font closing tags must be sanitized from dialogue")
        assertTrue(cleaned.contains("Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,Important announcement"))
        assertTrue(cleaned.contains("Dialogue: 0,0:00:09.00,0:00:12.00,Default,,0,0,0,,{\\an8}Who is there?"))
    }

    // =========================================================================
    // 7. Cache Directory & Cross-Platform Robustness
    // =========================================================================

    @Test
    @DisplayName("Cache directory resolves properly and hash computation is deterministic")
    fun testCacheDirectoryAndHashComputation() {
        val cacheDir = SubtitlePreProcessor.cacheDirectory
        assertNotNull(cacheDir)
        assertTrue(cacheDir.exists() || cacheDir.mkdirs(), "Cache directory must exist or be creatable")

        val hash1 = SubtitlePreProcessor.computeHash("test_input_123")
        val hash2 = SubtitlePreProcessor.computeHash("test_input_123")
        assertEquals(hash1, hash2, "Hash must be deterministic")
        assertEquals(64, hash1.length, "SHA-256 hash must be 64 hex characters")

        // Verify cache pruning and clearing executes without error
        assertDoesNotThrow {
            SubtitlePreProcessor.pruneCache(maxAgeHours = 1)
            SubtitlePreProcessor.clearCache()
        }
    }
}
