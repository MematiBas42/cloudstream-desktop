package unit

import com.lagradost.player.subtitles.SubtitleFormat
import com.lagradost.player.subtitles.SubtitleFormatParser
import com.lagradost.player.subtitles.SubtitleSanitizerConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * High-fidelity unit tests verifying 1:1 upstream architectural parity, zero-stub enforcement,
 * and robust desktop adaptation for SubtitleFormatParser (Cluster C21_SubtitleFormatParser):
 *
 * 1. SRT Timing Regex Parity & 2-Digit Millisecond Support (L48):
 *    - Validates SRT_TIMING_REGEX matches standard 3-digit milliseconds (\d{3}).
 *    - Validates SRT_TIMING_REGEX matches non-standard 2-digit milliseconds (\d{2,3}) from web scrapers.
 *    - Validates dot/comma decimal separators and optional hour components.
 * 2. Millisecond Right-Padding & Normalization:
 *    - Validates rightPadMillis correctly right-pads "45" -> "450" and "4" -> "400".
 *    - Validates normalizeTimingLine outputs standardized 3-digit millisecond timecodes.
 *    - Validates WebVTT coordinate/style preservation during normalization.
 * 3. Upstream parseTimecode Mathematical Equivalence:
 *    - Validates parseTimecodeToUs and parseTimecodeToMs calculations against upstream CustomSubripParser:260-278.
 * 4. Format Sniffing & Subtitle Processing:
 *    - Tests sniffFormat with 2-digit millisecond SRT files.
 *    - Tests processSrtOrVtt normalizing 2-digit timing lines while pruning bloat and renumbering cues.
 *    - Tests processAss preserving headers and sanitizing Dialogue payloads.
 * 5. Upstream Constants & Alignment Anchor Parity:
 *    - Validates getFractionalPositionForAnchorType (0.08f, 0.5f, 0.92f) and SSA alignment tags.
 */
class SubtitleFormatParserRemediationTest {

    // =========================================================================
    // 1. SRT Timing Regex & 2-Digit Millisecond Tests (L48 Defect Verification)
    // =========================================================================

    @Test
    @DisplayName("SRT_TIMING_REGEX matches standard 3-digit millisecond timing lines")
    fun testSrtTimingRegexMatchesStandard3DigitMillis() {
        val standardSrt = "00:01:23,456 --> 00:01:25,789"
        assertTrue(
            standardSrt.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "Standard 3-digit SRT timing line must match SRT_TIMING_REGEX"
        )

        val standardVtt = "00:01:23.456 --> 00:01:25.789"
        assertTrue(
            standardVtt.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "Standard 3-digit WebVTT timing line with dot separator must match SRT_TIMING_REGEX"
        )
    }

    @Test
    @DisplayName("SRT_TIMING_REGEX matches 2-digit millisecond timing lines (L48 Fix)")
    fun testSrtTimingRegexMatches2DigitMillis() {
        val twoDigitSrt = "00:01:23,45 --> 00:01:25,67"
        assertTrue(
            twoDigitSrt.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "2-digit millisecond SRT timing line must match SRT_TIMING_REGEX after L48 fix"
        )

        val twoDigitVtt = "00:01:23.45 --> 00:01:25.67"
        assertTrue(
            twoDigitVtt.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "2-digit millisecond WebVTT timing line must match SRT_TIMING_REGEX"
        )
    }

    @Test
    @DisplayName("SRT_TIMING_REGEX matches timing lines with optional hours and whitespace")
    fun testSrtTimingRegexMatchesOptionalHoursAndWhitespace() {
        val noHours = "01:23,45 --> 01:25,67"
        assertTrue(
            noHours.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "Timing line without hours prefix must match SRT_TIMING_REGEX"
        )

        val whitespacePadded = "   00:05:10,20   -->   00:05:12,80   "
        assertTrue(
            whitespacePadded.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "Whitespace-padded timing line must match SRT_TIMING_REGEX"
        )
    }

    @Test
    @DisplayName("SRT_TIMING_REGEX matches WebVTT lines with trailing coordinates")
    fun testSrtTimingRegexWithTrailingCoordinates() {
        val vttWithCoords = "00:01:23.45 --> 00:01:25.67 line:0 position:50% align:middle"
        assertTrue(
            vttWithCoords.matches(SubtitleFormatParser.SRT_TIMING_REGEX),
            "WebVTT timing line with trailing settings must match SRT_TIMING_REGEX"
        )
    }

    @Test
    @DisplayName("SRT_TIMING_REGEX rejects non-timing lines")
    fun testSrtTimingRegexRejectsNonTimingLines() {
        assertFalse("1".matches(SubtitleFormatParser.SRT_TIMING_REGEX))
        assertFalse("Hello world subtitle dialogue".matches(SubtitleFormatParser.SRT_TIMING_REGEX))
        assertFalse("00:01:23,45 -> 00:01:25,67".matches(SubtitleFormatParser.SRT_TIMING_REGEX)) // Single arrow
        assertFalse("WEBVTT".matches(SubtitleFormatParser.SRT_TIMING_REGEX))
    }

    // =========================================================================
    // 2. Millisecond Right-Padding & Normalization Tests
    // =========================================================================

    @Test
    @DisplayName("rightPadMillis right-pads 1-digit and 2-digit milliseconds to 3 digits")
    fun testRightPadMillisDirectBehavior() {
        assertEquals("450", SubtitleFormatParser.rightPadMillis("45"), "2 digits must be right-padded with 0")
        assertEquals("400", SubtitleFormatParser.rightPadMillis("4"), "1 digit must be right-padded with 00")
        assertEquals("450", SubtitleFormatParser.rightPadMillis("450"), "3 digits must remain unchanged")
        assertEquals("050", SubtitleFormatParser.rightPadMillis("05"), "Leading zero 2 digits must become 050")
        assertEquals("000", SubtitleFormatParser.rightPadMillis("0"), "Single zero must become 000")
        assertEquals("123", SubtitleFormatParser.rightPadMillis("1234"), "More than 3 digits must truncate to 3")
        assertEquals("000", SubtitleFormatParser.rightPadMillis(null), "Null must default to 000")
        assertEquals("000", SubtitleFormatParser.rightPadMillis(""), "Empty must default to 000")
    }

    @Test
    @DisplayName("normalizeTimingLine standardizes SRT timing lines to 3-digit milliseconds")
    fun testNormalizeTimingLineSrt() {
        val input = "00:01:23,45 --> 00:01:25,67"
        val expected = "00:01:23,450 --> 00:01:25,670"
        val actual = SubtitleFormatParser.normalizeTimingLine(input, isVtt = false)
        assertEquals(expected, actual, "normalizeTimingLine must right-pad 2-digit millis to 450 and 670")
    }

    @Test
    @DisplayName("normalizeTimingLine adds missing hour and normalizes dot to comma for SRT")
    fun testNormalizeTimingLineMissingHourAndDotSeparator() {
        val input = "01:23.45 --> 01:25.67"
        val expected = "00:01:23,450 --> 00:01:25,670"
        val actual = SubtitleFormatParser.normalizeTimingLine(input, isVtt = false)
        assertEquals(expected, actual, "Missing hour must become 00 and dot must normalize to comma in SRT")
    }

    @Test
    @DisplayName("normalizeTimingLine preserves WebVTT dot separator and trailing coordinates")
    fun testNormalizeTimingLineWebVttWithCoordinates() {
        val input = "00:01:23.45 --> 00:01:25.67 line:0 position:50%"
        val expected = "00:01:23.450 --> 00:01:25.670 line:0 position:50%"
        val actual = SubtitleFormatParser.normalizeTimingLine(input, isVtt = true)
        assertEquals(expected, actual, "WebVTT line must right-pad millis and preserve trailing coordinates")
    }

    @Test
    @DisplayName("normalizeTimingLine leaves already-valid 3-digit timing lines intact")
    fun testNormalizeTimingLineAlreadyValid() {
        val validSrt = "00:01:23,450 --> 00:01:25,670"
        assertEquals(validSrt, SubtitleFormatParser.normalizeTimingLine(validSrt, isVtt = false))

        val invalidLine = "Not a timing line"
        assertEquals(invalidLine, SubtitleFormatParser.normalizeTimingLine(invalidLine, isVtt = false))
    }

    // =========================================================================
    // 3. Upstream parseTimecode Mathematical Equivalence Tests
    // =========================================================================

    @Test
    @DisplayName("parseTimecodeToUs calculates exact microseconds matching upstream CustomSubripParser")
    fun testUpstreamParseTimecodeUsCalculations() {
        // 00:01:23,45 -> 1 min (60s) + 23s + 450ms = 83,450 ms = 83,450,000 Us
        val us = SubtitleFormatParser.parseTimecodeToUs("00:01:23,45")
        assertEquals(83_450_000L, us)

        // 01:23,4 -> 1 min + 23s + 400ms = 83,400 ms = 83,400,000 Us
        val usSingleDigit = SubtitleFormatParser.parseTimecodeToUs("01:23,4")
        assertEquals(83_400_000L, usSingleDigit)

        // 02:00:10,123 -> 2 hours + 10s + 123ms = 7,200,000 + 10,000 + 123 = 7,210,123 ms = 7,210,123,000 Us
        val usFull = SubtitleFormatParser.parseTimecodeToUs("02:00:10,123")
        assertEquals(7_210_123_000L, usFull)

        // 4-digit millis truncated: 00:00:01,1239 -> 1,123 ms = 1,123,000 Us
        val usTruncated = SubtitleFormatParser.parseTimecodeToUs("00:00:01,1239")
        assertEquals(1_123_000L, usTruncated)
    }

    @Test
    @DisplayName("parseTimecodeToMs converts correctly to milliseconds")
    fun testParseTimecodeMs() {
        val ms = SubtitleFormatParser.parseTimecodeToMs("00:01:23,45")
        assertEquals(83_450L, ms)
    }

    @Test
    @DisplayName("parseTimecode throws IllegalArgumentException on invalid format")
    fun testParseTimecodeThrowsOnInvalid() {
        assertThrows<IllegalArgumentException> {
            SubtitleFormatParser.parseTimecodeToUs("invalid_timecode")
        }
    }

    // =========================================================================
    // 4. Format Sniffing & Subtitle Processing Tests
    // =========================================================================

    @Test
    @DisplayName("sniffFormat detects SRT for 2-digit millisecond subtitles")
    fun testSniffFormatWith2DigitMillisSrt() {
        val srtContent = """
            1
            00:01:23,45 --> 00:01:25,67
            Sample subtitle text
        """.trimIndent()
        assertEquals(SubtitleFormat.SRT, SubtitleFormatParser.sniffFormat(srtContent))

        val srtWithoutIndices = """
            00:01:23,45 --> 00:01:25,67
            Subtitle line directly
        """.trimIndent()
        assertEquals(SubtitleFormat.SRT, SubtitleFormatParser.sniffFormat(srtWithoutIndices))

        val vttContent = "WEBVTT\n\n00:01:23.45 --> 00:01:25.67\nText"
        assertEquals(SubtitleFormat.WEBVTT, SubtitleFormatParser.sniffFormat(vttContent))

        val assContent = "[Script Info]\nTitle: Test\n\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"
        assertEquals(SubtitleFormat.ASS, SubtitleFormatParser.sniffFormat(assContent))
    }

    @Test
    @DisplayName("processSrtOrVtt right-pads 2-digit millis and preserves cue text")
    fun testProcessSrtOrVttNormalizesTiming() {
        val rawSrt = """
            1
            00:01:23,45 --> 00:01:25,67
            Dialogue line one

            2
            00:02:10,12 --> 00:02:12,34
            Dialogue line two
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = false, removeCaptions = false)
        val result = SubtitleFormatParser.processSrtOrVtt(rawSrt, isVtt = false, config = config)

        assertTrue(
            result.contains("00:01:23,450 --> 00:01:25,670"),
            "First cue timing must be right-padded to 3 digits"
        )
        assertTrue(
            result.contains("00:02:10,120 --> 00:02:12,340"),
            "Second cue timing must be right-padded to 3 digits"
        )
        assertTrue(result.contains("Dialogue line one"))
        assertTrue(result.contains("Dialogue line two"))
    }

    @Test
    @DisplayName("processSrtOrVtt prunes promotional bloat and renumbers indices")
    fun testProcessSrtPrunesBloatAndRenumbers() {
        val srtWithBloat = """
            1
            00:01:00,10 --> 00:01:02,20
            Support us and become VIP member to remove all ads from OpenSubtitles

            2
            00:01:05,30 --> 00:01:08,40
            Legitimate dialogue line
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true, removeCaptions = false)
        val result = SubtitleFormatParser.processSrtOrVtt(srtWithBloat, isVtt = false, config = config)

        assertFalse(result.contains("Support us and become VIP"), "Ad line must be stripped")
        assertTrue(result.contains("Legitimate dialogue line"))
        // Index must be renumbered to 1
        assertTrue(result.startsWith("1\n00:01:05,300 --> 00:01:08,400"))
    }

    @Test
    @DisplayName("processSrtOrVtt handles unpadded cues without blank lines between them")
    fun testProcessSrtWithoutBlankLinesBetweenCues() {
        val unseparatedSrt = """
            1
            00:01:00,10 --> 00:01:02,20
            First cue dialogue
            2
            00:01:05,30 --> 00:01:08,40
            Second cue dialogue
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = false, removeCaptions = false)
        val result = SubtitleFormatParser.processSrtOrVtt(unseparatedSrt, isVtt = false, config = config)

        assertTrue(result.contains("1\n00:01:00,100 --> 00:01:02,200\nFirst cue dialogue"))
        assertTrue(result.contains("2\n00:01:05,300 --> 00:01:08,400\nSecond cue dialogue"))
    }

    @Test
    @DisplayName("processAss sanitizes dialogue payload while safeguarding header and styles")
    fun testProcessAssSanitizesEventsDialogue() {
        val assContent = """
            [Script Info]
            Title: Sample

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:00.00,0:01:02.00,Default,,0,0,0,,Support us and become VIP member to remove all ads from OpenSubtitles
            Dialogue: 0,0:01:05.00,0:01:07.00,Default,,0,0,0,,Hello world
        """.trimIndent()

        val config = SubtitleSanitizerConfig(removeBloat = true)
        val processed = SubtitleFormatParser.processAss(assContent, config)

        assertTrue(processed.contains("[Script Info]"))
        assertTrue(processed.contains("[V4+ Styles]"))
        assertTrue(processed.contains("Dialogue: 0,0:01:05.00,0:01:07.00,Default,,0,0,0,,Hello world"))
        assertFalse(processed.contains("Support us and become VIP"), "Ad dialogue must be dropped")
    }

    // =========================================================================
    // 5. Upstream Constants & Alignment Anchor Parity Tests
    // =========================================================================

    @Test
    @DisplayName("getFractionalPositionForAnchorType matches upstream fractional positioning")
    fun testAnchorTypeFractionalPositions() {
        assertEquals(0.08f, SubtitleFormatParser.getFractionalPositionForAnchorType(0), 0.001f) // Start
        assertEquals(0.5f, SubtitleFormatParser.getFractionalPositionForAnchorType(1), 0.001f)  // Middle
        assertEquals(0.92f, SubtitleFormatParser.getFractionalPositionForAnchorType(2), 0.001f) // End

        assertThrows<IllegalArgumentException> {
            SubtitleFormatParser.getFractionalPositionForAnchorType(99)
        }
    }

    @Test
    @DisplayName("Upstream constants and SSA alignment tags match CustomSubripParser 1:1")
    fun testUpstreamConstantsParity() {
        assertEquals(1, SubtitleFormatParser.CUE_REPLACEMENT_BEHAVIOR)
        assertEquals(0.08f, SubtitleFormatParser.START_FRACTION, 0.001f)
        assertEquals(0.92f, SubtitleFormatParser.END_FRACTION, 0.001f)
        assertEquals(0.5f, SubtitleFormatParser.MID_FRACTION, 0.001f)

        assertEquals("{\\an1}", SubtitleFormatParser.ALIGN_BOTTOM_LEFT)
        assertEquals("{\\an2}", SubtitleFormatParser.ALIGN_BOTTOM_MID)
        assertEquals("{\\an3}", SubtitleFormatParser.ALIGN_BOTTOM_RIGHT)
        assertEquals("{\\an4}", SubtitleFormatParser.ALIGN_MID_LEFT)
        assertEquals("{\\an5}", SubtitleFormatParser.ALIGN_MID_MID)
        assertEquals("{\\an6}", SubtitleFormatParser.ALIGN_MID_RIGHT)
        assertEquals("{\\an7}", SubtitleFormatParser.ALIGN_TOP_LEFT)
        assertEquals("{\\an8}", SubtitleFormatParser.ALIGN_TOP_MID)
        assertEquals("{\\an9}", SubtitleFormatParser.ALIGN_TOP_RIGHT)

        assertTrue("{\\an5}".matches(SubtitleFormatParser.SUBRIP_ALIGNMENT_TAG.toRegex()))
        assertTrue("{\\b1}".matches(SubtitleFormatParser.SUBRIP_TAG_PATTERN.toRegex()))
    }
}
