package unit

import com.lagradost.cloudstream3.ui.settings.logcat.LogcatBinaryParser
import com.lagradost.cloudstream3.ui.settings.logcat.LogcatItem
import com.lagradost.cloudstream3.ui.settings.logcat.LogcatLevel
import com.lagradost.cloudstream3.ui.settings.logcat.LogcatParser
import com.lagradost.cloudstream3.ui.settings.logcat.toHumanReadable
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.logging.DiagnosticOrgan
import com.lagradost.common.logging.SystemDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.time.Instant

/**
 * Architectural Remediation & Parity Test Suite for [LogcatParser] & [LogcatBinaryParser].
 *
 * Validates 1:1 behavioral and contract parity against upstream CloudStream:
 * 1. LogcatLevel enumeration mapping, priority values (2..7), and identifier codes ("WTF", "E", "W", "I", "D", "V").
 * 2. LogcatItem data class contract, timestamp formatting ([toHumanReadable]), and [toString] parity.
 * 3. LogcatBinaryParser byte-to-byte little-endian v1 header decoding and null-terminated string parsing.
 * 4. Multi-item binary stream decoding and EOF stream handling.
 * 5. Extended v2/v3 header tolerance (headerSize > 20) with skipped metadata.
 * 6. Binary serialization and deserialization roundtrip.
 * 7. Desktop file log reading fallback ([PlatformPaths.logDir]/app.log) replacing Android shell execution (`logcat -d`).
 * 8. Log line extraction and clear operations replacing Android shell execution (`logcat -c`).
 * 9. Desktop log stream acquisition with graceful shell-to-file fallback.
 * 10. Multi-format log parsing: AppLogger, ExceptionHandler, toString, and standard Android logcat brief/threadtime.
 */
class LogcatParserRemediationTest {

    @Test
    fun `LogcatLevel maps priority bytes and identifiers accurately`() {
        assertEquals(LogcatLevel.Verbose, LogcatLevel.fromPriority(2.toByte()))
        assertEquals(LogcatLevel.Debug, LogcatLevel.fromPriority(3.toByte()))
        assertEquals(LogcatLevel.Info, LogcatLevel.fromPriority(4.toByte()))
        assertEquals(LogcatLevel.Warning, LogcatLevel.fromPriority(5.toByte()))
        assertEquals(LogcatLevel.Error, LogcatLevel.fromPriority(6.toByte()))
        assertEquals(LogcatLevel.Fatal, LogcatLevel.fromPriority(7.toByte()))
        assertNull(LogcatLevel.fromPriority(0.toByte()))
        assertNull(LogcatLevel.fromPriority(1.toByte()))
        assertNull(LogcatLevel.fromPriority(8.toByte()))

        assertEquals("WTF", LogcatLevel.Fatal.identifier)
        assertEquals("E", LogcatLevel.Error.identifier)
        assertEquals("W", LogcatLevel.Warning.identifier)
        assertEquals("I", LogcatLevel.Info.identifier)
        assertEquals("D", LogcatLevel.Debug.identifier)
        assertEquals("V", LogcatLevel.Verbose.identifier)

        assertEquals(LogcatLevel.Fatal, LogcatLevel.fromString("FATAL"))
        assertEquals(LogcatLevel.Fatal, LogcatLevel.fromString("WTF"))
        assertEquals(LogcatLevel.Error, LogcatLevel.fromString("E"))
        assertEquals(LogcatLevel.Error, LogcatLevel.fromString("ERROR"))
        assertEquals(LogcatLevel.Warning, LogcatLevel.fromString("W"))
        assertEquals(LogcatLevel.Warning, LogcatLevel.fromString("WARN"))
        assertEquals(LogcatLevel.Info, LogcatLevel.fromString("I"))
        assertEquals(LogcatLevel.Info, LogcatLevel.fromString("INFO"))
        assertEquals(LogcatLevel.Debug, LogcatLevel.fromString("D"))
        assertEquals(LogcatLevel.Debug, LogcatLevel.fromString("DEBUG"))
        assertEquals(LogcatLevel.Verbose, LogcatLevel.fromString("V"))
        assertEquals(LogcatLevel.Verbose, LogcatLevel.fromString("VERBOSE"))
        assertNull(LogcatLevel.fromString("UNKNOWN_LEVEL"))
    }

    @Test
    fun `LogcatItem data class formats toString conforming to upstream specification`() {
        val epochSeconds = 1700000000L
        val instant = Instant.fromEpochSeconds(epochSeconds)
        val item = LogcatItem(
            date = instant,
            pid = 1234,
            tid = 5678,
            level = LogcatLevel.Error,
            tag = "TestTag",
            message = "Simulated error message",
        )

        val humanReadable = instant.toHumanReadable()
        assertTrue(humanReadable.isNotEmpty())
        assertTrue(humanReadable.matches(Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}""")))

        val str = item.toString()
        assertEquals("$humanReadable 1234-5678 TestTag E Simulated error message", str)

        // Item with null level formats identifier as '?'
        val nullLevelItem = item.copy(level = null)
        assertEquals("$humanReadable 1234-5678 TestTag ? Simulated error message", nullLevelItem.toString())
    }

    @Test
    fun `LogcatBinaryParser decodes little-endian binary header and payload`() = runBlocking {
        val tag = "CloudStreamTest"
        val message = "Binary logcat packet decoded successfully"
        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val payloadLen = 1 + tagBytes.size + 1 + msgBytes.size + 1 // priority (1) + tag + \0 + msg + \0
        val headerSize = 20

        val bb = ByteBuffer.allocate(headerSize + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(payloadLen.toShort()) // len
        bb.putShort(headerSize.toShort()) // headerSize
        bb.putInt(1001) // pid
        bb.putInt(2002) // tid
        bb.putInt(1710000000) // sec
        bb.putInt(500000) // nsec

        bb.put(6.toByte()) // priority = 6 (Error)
        bb.put(tagBytes)
        bb.put(0.toByte())
        bb.put(msgBytes)
        bb.put(0.toByte())

        val input = ByteArrayInputStream(bb.array())
        val parser = LogcatBinaryParser(input)
        val parsedItem = parser.parseItem()

        assertNotNull(parsedItem)
        assertEquals(1001, parsedItem!!.pid)
        assertEquals(2002, parsedItem.tid)
        assertEquals(LogcatLevel.Error, parsedItem.level)
        assertEquals(tag, parsedItem.tag)
        assertEquals(message, parsedItem.message)
        assertEquals(1710000000L, parsedItem.date.epochSeconds)

        // Second call should return null (EOF)
        val nextItem = parser.parseItem()
        assertNull(nextItem)
        parser.close()
    }

    @Test
    fun `LogcatBinaryParser handles multi-item stream and extended header fields`() = runBlocking {
        val output = ByteArrayOutputStream()

        // Item 1: standard header size 20
        val item1 = LogcatItem(
            date = Instant.fromEpochSeconds(1710000100L),
            pid = 500,
            tid = 600,
            level = LogcatLevel.Info,
            tag = "FirstTag",
            message = "First stream message",
        )
        LogcatBinaryParser.writeItem(item1, output)

        // Item 2: extended header size 24 (4 additional bytes before payload)
        val tag2Bytes = "SecondTag".toByteArray(Charsets.UTF_8)
        val msg2Bytes = "Second stream message with v2 header".toByteArray(Charsets.UTF_8)
        val payloadLen = 1 + tag2Bytes.size + 1 + msg2Bytes.size + 1
        val headerSize = 24

        val bb2 = ByteBuffer.allocate(headerSize + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
        bb2.putShort(payloadLen.toShort())
        bb2.putShort(headerSize.toShort())
        bb2.putInt(700)
        bb2.putInt(800)
        bb2.putInt(1710000200)
        bb2.putInt(100000)
        // 4 additional header bytes (euid)
        bb2.putInt(9999)

        bb2.put(5.toByte()) // priority = 5 (Warning)
        bb2.put(tag2Bytes)
        bb2.put(0.toByte())
        bb2.put(msg2Bytes)
        bb2.put(0.toByte())

        output.write(bb2.array())

        val parser = LogcatBinaryParser(ByteArrayInputStream(output.toByteArray()))
        val parsed1 = parser.parseItem()
        assertNotNull(parsed1)
        assertEquals("FirstTag", parsed1!!.tag)
        assertEquals("First stream message", parsed1.message)
        assertEquals(LogcatLevel.Info, parsed1.level)
        assertEquals(500, parsed1.pid)

        val parsed2 = parser.parseItem()
        assertNotNull(parsed2)
        assertEquals("SecondTag", parsed2!!.tag)
        assertEquals("Second stream message with v2 header", parsed2.message)
        assertEquals(LogcatLevel.Warning, parsed2.level)
        assertEquals(700, parsed2.pid)

        assertNull(parser.parseItem())
        parser.close()
    }

    @Test
    fun `LogcatBinaryParser roundtrip serialization preserves item metadata`() = runBlocking {
        val original = LogcatItem(
            date = Instant.fromEpochMilliseconds(1715000000000L),
            pid = 4321,
            tid = 8765,
            level = LogcatLevel.Fatal,
            tag = "CrashReporter",
            message = "Uncaught fatal exception on background worker",
        )

        val output = ByteArrayOutputStream()
        LogcatBinaryParser.writeItem(original, output)

        val parser = LogcatBinaryParser(ByteArrayInputStream(output.toByteArray()))
        val recovered = parser.parseItem()

        assertNotNull(recovered)
        assertEquals(original.pid, recovered!!.pid)
        assertEquals(original.tid, recovered.tid)
        assertEquals(original.level, recovered.level)
        assertEquals(original.tag, recovered.tag)
        assertEquals(original.message, recovered.message)
        assertEquals(original.date.epochSeconds, recovered.date.epochSeconds)
    }

    @Test
    fun `Desktop file log reader parses AppLogger and ExceptionHandler entries`(@TempDir tempDir: Path) = runBlocking {
        val logFile = tempDir.resolve("app.log").toFile()
        val lines = listOf(
            "[2026-09-21 14:00:01.100] [INFO] [Thread-1 (101)] AppBootstrap: Initialized XDG paths",
            "[2026-09-21 14:00:02.200] [DEBUG] [worker (102)] PluginManager: Transpiled extension jar",
            "[2026-09-21 14:00:03.300] [WARN] [Thread-3 (103)] NetworkRepo: Request retry attempt 1",
            "[2026-09-21 14:00:04.400] [ERROR] [main (1)] MpvPlayer: Pipeline playback error",
            "[2026-09-21 14:00:05.500] [FATAL] [Thread: main (1)] Currently loading extension: com.example.plugin",
        )
        logFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)

        val items = LogcatParser.getLogs(logFile)
        assertEquals(5, items.size)

        assertEquals("AppBootstrap", items[0].tag)
        assertEquals(LogcatLevel.Info, items[0].level)
        assertEquals(101, items[0].tid)
        assertEquals("Initialized XDG paths", items[0].message)

        assertEquals("PluginManager", items[1].tag)
        assertEquals(LogcatLevel.Debug, items[1].level)
        assertEquals(102, items[1].tid)

        assertEquals("NetworkRepo", items[2].tag)
        assertEquals(LogcatLevel.Warning, items[2].level)

        assertEquals("MpvPlayer", items[3].tag)
        assertEquals(LogcatLevel.Error, items[3].level)

        assertEquals(LogcatLevel.Fatal, items[4].level)
        assertTrue(items[4].message.contains("Currently loading extension: com.example.plugin"))

        val readLines = LogcatParser.getLogLines(logFile)
        assertEquals(5, readLines.size)
        assertEquals(lines[0], readLines[0])
    }

    @Test
    fun `Desktop file log reader parses LogcatItem toString and Android brief format`(@TempDir tempDir: Path) = runBlocking {
        val logFile = tempDir.resolve("app.log").toFile()
        val lines = listOf(
            "2026-09-21 15:30:00 4001-4002 StorageEngine I Database transaction committed",
            "2026-09-21 15:30:01 4001-4003 PlayerBridge W Dropped video frame detected",
            "09-21 15:30:02.123  5001  5002 D VideoExtractor: Resolved stream url",
            "09-21 15:30:03.456  5001  5003 E SubtitleDecoder: Broken timestamp header",
        )
        logFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)

        val items = LogcatParser.getLogs(logFile)
        assertEquals(4, items.size)

        assertEquals(4001, items[0].pid)
        assertEquals(4002, items[0].tid)
        assertEquals("StorageEngine", items[0].tag)
        assertEquals(LogcatLevel.Info, items[0].level)
        assertEquals("Database transaction committed", items[0].message)

        assertEquals("PlayerBridge", items[1].tag)
        assertEquals(LogcatLevel.Warning, items[1].level)

        assertEquals(5001, items[2].pid)
        assertEquals(5002, items[2].tid)
        assertEquals("VideoExtractor", items[2].tag)
        assertEquals(LogcatLevel.Debug, items[2].level)
        assertEquals("Resolved stream url", items[2].message)

        assertEquals("SubtitleDecoder", items[3].tag)
        assertEquals(LogcatLevel.Error, items[3].level)
        assertEquals("Broken timestamp header", items[3].message)
    }

    @Test
    fun `Desktop log clear replaces Android shell logcat -c`(@TempDir tempDir: Path) = runBlocking {
        val logFile = tempDir.resolve("app.log").toFile()
        logFile.writeText("Test log line before clear\n", Charsets.UTF_8)
        assertTrue(logFile.length() > 0L)

        val cleared = LogcatParser.clearLogs(logFile)
        assertTrue(cleared)
        assertEquals(0L, logFile.length())
        assertTrue(LogcatParser.getLogLines(logFile).isEmpty())
    }

    @Test
    fun `LogcatParser getLogcatStream falls back to file stream on desktop`(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("app.log").toFile()
        val content = "[12:00:00.000] [INFO] [main] App: Stream fallback payload"
        logFile.writeText(content, Charsets.UTF_8)

        // Try with shell fallback enabled
        val stream = LogcatParser.getLogcatStream(file = logFile, tryShellFirst = true)
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(content, text)

        // Stream for non-existent file returns empty stream without crash
        val nonExistent = tempDir.resolve("missing.log").toFile()
        val emptyStream = LogcatParser.getLogcatStream(file = nonExistent, tryShellFirst = false)
        assertEquals("", emptyStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun `LogcatParser falls back to SystemDiagnostics when log file is absent or empty`(@TempDir tempDir: Path) = runBlocking {
        val nonExistent = tempDir.resolve("empty.log").toFile()
        assertFalse(nonExistent.exists())

        // Ingest telemetry event into SystemDiagnostics
        SystemDiagnostics.record(
            organ = DiagnosticOrgan.SYSTEM,
            level = AppLogger.Level.INFO,
            tag = "TestTelemetry",
            message = "In-memory telemetry event fallback",
        )

        val items = LogcatParser.getLogs(nonExistent)
        assertTrue(items.isNotEmpty())
        val target = items.find { it.tag == "TestTelemetry" }
        assertNotNull(target)
        assertEquals(LogcatLevel.Info, target!!.level)
        assertTrue(target.message.contains("In-memory telemetry event fallback"))

        // Clear telemetry
        SystemDiagnostics.clear()
    }

    @Test
    fun `Unstructured stack trace lines are safely wrapped in LogcatItem`() {
        val traceLine = "    at com.lagradost.cloudstream3.Main.main(Main.kt:55)"
        val parsedTrace = LogcatParser.parseTextLine(traceLine)
        assertNotNull(parsedTrace)
        assertEquals("App", parsedTrace!!.tag)
        assertEquals(traceLine.trim(), parsedTrace.message)

        val exceptionLine = "java.lang.RuntimeException: Simulated crash for remediation test"
        val parsedException = LogcatParser.parseTextLine(exceptionLine)
        assertNotNull(parsedException)
        assertEquals(LogcatLevel.Error, parsedException!!.level)
        assertEquals(exceptionLine, parsedException.message)

        // Blank lines return null
        assertNull(LogcatParser.parseTextLine("   "))
        assertNull(LogcatParser.parseTextLine(""))
    }
}
