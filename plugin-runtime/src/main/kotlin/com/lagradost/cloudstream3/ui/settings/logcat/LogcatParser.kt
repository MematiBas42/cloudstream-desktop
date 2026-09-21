// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/logcat/LogcatParser.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.settings.logcat

import androidx.compose.runtime.Immutable
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.logging.SystemDiagnostics
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.asSource
import kotlinx.io.readByteArray
import kotlinx.io.readIntLe
import kotlinx.io.readString
import kotlinx.io.readUShortLe
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Instant

fun Instant.toHumanReadable(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    return formatter.format(Date(this.toEpochMilliseconds()))
}

@Immutable
data class LogcatItem(
    val date: Instant,
    val pid: Int,
    val tid: Int,
    val level: LogcatLevel?,
    val tag: String,
    val message: String,
) {
    override fun toString(): String {
        return "${date.toHumanReadable()} $pid-$tid $tag ${level?.identifier ?: "?"} $message"
    }
}

enum class LogcatLevel(val identifier: String) {
    Fatal("WTF"),
    Error("E"),
    Warning("W"),
    Info("I"),
    Debug("D"),
    Verbose("V");

    companion object {
        fun fromPriority(priority: Byte): LogcatLevel? {
            return when (priority) {
                2.toByte() -> Verbose
                3.toByte() -> Debug
                4.toByte() -> Info
                5.toByte() -> Warning
                6.toByte() -> Error
                7.toByte() -> Fatal
                else -> null
            }
        }

        fun fromString(value: String): LogcatLevel? {
            val upper = value.trim().uppercase(Locale.ROOT)
            return entries.firstOrNull {
                it.name.equals(upper, ignoreCase = true) ||
                    it.identifier.equals(upper, ignoreCase = true)
            } ?: when (upper) {
                "F", "FATAL", "WTF", "CRASH" -> Fatal
                "E", "ERROR", "ERR" -> Error
                "W", "WARN", "WARNING" -> Warning
                "I", "INFO" -> Info
                "D", "DEBUG" -> Debug
                "V", "VERBOSE", "TRACE" -> Verbose
                else -> null
            }
        }
    }
}

/**https://github.com/brudaswen/android-logcat/blob/main/library/logcat-core/src/main/kotlin/de/brudaswen/android/logcat/core/parser/LogcatBinaryParser.kt  */
class LogcatBinaryParser(
    private val input: InputStream,
) : Closeable by input {
    val source = input.asSource()
    private val buffer = Buffer()

    /**
     * Parse one [LogcatItem] from the current [input] stream.
     *
     * @return The parsed [LogcatItem] or `null` if stream reached EOF.
     */
    suspend fun parseItem(): LogcatItem? = withContext(Dispatchers.IO) {
        val firstByte = input.read()
        if (firstByte == -1) return@withContext null

        // Read v1 header
        buffer.writeByte(firstByte.toByte())
        buffer.write(source = source, byteCount = 19)

        val len = buffer.readUShortLe().toInt()
        val headerSize = buffer.readUShortLe().toInt()
        val pid = buffer.readIntLe()
        val tid = buffer.readIntLe()
        val sec = buffer.readIntLe()
        val nsec = buffer.readIntLe()

        // Read additional header fields
        buffer.write(source = source, byteCount = headerSize - 20L)

        val additionalHeaderBytes = (headerSize - 20).coerceAtLeast(0)
        buffer.readByteArray(byteCount = additionalHeaderBytes)

        // Read payload
        buffer.write(source = source, byteCount = len.toLong())

        val priority = buffer.readByte()

        val payload = buffer.readString()
        val texts = payload.split('\u0000', limit = 2)
        val tag = texts.getOrNull(0).orEmpty()
        val message = texts.getOrNull(1).orEmpty().removeSuffix("\u0000").trim()

        // Clear buffer
        buffer.clear()

        // Convert raw values to item
        LogcatItem(
            sec = sec,
            nsec = nsec,
            priority = priority,
            pid = pid,
            tid = tid,
            tag = tag,
            message = message,
        )
    }

    private fun LogcatItem(
        sec: Int,
        nsec: Int,
        priority: Byte,
        pid: Int,
        tid: Int,
        tag: String,
        message: String,
    ): LogcatItem {
        val date = Instant.fromEpochSeconds(
            epochSeconds = sec.toLong(),
            nanosecondAdjustment = nsec.toLong(),
        )

        val level = LogcatLevel.fromPriority(priority)

        return LogcatItem(
            date = date,
            pid = pid,
            tid = tid,
            level = level,
            tag = tag,
            message = message,
        )
    }

    companion object {
        /**
         * Reads all logcat items from [PlatformPaths.logDir]/app.log.
         * Direct desktop fallback for Android `logcat --binary -d` execution.
         */
        suspend fun parseFromAppLog(file: File = LogcatParser.defaultLogFile): List<LogcatItem> {
            return LogcatParser.getLogs(file)
        }

        /**
         * Encodes a [LogcatItem] into the Android logger v1 binary format.
         * Used for round-trip testing and synthesizing binary streams.
         */
        fun writeItem(item: LogcatItem, output: java.io.OutputStream) {
            val tagBytes = item.tag.toByteArray(Charsets.UTF_8)
            val msgBytes = item.message.toByteArray(Charsets.UTF_8)
            val priorityByte = when (item.level) {
                LogcatLevel.Verbose -> 2.toByte()
                LogcatLevel.Debug -> 3.toByte()
                LogcatLevel.Info -> 4.toByte()
                LogcatLevel.Warning -> 5.toByte()
                LogcatLevel.Error -> 6.toByte()
                LogcatLevel.Fatal -> 7.toByte()
                null -> 0.toByte()
            }

            // Payload: priority (1) + tagBytes + 0 + msgBytes + 0
            val payloadLen = 1 + tagBytes.size + 1 + msgBytes.size + 1
            val headerSize = 20

            val bb = ByteBuffer.allocate(headerSize + payloadLen)
                .order(ByteOrder.LITTLE_ENDIAN)

            bb.putShort(payloadLen.toShort())
            bb.putShort(headerSize.toShort())
            bb.putInt(item.pid)
            bb.putInt(item.tid)
            bb.putInt((item.date.toEpochMilliseconds() / 1000).toInt())
            bb.putInt(((item.date.toEpochMilliseconds() % 1000) * 1_000_000).toInt())

            bb.put(priorityByte)
            bb.put(tagBytes)
            bb.put(0.toByte())
            bb.put(msgBytes)
            bb.put(0.toByte())

            output.write(bb.array())
            output.flush()
        }
    }
}

/**
 * Desktop file log reading fallback and bridge replacing Android shell execution (`logcat -d`, `logcat -c`).
 * Reads and persists structured logs from [PlatformPaths.logDir]/app.log and [SystemDiagnostics].
 */
object LogcatParser {
    val defaultLogFile: File
        get() = PlatformPaths.logDir.resolve("app.log").toFile()

    fun getLogFile(): File = defaultLogFile

    /**
     * Reads structured [LogcatItem]s from desktop log storage.
     * Replaces Android shell `logcat -d` execution on Linux/Windows/macOS platforms.
     */
    suspend fun getLogs(file: File = defaultLogFile): List<LogcatItem> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            // Fallback to in-memory SystemDiagnostics telemetry events if app.log is empty or missing
            val telemetry = SystemDiagnostics.events.value
            if (telemetry.isNotEmpty()) {
                val currentPid = try {
                    ProcessHandle.current().pid().toInt()
                } catch (t: Throwable) {
                    AppLogger.d("LogcatParser", "ProcessHandle pid fallback: ${t.message}")
                    1
                }
                return@withContext telemetry.map { event ->
                    val level = when (event.level) {
                        AppLogger.Level.DEBUG -> LogcatLevel.Debug
                        AppLogger.Level.INFO -> LogcatLevel.Info
                        AppLogger.Level.WARN -> LogcatLevel.Warning
                        AppLogger.Level.ERROR -> LogcatLevel.Error
                    }
                    LogcatItem(
                        date = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                        pid = currentPid,
                        tid = 0,
                        level = level,
                        tag = event.tag,
                        message = event.formatted,
                    )
                }
            }
            return@withContext emptyList()
        }

        val items = mutableListOf<LogcatItem>()
        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val parsed = parseTextLine(line)
                    if (parsed != null) {
                        items.add(parsed)
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.e("LogcatParser", "Failed to read logs from ${file.absolutePath}", t)
        }
        items
    }

    /**
     * Reads raw lines from desktop log storage.
     * Replaces `Runtime.getRuntime().exec("logcat -d").inputStream` text line extraction.
     */
    suspend fun getLogLines(file: File = defaultLogFile): List<String> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) {
            val telemetry = SystemDiagnostics.events.value
            if (telemetry.isNotEmpty()) {
                return@withContext telemetry.map { it.formatted }
            }
            return@withContext emptyList()
        }
        try {
            file.readLines(Charsets.UTF_8)
        } catch (t: Throwable) {
            AppLogger.e("LogcatParser", "Failed to read lines from ${file.absolutePath}", t)
            emptyList()
        }
    }

    /**
     * Clears desktop application log file and telemetry buffer.
     * Replaces Android `Runtime.getRuntime().exec("logcat -c")` shell execution.
     */
    suspend fun clearLogs(file: File = defaultLogFile): Boolean = withContext(Dispatchers.IO) {
        var success = true
        try {
            if (file.exists()) {
                file.writeText("", Charsets.UTF_8)
            }
            SystemDiagnostics.clear()
        } catch (t: Throwable) {
            AppLogger.e("LogcatParser", "Failed to clear log file ${file.absolutePath}", t)
            success = false
        }
        success
    }

    /**
     * Returns an [InputStream] providing log data.
     * On desktop, reads directly from [file]. When [tryShellFirst] is enabled,
     * attempts `logcat -d` and gracefully falls back to the file on failure.
     */
    fun getLogcatStream(file: File = defaultLogFile, tryShellFirst: Boolean = false): InputStream {
        if (tryShellFirst) {
            try {
                val process = Runtime.getRuntime().exec("logcat -d")
                return process.inputStream
            } catch (t: Throwable) {
                AppLogger.d("LogcatParser", "Android logcat shell unavailable on desktop (${t.message}). Falling back to ${file.absolutePath}")
            }
        }

        return if (file.exists() && file.isFile) {
            FileInputStream(file)
        } else {
            java.io.ByteArrayInputStream(ByteArray(0))
        }
    }

    /**
     * Parses a single text line into a structured [LogcatItem].
     * Supports:
     * 1. AppLogger / ExceptionHandler format: `[yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] [Thread: name (tid)] Tag: Message`
     * 2. LogcatItem.toString format: `yyyy-MM-dd HH:mm:ss pid-tid Tag Level Message`
     * 3. Android logcat standard format: `MM-dd HH:mm:ss.SSS pid tid Level Tag: Message`
     * 4. Unstructured line fallback (e.g. stack traces)
     */
    fun parseTextLine(line: String): LogcatItem? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val currentPid = try {
            ProcessHandle.current().pid().toInt()
        } catch (t: Throwable) {
            AppLogger.d("LogcatParser", "ProcessHandle pid fallback: ${t.message}")
            1
        }

        // 1. AppLogger / ExceptionHandler format
        val appLoggerMatch = APP_LOGGER_REGEX.matchEntire(trimmed)
        if (appLoggerMatch != null) {
            val dateStr = appLoggerMatch.groups[1]?.value?.trim()
            val timeStr = appLoggerMatch.groups[2]?.value?.trim().orEmpty()
            val levelStr = appLoggerMatch.groups[3]?.value?.trim().orEmpty()
            val tidStr = appLoggerMatch.groups[5]?.value?.trim()
            val tag = appLoggerMatch.groups[6]?.value?.trim().orEmpty()
            val msg = appLoggerMatch.groups[7]?.value?.trim().orEmpty()

            val instant = parseTimestamp(dateStr, timeStr)
            val level = LogcatLevel.fromString(levelStr)
            val tid = tidStr?.toIntOrNull() ?: 0

            return LogcatItem(
                date = instant,
                pid = currentPid,
                tid = tid,
                level = level,
                tag = tag,
                message = msg,
            )
        }

        // 2. LogcatItem.toString() format
        val toStringMatch = TO_STRING_REGEX.matchEntire(trimmed)
        if (toStringMatch != null) {
            val dateTimeStr = toStringMatch.groups[1]?.value?.trim().orEmpty()
            val pid = toStringMatch.groups[2]?.value?.toIntOrNull() ?: currentPid
            val tid = toStringMatch.groups[3]?.value?.toIntOrNull() ?: 0
            val tag = toStringMatch.groups[4]?.value?.trim().orEmpty()
            val levelStr = toStringMatch.groups[5]?.value?.trim().orEmpty()
            val msg = toStringMatch.groups[6]?.value?.trim().orEmpty()

            val instant = parseDateTime(dateTimeStr)
            val level = LogcatLevel.fromString(levelStr)

            return LogcatItem(
                date = instant,
                pid = pid,
                tid = tid,
                level = level,
                tag = tag,
                message = msg,
            )
        }

        // 3. Standard Android logcat brief/threadtime format
        val androidLogcatMatch = ANDROID_LOGCAT_REGEX.matchEntire(trimmed)
        if (androidLogcatMatch != null) {
            val monthTimeStr = androidLogcatMatch.groups[1]?.value?.trim().orEmpty()
            val pid = androidLogcatMatch.groups[2]?.value?.toIntOrNull() ?: currentPid
            val tid = androidLogcatMatch.groups[3]?.value?.toIntOrNull() ?: 0
            val levelStr = androidLogcatMatch.groups[4]?.value?.trim().orEmpty()
            val tag = androidLogcatMatch.groups[5]?.value?.trim().orEmpty()
            val msg = androidLogcatMatch.groups[6]?.value?.trim().orEmpty()

            val instant = parseMonthDateTime(monthTimeStr)
            val level = LogcatLevel.fromString(levelStr)

            return LogcatItem(
                date = instant,
                pid = pid,
                tid = tid,
                level = level,
                tag = tag,
                message = msg,
            )
        }

        // 4. Fallback line (e.g. stack trace line, exception message, plain note)
        val inferredLevel = when {
            trimmed.contains("FATAL", ignoreCase = true) || trimmed.contains("WTF", ignoreCase = true) -> LogcatLevel.Fatal
            trimmed.contains("ERROR", ignoreCase = true) || trimmed.contains("Exception", ignoreCase = true) -> LogcatLevel.Error
            trimmed.contains("WARN", ignoreCase = true) -> LogcatLevel.Warning
            trimmed.contains("DEBUG", ignoreCase = true) -> LogcatLevel.Debug
            else -> LogcatLevel.Info
        }

        return LogcatItem(
            date = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            pid = currentPid,
            tid = 0,
            level = inferredLevel,
            tag = "App",
            message = trimmed,
        )
    }

    private val APP_LOGGER_REGEX = Regex(
        """^\[(?:(\d{4}-\d{2}-\d{2})\s+)?(\d{2}:\d{2}:\d{2}(?:\.\d{3})?)\]\s+\[([A-Za-z]+)\]\s+\[(?:Thread:\s*)?([^\s(\]]+)(?:\s*\((\d+)\))?\]\s+([^:]+):\s*(.*)$"""
    )

    private val TO_STRING_REGEX = Regex(
        """^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(\d+)-(\d+)\s+([^\s]+)\s+([A-Za-z?]+)\s*(.*)$"""
    )

    private val ANDROID_LOGCAT_REGEX = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+):\s*(.*)$"""
    )

    private fun parseTimestamp(dateStr: String?, timeStr: String): Instant {
        return try {
            val datePrefix = if (!dateStr.isNullOrBlank()) {
                dateStr
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            }
            val pattern = if (timeStr.contains('.')) "yyyy-MM-dd HH:mm:ss.SSS" else "yyyy-MM-dd HH:mm:ss"
            val format = SimpleDateFormat(pattern, Locale.getDefault())
            val parsedDate = format.parse("$datePrefix $timeStr")
            Instant.fromEpochMilliseconds(parsedDate?.time ?: System.currentTimeMillis())
        } catch (t: Throwable) {
            AppLogger.d("LogcatParser", "parseTimestamp fallback for $dateStr $timeStr: ${t.message}")
            Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
    }

    private fun parseDateTime(dateTimeStr: String): Instant {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val parsedDate = format.parse(dateTimeStr)
            Instant.fromEpochMilliseconds(parsedDate?.time ?: System.currentTimeMillis())
        } catch (t: Throwable) {
            AppLogger.d("LogcatParser", "parseDateTime fallback for $dateTimeStr: ${t.message}")
            Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
    }

    private fun parseMonthDateTime(monthTimeStr: String): Instant {
        return try {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val parsedDate = format.parse("$currentYear-$monthTimeStr")
            Instant.fromEpochMilliseconds(parsedDate?.time ?: System.currentTimeMillis())
        } catch (t: Throwable) {
            AppLogger.d("LogcatParser", "parseMonthDateTime fallback for $monthTimeStr: ${t.message}")
            Instant.fromEpochMilliseconds(System.currentTimeMillis())
        }
    }
}
