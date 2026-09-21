package integration

import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.WatchHistoryRepository
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
 * Realistic Integration Test according to anti-mock architectural standard (Memory a9b05e13):
 * - Real temporary AF_UNIX domain socket on /tmp/cs_test_mpv_<uuid>.sock
 * - Real Java NIO ServerSocketChannel accepting IPC connection
 * - Real MpvIpcClient connection and message loop
 * - Real WatchHistoryCoordinator tracking progress events
 * - Real atomic disk commits to history.json via WatchHistoryRepository
 */
class RealSocketMpvSyncTest {

    private lateinit var socketPath: Path
    private lateinit var testScope: CoroutineScope
    private var serverChannel: ServerSocketChannel? = null
    private var acceptedClientChannel: SocketChannel? = null
    private var mpvClient: MpvIpcClient? = null
    private var coordinator: WatchHistoryCoordinator? = null

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        WatchHistoryRepository.clearAll()

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socketPath = Path.of("/tmp/cs_test_mpv_${UUID.randomUUID().toString().take(12)}.sock")
        Files.deleteIfExists(socketPath)
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            coordinator?.stop()
            mpvClient?.close()
            runCatching { acceptedClientChannel?.close() }
            runCatching { serverChannel?.close() }
            Files.deleteIfExists(socketPath)
            testScope.cancel()
            WatchHistoryRepository.clearAll()
        }
    }

    @Test
    fun testRealUnixDomainSocketMpvSyncAndWatchHistoryCommit() = runBlocking {
        // 1. Create real temporary Unix Domain Socket on /tmp/cs_test_mpv_<uuid>.sock
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
            configureBlocking(true)
        }
        assertTrue(Files.exists(socketPath), "Unix domain socket file must exist on filesystem at $socketPath")

        // 2. Initialize MpvIpcClient and WatchHistoryCoordinator
        val client = MpvIpcClient(scope = testScope)
        mpvClient = client

        val testMediaUrl = "https://cdn.cloudstream.test/episodes/steins-gate-ep1.mp4"
        val testCoordinator = WatchHistoryCoordinator(
            ipcClient = client,
            scope = testScope,
            currentItem = WatchHistoryCoordinator.WatchHistoryItem(
                url = testMediaUrl,
                parentId = "anime_steins_gate",
                episodeId = "sg_ep_01",
                title = "Prologue of the Beginning and the End",
                episode = 1,
                season = 1,
                posterUrl = "https://cdn.cloudstream.test/posters/sg.jpg"
            )
        )
        coordinator = testCoordinator

        // 3. Start simulated MPV process in background coroutine
        val serverJob = testScope.launch(Dispatchers.IO) {
            val accepted = serverChannel!!.accept()
            acceptedClientChannel = accepted
            accepted.configureBlocking(true)

            // Read the initial observe_property commands emitted by MpvIpcClient
            val receiveBuffer = ByteBuffer.allocate(4096)
            val bytesRead = accepted.read(receiveBuffer)
            assertTrue(bytesRead > 0, "Server must receive initial subscription handshake")
            receiveBuffer.flip()
            val handshakeCommands = StandardCharsets.UTF_8.decode(receiveBuffer).toString()
            assertTrue(
                handshakeCommands.contains("observe_property"),
                "Handshake must include observe_property commands"
            )

            // Send initial duration property change (duration = 1440.0s / 24 mins)
            val durationPayload = """{"event":"property-change","id":2,"name":"duration","data":1440.0}""" + "\n"
            accepted.write(ByteBuffer.wrap(durationPayload.toByteArray(StandardCharsets.UTF_8)))

            delay(60)

            // Stream simulated playback progress events: time-pos = 10, 20, 30... 150
            for (pos in 10..150 step 10) {
                val timePosPayload = """{"event":"property-change","id":1,"name":"time-pos","data":${pos.toDouble()}}""" + "\n"
                accepted.write(ByteBuffer.wrap(timePosPayload.toByteArray(StandardCharsets.UTF_8)))
                delay(35)
            }

            // Send pause event at 150s to trigger instant flush
            val pausePayload = """{"event":"property-change","id":3,"name":"pause","data":true}""" + "\n"
            accepted.write(ByteBuffer.wrap(pausePayload.toByteArray(StandardCharsets.UTF_8)))

            delay(100)
        }

        // 4. Connect MpvIpcClient to the Unix domain socket
        val connected = client.connect(socketPath)
        assertTrue(connected, "MpvIpcClient must successfully connect to AF_UNIX socket at $socketPath")
        assertTrue(client.isConnected, "Client isConnected state must be true")

        // Wait for server simulation to complete
        serverJob.join()

        // Wait for client state to reflect position >= 150.0 seconds
        withTimeout(5000) {
            while (client.state.value.positionSec < 150.0) {
                delay(25)
            }
        }

        assertEquals(150.0, client.state.value.positionSec)
        assertEquals(1440.0, client.state.value.durationSec)

        // Force coordinator flush to guarantee all buffer state is persisted
        testCoordinator.flush()

        // 5. Verify that WatchHistoryCoordinator commits progress = 150_000L (milliseconds) into WatchHistoryRepository
        val historyRecord = WatchHistoryRepository.getLastWatched("anime_steins_gate", "sg_ep_01")
        assertNotNull(historyRecord, "WatchHistory record must exist in WatchHistoryRepository")
        assertEquals(150_000L, historyRecord!!.position, "Committed progress position must equal 150_000L (ms)")
        assertEquals(1_440_000L, historyRecord.duration, "Committed duration must equal 1_440_000L (ms)")
        assertEquals(testMediaUrl, historyRecord.url)
        assertEquals("Prologue of the Beginning and the End", historyRecord.title)
        assertEquals(1, historyRecord.episode)
        assertEquals(1, historyRecord.season)
        assertFalse(historyRecord.isCompleted, "150s / 1440s (~10.4%) must not be marked as completed")

        // 6. Verify real atomic persistence on physical disk
        val historyFile = PlatformPaths.dataDir.resolve("history.json").toFile()
        assertTrue(historyFile.exists(), "history.json must physically exist on disk at ${historyFile.absolutePath}")
        assertTrue(historyFile.length() > 0, "history.json on disk must not be empty")

        val diskContent = historyFile.readText(StandardCharsets.UTF_8)
        assertTrue(diskContent.contains("anime_steins_gate"), "history.json must contain parentId")
        assertTrue(diskContent.contains("sg_ep_01"), "history.json must contain episodeId")
        assertTrue(
            diskContent.contains(""""position" : 150000""") || diskContent.contains(""""position":150000"""),
            "history.json must contain atomically committed progress = 150000L"
        )
    }

    @Test
    fun testCompletionThresholdCommitOnDisk() = runBlocking {
        // Initialize MpvIpcClient and WatchHistoryCoordinator
        val client = MpvIpcClient(scope = testScope)
        mpvClient = client

        val testUrl = "https://cdn.cloudstream.test/movies/cyberpunk.mp4"
        val testCoordinator = WatchHistoryCoordinator(
            ipcClient = client,
            scope = testScope,
            currentItem = WatchHistoryCoordinator.WatchHistoryItem(
                url = testUrl,
                title = "Cyberpunk 2077 Movie",
            )
        )
        coordinator = testCoordinator

        // Simulate 92% completion directly via IPC lines (920s / 1000s)
        client.processIpcLine("""{"event":"property-change","name":"duration","data":1000.0}""")
        client.processIpcLine("""{"event":"property-change","name":"time-pos","data":920.0}""")
        client.processIpcLine("""{"event":"property-change","name":"pause","data":true}""")

        testCoordinator.flush()

        // Verify that record was committed with isCompleted = true
        val record = WatchHistoryRepository.getAllWatchHistory().firstOrNull { it.url == testUrl }
        assertNotNull(record)
        assertEquals(920_000L, record!!.position)
        assertEquals(1_000_000L, record.duration)
        assertTrue(record.isCompleted, "920s / 1000s (92%) must be marked as completed")

        // Verify disk content
        val historyFile = PlatformPaths.dataDir.resolve("history.json").toFile()
        assertTrue(historyFile.exists())
        val diskContent = historyFile.readText(StandardCharsets.UTF_8)
        assertTrue(diskContent.contains(""""isCompleted" : true""") || diskContent.contains(""""isCompleted":true"""))
    }
}
