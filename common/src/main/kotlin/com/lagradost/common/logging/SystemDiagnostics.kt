package com.lagradost.common.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Organ classification for full-system observability.
 */
enum class DiagnosticOrgan(val label: String, val badgeColorHex: Long) {
    NETWORK("NET", 0xFF00E5FF),
    IMAGE("COIL", 0xFFE040FB),
    PLAYER("MPV", 0xFF00E676),
    PLUGIN("DEX", 0xFFFF9100),
    STORAGE("DISK", 0xFF40C4FF),
    SYSTEM("SYS", 0xFFB0BEC5),
    CRASH("FATAL", 0xFFFF1744),
}

/**
 * A single structured diagnostic telemetry event.
 */
data class DiagnosticEvent(
    val id: Int,
    val timestamp: String,
    val organ: DiagnosticOrgan,
    val level: AppLogger.Level,
    val threadName: String,
    val tag: String,
    val message: String,
    val details: String? = null,
    val error: String? = null,
) {
    val formatted: String
        get() = "[$timestamp] [${organ.label}] [${level.label}] [$threadName] $tag: $message" +
                (if (!details.isNullOrBlank()) "\n    |-> $details" else "") +
                (if (!error.isNullOrBlank()) "\n    |-> ERROR: $error" else "")
}

/**
 * High-performance, lock-free diagnostic event bus and ring buffer.
 * Automatically ingests telemetry from OkHttp, Coil, MPV IPC, Dalvik runtime, and Storage.
 */
object SystemDiagnostics {
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val MAX_EVENTS = 1000
    private val idCounter = AtomicInteger(1)

    @Volatile
    var isEnabled: Boolean = true

    private val ringBuffer = ConcurrentLinkedDeque<DiagnosticEvent>()
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    fun record(
        organ: DiagnosticOrgan,
        level: AppLogger.Level,
        tag: String,
        message: String,
        details: String? = null,
        error: String? = null,
    ) {
        if (!isEnabled) return

        val event = DiagnosticEvent(
            id = idCounter.getAndIncrement(),
            timestamp = LocalTime.now().format(timeFormatter),
            organ = organ,
            level = level,
            threadName = Thread.currentThread().name,
            tag = tag,
            message = message,
            details = details,
            error = error,
        )

        ringBuffer.addLast(event)
        while (ringBuffer.size > MAX_EVENTS) {
            ringBuffer.pollFirst()
        }
        _events.value = ringBuffer.toList()
    }

    fun clear() {
        ringBuffer.clear()
        _events.value = emptyList()
    }

    fun exportToString(filterQuery: String = "", organFilter: DiagnosticOrgan? = null, onlyErrors: Boolean = false): String {
        val q = filterQuery.trim().lowercase()
        return _events.value
            .filter { organFilter == null || it.organ == organFilter }
            .filter { !onlyErrors || it.level == AppLogger.Level.ERROR || it.level == AppLogger.Level.WARN }
            .filter { q.isEmpty() || it.message.lowercase().contains(q) || it.tag.lowercase().contains(q) || (it.error?.lowercase()?.contains(q) == true) || (it.details?.lowercase()?.contains(q) == true) }
            .joinToString("\n") { it.formatted }
    }
}
