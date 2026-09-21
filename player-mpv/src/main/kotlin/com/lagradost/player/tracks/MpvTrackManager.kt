package com.lagradost.player.tracks

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.common.logging.AppLogger
import com.lagradost.player.api.TrackInfo
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Origin classification for subtitle streams.
 */
enum class SubtitleOrigin {
    EMBEDDED_IN_VIDEO,
    URL,
    DOWNLOADED_FILE
}

/**
 * Structured model representing an audio stream parsed from MPV IPC track-list.
 */
data class AudioTrack(
    val id: Int,
    val title: String?,
    val lang: String?,
    val channels: String?,
    val codec: String?,
    val isSelected: Boolean
)

/**
 * Structured model representing a subtitle stream parsed from MPV IPC track-list.
 */
data class SubtitleTrack(
    val id: Int,
    val title: String?,
    val lang: String?,
    val isSelected: Boolean,
    val origin: SubtitleOrigin = SubtitleOrigin.EMBEDDED_IN_VIDEO
)

/**
 * High-performance Track & Subtitle Timing Synchronization Manager for MPV.
 *
 * Responsibilities:
 * - Audio track (`aid`), subtitle track (`sid`), and delay (`sub-delay`) management over MPV IPC.
 * - Parses MPV IPC `track-list` property into structured [AudioTrack] and [SubtitleTrack] models.
 * - ISO 639-1 / ISO 639-2 language code mapping to localized human-readable names.
 * - Subtitle synchronization delay slider (-5.0s to +5.0s in 100ms steps, with reset to 0.0s).
 * - Playlist index safety bounds checking.
 */
class MpvTrackManager(
    val ipcClient: MpvIpcClient? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _audioTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrack>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    private val _selectedAudioTrackId = MutableStateFlow<Int>(0)
    val selectedAudioTrackId: StateFlow<Int> = _selectedAudioTrackId.asStateFlow()

    private val _selectedSubtitleTrackId = MutableStateFlow<Int>(0)
    val selectedSubtitleTrackId: StateFlow<Int> = _selectedSubtitleTrackId.asStateFlow()

    private val _subtitleDelaySec = MutableStateFlow<Double>(0.0)
    val subtitleDelaySec: StateFlow<Double> = _subtitleDelaySec.asStateFlow()

    init {
        ipcClient?.state?.onEach { state ->
            val (audios, subs) = parseTrackList(state.tracks)
            _audioTracks.value = audios
            _subtitleTracks.value = subs
            _selectedAudioTrackId.value = state.activeAudioTrackId
                ?: audios.firstOrNull { it.isSelected }?.id ?: 0
            _selectedSubtitleTrackId.value = state.activeSubtitleTrackId
                ?: subs.firstOrNull { it.isSelected }?.id ?: 0
            _subtitleDelaySec.value = state.subtitleDelaySec
        }?.launchIn(scope)
    }

    fun selectAudioTrack(trackId: Int): Boolean {
        _selectedAudioTrackId.value = trackId
        _audioTracks.update { list ->
            list.map { it.copy(isSelected = it.id == trackId) }
        }
        val client = ipcClient ?: return true
        return if (trackId <= 0) {
            client.sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_AID, "no"))
        } else {
            client.selectAudio(trackId)
            true
        }
    }

    fun selectSubtitleTrack(trackId: Int): Boolean {
        _selectedSubtitleTrackId.value = trackId
        _subtitleTracks.update { list ->
            list.map { it.copy(isSelected = it.id == trackId) }
        }
        val client = ipcClient ?: return true
        return if (trackId <= 0) {
            client.sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_SID, "no"))
        } else {
            client.selectSubtitle(trackId)
            true
        }
    }

    fun setSubtitleDelay(delaySec: Double): Double {
        val clamped = clampSubtitleDelay(delaySec)
        _subtitleDelaySec.value = clamped
        ipcClient?.setSubtitleDelay(clamped)
        return clamped
    }

    fun adjustSubtitleDelay(deltaSec: Double): Double {
        val target = _subtitleDelaySec.value + deltaSec
        return setSubtitleDelay(target)
    }

    fun resetSubtitleDelay(): Double {
        return setSubtitleDelay(0.0)
    }

    fun updateTracks(data: Any?) {
        val (audios, subs) = parseTrackList(data)
        _audioTracks.value = audios
        _subtitleTracks.value = subs
        _selectedAudioTrackId.value = audios.firstOrNull { it.isSelected }?.id ?: 0
        _selectedSubtitleTrackId.value = subs.firstOrNull { it.isSelected }?.id ?: 0
    }

    fun updateTracksFromJson(json: String) {
        val (audios, subs) = parseTrackListJson(json)
        _audioTracks.value = audios
        _subtitleTracks.value = subs
        _selectedAudioTrackId.value = audios.firstOrNull { it.isSelected }?.id ?: 0
        _selectedSubtitleTrackId.value = subs.firstOrNull { it.isSelected }?.id ?: 0
    }

    companion object {
        const val MIN_SUB_DELAY_SEC = -5.0
        const val MAX_SUB_DELAY_SEC = 5.0
        const val SUB_DELAY_STEP_SEC = 0.1 // 100ms steps

        private val mapper: ObjectMapper = jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private val KNOWN_ISO_639_MAP = mapOf(
            "en" to "English", "eng" to "English",
            "tr" to "Turkish", "tur" to "Turkish",
            "es" to "Spanish", "spa" to "Spanish",
            "fr" to "French", "fra" to "French", "fre" to "French",
            "de" to "German", "deu" to "German", "ger" to "German",
            "it" to "Italian", "ita" to "Italian",
            "ja" to "Japanese", "jpn" to "Japanese",
            "ko" to "Korean", "kor" to "Korean",
            "zh" to "Chinese", "zho" to "Chinese", "chi" to "Chinese",
            "ru" to "Russian", "rus" to "Russian",
            "pt" to "Portuguese", "por" to "Portuguese",
            "ar" to "Arabic", "ara" to "Arabic",
            "hi" to "Hindi", "hin" to "Hindi",
            "nl" to "Dutch", "nld" to "Dutch", "dut" to "Dutch",
            "pl" to "Polish", "pol" to "Polish",
            "sv" to "Swedish", "swe" to "Swedish",
            "da" to "Danish", "dan" to "Danish",
            "fi" to "Finnish", "fin" to "Finnish",
            "no" to "Norwegian", "nor" to "Norwegian",
            "cs" to "Czech", "ces" to "Czech", "cze" to "Czech",
            "el" to "Greek", "ell" to "Greek", "gre" to "Greek",
            "he" to "Hebrew", "heb" to "Hebrew",
            "id" to "Indonesian", "ind" to "Indonesian",
            "uk" to "Ukrainian", "ukr" to "Ukrainian",
            "vi" to "Vietnamese", "vie" to "Vietnamese",
            "th" to "Thai", "tha" to "Thai",
            "ro" to "Romanian", "ron" to "Romanian", "rum" to "Romanian",
            "hu" to "Hungarian", "hun" to "Hungarian",
        )

        /**
         * Clamps subtitle synchronization delay strictly to [-5.0s, +5.0s] in 2-decimal precision.
         */
        fun clampSubtitleDelay(delaySec: Double): Double {
            val rounded = (delaySec * 100.0).roundToInt() / 100.0
            return rounded.coerceIn(MIN_SUB_DELAY_SEC, MAX_SUB_DELAY_SEC)
        }

        /**
         * Steps subtitle delay by [steps] of [SUB_DELAY_STEP_SEC] (100ms).
         */
        fun stepSubtitleDelay(currentDelaySec: Double, steps: Int): Double {
            val target = currentDelaySec + (steps * SUB_DELAY_STEP_SEC)
            return clampSubtitleDelay(target)
        }

        /**
         * Resets subtitle delay to exactly 0.0s.
         */
        fun resetSubtitleDelay(): Double = 0.0

        /**
         * Resolves ISO 639-1 / ISO 639-2 language code to English / localized human-readable name.
         */
        fun resolveLanguageName(rawTag: String?): String {
            if (rawTag.isNullOrBlank()) return "Unknown"
            val trimmed = rawTag.trim()
            val lower = trimmed.lowercase(Locale.ENGLISH)
            val base = lower.replace('_', '-').substringBefore('-')

            // 1. Direct dictionary match for common ISO 639-1 / 639-2 codes
            val exact = KNOWN_ISO_639_MAP[lower] ?: KNOWN_ISO_639_MAP[base]
            if (exact != null) return exact

            // 2. SubtitleHelper lookup
            val fromHelper = SubtitleHelper.fromTagToLanguageName(trimmed)
                ?: SubtitleHelper.fromTagToEnglishLanguageName(trimmed)
                ?: SubtitleHelper.fromTagToLanguageName(base)
                ?: SubtitleHelper.fromTagToEnglishLanguageName(base)
                ?: SubtitleHelper.fromTwoLettersToLanguage(trimmed)
                ?: SubtitleHelper.fromThreeLettersToLanguage(trimmed)
                ?: SubtitleHelper.fromTwoLettersToLanguage(base)
                ?: SubtitleHelper.fromThreeLettersToLanguage(base)

            if (!fromHelper.isNullOrBlank()) {
                return fromHelper.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
            }

            // 3. Java Locale fallback
            val fromLocale = runCatching {
                val locale = Locale.forLanguageTag(trimmed)
                val display = locale.getDisplayLanguage(Locale.ENGLISH)
                if (display.isNotBlank() && !display.equals(trimmed, ignoreCase = true)) display else null
            }.getOrNull() ?: runCatching {
                val locale = Locale(trimmed)
                val display = locale.getDisplayLanguage(Locale.ENGLISH)
                if (display.isNotBlank() && !display.equals(trimmed, ignoreCase = true)) display else null
            }.getOrNull()

            if (!fromLocale.isNullOrBlank()) {
                return fromLocale.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
            }

            return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
        }

        /**
         * Resolves the effective channel count from audio channel metadata.
         */
        fun resolveEffectiveChannelCount(
            audioChannels: Int? = null,
            demuxChannels: String? = null,
            demuxChannelCount: Int? = null
        ): Int {
            if (audioChannels != null && audioChannels > 0) return audioChannels
            if (demuxChannelCount != null && demuxChannelCount > 0) return demuxChannelCount
            if (demuxChannels != null) {
                val lower = demuxChannels.lowercase(Locale.ENGLISH).trim()
                return when {
                    lower == "mono" -> 1
                    lower == "stereo" -> 2
                    lower.startsWith("5.1") -> 6
                    lower.startsWith("7.1") -> 8
                    lower.startsWith("2.1") -> 3
                    lower.startsWith("3.1") -> 4
                    lower.startsWith("4.0") || lower.startsWith("quad") -> 4
                    lower.startsWith("5.0") -> 5
                    lower.endsWith("ch") -> lower.removeSuffix("ch").toIntOrNull() ?: 0
                    else -> 0
                }
            }
            return 0
        }

        /**
         * Formats audio channel topology into human-readable presentation.
         */
        fun formatAudioChannels(
            audioChannels: Int? = null,
            demuxChannels: String? = null,
            demuxChannelCount: Int? = null
        ): String {
            val count = resolveEffectiveChannelCount(audioChannels, demuxChannels, demuxChannelCount)
            return when (count) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1 Surround"
                8 -> "7.1 Surround"
                in 3..5, 7 -> "${count}ch"
                else -> {
                    val trimmed = demuxChannels?.trim()
                    if (!trimmed.isNullOrBlank()) {
                        when (trimmed.lowercase(Locale.ENGLISH)) {
                            "mono" -> "Mono"
                            "stereo" -> "Stereo"
                            "5.1", "5.1(side)" -> "5.1 Surround"
                            "7.1" -> "7.1 Surround"
                            else -> trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
                        }
                    } else if (count > 0) {
                        "${count}ch"
                    } else {
                        ""
                    }
                }
            }
        }

        /**
         * Formats audio codec to normalized display string (e.g. EAC3 -> E-AC-3 (Dolby Digital Plus)).
         */
        fun formatAudioCodec(codec: String?): String {
            if (codec.isNullOrBlank()) return ""
            return when (codec.lowercase(Locale.ENGLISH).trim()) {
                "eac3" -> "E-AC-3 (Dolby Digital Plus)"
                "ac3" -> "AC-3 (Dolby Digital)"
                "dts", "dca" -> "DTS"
                "dts-hd", "dtshd" -> "DTS-HD MA"
                "truehd" -> "Dolby TrueHD"
                "aac" -> "AAC"
                "opus" -> "Opus"
                "flac" -> "FLAC"
                "vorbis" -> "Vorbis"
                "mp3" -> "MP3"
                "subrip" -> "SRT"
                "ass" -> "ASS/SSA"
                "webvtt" -> "VTT"
                else -> codec.trim().uppercase(Locale.ENGLISH)
            }
        }

        /**
         * Determines SubtitleOrigin from external flag, filename, or URL.
         */
        fun resolveSubtitleOrigin(
            external: Boolean?,
            externalFilename: String?,
            url: String? = null
        ): SubtitleOrigin {
            val src = externalFilename ?: url
            return when {
                external == true -> {
                    if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) {
                        SubtitleOrigin.URL
                    } else if (src != null && (src.startsWith("/") || src.startsWith("file://"))) {
                        SubtitleOrigin.DOWNLOADED_FILE
                    } else {
                        SubtitleOrigin.URL
                    }
                }
                src != null && (src.startsWith("http://") || src.startsWith("https://")) -> SubtitleOrigin.URL
                src != null && (src.startsWith("/") || src.startsWith("file://")) -> SubtitleOrigin.DOWNLOADED_FILE
                else -> SubtitleOrigin.EMBEDDED_IN_VIDEO
            }
        }

        /**
         * Safely clamps playlist episode navigation index within [0, playlistSize - 1].
         */
        fun clampPlaylistIndex(index: Int, playlistSize: Int): Int {
            if (playlistSize <= 0) return 0
            return index.coerceIn(0, playlistSize - 1)
        }

        /**
         * Returns next episode index or null if currently at end of playlist.
         */
        fun getNextEpisodeIndex(currentIndex: Int, playlistSize: Int): Int? {
            if (playlistSize <= 0) return null
            val next = currentIndex + 1
            return if (next in 0 until playlistSize) next else null
        }

        /**
         * Returns previous episode index or null if currently at beginning of playlist.
         */
        fun getPreviousEpisodeIndex(currentIndex: Int, playlistSize: Int): Int? {
            if (playlistSize <= 0) return null
            val prev = currentIndex - 1
            return if (prev in 0 until playlistSize) prev else null
        }

        /**
         * Checks if an episode index is within bounds of the playlist.
         */
        fun isIndexValid(index: Int, playlistSize: Int): Boolean {
            return playlistSize > 0 && index in 0 until playlistSize
        }

        /**
         * Parses raw MPV IPC JSON string into Audio and Subtitle tracks.
         */
        fun parseTrackListJson(json: String): Pair<List<AudioTrack>, List<SubtitleTrack>> {
            if (json.isBlank()) return Pair(emptyList(), emptyList())
            return try {
                val root = mapper.readTree(json)
                val dataNode = if (root.has("data")) root.get("data") else root
                parseTrackListNode(dataNode)
            } catch (e: Exception) {
                AppLogger.w("MpvTrackManager failed to parse track-list JSON: ${e.message}")
                Pair(emptyList(), emptyList())
            }
        }

        /**
         * Parses Jackson JsonNode representing track list.
         */
        fun parseTrackListNode(node: JsonNode?): Pair<List<AudioTrack>, List<SubtitleTrack>> {
            if (node == null || !node.isArray) return Pair(emptyList(), emptyList())
            val audioTracks = mutableListOf<AudioTrack>()
            val subtitleTracks = mutableListOf<SubtitleTrack>()

            for (item in node) {
                val id = item.path("id").asInt(0)
                val type = item.path("type").asText("").lowercase(Locale.ENGLISH)
                val title = item.path("title").takeIf { !it.isMissingNode && !it.isNull }?.asText()
                val lang = item.path("lang").takeIf { !it.isMissingNode && !it.isNull }?.asText()
                val selectedNode = item.path("selected")
                val selected = when {
                    selectedNode.isBoolean -> selectedNode.asBoolean()
                    selectedNode.isNumber -> selectedNode.asInt() != 0
                    selectedNode.isTextual -> selectedNode.asText().equals("true", ignoreCase = true) ||
                            selectedNode.asText().equals("yes", ignoreCase = true) ||
                            selectedNode.asText() == "1"
                    else -> false
                }
                val codec = item.path("codec").takeIf { !it.isMissingNode && !it.isNull }?.asText()
                val demuxChannels = item.path("demux-channels").takeIf { !it.isMissingNode && !it.isNull }?.asText()
                val audioChannels = item.path("audio-channels").takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val demuxChannelCount = item.path("demux-channel-count").takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val externalNode = item.path("external")
                val external = when {
                    externalNode.isBoolean -> externalNode.asBoolean()
                    externalNode.isNumber -> externalNode.asInt() != 0
                    externalNode.isTextual -> externalNode.asText().equals("true", ignoreCase = true) ||
                            externalNode.asText().equals("yes", ignoreCase = true) ||
                            externalNode.asText() == "1"
                    else -> false
                }
                val externalFilename = item.path("external-filename").takeIf { !it.isMissingNode && !it.isNull }?.asText()

                when (type) {
                    "audio" -> {
                        val formattedChannels = formatAudioChannels(audioChannels, demuxChannels, demuxChannelCount)
                            .takeIf { it.isNotBlank() }
                        val formattedCodec = formatAudioCodec(codec).takeIf { it.isNotBlank() }
                        audioTracks.add(
                            AudioTrack(
                                id = id,
                                title = title,
                                lang = lang,
                                channels = formattedChannels,
                                codec = formattedCodec,
                                isSelected = selected
                            )
                        )
                    }
                    "sub" -> {
                        val origin = resolveSubtitleOrigin(external, externalFilename)
                        subtitleTracks.add(
                            SubtitleTrack(
                                id = id,
                                title = title,
                                lang = lang,
                                isSelected = selected,
                                origin = origin
                            )
                        )
                    }
                }
            }

            return Pair(audioTracks, subtitleTracks)
        }

        /**
         * Parses arbitrary data (List of TrackInfo, List of Maps, or Jackson Nodes) into structured tracks.
         */
        fun parseTrackList(data: Any?): Pair<List<AudioTrack>, List<SubtitleTrack>> {
            if (data == null) return Pair(emptyList(), emptyList())
            return try {
                when (data) {
                    is JsonNode -> parseTrackListNode(data)
                    is String -> parseTrackListJson(data)
                    is List<*> -> {
                        val audioTracks = mutableListOf<AudioTrack>()
                        val subtitleTracks = mutableListOf<SubtitleTrack>()

                        for (item in data) {
                            when (item) {
                                is TrackInfo -> {
                                    val type = item.type.lowercase(Locale.ENGLISH)
                                    if (type == "audio") {
                                        val channels = formatAudioChannels(
                                            item.audioChannels,
                                            item.demuxChannels,
                                            item.demuxChannelCount
                                        ).takeIf { it.isNotBlank() }
                                        val codec = formatAudioCodec(item.codec).takeIf { it.isNotBlank() }
                                        audioTracks.add(
                                            AudioTrack(
                                                id = item.id,
                                                title = item.title,
                                                lang = item.lang,
                                                channels = channels,
                                                codec = codec,
                                                isSelected = item.selected
                                            )
                                        )
                                    } else if (type == "sub") {
                                        val origin = resolveSubtitleOrigin(item.external, item.externalFilename)
                                        subtitleTracks.add(
                                            SubtitleTrack(
                                                id = item.id,
                                                title = item.title,
                                                lang = item.lang,
                                                isSelected = item.selected,
                                                origin = origin
                                            )
                                        )
                                    }
                                }
                                is Map<*, *> -> {
                                    val id = (item["id"] as? Number)?.toInt() ?: 0
                                    val type = item["type"]?.toString().orEmpty().lowercase(Locale.ENGLISH)
                                    val title = item["title"]?.toString()
                                    val lang = item["lang"]?.toString()
                                    val selected = when (val sel = item["selected"]) {
                                        is Boolean -> sel
                                        is Number -> sel.toInt() == 1
                                        is String -> sel.equals("true", ignoreCase = true) || sel.equals("yes", ignoreCase = true)
                                        else -> false
                                    }
                                    val codec = item["codec"]?.toString()
                                    val demuxChannels = item["demux-channels"]?.toString()
                                    val audioChannels = (item["audio-channels"] as? Number)?.toInt()
                                    val demuxChannelCount = (item["demux-channel-count"] as? Number)?.toInt()
                                    val external = when (val ext = item["external"]) {
                                        is Boolean -> ext
                                        is Number -> ext.toInt() == 1
                                        is String -> ext.equals("true", ignoreCase = true)
                                        else -> false
                                    }
                                    val externalFilename = item["external-filename"]?.toString()

                                    if (type == "audio") {
                                        val channels = formatAudioChannels(audioChannels, demuxChannels, demuxChannelCount)
                                            .takeIf { it.isNotBlank() }
                                        val formattedCodec = formatAudioCodec(codec).takeIf { it.isNotBlank() }
                                        audioTracks.add(
                                            AudioTrack(
                                                id = id,
                                                title = title,
                                                lang = lang,
                                                channels = channels,
                                                codec = formattedCodec,
                                                isSelected = selected
                                            )
                                        )
                                    } else if (type == "sub") {
                                        val origin = resolveSubtitleOrigin(external, externalFilename)
                                        subtitleTracks.add(
                                            SubtitleTrack(
                                                id = id,
                                                title = title,
                                                lang = lang,
                                                isSelected = selected,
                                                origin = origin
                                            )
                                        )
                                    }
                                }
                                else -> {
                                    val json = mapper.writeValueAsString(item)
                                    val (a, s) = parseTrackListJson("[$json]")
                                    audioTracks.addAll(a)
                                    subtitleTracks.addAll(s)
                                }
                            }
                        }
                        Pair(audioTracks, subtitleTracks)
                    }
                    else -> {
                        val json = mapper.writeValueAsString(data)
                        parseTrackListJson(json)
                    }
                }
            } catch (e: Exception) {
                AppLogger.w("MpvTrackManager failed to parse track-list: ${e.message}")
                Pair(emptyList(), emptyList())
            }
        }

        fun parseAudioTracks(data: Any?): List<AudioTrack> = parseTrackList(data).first
        fun parseSubtitleTracks(data: Any?): List<SubtitleTrack> = parseTrackList(data).second
        fun parseAudioTracksJson(json: String): List<AudioTrack> = parseTrackListJson(json).first
        fun parseSubtitleTracksJson(json: String): List<SubtitleTrack> = parseTrackListJson(json).second
    }
}
