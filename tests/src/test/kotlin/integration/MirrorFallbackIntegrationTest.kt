package integration

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.player.impl.MirrorFallbackCoordinator
import com.lagradost.player.impl.MpvPlayer
import com.lagradost.player.impl.PlayerError
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
 * Domain 20: Real AF_UNIX Socket Integration Test for In-Flight Mirror Fallback & Auto-Recovery Engine.
 *
 * Anti-mock architectural verification:
 * - Real temporary AF_UNIX domain socket on /tmp/cs_test_fallback_<uuid>.sock
 * - Real Java NIO ServerSocketChannel accepting IPC connections
 * - Real MpvIpcClient connection, telemetry streaming, and JSON-RPC error dispatching
 * - Simulates stream failure on Mirror 1 at timestamp 45.0s
 * - Asserts that MirrorFallbackCoordinator selects Mirror 2 and launches playback with startPositionMs = 45000L
 * - Asserts that PlayerError.NoMirrorsRemaining is surfaced when all candidate mirrors are exhausted
 */
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
class MirrorFallbackIntegrationTest {

    private lateinit var socketPath: Path
    private lateinit var testScope: CoroutineScope
    private var serverChannel: ServerSocketChannel? = null
    private var acceptedClientChannel: SocketChannel? = null
    private var player: MpvPlayer? = null

    private fun createLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
    ): ExtractorLink {
        val link = ExtractorLink(
            source = source,
            name = name,
            url = url,
            type = ExtractorLinkType.VIDEO,
        )
        link.referer = referer
        link.quality = quality
        return link
    }

    private val mirror1 = createLink(
        source = "StreamTape",
        name = "StreamTape Mirror 1",
        url = "https://cdn.streamtape.test/video1.mp4",
        referer = "https://streamtape.test",
        quality = Qualities.P1080.value,
    )

    private val mirror2 = createLink(
        source = "DoodStream",
        name = "DoodStream Mirror 2",
        url = "https://cdn.doodstream.test/video2.mp4",
        referer = "https://doodstream.test",
        quality = Qualities.P720.value,
    )

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socketPath = Path.of("/tmp/cs_test_fallback_${UUID.randomUUID().toString().take(12)}.sock")
        Files.deleteIfExists(socketPath)

        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
            configureBlocking(true)
        }

        val p = MpvPlayer(testScope)
        p.socketFactory = { socketPath.toFile() }
        p.processLauncher = { _, _, _, _, _, _, _, _, _, _, _ ->
            ProcessBuilder("sleep", "60").start()
        }
        player = p
    }

    @AfterEach
    fun tearDown() = runBlocking {
        player?.stop()
        runCatching { acceptedClientChannel?.close() }
        runCatching { serverChannel?.close() }
        Files.deleteIfExists(socketPath)
        testScope.cancel()
    }

    @Test
    fun testInFlightMirrorFallbackOnStreamFailureAt45Seconds() = runBlocking {
        val mpvPlayer = player!!

        // 1. Initialize candidate mirror pool with Mirror 1 and Mirror 2
        mpvPlayer.mirrorCoordinator.reset(listOf(mirror1, mirror2))
        assertTrue(mpvPlayer.mirrorCoordinator.hasNextMirror(), "Coordinator must have an alternate mirror")
        assertEquals(mirror1.url, mpvPlayer.mirrorCoordinator.currentLink?.url, "Initial mirror must be Mirror 1")

        // 2. Start simulated MPV server handling connections
        val serverJob = testScope.launch(Dispatchers.IO) {
            try {
                // Accept Client 1 (for Mirror 1)
                val client1 = serverChannel!!.accept().apply { configureBlocking(true) }
                acceptedClientChannel = client1

                val handshakeBuf = ByteBuffer.allocate(4096)
                client1.read(handshakeBuf)

                // Simulate active playback on Mirror 1 progressing to timestamp 45.0s
                val timePosPayload = """{"event":"property-change","id":1,"name":"time-pos","data":45.0}""" + "\n"
                client1.write(ByteBuffer.wrap(timePosPayload.toByteArray(StandardCharsets.UTF_8)))
                delay(100)

                // Simulate fatal stream failure on Mirror 1 (e.g. HTTP 403 Forbidden / demuxer failure)
                val errorPayload = """{"event":"end-file","reason":"error","error":"loading failed","file_error":"Server returned 403 Forbidden"}""" + "\n"
                client1.write(ByteBuffer.wrap(errorPayload.toByteArray(StandardCharsets.UTF_8)))

                // Accept Client 2 (for Mirror 2 recovery launch)
                val client2 = serverChannel!!.accept().apply { configureBlocking(true) }
                val handshakeBuf2 = ByteBuffer.allocate(4096)
                client2.read(handshakeBuf2)
            } catch (_: Exception) {
                // Expected when test completes and server channel is closed/cancelled
            }
        }

        // 3. Launch playback for Mirror 1
        val playResult = mpvPlayer.play(
            link = mirror1,
            title = "Test Media Episode 1",
            subtitles = emptyList(),
            startPositionMs = 0L,
        )
        assertTrue(playResult.isSuccess, "Initial play() must succeed")

        // 4. Wait for player state to record playback at 45.0s and then execute in-flight failover
        withTimeout(8000) {
            while (mpvPlayer.mirrorCoordinator.currentLink?.url != mirror2.url) {
                delay(25)
            }
        }

        // 5. Assert that MirrorFallbackCoordinator selected Mirror 2 and errored Mirror 1
        assertEquals(mirror2.url, mpvPlayer.mirrorCoordinator.currentLink?.url, "Coordinator must advance to Mirror 2")
        assertTrue(mpvPlayer.mirrorCoordinator.erroredLinks.contains(mirror1.url), "Mirror 1 must be recorded in erroredLinks")
        assertFalse(mpvPlayer.mirrorCoordinator.hasNextMirror(), "No further mirrors should remain after Mirror 2")

        // 6. Assert that MpvPlayer launched playback for Mirror 2 with exact startPositionMs = 45000L
        val lastCall = mpvPlayer.lastPlayCall
        assertNotNull(lastCall, "play() must be invoked for Mirror 2")
        assertEquals(mirror2.url, lastCall!!.link.url, "Playback must be launched with Mirror 2")
        assertEquals(45000L, lastCall.startPositionMs, "Playback must resume at exact timestamp 45000L (45.0s)")

        serverJob.cancel()
    }

    @Test
    fun testAllMirrorsExhaustedSurfacesPlayerErrorNoMirrorsRemaining() = runBlocking {
        val mpvPlayer = player!!

        // Initialize candidate pool with only Mirror 1
        mpvPlayer.mirrorCoordinator.reset(listOf(mirror1))
        assertFalse(mpvPlayer.mirrorCoordinator.hasNextMirror(), "Single mirror pool must have no next mirror")

        var capturedError: PlayerError? = null
        mpvPlayer.onPlayerError = { capturedError = it }

        val serverJob = testScope.launch(Dispatchers.IO) {
            try {
                val client = serverChannel!!.accept().apply { configureBlocking(true) }
                val buf = ByteBuffer.allocate(4096)
                client.read(buf)

                // Simulate immediate failure
                val errorPayload = """{"event":"end-file","reason":"error","error":"loading failed","file_error":"HTTP 404 Not Found"}""" + "\n"
                client.write(ByteBuffer.wrap(errorPayload.toByteArray(StandardCharsets.UTF_8)))
            } catch (_: Exception) {
                // Expected when test completes and server channel is closed/cancelled
            }
        }

        mpvPlayer.play(
            link = mirror1,
            title = "Test Episode",
            subtitles = emptyList(),
            startPositionMs = 0L,
        )

        // Wait for all mirrors to be exhausted and error to be surfaced
        withTimeout(5000) {
            while (mpvPlayer.playerError.value == null && capturedError == null) {
                delay(25)
            }
        }

        assertEquals(PlayerError.NoMirrorsRemaining, mpvPlayer.playerError.value)
        assertEquals(PlayerError.NoMirrorsRemaining, capturedError)
        assertTrue(mpvPlayer.mirrorCoordinator.erroredLinks.contains(mirror1.url))

        serverJob.cancel()
    }

    @Test
    fun testCoordinatorStandaloneLogic() {
        val coordinator = MirrorFallbackCoordinator()

        // 1. Initially empty
        assertFalse(coordinator.hasNextMirror())
        assertNull(coordinator.nextMirror())

        // 2. Reset with 3 mirrors
        val mirror3 = createLink(
            source = "Vidcloud",
            name = "Vidcloud Mirror 3",
            url = "https://cdn.vidcloud.test/video3.mp4",
            referer = "https://vidcloud.test",
            quality = Qualities.P480.value,
        )

        coordinator.reset(listOf(mirror1, mirror2, mirror3))
        assertEquals(3, coordinator.candidateMirrors.size)
        assertEquals(mirror1.url, coordinator.currentLink?.url)
        assertTrue(coordinator.hasNextMirror())

        // 3. Advance to Mirror 2
        val next1 = coordinator.nextMirror()
        assertNotNull(next1)
        assertEquals(mirror2.url, next1!!.url)
        assertEquals(mirror2.url, coordinator.currentLink?.url)
        assertTrue(coordinator.erroredLinks.contains(mirror1.url))
        assertTrue(coordinator.hasNextMirror())

        // 4. Advance to Mirror 3
        val next2 = coordinator.nextMirror()
        assertNotNull(next2)
        assertEquals(mirror3.url, next2!!.url)
        assertEquals(mirror3.url, coordinator.currentLink?.url)
        assertTrue(coordinator.erroredLinks.contains(mirror2.url))
        assertFalse(coordinator.hasNextMirror())

        // 5. Exhausted
        val next3 = coordinator.nextMirror()
        assertNull(next3)
        assertTrue(coordinator.erroredLinks.contains(mirror3.url))
    }
}
