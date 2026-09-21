package integration

import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.history.WatchHistoryCoordinator
import com.lagradost.player.impl.PlayerLinkHandler
import com.lagradost.player.ipc.MpvIpcClient
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
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
 * End-to-End Tracer Bullet / Walking Skeleton Integration Test:
 * Verifies the full wiring between:
 * 1. EmbeddedPlayerView / MpvEmbeddedSurface (in-process embedded surface, windowId attachment)
 * 2. MpvPlayer / WatchHistoryCoordinator telemetry
 * 3. DataStoreHelper.setViewPosAndResume atomic disk persistence to datastore.json
 * 4. DataStoreHelper.getViewPos resume start position on subsequent playback
 * 5. 90% threshold auto-advance to next episode
 */
class TracerBulletPlaybackIntegrationTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var testDataFile: File
    private lateinit var socketPath: Path
    private var serverChannel: ServerSocketChannel? = null
    private var acceptedClientChannel: SocketChannel? = null

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        DataStoreHelper.selectedKeyIndex = 0
        com.lagradost.common.storage.WatchHistoryRepository.clearAll()

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        socketPath = Path.of("/tmp/cs_tb_test_${UUID.randomUUID().toString().take(12)}.sock")
        Files.deleteIfExists(socketPath)
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            runCatching { acceptedClientChannel?.close() }
            runCatching { serverChannel?.close() }
            Files.deleteIfExists(socketPath)
            testScope.cancel()
            DesktopDataStore.customDataFile = null
            DesktopDataStore.reload()
            com.lagradost.common.storage.WatchHistoryRepository.clearAll()
        }
    }

    @Test
    fun testTracerBulletPlaybackAndDataStoreResumeLifecycle() = runBlocking {
        // 1. Initial State: Episode has no prior watch history
        val episodeUrl = "https://cdn.example.com/anime/frieren/ep01.mp4"
        val episodeData = "ep_frieren_01"
        val episodeIntId = episodeData.toIntOrNull() ?: episodeData.hashCode()
        val parentId = "show_frieren_s1"
        val parentIntId = parentId.toIntOrNull() ?: parentId.hashCode()

        assertNull(
            DataStoreHelper.getViewPos(episodeIntId),
            "Fresh episode must have no saved watch progress in DataStore"
        )

        // 2. Set up MpvPlayer with embedded window handle attachment
        val player = MpvPlayer(scope = testScope)
        val testWid = 987654321L
        player.attachWindow(testWid)
        assertEquals(testWid, player.windowId, "MpvPlayer must be attached to embedded in-process surface (wid=$testWid)")

        var launchedStartSec: Long? = null
        var launchedWid: Long? = null
        player.socketFactory = { socketPath.toFile() }
        player.processLauncher = { _, _, _, startSec, _, _, _, _, _, _, wid ->
            launchedStartSec = startSec
            launchedWid = wid
            ProcessBuilder("sleep", "2").start()
        }

        // Set up real Unix Domain Socket server for MPV IPC communication
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
            configureBlocking(true)
        }

        val acceptJob = testScope.launch {
            val clientChannel = serverChannel!!.accept()
            acceptedClientChannel = clientChannel
        }

        // 3. Connect WatchHistoryCoordinator to DataStoreHelper.setViewPosAndResume
        val historyItem = WatchHistory(
            url = episodeUrl,
            parentId = parentId,
            episodeId = episodeData,
            title = "Frieren Ep 1",
            episode = 1,
            season = 1,
            position = 0L,
            duration = 0L,
        )

        player.historyCoordinator.currentItem = WatchHistoryCoordinator.WatchHistoryItem(
            url = historyItem.url,
            parentId = historyItem.parentId,
            episodeId = historyItem.episodeId,
            title = historyItem.title,
            episode = historyItem.episode,
            season = historyItem.season,
        )

        player.historyCoordinator.onProgressUpdate = { posMs, durMs, isCompleted ->
            DataStoreHelper.setViewPosAndResume(
                id = episodeIntId,
                position = posMs,
                duration = durMs,
                currentEpisode = historyItem,
                nextEpisode = null,
            )
        }

        // 4. Launch initial playback (startPositionMs = 0L)
        val testLink = newExtractorLink(
            source = "TestCDN",
            name = "1080p Stream",
            url = episodeUrl,
            type = ExtractorLinkType.VIDEO,
        )

        player.play(
            link = testLink,
            title = "Frieren: Beyond Journey's End - E1",
            subtitles = emptyList(),
            startPositionMs = 0L,
        )

        acceptJob.join()
        assertNotNull(acceptedClientChannel, "IPC connection must be established")
        assertEquals(0L, launchedStartSec, "Initial playback must launch at 0s")
        assertEquals(testWid, launchedWid, "Playback must launch embedded inside the provided wid")

        // 5. Simulate playback progress: 45s of 150s (45_000ms / 150_000ms)
        // Send MPV IPC property-change telemetry through the real socket
        val propertyChangeJson = """{"event":"property-change","name":"time-pos","data":45.0}""" + "\n" +
                """{"event":"property-change","name":"duration","data":150.0}""" + "\n" +
                """{"event":"property-change","name":"pause","data":true}""" + "\n"

        acceptedClientChannel!!.write(ByteBuffer.wrap(propertyChangeJson.toByteArray(StandardCharsets.UTF_8)))

        // Allow coroutine event loop to process telemetry and flush to DataStoreHelper
        delay(400)

        // 6. Simulate player stop / Esc key dismissal
        player.stop()

        // 7. Verify atomic persistence to datastore.json
        assertTrue(testDataFile.exists(), "datastore.json must physically exist on disk")
        assertTrue(testDataFile.length() > 0, "datastore.json must contain non-empty bytes")

        // 8. Reload DesktopDataStore from disk to prove zero in-memory cheating
        DesktopDataStore.reload()

        val savedPosDur = DataStoreHelper.getViewPos(episodeIntId)
        assertNotNull(savedPosDur, "Saved position must be retrieved from DataStoreHelper.getViewPos")
        assertEquals(45_000L, savedPosDur!!.position, "Saved position must be exactly 45,000ms")
        assertEquals(150_000L, savedPosDur.duration, "Saved duration must be 150,000ms")

        // 9. Second Playback: Resume from saved position
        val resumeStartSec = PlayerLinkHandler.resumeStartSeconds(savedPosDur.position, savedPosDur.duration)
        assertEquals(45L, resumeStartSec, "Calculated resume start seconds must be 45s")
        val resumeStartMs = resumeStartSec * 1000L

        var secondLaunchStartSec: Long? = null
        player.processLauncher = { _, _, _, startSec, _, _, _, _, _, _, _ ->
            secondLaunchStartSec = startSec
            ProcessBuilder("sleep", "1").start()
        }

        player.play(
            link = testLink,
            title = "Frieren: Beyond Journey's End - E1",
            subtitles = emptyList(),
            startPositionMs = resumeStartMs,
        )

        assertEquals(45L, secondLaunchStartSec, "Subsequent playback must resume at exactly 45s")

        // 10. Verify 90% threshold advancement to next episode
        val nextEpisodeData = "ep_frieren_02"
        val nextEpisodeIntId = nextEpisodeData.toIntOrNull() ?: nextEpisodeData.hashCode()
        val nextEpisode = ResultEpisode(
            headerName = "Frieren Ep 2",
            name = "Episode 2",
            poster = null,
            episode = 2,
            season = 1,
            id = nextEpisodeIntId,
            parentId = parentIntId,
            videoWatchState = VideoWatchState.None,
        )

        // Progress at 140s of 150s (>93%)
        DataStoreHelper.setViewPosAndResume(
            id = episodeIntId,
            position = 140_000L,
            duration = 150_000L,
            currentEpisode = historyItem,
            nextEpisode = nextEpisode,
        )

        // Verify that resume pointer advanced to episode 2
        val lastWatched = DataStoreHelper.getLastWatched(parentIntId)
        assertNotNull(lastWatched, "Series last watched entry must exist")
        assertEquals(nextEpisodeIntId, lastWatched!!.episodeId, "Resume pointer must have advanced to episode 2 upon reaching 90%")

        player.destroy()
    }
}
