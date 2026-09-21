package integration

import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.PosDur
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import com.lagradost.common.storage.getDisplayPosition
import com.lagradost.common.storage.getRealPosition
import com.lagradost.common.storage.getWatchProgress
import com.lagradost.player.history.WatchHistoryCoordinator
import com.lagradost.player.ipc.MpvIpcClient
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Industrial anti-mock test suite for Domain 09:
 * - 64-bit millisecond normalization ((positionSec * 1000.0).toLong())
 * - Duration rejection under 30 seconds (< 30_000L)
 * - Upstream getDisplayPosition and getRealPosition progress clamping math
 * - 90% auto-completion marking and parent resume pointer advancement
 * - Series finale pruning on 90% completion with no next episode
 * - Real AF_UNIX domain sockets and real physical filesystem persistence
 */
class WatchHistoryNormalizationTest {

    private lateinit var socketPath: Path
    private lateinit var testScope: CoroutineScope
    private var serverChannel: ServerSocketChannel? = null
    private var clientChannel: SocketChannel? = null
    private var mpvClient: MpvIpcClient? = null
    private var coordinator: WatchHistoryCoordinator? = null

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        WatchHistoryRepository.clearAll()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socketPath = Path.of("/tmp/cs_test_mpv_norm_${UUID.randomUUID().toString().take(8)}.sock")
        Files.deleteIfExists(socketPath)
    }

    @AfterEach
    fun tearDown() = runBlocking {
        coordinator?.stop()
        mpvClient?.close()
        runCatching { clientChannel?.close() }
        runCatching { serverChannel?.close() }
        Files.deleteIfExists(socketPath)
        testScope.cancel()
        WatchHistoryRepository.clearAll()
    }

    /**
     * Requirement 2: Upstream progress clamping logic verification.
     */
    @Test
    fun testProgressClampingDisplayAndRealMath() {
        val dur = 1_440_000L // 24 minutes in milliseconds

        // 1. Zero or negative duration guards
        assertEquals(0L, getDisplayPosition(500L, 0L))
        assertEquals(0L, getDisplayPosition(500L, -100L))
        assertEquals(0L, getRealPosition(500L, 0L))
        assertEquals(0L, getRealPosition(500L, -100L))

        // 2. Dead-zone (percentage <= 1% -> 0)
        assertEquals(0L, getDisplayPosition(0L, dur))
        assertEquals(0L, getDisplayPosition(10_000L, dur)) // 0.69% -> 0
        assertEquals(0L, getDisplayPosition(14_400L, dur)) // 1.00% -> 0
        assertEquals(0L, getDisplayPosition(15_000L, dur)) // integer division 1500000 / 1440000 = 1% -> 0

        // 3. Visual affordance boost (1% < percentage <= 5% -> 5% of duration)
        val expectedFivePercent = 5L * dur / 100L // 72,000L
        assertEquals(expectedFivePercent, getDisplayPosition(28_800L, dur)) // 2.00% -> 5%
        assertEquals(expectedFivePercent, getDisplayPosition(72_000L, dur)) // 5.00% -> 5%

        // 4. Linear pass-through (5% < percentage < 95% -> position)
        assertEquals(86_400L, getDisplayPosition(86_400L, dur))     // 6.0%
        assertEquals(720_000L, getDisplayPosition(720_000L, dur))   // 50.0%
        assertEquals(1_296_000L, getDisplayPosition(1_296_000L, dur)) // 90.0%

        // 5. Outro snap (percentage >= 95% -> duration)
        assertEquals(dur, getDisplayPosition(1_368_000L, dur)) // 95.0% -> 100%
        assertEquals(dur, getDisplayPosition(1_430_000L, dur)) // 99.3% -> 100%
        assertEquals(dur, getDisplayPosition(dur, dur))        // 100.0% -> 100%

        // 6. getRealPosition: <= 5% or >= 95% return 0, else return position
        assertEquals(0L, getRealPosition(0L, dur))
        assertEquals(0L, getRealPosition(14_400L, dur))
        assertEquals(0L, getRealPosition(72_000L, dur))
        assertEquals(86_400L, getRealPosition(86_400L, dur))
        assertEquals(720_000L, getRealPosition(720_000L, dur))
        assertEquals(1_296_000L, getRealPosition(1_296_000L, dur))
        assertEquals(0L, getRealPosition(1_368_000L, dur))
        assertEquals(0L, getRealPosition(dur, dur))

        // 7. PosDur and WatchHistory helper extension methods
        val posDur = PosDur(position = 28_800L, duration = dur)
        assertEquals(expectedFivePercent, posDur.fixVisual().position)
        assertEquals(0L, posDur.getRealPosition())
        assertEquals(0.05f, posDur.getWatchProgress(), 0.001f)

        val history = WatchHistory(url = "https://test.com/1", position = 720_000L, duration = dur)
        assertEquals(720_000L, history.getDisplayPosition())
        assertEquals(720_000L, history.getRealPosition())
        assertEquals(0.50f, history.getWatchProgress(), 0.001f)
    }

    /**
     * Requirement 1: Rejection of durations under 30 seconds (< 30_000L).
     */
    @Test
    fun testDurationsUnder30SecondsRejected() {
        // Direct repository call with duration < 30s (20,000ms)
        WatchHistoryRepository.setViewPosAndResume(
            parentId = "show_short",
            episodeId = "ep_short",
            positionMs = 10_000L,
            durationMs = 20_000L
        )
        assertNull(
            WatchHistoryRepository.getLastWatched("show_short", "ep_short"),
            "Duration < 30_000L must be rejected by WatchHistoryRepository"
        )

        // Via WatchHistory object with duration < 30_000L (and not matching legacy seconds)
        WatchHistoryRepository.setLastWatched(
            WatchHistory(
                url = "https://short.clip/video.mp4",
                duration = 20L // 20s migrated to 20_000ms -> still < 30_000ms
            )
        )
        assertTrue(
            WatchHistoryRepository.getAllWatchHistory().none { it.url == "https://short.clip/video.mp4" },
            "Short clips under 30 seconds must not be persisted"
        )
    }

    /**
     * Requirement 1 & 4: MPV IPC conversion to milliseconds and physical disk persistence.
     */
    @Test
    fun testMpvIpcMillisecondNormalizationAndDiskPersistence() = runBlocking {
        // Create real UNIX domain socket
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
            configureBlocking(true)
        }

        val client = MpvIpcClient(scope = testScope)
        mpvClient = client

        val mediaUrl = "https://cdn.cloudstream.test/anime/frieren/ep1.mp4"
        val testCoordinator = WatchHistoryCoordinator(
            ipcClient = client,
            scope = testScope,
            currentItem = WatchHistoryCoordinator.WatchHistoryItem(
                url = mediaUrl,
                parentId = "frieren_anime",
                episodeId = "frieren_ep_01",
                title = "The Journey's End",
                episode = 1,
                season = 1,
            )
        )
        coordinator = testCoordinator

        // Start simulated MPV process
        val serverJob = testScope.launch(Dispatchers.IO) {
            val accepted = serverChannel!!.accept()
            clientChannel = accepted
            accepted.configureBlocking(true)

            // Read initial observe commands
            val buf = ByteBuffer.allocate(4096)
            accepted.read(buf)

            // Duration: 1440.0s = 1,440,000ms
            val durPayload = """{"event":"property-change","id":2,"name":"duration","data":1440.0}""" + "\n"
            accepted.write(ByteBuffer.wrap(durPayload.toByteArray(StandardCharsets.UTF_8)))
            delay(50)

            // Position: 720.5s = 720,500ms
            val posPayload = """{"event":"property-change","id":1,"name":"time-pos","data":720.5}""" + "\n"
            accepted.write(ByteBuffer.wrap(posPayload.toByteArray(StandardCharsets.UTF_8)))
            delay(50)

            // Pause to trigger flush
            val pausePayload = """{"event":"property-change","id":3,"name":"pause","data":true}""" + "\n"
            accepted.write(ByteBuffer.wrap(pausePayload.toByteArray(StandardCharsets.UTF_8)))
            delay(50)
        }

        assertTrue(client.connect(socketPath))
        serverJob.join()

        withTimeout(5000) {
            while (client.state.value.positionSec < 720.0) {
                delay(25)
            }
        }

        testCoordinator.flush()

        // Verify that position and duration were normalized to 64-bit milliseconds
        val saved = WatchHistoryRepository.getLastWatched("frieren_anime", "frieren_ep_01")
        assertNotNull(saved)
        assertEquals(720_500L, saved!!.position, "positionSec (720.5s) must be normalized to 720500ms")
        assertEquals(1_440_000L, saved.duration, "durationSec (1440.0s) must be normalized to 1440000ms")
        assertFalse(saved.isCompleted, "50% progress must not be marked completed")

        // Verify physical persistence on disk
        val historyFile = PlatformPaths.dataDir.resolve("history.json").toFile()
        assertTrue(historyFile.exists())
        val text = historyFile.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("720500"), "history.json must contain 720500 ms")
        assertTrue(text.contains("1440000"), "history.json must contain 1440000 ms")
    }

    /**
     * Requirement 3 & 4: 90% auto-completion marking, parent resume pointer advancement, and series finale pruning.
     */
    @Test
    fun test90PercentAutoNextAdvancementAndSeriesFinalePruning() = runBlocking {
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
            configureBlocking(true)
        }

        val client = MpvIpcClient(scope = testScope)
        mpvClient = client

        var autoNextCalledWith: WatchHistoryCoordinator.WatchHistoryItem? = null

        val ep1 = WatchHistoryCoordinator.WatchHistoryItem(
            url = "https://cdn.test/shows/breaking-bad/s1e1.mp4",
            parentId = "show_bb",
            episodeId = "bb_s1e1",
            title = "Pilot",
            episode = 1,
            season = 1,
        )

        val ep2 = WatchHistoryCoordinator.WatchHistoryItem(
            url = "https://cdn.test/shows/breaking-bad/s1e2.mp4",
            parentId = "show_bb",
            episodeId = "bb_s1e2",
            title = "Cat's in the Bag...",
            episode = 2,
            season = 1,
        )

        val testCoordinator = WatchHistoryCoordinator(
            ipcClient = client,
            scope = testScope,
            currentItem = ep1,
            nextItem = ep2,
            onAutoNextTriggered = { next -> autoNextCalledWith = next }
        )
        coordinator = testCoordinator

        val serverJob = testScope.launch(Dispatchers.IO) {
            val accepted = serverChannel!!.accept()
            clientChannel = accepted
            accepted.configureBlocking(true)

            val buf = ByteBuffer.allocate(4096)
            accepted.read(buf)

            // Duration: 2400.0s (40 minutes) = 2,400,000ms
            accepted.write(ByteBuffer.wrap("""{"event":"property-change","id":2,"name":"duration","data":2400.0}"""".toByteArray() + "\n".toByteArray()))
            delay(50)

            // Progress to 90% (2160.0s = 2,160,000ms)
            accepted.write(ByteBuffer.wrap("""{"event":"property-change","id":1,"name":"time-pos","data":2160.0}"""".toByteArray() + "\n".toByteArray()))
            delay(50)

            // Pause
            accepted.write(ByteBuffer.wrap("""{"event":"property-change","id":3,"name":"pause","data":true}"""".toByteArray() + "\n".toByteArray()))
            delay(50)
        }

        assertTrue(client.connect(socketPath))
        serverJob.join()

        withTimeout(5000) {
            while (autoNextCalledWith == null) {
                delay(25)
            }
        }

        // 1. Verify onAutoNextTriggered callback was executed with Episode 2
        assertNotNull(autoNextCalledWith)
        assertEquals("bb_s1e2", autoNextCalledWith?.episodeId)
        assertEquals(2, autoNextCalledWith?.episode)

        testCoordinator.flush()

        // 2. Verify Episode 1 is marked completed and has normalized ms
        val ep1Record = WatchHistoryRepository.getLastWatched("show_bb", "bb_s1e1")
        assertNotNull(ep1Record)
        assertEquals(2_160_000L, ep1Record!!.position)
        assertEquals(2_400_000L, ep1Record.duration)
        assertTrue(ep1Record.isCompleted, "Must be completed when percentage >= 90%")

        // 3. Verify series resume pointer is ADVANCED to Episode 2
        val resumePointer = WatchHistoryRepository.getResumePointer("show_bb")
        assertNotNull(resumePointer, "Resume pointer for show_bb must exist")
        assertEquals("bb_s1e2", resumePointer!!.episodeId, "Resume pointer must point to Episode 2")
        assertEquals(2, resumePointer.episode)
        assertEquals(1, resumePointer.season)

        // 4. Verify physical files on disk
        val resumeFile = PlatformPaths.dataDir.resolve("resume_watching.json").toFile()
        assertTrue(resumeFile.exists())
        val resumeJson = resumeFile.readText(StandardCharsets.UTF_8)
        assertTrue(resumeJson.contains("show_bb"))
        assertTrue(resumeJson.contains("bb_s1e2"))

        // 5. Test Series Finale Pruning (no next episode)
        testCoordinator.currentItem = ep2
        testCoordinator.nextItem = null // finale!

        // Play Episode 2 past 90% (e.g. 91% = 2184.0s / 2400.0s)
        WatchHistoryRepository.setViewPosAndResume(
            parentId = "show_bb",
            episodeId = "bb_s1e2",
            positionMs = 2_184_000L,
            durationMs = 2_400_000L,
            currentEpisode = ep2.toWatchHistory(2_184_000L, 2_400_000L, true),
            nextEpisode = null
        )

        // Verify parent resume pointer is pruned
        val prunedPointer = WatchHistoryRepository.getResumePointer("show_bb")
        assertNull(prunedPointer, "Series finale with no next episode must prune resume pointer from Continue Watching")

        val resumeJsonAfterPrune = resumeFile.readText(StandardCharsets.UTF_8)
        assertFalse(resumeJsonAfterPrune.contains("show_bb"), "resume_watching.json must no longer contain show_bb")
    }

    /**
     * Requirement 1: Self-healing migration for existing databases stored in seconds.
     */
    @Test
    fun testLegacySecondsHeuristicMigration() {
        val legacyRecord = WatchHistory(
            url = "https://cdn.test/movie.mp4",
            parentId = "movie_parent",
            position = 720L,  // 720 seconds (12 minutes)
            duration = 1440L  // 1440 seconds (24 minutes)
        )
        val migrated = WatchHistoryRepository.migrateRecordIfNeeded(legacyRecord)
        assertEquals(720_000L, migrated.position)
        assertEquals(1_440_000L, migrated.duration)

        // Record already in milliseconds is not modified
        val msRecord = WatchHistory(
            url = "https://cdn.test/movie2.mp4",
            position = 720_000L,
            duration = 1_440_000L
        )
        val untouched = WatchHistoryRepository.migrateRecordIfNeeded(msRecord)
        assertEquals(720_000L, untouched.position)
        assertEquals(1_440_000L, untouched.duration)
    }
}
