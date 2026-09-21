// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubripParser.kt", upstreamCommit = "caeec18")
package com.lagradost.player.subtitles

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Structural cue parser and normalizer for SubRip (.srt), WebVTT (.vtt),
 * and Advanced SubStation Alpha (.ass / .ssa).
 *
 * Implements 1:1 architectural parity with upstream CustomSubripParser:
 * - Supports standard 3-digit and non-standard 2-digit milliseconds (\d{2,3})
 * - Normalizes and right-pads milliseconds (e.g. "45" -> "450", "4" -> "400") to avoid desync
 * - Normalizes timecodes (HH:MM:SS,mmm for SRT, HH:MM:SS.mmm for WebVTT)
 * - Detects subtitle formats via sniffFormat
 * - Sanitizes dialogue cues via processSrtOrVtt and processAss
 */
object SubtitleFormatParser {

    /**
     * Upstream timecode regex specification from CustomSubripParser.kt:241-244:
     * SUBRIP_TIMECODE = "(?:(\\d+):)?(\\d+):(\\d+)(?:[,.](\\d+))?"
     * SUBRIP_TIMING_LINE = "\\s*($SUBRIP_TIMECODE)\\s*-->\\s*($SUBRIP_TIMECODE)\\s*"
     */
    const val SUBRIP_TIMECODE = "(?:(\\d+):)?(\\d+):(\\d+)(?:[,.](\\d+))?"

    val SUBRIP_TIMING_LINE: Pattern =
        Pattern.compile("\\s*($SUBRIP_TIMECODE)\\s*-->\\s*($SUBRIP_TIMECODE)\\s*")

    val SUBRIP_TIMECODE_PATTERN: Pattern =
        Pattern.compile("^$SUBRIP_TIMECODE$")

    // Android Media3 SubtitleParser parity constants
    const val CUE_REPLACEMENT_BEHAVIOR: Int = 1 // Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    const val START_FRACTION: Float = 0.08f
    const val END_FRACTION: Float = 1.0f - START_FRACTION // 0.92f
    const val MID_FRACTION: Float = 0.5f
    private const val TAG = "SubripParser"

    // SSA V4+ alignment tags (CustomSubripParser.kt:250-258)
    val SUBRIP_TAG_PATTERN: Pattern = Pattern.compile("\\{\\\\.*?\\}")
    const val SUBRIP_ALIGNMENT_TAG = "\\{\\\\an[1-9]\\}"
    const val ALIGN_BOTTOM_LEFT = "{\\an1}"
    const val ALIGN_BOTTOM_MID = "{\\an2}"
    const val ALIGN_BOTTOM_RIGHT = "{\\an3}"
    const val ALIGN_MID_LEFT = "{\\an4}"
    const val ALIGN_MID_MID = "{\\an5}"
    const val ALIGN_MID_RIGHT = "{\\an6}"
    const val ALIGN_TOP_LEFT = "{\\an7}"
    const val ALIGN_TOP_MID = "{\\an8}"
    const val ALIGN_TOP_RIGHT = "{\\an9}"

    /**
     * Regex matching SRT and WebVTT timing lines.
     * Updated to support two-digit milliseconds (\d{2,3}) matching upstream tolerance
     * for malformed web scrapers and edge-case subtitle sources.
     */
    val SRT_TIMING_REGEX = Regex(
        """^\s*(?:(?:\d{1,2}:)?\d{2}:\d{2}[:,\.]\d{2,3})\s*-->\s*(?:(?:\d{1,2}:)?\d{2}:\d{2}[:,\.]\d{2,3}).*$"""
    )

    /**
     * Extraction regex for decomposing timing lines into constituent groups:
     * Group 1: Start hours (optional)
     * Group 2: Start minutes
     * Group 3: Start seconds
     * Group 4: Start separator (comma or dot)
     * Group 5: Start milliseconds (1 to 4+ digits)
     * Group 6: End hours (optional)
     * Group 7: End minutes
     * Group 8: End seconds
     * Group 9: End separator (comma or dot)
     * Group 10: End milliseconds (1 to 4+ digits)
     * Group 11: Trailing settings/coordinates (WebVTT parameters)
     */
    private val TIMING_LINE_EXTRACTOR = Regex(
        """^\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})([:,\.])(\d+)\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{1,2})([:,\.])(\d+)(.*)$"""
    )

    /**
     * Right-pads millisecond string to 3 digits (e.g. "4" -> "400", "45" -> "450", "450" -> "450").
     * If longer than 3 digits, truncates to 3 digits matching upstream CustomSubripParser behavior.
     */
    fun rightPadMillis(millis: String?): String {
        if (millis.isNullOrEmpty()) return "000"
        return when {
            millis.length == 1 -> millis + "00"
            millis.length == 2 -> millis + "0"
            millis.length == 3 -> millis
            else -> millis.substring(0, 3)
        }
    }

    /**
     * Normalizes an SRT or WebVTT timing line.
     * Right-pads 2-digit (or 1-digit) milliseconds to standard 3-digit format,
     * ensures two-digit hours/minutes/seconds, normalizes separators (comma for SRT, dot for WebVTT),
     * and preserves any trailing WebVTT settings/cue positioning.
     *
     * Example:
     * "00:01:23,45 --> 00:01:25,67" -> "00:01:23,450 --> 00:01:25,670"
     * "01:23.45 --> 01:25.67" (isVtt=false) -> "00:01:23,450 --> 00:01:25,670"
     * "00:01:23.45 --> 00:01:25.67 line:0" (isVtt=true) -> "00:01:23.450 --> 00:01:25.670 line:0"
     */
    fun normalizeTimingLine(timingLine: String, isVtt: Boolean = false): String {
        val match = TIMING_LINE_EXTRACTOR.matchEntire(timingLine.trim()) ?: return timingLine

        val startHours = match.groupValues[1].ifEmpty { null }
        val startMins = match.groupValues[2]
        val startSecs = match.groupValues[3]
        val startMillis = match.groupValues[5]

        val endHours = match.groupValues[6].ifEmpty { null }
        val endMins = match.groupValues[7]
        val endSecs = match.groupValues[8]
        val endMillis = match.groupValues[10]

        val trailing = match.groupValues[11]

        val sep = if (isVtt) "." else ","
        val formattedStartHours = (startHours ?: "00").padStart(2, '0')
        val formattedStartMins = startMins.padStart(2, '0')
        val formattedStartSecs = startSecs.padStart(2, '0')
        val formattedStartMillis = rightPadMillis(startMillis)

        val formattedEndHours = (endHours ?: "00").padStart(2, '0')
        val formattedEndMins = endMins.padStart(2, '0')
        val formattedEndSecs = endSecs.padStart(2, '0')
        val formattedEndMillis = rightPadMillis(endMillis)

        val startTime = "$formattedStartHours:$formattedStartMins:$formattedStartSecs$sep$formattedStartMillis"
        val endTime = "$formattedEndHours:$formattedEndMins:$formattedEndSecs$sep$formattedEndMillis"

        return "$startTime --> $endTime$trailing"
    }

    /**
     * 1:1 upstream port of CustomSubripParser.parseTimecode (lines 260-278).
     * Calculates microseconds (Us) given a Matcher with groups (hours, minutes, seconds, millis).
     */
    fun parseTimecode(matcher: Matcher, groupOffset: Int): Long {
        val hours = matcher.group(groupOffset + 1)
        var timestampMs = if (hours != null) hours.toLong() * 60 * 60 * 1000 else 0L
        timestampMs += checkNotNull(matcher.group(groupOffset + 2)) { "Minutes group cannot be null" }
            .toLong() * 60 * 1000
        timestampMs += checkNotNull(matcher.group(groupOffset + 3)) { "Seconds group cannot be null" }
            .toLong() * 1000
        val millis = matcher.group(groupOffset + 4)

        timestampMs += when (millis?.length) {
            null -> 0L
            1 -> millis.toLong() * 100L
            2 -> millis.toLong() * 10L
            3 -> millis.toLong() * 1L
            else -> millis.substring(0, 3).toLong()
        }

        return timestampMs * 1000L
    }

    /**
     * Parses a timecode string to microseconds (Us) matching upstream CustomSubripParser.
     */
    fun parseTimecodeToUs(timecode: String): Long {
        val matcher = SUBRIP_TIMECODE_PATTERN.matcher(timecode.trim())
        require(matcher.matches()) { "Invalid timecode format: $timecode" }
        return parseTimecode(matcher, 0)
    }

    /**
     * Parses a timecode string to milliseconds (Ms).
     */
    fun parseTimecodeToMs(timecode: String): Long {
        return parseTimecodeToUs(timecode) / 1000L
    }

    /**
     * Upstream getFractionalPositionForAnchorType parity (CustomSubripParser.kt:283-294).
     */
    fun getFractionalPositionForAnchorType(anchorType: Int): Float {
        return when (anchorType) {
            0 -> START_FRACTION // Cue.ANCHOR_TYPE_START
            1 -> MID_FRACTION   // Cue.ANCHOR_TYPE_MIDDLE
            2 -> END_FRACTION   // Cue.ANCHOR_TYPE_END
            else -> throw IllegalArgumentException("Unsupported anchorType: $anchorType")
        }
    }

    /**
     * Sniffs subtitle format from content header and first lines.
     */
    fun sniffFormat(content: String): SubtitleFormat {
        val trimmed = content.trimStart {
            it.isWhitespace() || it == '﻿' || it == '​' || it.category == CharCategory.CONTROL || it.category == CharCategory.FORMAT
        }
        return when {
            trimmed.startsWith("WEBVTT", ignoreCase = true) -> SubtitleFormat.WEBVTT
            trimmed.startsWith("[Script Info]", ignoreCase = true) || trimmed.contains("\n[Events]") -> SubtitleFormat.ASS
            trimmed.lines().take(5).any { it.matches(SRT_TIMING_REGEX) } -> SubtitleFormat.SRT
            trimmed.lines().firstOrNull()?.trim()?.toIntOrNull() != null -> SubtitleFormat.SRT
            else -> SubtitleFormat.UNKNOWN
        }
    }

    /**
     * Parses and cleans SubRip (.srt) and WebVTT (.vtt) documents.
     * Preserves timing codes, right-padding two-digit milliseconds to standard 3-digit format;
     * removes bloat and SDH tags; prunes blank cues; renumbers indices for SRT.
     */
    fun processSrtOrVtt(
        content: String,
        isVtt: Boolean,
        config: SubtitleSanitizerConfig,
    ): String {
        val lines = content.lines()
        val output = StringBuilder()
        var lineIndex = 0
        var cueCounter = 1

        // Preserve WebVTT header blocks
        if (isVtt) {
            while (lineIndex < lines.size) {
                val headerLine = lines[lineIndex++]
                output.append(headerLine).append("\n")
                if (headerLine.isBlank()) break
            }
        }

        val totalLines = lines.size
        while (lineIndex < totalLines) {
            val line = lines[lineIndex].trim()
            if (line.isBlank()) {
                lineIndex++
                continue
            }

            val potentialNext = if (lineIndex + 1 < totalLines) lines[lineIndex + 1].trim() else ""
            val isTiming = line.matches(SRT_TIMING_REGEX)
            val nextIsTiming = potentialNext.matches(SRT_TIMING_REGEX)

            val timingLine: String
            if (isTiming) {
                timingLine = normalizeTimingLine(line, isVtt)
                lineIndex++
            } else if (nextIsTiming) {
                timingLine = normalizeTimingLine(potentialNext, isVtt)
                lineIndex += 2
            } else {
                lineIndex++
                continue
            }

            // Collect cue dialogue lines until empty line or next cue
            val cueLines = mutableListOf<String>()
            while (lineIndex < totalLines && lines[lineIndex].isNotBlank()) {
                val current = lines[lineIndex].trim()
                if (current.matches(SRT_TIMING_REGEX)) break
                if (lineIndex + 1 < totalLines && lines[lineIndex + 1].trim().matches(SRT_TIMING_REGEX) && current.toIntOrNull() != null) break
                cueLines.add(lines[lineIndex])
                lineIndex++
            }

            val cleanedLines = cueLines.mapNotNull { SubtitleSanitizer.sanitizeLine(it, config) }

            if (cleanedLines.isNotEmpty()) {
                if (!isVtt) {
                    output.append(cueCounter++).append("\n")
                }
                output.append(timingLine).append("\n")
                for (cleaned in cleanedLines) {
                    output.append(cleaned).append("\n")
                }
                output.append("\n")
            }
        }

        return output.toString().trimEnd() + "\n"
    }

    /**
     * Sanitizes Advanced SubStation Alpha (.ass / .ssa) files.
     * Only modifies dialogue lines within the [Events] section to ensure styles and headers are safeguarded.
     */
    fun processAss(content: String, config: SubtitleSanitizerConfig): String {
        val lines = content.lines()
        val output = StringBuilder()
        var inEvents = false

        for (line in lines) {
            if (line.startsWith("[Events]", ignoreCase = true)) {
                inEvents = true
                output.append(line).append("\n")
                continue
            } else if (line.startsWith("[") && inEvents) {
                inEvents = false
            }

            if (inEvents && (line.startsWith("Dialogue:", ignoreCase = true) || line.startsWith("Comment:", ignoreCase = true))) {
                val parts = line.split(",", limit = 10)
                if (parts.size == 10) {
                    val prefix = parts.subList(0, 9).joinToString(",")
                    val textPayload = parts[9]
                    val cleaned = SubtitleSanitizer.sanitizeLine(textPayload, config)
                    if (cleaned != null) {
                        output.append(prefix).append(",").append(cleaned).append("\n")
                    }
                    continue
                }
            }

            output.append(line).append("\n")
        }

        return output.toString()
    }
}
