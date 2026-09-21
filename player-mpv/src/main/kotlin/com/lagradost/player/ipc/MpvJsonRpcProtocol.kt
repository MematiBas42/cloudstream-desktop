package com.lagradost.player.ipc

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.player.api.PlayerState
import com.lagradost.player.api.TrackInfo

typealias TrackInfo = com.lagradost.player.api.TrackInfo

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MpvEvent(
    @JsonProperty("event") val event: String? = null,
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("data") val data: Any? = null,
    @JsonProperty("reason") val reason: String? = null,
    @JsonProperty("error") val error: String? = null,
    @JsonProperty("request_id") val requestId: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MpvCommand(
    @JsonProperty("command") val command: List<Any>,
    @JsonProperty("request_id") val requestId: Int? = null,
)

object MpvJsonRpcProtocol {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    const val PROP_TIME_POS = "time-pos"
    const val PROP_DURATION = "duration"
    const val PROP_PAUSE = "pause"
    const val PROP_EOF_REACHED = "eof-reached"
    const val PROP_TRACK_LIST = "track-list"
    const val PROP_SID = "sid"
    const val PROP_AID = "aid"
    const val PROP_VOLUME = "volume"
    const val PROP_SUB_DELAY = "sub-delay"
    const val PROP_VIDEO_PARAMS_ASPECT = "video-params/aspect"
    const val PROP_DWIDTH = "dwidth"
    const val PROP_DHEIGHT = "dheight"
    const val PROP_SUB_TEXT = "sub-text"

    const val OBSERVE_ID_TIME_POS = 1
    const val OBSERVE_ID_DURATION = 2
    const val OBSERVE_ID_PAUSE = 3
    const val OBSERVE_ID_EOF_REACHED = 4
    const val OBSERVE_ID_TRACK_LIST = 5
    const val OBSERVE_ID_SUB_DELAY = 6
    const val OBSERVE_ID_AID = 7
    const val OBSERVE_ID_SID = 8
    const val OBSERVE_ID_VIDEO_PARAMS_ASPECT = 9
    const val OBSERVE_ID_DWIDTH = 10
    const val OBSERVE_ID_DHEIGHT = 11
    const val OBSERVE_ID_SUB_TEXT = 12

    fun buildObservePropertyCommand(id: Int, name: String, requestId: Int? = null): String {
        return formatCommand(listOf("observe_property", id, name), requestId)
    }

    fun buildSetPropertyCommand(name: String, value: Any, requestId: Int? = null): String {
        return formatCommand(listOf("set_property", name, value), requestId)
    }

    fun buildSeekCommand(targetSeconds: Double, mode: String = "absolute", requestId: Int? = null): String {
        return formatCommand(listOf("seek", targetSeconds, mode), requestId)
    }

    fun buildCycleCommand(property: String, requestId: Int? = null): String {
        return formatCommand(listOf("cycle", property), requestId)
    }

    fun buildQuitCommand(requestId: Int? = null): String {
        return formatCommand(listOf("quit"), requestId)
    }

    fun buildLoadFileCommand(url: String, mode: String = "replace", requestId: Int? = null): String {
        return formatCommand(listOf("loadfile", url, mode), requestId)
    }

    fun buildLoadFileCommand(url: String, mode: String, positionMs: Long, requestId: Int? = null): String {
        val cmd = com.lagradost.player.impl.PlayerLinkHandler.buildLoadFileCommand(url, mode, positionMs)
        return formatCommand(cmd, requestId)
    }

    fun buildCommand(command: String, args: List<Any> = emptyList(), requestId: Int? = null): String {
        return formatCommand(listOf(command) + args, requestId)
    }

    fun formatCommand(command: List<Any>, requestId: Int? = null): String {
        val payload = MpvCommand(command = command, requestId = requestId)
        return mapper.writeValueAsString(payload)
    }

    fun parseMessage(json: String): MpvEvent? {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            mapper.readValue(trimmed, MpvEvent::class.java)
        }.getOrNull()
    }

    fun parseDouble(data: Any?): Double? {
        return when (data) {
            is Number -> data.toDouble()
            is String -> data.toDoubleOrNull()
            else -> null
        }
    }

    fun parseBoolean(data: Any?): Boolean? {
        return when (data) {
            is Boolean -> data
            is Number -> data.toInt() != 0
            is String -> data.equals("true", ignoreCase = true) || data.equals("yes", ignoreCase = true)
            else -> null
        }
    }

    fun parseTrackList(data: Any?): List<TrackInfo> {
        if (data == null) return emptyList()
        return try {
            when (data) {
                is List<*> -> {
                    data.mapNotNull { item ->
                        when (item) {
                            is TrackInfo -> item
                            is Map<*, *> -> {
                                val id = (item["id"] as? Number)?.toInt() ?: 0
                                val type = item["type"]?.toString().orEmpty()
                                val title = item["title"]?.toString()
                                val lang = item["lang"]?.toString()
                                val selected = item["selected"] as? Boolean
                                    ?: ((item["selected"] as? Number)?.toInt() == 1)
                                val codec = item["codec"]?.toString()
                                val demuxChannels = item["demux-channels"]?.toString()
                                val audioChannels = (item["audio-channels"] as? Number)?.toInt()
                                val demuxChannelCount = (item["demux-channel-count"] as? Number)?.toInt()
                                val external = (item["external"] as? Boolean)
                                    ?: ((item["external"] as? Number)?.toInt() == 1)
                                val externalFilename = item["external-filename"]?.toString()
                                val default = (item["default"] as? Boolean)
                                    ?: ((item["default"] as? Number)?.toInt() == 1)
                                val forced = (item["forced"] as? Boolean)
                                    ?: ((item["forced"] as? Number)?.toInt() == 1)
                                TrackInfo(
                                    id = id,
                                    type = type,
                                    title = title,
                                    lang = lang,
                                    selected = selected,
                                    codec = codec,
                                    demuxChannels = demuxChannels,
                                    audioChannels = audioChannels,
                                    demuxChannelCount = demuxChannelCount,
                                    external = external,
                                    externalFilename = externalFilename,
                                    default = default,
                                    forced = forced
                                )
                            }
                            else -> {
                                val json = mapper.writeValueAsString(item)
                                mapper.readValue(json, TrackInfo::class.java)
                            }
                        }
                    }
                }
                else -> {
                    val json = mapper.writeValueAsString(data)
                    mapper.readValue(json, object : TypeReference<List<TrackInfo>>() {})
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Pure reducer updating PlayerState from an incoming MPV event.
     */
    fun applyEvent(currentState: PlayerState, event: MpvEvent): PlayerState {
        when (event.event) {
            "property-change" -> {
                when (event.name) {
                    PROP_TIME_POS -> {
                        val posSec = parseDouble(event.data) ?: return currentState
                        val durSec = currentState.durationSec
                        val isCompleted = currentState.isCompleted || (durSec > 0.0 && posSec / durSec >= 0.90)
                        return currentState.copy(
                            positionSec = posSec,
                            position = (posSec * 1000).toLong(),
                            isPlaying = !currentState.isPaused && !currentState.isFinished,
                            isCompleted = isCompleted,
                        )
                    }
                    PROP_DURATION -> {
                        val durSec = parseDouble(event.data) ?: return currentState
                        val posSec = currentState.positionSec
                        val isCompleted = currentState.isCompleted || (durSec > 0.0 && posSec / durSec >= 0.90)
                        return currentState.copy(
                            durationSec = durSec,
                            duration = (durSec * 1000).toLong(),
                            isCompleted = isCompleted,
                        )
                    }
                    PROP_PAUSE -> {
                        val isPaused = parseBoolean(event.data) ?: return currentState
                        return currentState.copy(
                            isPaused = isPaused,
                            isPlaying = !isPaused && !currentState.isFinished,
                        )
                    }
                    PROP_EOF_REACHED -> {
                        val eof = parseBoolean(event.data) ?: false
                        if (eof) {
                            return currentState.copy(
                                isPlaying = false,
                                isFinished = true,
                                isCompleted = true,
                            )
                        }
                        return currentState
                    }
                    PROP_TRACK_LIST -> {
                        val tracks = parseTrackList(event.data)
                        return currentState.copy(tracks = tracks)
                    }
                    PROP_SUB_DELAY -> {
                        val subDelay = parseDouble(event.data) ?: return currentState
                        return currentState.copy(subtitleDelaySec = subDelay)
                    }
                    PROP_AID -> {
                        val aid = when (val d = event.data) {
                            is Number -> d.toInt()
                            is String -> d.toIntOrNull()
                            else -> null
                        }
                        return currentState.copy(activeAudioTrackId = aid)
                    }
                    PROP_SID -> {
                        val sid = when (val d = event.data) {
                            is Number -> d.toInt()
                            is String -> d.toIntOrNull()
                            else -> null
                        }
                        return currentState.copy(activeSubtitleTrackId = sid)
                    }
                    PROP_VIDEO_PARAMS_ASPECT -> {
                        val aspect = parseDouble(event.data)
                        return currentState.copy(videoAspect = aspect)
                    }
                    PROP_DWIDTH -> {
                        val dw = when (val d = event.data) {
                            is Number -> d.toInt()
                            is String -> d.toIntOrNull()
                            else -> null
                        }
                        return currentState.copy(dwidth = dw)
                    }
                    PROP_DHEIGHT -> {
                        val dh = when (val d = event.data) {
                            is Number -> d.toInt()
                            is String -> d.toIntOrNull()
                            else -> null
                        }
                        return currentState.copy(dheight = dh)
                    }
                    PROP_SUB_TEXT -> {
                        val text = event.data?.toString()
                        return currentState.copy(currentSubText = text?.takeIf { it.isNotBlank() })
                    }
                }
            }
            "end-file" -> {
                val isEof = event.reason.equals("eof", ignoreCase = true)
                return currentState.copy(
                    isPlaying = false,
                    isFinished = true,
                    isCompleted = currentState.isCompleted || isEof,
                )
            }
            "pause" -> {
                return currentState.copy(isPaused = true, isPlaying = false)
            }
            "unpause" -> {
                return currentState.copy(isPaused = false, isPlaying = !currentState.isFinished)
            }
        }
        return currentState
    }
}
