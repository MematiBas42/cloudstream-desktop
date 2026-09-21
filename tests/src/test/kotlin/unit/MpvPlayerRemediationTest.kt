package unit

import android.content.Context
import android.util.Rational
import androidx.core.net.toUri
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.player.impl.MpvPlayer
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-fidelity unit test suite for Domain 23 / Atomic Cluster C17: MpvPlayer.
 *
 * Verifies:
 * 1. L677-679: Relative seek vs absolute seek (seekTime vs seekTo, seek with seconds to MPV).
 * 2. L826-832: SkipCurrentChapter next episode triggering on skipToNextEpisode and chapter EOF boundary.
 * 3. L720-766: Offline media playback (ExtractorUri local file redirection via PlayerLinkHandler.loadLink).
 * 4. Lifecycle parity (onStop and onPause saving data without terminating the engine process).
 * 5. MPV IPC state transitions (position, pause, eof, embedded tracks).
 */
class MpvPlayerRemediationTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
    }

    // =========================================================================
    // 1. Relative vs Absolute Seek (L677-679)
    // =========================================================================

    @Test
    fun testSeekTimeRelativeCalculation() {
        val player = MpvPlayer()

        // Simulate MPV time-pos at 50 seconds (50_000 ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"time-pos","data":50.0}""")
        assertEquals(50_000L, player.getPosition(), "Initial position should be 50,000ms")

        // seekTime(+15,000ms) -> relative seek: 50,000 + 15,000 = 65,000ms
        player.seekTime(15_000L, PlayerEventSource.UI)
        assertEquals(listOf("seek", 65.0, "absolute"), player.ipcClient.lastSentCommandList)

        // seekTime(-20,000ms) -> relative seek: 50,000 - 20,000 = 30,000ms
        player.seekTime(-20_000L, PlayerEventSource.UI)
        assertEquals(listOf("seek", 30.0, "absolute"), player.ipcClient.lastSentCommandList)

        // seekTime(-80,000ms) -> negative position coerced to 0ms
        player.seekTime(-80_000L, PlayerEventSource.UI)
        assertEquals(listOf("seek", 0.0, "absolute"), player.ipcClient.lastSentCommandList)

        // seekTo(45,000ms) -> absolute seek regardless of current position
        player.seekTo(45_000L, PlayerEventSource.UI)
        assertEquals(listOf("seek", 45.0, "absolute"), player.ipcClient.lastSentCommandList)

        // seekTo(-10,000ms) -> absolute seek coerced to 0ms
        player.seekTo(-10_000L, PlayerEventSource.UI)
        assertEquals(listOf("seek", 0.0, "absolute"), player.ipcClient.lastSentCommandList)

        player.destroy()
    }

    @Test
    fun testHandleEventSeekForwardAndBackParity() {
        val player = MpvPlayer()

        player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"time-pos","data":30.0}""")
        assertEquals(30_000L, player.getPosition())

        // SeekForward -> should seek relative +10,000ms
        player.handleEvent(CSPlayerEvent.SeekForward, PlayerEventSource.UI)
        assertEquals(listOf("seek", 40.0, "absolute"), player.ipcClient.lastSentCommandList)

        // SeekBack -> should seek relative -10,000ms
        player.handleEvent(CSPlayerEvent.SeekBack, PlayerEventSource.UI)
        assertEquals(listOf("seek", 20.0, "absolute"), player.ipcClient.lastSentCommandList)

        // Restart -> should seek absolute to 0ms
        player.handleEvent(CSPlayerEvent.Restart, PlayerEventSource.UI)
        assertEquals(listOf("seek", 0.0, "absolute"), player.ipcClient.lastSentCommandList)

        player.destroy()
    }

    // =========================================================================
    // 2. Chapter Skipping & EOF Boundary Next Episode Trigger (L826-832)
    // =========================================================================

    @Test
    fun testSkipCurrentChapterAtEofTriggersNextEpisode() {
        val player = MpvPlayer()
        val capturedEvents = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ capturedEvents.add(it) })

        // Duration = 100 seconds (100_000ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":2,"name":"duration","data":100.0}""")
        assertEquals(100_000L, player.getDuration())

        // Position = 85 seconds (85_000ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"time-pos","data":85.0}""")

        // Chapter stamp spanning 80_000ms to 100_000ms (ends at video duration / EOF)
        val eofChapter = VideoSkipStamp(
            timestamp = SkipStamp(type = SkipType.MixedAsc, startMs = 80_000L, endMs = 100_000L),
            skipToNextEpisode = false,
            source = "TestEOF"
        )
        player.addTimeStamps(listOf(eofChapter))

        // Trigger SkipCurrentChapter
        player.handleEvent(CSPlayerEvent.SkipCurrentChapter, PlayerEventSource.UI)

        // Since stamp ends at or past duration (100_000 + 1 >= 100_000), it must trigger NextEpisode
        val nextEpisodeEvent = capturedEvents.filterIsInstance<EpisodeSeekEvent>().firstOrNull()
        assertNotNull(nextEpisodeEvent, "Skipping a chapter at EOF must trigger EpisodeSeekEvent")
        assertEquals(1, nextEpisodeEvent?.offset, "Episode offset must be +1 for next episode")
        assertEquals(PlayerEventSource.UI, nextEpisodeEvent?.source)

        val skippedEvent = capturedEvents.filterIsInstance<TimestampSkippedEvent>().firstOrNull()
        assertNotNull(skippedEvent, "TimestampSkippedEvent must be emitted")
        assertEquals(eofChapter, skippedEvent?.timestamp)

        player.destroy()
    }

    @Test
    fun testSkipCurrentChapterWithSkipToNextEpisodeFlag() {
        val player = MpvPlayer()
        val capturedEvents = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ capturedEvents.add(it) })

        // Duration = 200 seconds (200_000ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":2,"name":"duration","data":200.0}""")
        // Position = 160 seconds (160_000ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"time-pos","data":160.0}""")

        // Anime Outro stamp marked with skipToNextEpisode = true (ends well before duration)
        val outroChapter = VideoSkipStamp(
            timestamp = SkipStamp(type = SkipType.Outro, startMs = 150_000L, endMs = 175_000L),
            skipToNextEpisode = true,
            source = "AniSkip"
        )
        player.addTimeStamps(listOf(outroChapter))

        player.handleEvent(CSPlayerEvent.SkipCurrentChapter, PlayerEventSource.UI)

        val nextEpisodeEvent = capturedEvents.filterIsInstance<EpisodeSeekEvent>().firstOrNull()
        assertNotNull(nextEpisodeEvent, "Outro with skipToNextEpisode=true must trigger EpisodeSeekEvent")
        assertEquals(1, nextEpisodeEvent?.offset)

        val skippedEvent = capturedEvents.filterIsInstance<TimestampSkippedEvent>().firstOrNull()
        assertNotNull(skippedEvent)
        assertEquals(outroChapter, skippedEvent?.timestamp)

        player.destroy()
    }

    @Test
    fun testSkipCurrentChapterStandardIntroSeeksToEnd() {
        val player = MpvPlayer()
        val capturedEvents = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ capturedEvents.add(it) })

        // Duration = 300 seconds (300_000ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":2,"name":"duration","data":300.0}""")
        // Position = 15 seconds (15_000ms)
        player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"time-pos","data":15.0}""")

        // Standard intro stamp 10_000ms to 25_000ms
        val introChapter = VideoSkipStamp(
            timestamp = SkipStamp(type = SkipType.Intro, startMs = 10_000L, endMs = 25_000L),
            skipToNextEpisode = false,
            source = "AniSkip"
        )
        player.addTimeStamps(listOf(introChapter))

        player.handleEvent(CSPlayerEvent.SkipCurrentChapter, PlayerEventSource.UI)

        // Must seek to endMs + 1L (25_001ms -> 25.001s)
        assertEquals(listOf("seek", 25.001, "absolute"), player.ipcClient.lastSentCommandList)

        // Must NOT trigger EpisodeSeekEvent
        val nextEpisodeEvent = capturedEvents.filterIsInstance<EpisodeSeekEvent>().firstOrNull()
        assertNull(nextEpisodeEvent, "Standard intro skip must NOT trigger EpisodeSeekEvent")

        val skippedEvent = capturedEvents.filterIsInstance<TimestampSkippedEvent>().firstOrNull()
        assertNotNull(skippedEvent)
        assertEquals(introChapter, skippedEvent?.timestamp)

        player.destroy()
    }

    // =========================================================================
    // 3. Offline Media Playback & Local Media Redirection (L720-766)
    // =========================================================================

    @Test
    fun testPlayerLinkHandlerOfflineAndOnlineValidation() {
        // 1. Local file URI (file://...)
        val localFileLink = ExtractorLink(
            source = "LocalDownload",
            name = "Downloaded Episode 1",
            url = "file:///home/user/Videos/downloaded_ep1.mp4",
            referer = "",
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.VIDEO
        )

        val localResult = PlayerLinkHandler.loadLink(localFileLink, "Offline Episode")
        assertTrue(localResult.isSuccess, "PlayerLinkHandler.loadLink must support file:// URIs")
        val validatedLocal = localResult.getOrThrow()
        assertEquals("/home/user/Videos/downloaded_ep1.mp4", validatedLocal.url, "file:// prefix must be resolved to file path")
        assertEquals(PlayerLinkHandler.StreamKind.PROGRESSIVE, validatedLocal.streamKind)
        assertFalse(validatedLocal.useUrlFile, "Local files must never use url playlist file")

        // 1b. Windows file URI (file:///C:/...)
        val winFileUriLink = ExtractorLink(
            source = "WinFileUri",
            name = "Windows File URI",
            url = "file:///C:/Videos/offline.mp4",
            referer = "",
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.VIDEO
        )
        val winFileResult = PlayerLinkHandler.loadLink(winFileUriLink)
        assertTrue(winFileResult.isSuccess)
        assertEquals("C:/Videos/offline.mp4", winFileResult.getOrThrow().url)

        // 2. Local POSIX absolute path
        val posixLink = ExtractorLink(
            source = "LocalPosix",
            name = "Local Movie",
            url = "/var/media/downloads/movie.mkv",
            referer = "",
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.VIDEO
        )
        val posixResult = PlayerLinkHandler.loadLink(posixLink)
        assertTrue(posixResult.isSuccess, "PlayerLinkHandler must support POSIX paths")
        assertEquals("/var/media/downloads/movie.mkv", posixResult.getOrThrow().url)

        // 3. Local Windows absolute path
        val winLink = ExtractorLink(
            source = "LocalWindows",
            name = "Windows Video",
            url = "C:\\Videos\\offline.mp4",
            referer = "",
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.VIDEO
        )
        val winResult = PlayerLinkHandler.loadLink(winLink)
        assertTrue(winResult.isSuccess, "PlayerLinkHandler must support Windows paths")

        // 4. Online HLS stream
        val hlsLink = ExtractorLink(
            source = "RemoteHls",
            name = "Remote Stream",
            url = "https://cdn.example.com/live/playlist.m3u8",
            referer = "https://example.com",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.M3U8
        )
        val hlsResult = PlayerLinkHandler.loadLink(hlsLink)
        assertTrue(hlsResult.isSuccess)
        assertEquals(PlayerLinkHandler.StreamKind.HLS, hlsResult.getOrThrow().streamKind)
    }

    @Test
    fun testLoadPlayerOfflineExtractorUriRedirection() {
        val player = MpvPlayer()

        val extractorUri = ExtractorUri(
            uri = "file:///tmp/downloaded_video.mp4".toUri(),
            name = "Offline Episode 1",
            displayName = "Offline Ep 1",
            episode = 1,
            season = 1,
            tvType = TvType.TvSeries
        )

        // loadPlayer with link = null, data = extractorUri must synthesize targetLink
        // and route through PlayerLinkHandler.loadLink without throwing
        assertDoesNotThrow {
            player.loadPlayer(
                context = Context(),
                sameEpisode = false,
                link = null,
                data = extractorUri,
                startPosition = 0L,
                subtitles = emptySet(),
                subtitle = null,
                autoPlay = true,
                preview = false
            )
        }

        player.destroy()
    }

    // =========================================================================
    // 4. Lifecycle Parity (onStop & onPause)
    // =========================================================================

    @Test
    fun testOnStopAndOnPauseLifecycleParity() {
        val player = MpvPlayer()
        val capturedEvents = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ capturedEvents.add(it) })

        // 1. Regular video mode
        player.onPause()
        val pauseEvents = capturedEvents.filterIsInstance<PauseEvent>()
        assertEquals(1, pauseEvents.size, "onPause must trigger PauseEvent")

        player.onStop()
        val stopPauseEvents = capturedEvents.filterIsInstance<PauseEvent>()
        assertEquals(2, stopPauseEvents.size, "onStop must trigger PauseEvent without destroying the player")

        // 2. Audio-only mode: onStop / onPause should not pause background audio
        player.handleEvent(CSPlayerEvent.PlayAsAudio, PlayerEventSource.UI)
        assertTrue(player.isAudioOnly(), "Player must be in audio-only mode")

        val countBefore = capturedEvents.filterIsInstance<PauseEvent>().size
        player.onPause()
        player.onStop()
        val countAfter = capturedEvents.filterIsInstance<PauseEvent>().size
        assertEquals(countBefore, countAfter, "onPause and onStop must NOT pause background audio when isAudioOnly is true")

        player.destroy()
    }

    // =========================================================================
    // 5. MPV IPC State Transitions & Event Flow
    // =========================================================================

    @Test
    fun testMpvIpcStateTransitionsAndEvents() {
        val player = MpvPlayer()
        val capturedEvents = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ capturedEvents.add(it) })

        // Duration setup
        player.ipcClient.processIpcLine("""{"event":"property-change","id":2,"name":"duration","data":120.0}""")

        // Position update
        player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"time-pos","data":10.5}""")
        val posEvent = capturedEvents.filterIsInstance<PositionEvent>().lastOrNull()
        assertNotNull(posEvent)
        assertEquals(10_500L, posEvent?.toMs)
        assertEquals(120_000L, posEvent?.durationMs)

        // Pause state
        player.ipcClient.processIpcLine("""{"event":"property-change","id":3,"name":"pause","data":true}""")
        val pauseEvent = capturedEvents.filterIsInstance<PauseEvent>().lastOrNull()
        assertNotNull(pauseEvent)

        // Resume state
        player.ipcClient.processIpcLine("""{"event":"property-change","id":3,"name":"pause","data":false}""")
        val playEvent = capturedEvents.filterIsInstance<PlayEvent>().lastOrNull()
        assertNotNull(playEvent)

        // EOF reached
        player.ipcClient.processIpcLine("""{"event":"property-change","id":4,"name":"eof-reached","data":true}""")
        val endedEvent = capturedEvents.filterIsInstance<VideoEndedEvent>().lastOrNull()
        assertNotNull(endedEvent)

        // Aspect ratio
        player.ipcClient.processIpcLine("""{"event":"property-change","id":10,"name":"dwidth","data":1280}""")
        player.ipcClient.processIpcLine("""{"event":"property-change","id":11,"name":"dheight","data":720}""")
        assertEquals(Rational(1280, 720), player.getAspectRatio())

        player.destroy()
    }

    @Test
    fun testDoubleToRationalCalculations() {
        assertEquals(Rational(16, 9), MpvPlayer.doubleToRational(16.0 / 9.0))
        assertEquals(Rational(4, 3), MpvPlayer.doubleToRational(4.0 / 3.0))
        assertEquals(Rational(21, 9), MpvPlayer.doubleToRational(21.0 / 9.0))
    }
}
