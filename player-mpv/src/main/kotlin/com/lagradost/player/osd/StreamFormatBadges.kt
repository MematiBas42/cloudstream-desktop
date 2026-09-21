package com.lagradost.player.osd

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Technical stream metadata container for Leanback TV badges.
 */
data class StreamMetadata(
    val videoCodec: String?,
    val audioChannels: String?,
    val resolution: String?,
    val dynamicRange: String?
) {
    fun formatBadge(): String = StreamFormatBadges.formatBadge(this)
}

object StreamFormatBadges {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * Formats stream metadata into a clean TV badge string:
     * e.g. "HEVC • 1080p • 5.1 • HDR10"
     * Null or blank elements are omitted.
     */
    fun formatBadge(metadata: StreamMetadata): String {
        return listOfNotNull(
            metadata.videoCodec?.takeIf { it.isNotBlank() },
            metadata.resolution?.takeIf { it.isNotBlank() },
            metadata.audioChannels?.takeIf { it.isNotBlank() },
            metadata.dynamicRange?.takeIf { it.isNotBlank() }
        ).joinToString(" • ")
    }

    fun format(metadata: StreamMetadata): String = formatBadge(metadata)

    /**
     * Calculates projected finish time: currentTime + (duration - position),
     * formatted as HH:mm.
     */
    fun calculateProjectedFinishTime(
        currentTimeMs: Long,
        durationMs: Long,
        positionMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        speed: Double = 1.0
    ): String {
        val remainingMs = if (speed > 0.0) {
            ((durationMs - positionMs).coerceAtLeast(0L) / speed).toLong()
        } else {
            (durationMs - positionMs).coerceAtLeast(0L)
        }
        val finishInstant = Instant.ofEpochMilli(currentTimeMs + remainingMs)
        return finishInstant.atZone(zoneId).toLocalTime().format(timeFormatter)
    }

    /**
     * Calculates projected finish time from LocalTime: currentTime + (duration - position),
     * formatted as HH:mm.
     */
    fun calculateProjectedFinishTime(
        currentTime: LocalTime,
        durationMs: Long,
        positionMs: Long,
        speed: Double = 1.0
    ): String {
        val remainingMs = if (speed > 0.0) {
            ((durationMs - positionMs).coerceAtLeast(0L) / speed).toLong()
        } else {
            (durationMs - positionMs).coerceAtLeast(0L)
        }
        val remainingSeconds = remainingMs / 1000L
        return currentTime.plusSeconds(remainingSeconds).format(timeFormatter)
    }

    /**
     * Calculates projected finish time using current system wall-clock time.
     */
    fun calculateProjectedFinishTime(
        durationMs: Long,
        positionMs: Long,
        speed: Double = 1.0
    ): String {
        return calculateProjectedFinishTime(
            currentTimeMs = System.currentTimeMillis(),
            durationMs = durationMs,
            positionMs = positionMs,
            speed = speed
        )
    }

    /**
     * Formats digital clock time as HH:mm.
     */
    fun formatDigitalClock(
        currentTimeMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        return Instant.ofEpochMilli(currentTimeMs).atZone(zoneId).toLocalTime().format(timeFormatter)
    }

    fun formatDigitalClock(time: LocalTime): String {
        return time.format(timeFormatter)
    }
}

fun formatStreamBadge(metadata: StreamMetadata): String = StreamFormatBadges.formatBadge(metadata)

fun calculateProjectedFinishTime(
    currentTimeMs: Long,
    durationMs: Long,
    positionMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    speed: Double = 1.0
): String = StreamFormatBadges.calculateProjectedFinishTime(currentTimeMs, durationMs, positionMs, zoneId, speed)

fun calculateProjectedFinishTime(
    currentTime: LocalTime,
    durationMs: Long,
    positionMs: Long,
    speed: Double = 1.0
): String = StreamFormatBadges.calculateProjectedFinishTime(currentTime, durationMs, positionMs, speed)

fun calculateProjectedFinishTime(
    durationMs: Long,
    positionMs: Long,
    speed: Double = 1.0
): String = StreamFormatBadges.calculateProjectedFinishTime(durationMs, positionMs, speed)
