package com.lagradost.player.osd

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import java.util.Locale

/**
 * Immutable metadata payload required to render the 1:1 Netflix-style player HUD.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayerHudMetadata(
    @param:JsonProperty("title") val title: String,
    @param:JsonProperty("logoUrl") val logoUrl: String? = null,
    @param:JsonProperty("posterHeaders") val posterHeaders: Map<String, String> = emptyMap(),
    @param:JsonProperty("tags") val tags: List<String> = emptyList(),
    @param:JsonProperty("year") val year: Int? = null,
    @param:JsonProperty("durationMinutes") val durationMinutes: Int? = null,
    @param:JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @param:JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @param:JsonProperty("imdbScore") val imdbScore: Double? = null,
    @param:JsonProperty("contentRating") val contentRating: String? = null,
    @param:JsonProperty("plotOverview") val plotOverview: String? = null,
    @param:JsonProperty("isMovie") val isMovie: Boolean = true,
) {
    /**
     * Formats the upstream-compliant single-line bulleted metadata badge string:
     * e.g. "Action, Sci-Fi • 2025 • S1:E4 • ⭐ 8.4 • 1h 48m • TV-MA"
     */
    fun formatBadgeText(): String {
        val badges = mutableListOf<String>()

        // 1. Genres (Top 3 on TV to prevent horizontal overflow)
        if (tags.isNotEmpty()) {
            badges.add(tags.take(3).joinToString(", "))
        }

        // 2. Year
        year?.let { badges.add(it.toString()) }

        // 3. Season / Episode identifier
        if (!isMovie && (seasonNumber != null || episodeNumber != null)) {
            val s = seasonNumber?.let { "S$it" } ?: ""
            val e = episodeNumber?.let { "E$it" } ?: ""
            val se = if (s.isNotEmpty() && e.isNotEmpty()) "$s:$e" else "$s$e"
            if (se.isNotEmpty()) badges.add(se)
        }

        // 4. IMDb Rating
        imdbScore?.let {
            if (it > 0.0) {
                badges.add(String.format(Locale.US, "⭐ %.1f", it))
            }
        }

        // 5. Duration
        durationMinutes?.let {
            if (it > 0) {
                val hrs = it / 60
                val mins = it % 60
                val durStr = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                badges.add(durStr)
            }
        }

        // 6. Content Rating
        contentRating?.takeIf { it.isNotBlank() }?.let { badges.add(it) }

        return badges.joinToString(" • ")
    }
}

/**
 * Native MPV SubStation Alpha (ASS) OSD Renderer.
 * Renders vector graphics, gradients, title typography, and metadata badges
 * directly inside MPV's hardware-accelerated video pipeline via Unix socket IPC.
 */
class MpvOsdRenderer(
    private val ipcClient: MpvIpcClient,
    val overlayId: Int = 1,
) {
    companion object {
        const val RES_X = 1920
        const val RES_Y = 1080
        const val SCRIM_WIDTH = 680
    }

    /**
     * Renders or clears the Netflix-style metadata HUD on MPV.
     */
    fun render(metadata: PlayerHudMetadata?, isVisible: Boolean) {
        if (!isVisible || metadata == null) {
            clearOverlay()
            return
        }

        val assScript = buildAssScript(metadata)
        val command = MpvJsonRpcProtocol.buildCommand(
            command = "osd-overlay",
            args = listOf(overlayId, "ass-events", assScript)
        )
        ipcClient.sendCommand(command)
    }

    /**
     * Clears the ASS overlay from MPV.
     */
    fun clearOverlay() {
        val removeCommand = MpvJsonRpcProtocol.buildCommand(
            command = "osd-overlay",
            args = listOf(overlayId, "none", "")
        )
        ipcClient.sendCommand(removeCommand)
    }

    /**
     * Builds standard ASS script formatted for 1080p canvas resolution.
     */
    fun buildAssScript(meta: PlayerHudMetadata): String {
        val sb = StringBuilder()

        // ASS Header configuration
        sb.append("[Script Info]\n")
        sb.append("ScriptType: v4.00+\n")
        sb.append("PlayResX: $RES_X\n")
        sb.append("PlayResY: $RES_Y\n")
        sb.append("WrapStyle: 2\n")
        sb.append("ScaledBorderAndShadow: yes\n\n")

        // Style Definitions
        sb.append("[V4+ Styles]\n")
        sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        sb.append("Style: ScrimBox,Sans,10,&H00000000,&H00000000,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1\n")
        sb.append("Style: Title,Sans,44,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,3,7,64,32,0,1\n")
        sb.append("Style: Meta,Sans,22,&H00B3FFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,1,2,7,64,32,0,1\n")
        sb.append("Style: Synopsis,Sans,24,&H00E6FFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,1,2,7,64,32,0,1\n\n")

        // Events / Drawing Commands
        sb.append("[Events]\n")
        sb.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")

        // 1. Draw Left Netflix Scrim (Approximated via 3 vertical gradient bands in ASS vector mode)
        // Band 1: 0 to 220px (90% black: &H19000000)
        sb.append("Dialogue: 0,0:00:00.00,9:59:59.00,ScrimBox,,0,0,0,,")
        sb.append("{\\pos(0,0)\\1c&H000000&\\1a&H19&\\p1}m 0 0 l 220 0 l 220 $RES_Y l 0 $RES_Y{\\p0}\n")

        // Band 2: 220 to 450px (60% black: &H66000000)
        sb.append("Dialogue: 0,0:00:00.00,9:59:59.00,ScrimBox,,0,0,0,,")
        sb.append("{\\pos(220,0)\\1c&H000000&\\1a&H66&\\p1}m 0 0 l 230 0 l 230 $RES_Y l 0 $RES_Y{\\p0}\n")

        // Band 3: 450 to 680px (30% black: &HB3000000)
        sb.append("Dialogue: 0,0:00:00.00,9:59:59.00,ScrimBox,,0,0,0,,")
        sb.append("{\\pos(450,0)\\1c&H000000&\\1a&HB3&\\p1}m 0 0 l 230 0 l 230 $RES_Y l 0 $RES_Y{\\p0}\n")

        // 2. Title Typography
        val sanitizedTitle = sanitizeAssText(meta.title)
        sb.append("Dialogue: 1,0:00:00.00,9:59:59.00,Title,,64,32,0,,")
        sb.append("{\\pos(64,380)}$sanitizedTitle\n")

        // 3. Metadata Badges
        val badgeText = sanitizeAssText(meta.formatBadgeText())
        if (badgeText.isNotBlank()) {
            sb.append("Dialogue: 1,0:00:00.00,9:59:59.00,Meta,,64,32,0,,")
            sb.append("{\\pos(64,450)}$badgeText\n")
        }

        // 4. Synopsis
        meta.plotOverview?.takeIf { it.isNotBlank() }?.let { synopsis ->
            val wrappedSynopsis = wrapAndSanitizeText(synopsis, 45, 5)
            sb.append("Dialogue: 1,0:00:00.00,9:59:59.00,Synopsis,,64,32,0,,")
            sb.append("{\\pos(64,500)}$wrappedSynopsis\n")
        }

        return sb.toString()
    }

    fun sanitizeAssText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\n", "\\N")
    }

    fun wrapAndSanitizeText(text: String, lineLength: Int = 45, maxLines: Int = 5): String {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.length + word.length + 1 > lineLength) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    lines.add(word)
                }
                if (lines.size >= maxLines) break
            } else {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(word)
            }
        }
        if (lines.size < maxLines && currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines.joinToString("\\N") { sanitizeAssText(it) }
    }
}
