package unit

import android.content.Context
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Comprehensive Unit Test Suite for Domain 23 / Atomic Cluster C18: CS3IPlayer.
 *
 * Validates:
 * 1. 1:1 IPlayer contract parity and lifecycle management.
 * 2. Track-list parsing and embedded subtitle/audio extraction emitting EmbeddedSubtitlesFetchedEvent,
 *    SubtitlesUpdatedEvent, and TracksChangedEvent.
 * 3. TorrServerClient speed and peer metrics wired into torrentEventLooper emitting DownloadEvent.
 * 4. Torrent looper cancellation on index increment or player release.
 * 5. Media navigation, timestamp detection, skip events, and audio/subtitle track selection.
 */
class CS3IPlayerRemediationTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        TorrServerClient.resetClient()
    }

    @AfterEach
    fun tearDown() {
        TorrServerClient.resetClient()
    }

    // =========================================================================
    // 1. Contract & Lifecycle Parity
    // =========================================================================

    @Test
    fun `test CS3IPlayer contract defaults and lifecycle`() {
        val player = CS3IPlayer()
        assertTrue(player is IPlayer, "CS3IPlayer must implement IPlayer interface")

        assertEquals(1.0f, player.getPlaybackSpeed())
        assertFalse(player.getIsPlaying())
        assertNull(player.getDuration())
        assertNull(player.getPosition())
        assertEquals(0L, player.getSubtitleOffset())
        assertFalse(player.isPlayerActive)

        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })
        assertTrue(player.isPlayerActive, "Player must be active after initCallbacks")

        player.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, player.getPlaybackSpeed())

        player.setSubtitleOffset(500L)
        assertEquals(500L, player.getSubtitleOffset())

        player.releaseCallbacks()
        assertFalse(player.isPlayerActive, "Player must be inactive after releaseCallbacks")
    }

    // =========================================================================
    // 2. Track-List Parsing & Embedded Subtitle / Audio Events
    // =========================================================================

    @Test
    fun `test onTracksChanged parses embedded subtitles and emits expected events`() {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        val videoTracks = listOf(
            VideoTrack(id = "1:0", label = "1080p", language = "und", width = 1920, height = 1080, sampleMimeType = "video/mp4")
        )
        val audioTracks = listOf(
            AudioTrack(id = "2:1", label = "English Audio", language = "eng", sampleMimeType = "audio/aac", channelCount = 2, formatIndex = 0),
            AudioTrack(id = "2:2", label = "Japanese Audio", language = "jpn", sampleMimeType = "audio/aac", channelCount = 2, formatIndex = 1)
        )
        val textTracks = listOf(
            TextTrack(id = "3:10", label = "English Full", language = "en", sampleMimeType = "application/x-subrip"),
            TextTrack(id = "3:11", label = "Turkish Signs", language = "tr", sampleMimeType = "application/x-subrip"),
            TextTrack(id = "3:12", label = "Invalid Lang", language = "-sub", sampleMimeType = "application/x-subrip"),
            TextTrack(id = null, label = "No ID", language = "fr", sampleMimeType = "application/x-subrip")
        )

        player.onTracksChanged(
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            textTracks = textTracks,
            selectedVideoTrack = videoTracks[0],
            selectedAudioTrack = audioTracks[0]
        )

        // 1. Verify EmbeddedSubtitlesFetchedEvent
        val embeddedEvents = events.filterIsInstance<EmbeddedSubtitlesFetchedEvent>()
        assertEquals(1, embeddedEvents.size, "EmbeddedSubtitlesFetchedEvent must be emitted exactly once")
        val fetchedSubs = embeddedEvents[0].tracks
        assertEquals(2, fetchedSubs.size, "Only valid text tracks (excluding null id and '-' prefix) should be emitted")

        val sub1 = fetchedSubs[0]
        assertEquals("English", sub1.originalName)
        assertEquals("English Full", sub1.nameSuffix)
        assertEquals("10", sub1.url, "Track ID prefix 3: must be stripped")
        assertEquals(SubtitleOrigin.EMBEDDED_IN_VIDEO, sub1.origin)
        assertEquals("en", sub1.languageCode)

        val sub2 = fetchedSubs[1]
        assertEquals("Turkish", sub2.originalName)
        assertEquals("Turkish Signs", sub2.nameSuffix)
        assertEquals("11", sub2.url, "Track ID prefix 3: must be stripped")
        assertEquals(SubtitleOrigin.EMBEDDED_IN_VIDEO, sub2.origin)
        assertEquals("tr", sub2.languageCode)

        // 2. Verify SubtitlesUpdatedEvent and TracksChangedEvent
        val subUpdateEvents = events.filterIsInstance<SubtitlesUpdatedEvent>()
        assertEquals(1, subUpdateEvents.size, "SubtitlesUpdatedEvent must be emitted")

        val tracksChangedEvents = events.filterIsInstance<TracksChangedEvent>()
        assertEquals(1, tracksChangedEvents.size, "TracksChangedEvent must be emitted")

        // 3. Verify CurrentTracks populated
        val currentTracks = player.getVideoTracks()
        assertNotNull(currentTracks.currentVideoTrack)
        assertEquals(1920, currentTracks.currentVideoTrack?.width)
        assertEquals("eng", currentTracks.currentAudioTrack?.language)
        assertEquals(2, currentTracks.allAudioTracks.size)
        assertEquals(4, currentTracks.allTextTracks.size)
    }

    @Test
    fun `test onTrackListReceived transforms raw track descriptors into typed tracks`() {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        val descriptors = listOf(
            TrackDescriptor(id = "0:1", type = "video", label = "Source", language = "und", isSelected = true, width = 3840, height = 2160),
            TrackDescriptor(id = "1:2", type = "audio", label = "Surround 5.1", language = "eng", isSelected = true, channelCount = 6),
            TrackDescriptor(id = "1:3", type = "audio", label = "Stereo 2.0", language = "spa", isSelected = false, channelCount = 2),
            TrackDescriptor(id = "2:4", type = "sub", label = "Spanish CC", language = "es", isSelected = true),
            TrackDescriptor(id = "2:5", type = "text", label = "French", language = "fr", isSelected = false)
        )

        player.onTrackListReceived(descriptors)

        val embeddedEvents = events.filterIsInstance<EmbeddedSubtitlesFetchedEvent>()
        assertEquals(1, embeddedEvents.size)
        assertEquals(2, embeddedEvents[0].tracks.size)

        val tracks = player.getVideoTracks()
        assertEquals(1, tracks.allVideoTracks.size)
        assertEquals(2, tracks.allAudioTracks.size)
        assertEquals(2, tracks.allTextTracks.size)
        assertEquals("Spanish", embeddedEvents[0].tracks[0].originalName)
        assertEquals("French", embeddedEvents[0].tracks[1].originalName)

        assertEquals("Spanish CC", tracks.currentTextTracks.firstOrNull()?.label)
    }

    @Test
    fun `test setPreferredAudioTrack and setPreferredSubtitles update state and fire events`() {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        val audioTracks = listOf(
            AudioTrack(id = "1", label = "English", language = "en", sampleMimeType = "audio/mp4a", channelCount = 2, formatIndex = 0),
            AudioTrack(id = "2", label = "German", language = "de", sampleMimeType = "audio/mp4a", channelCount = 2, formatIndex = 1)
        )
        val textTracks = listOf(
            TextTrack(id = "10", label = "German Sub", language = "de", sampleMimeType = "application/x-subrip")
        )

        player.onTracksChanged(audioTracks = audioTracks, textTracks = textTracks)
        events.clear()

        // Select German audio track
        player.setPreferredAudioTrack(trackLanguage = "de", id = "2")
        assertEquals("de", player.selectedAudioTrackLanguage)
        assertEquals("2", player.selectedAudioTrackId)
        assertEquals("2", player.getVideoTracks().currentAudioTrack?.id)
        assertTrue(events.any { it is TracksChangedEvent }, "TracksChangedEvent must be emitted on audio track change")

        events.clear()

        // Select subtitle track
        val germanSub = SubtitleData(
            originalName = "German",
            nameSuffix = "German Sub",
            url = "10",
            origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "de"
        )
        val requiresReload = player.setPreferredSubtitles(germanSub)
        assertFalse(requiresReload, "Desktop player does not require player reload on subtitle change")
        assertEquals(germanSub, player.getCurrentPreferredSubtitle())
        assertTrue(events.any { it is SubtitlesUpdatedEvent }, "SubtitlesUpdatedEvent must be emitted")
        assertTrue(events.any { it is TracksChangedEvent }, "TracksChangedEvent must be emitted")
    }

    // =========================================================================
    // 3. TorrServerClient Speed & Peer Metrics in torrentEventLooper
    // =========================================================================

    @Test
    fun `test torrentEventLooper queries TorrServerClient and emits DownloadEvent with speed and peer metrics`() = runBlocking {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        val fakeStatus = Torrent.TorrentStatus(
            title = "Test Movie",
            poster = "",
            data = null,
            timestamp = System.currentTimeMillis(),
            name = "Test.Movie.2026.1080p.mkv",
            hash = "aabbccddeeff112233445566",
            stat = 2,
            statString = "downloading",
            loadedSize = 524288000L,
            torrentSize = 1073741824L,
            preloadedBytes = 0L,
            preloadSize = 0L,
            downloadSpeed = 2621440.0, // 2.5 MB/s
            uploadSpeed = 524288.0,
            totalPeers = 60,
            pendingPeers = 5,
            activePeers = 35,
            connectedSeeders = 12,
            halfOpenPeers = 2,
            bytesWritten = null,
            bytesWrittenData = null,
            bytesRead = 524288000L,
            bytesReadData = null,
            bytesReadUsefulData = null,
            chunksWritten = null,
            chunksRead = null,
            chunksReadUseful = null,
            chunksReadWasted = null,
            piecesDirtiedGood = null,
            piecesDirtiedBad = null,
            durationSeconds = 120.0,
            bitRate = null,
            fileStats = null,
            trackers = null
        )

        var queryCount = 0
        TorrServerClient.setClient(object : TorrServerClient {
            override suspend fun getStats(hash: String): Torrent.TorrentStatus {
                queryCount++
                assertEquals("aabbccddeeff112233445566", hash)
                return fakeStatus
            }
        })

        player.torrentEventLooper("aabbccddeeff112233445566")

        // Wait briefly for at least one emission
        var downloadEvents = events.filterIsInstance<DownloadEvent>()
        var attempts = 0
        while (downloadEvents.isEmpty() && attempts < 25) {
            delay(100)
            downloadEvents = events.filterIsInstance<DownloadEvent>()
            attempts++
        }

        assertTrue(downloadEvents.isNotEmpty(), "At least one DownloadEvent must be emitted by torrentEventLooper")
        val downloadEvent = downloadEvents.first()

        assertEquals(35, downloadEvent.connections, "Active peers (35) must be wired into connections")
        assertEquals(2621440L, downloadEvent.downloadSpeed, "Download speed must be converted to Long bytes/sec")
        assertEquals(1073741824L, downloadEvent.totalBytes, "Torrent size must match")
        assertEquals(524288000L, downloadEvent.downloadedBytes, "Bytes read must match")
        assertEquals(PlayerEventSource.Player, downloadEvent.source)
        assertTrue(queryCount >= 1, "TorrServerClient.getStats must have been queried")

        // Release player to terminate looper
        player.release()
        val countAfterRelease = events.filterIsInstance<DownloadEvent>().size
        delay(1200)
        val countAfterDelay = events.filterIsInstance<DownloadEvent>().size
        assertEquals(countAfterRelease, countAfterDelay, "Looper must cease emitting events once player is released")
    }

    @Test
    fun `test torrentEventLooper fallback metrics when activePeers or bytesRead are null`() = runBlocking {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        val fallbackStatus = Torrent.TorrentStatus(
            title = "Fallback Torrent",
            poster = "",
            data = null,
            timestamp = System.currentTimeMillis(),
            name = "Fallback.mkv",
            hash = "11223344556677889900aabb",
            stat = 1,
            statString = "connecting",
            loadedSize = 1048576L,
            torrentSize = 500000000L,
            preloadedBytes = null,
            preloadSize = null,
            downloadSpeed = 1048576.0,
            uploadSpeed = null,
            totalPeers = 20,
            pendingPeers = null,
            activePeers = null, // null activePeers -> fallback to totalPeers
            connectedSeeders = null,
            halfOpenPeers = null,
            bytesWritten = null,
            bytesWrittenData = null,
            bytesRead = null, // null bytesRead -> fallback to loadedSize
            bytesReadData = null,
            bytesReadUsefulData = null,
            chunksWritten = null,
            chunksRead = null,
            chunksReadUseful = null,
            chunksReadWasted = null,
            piecesDirtiedGood = null,
            piecesDirtiedBad = null,
            durationSeconds = null,
            bitRate = null,
            fileStats = null,
            trackers = null
        )

        TorrServerClient.setClient(object : TorrServerClient {
            override suspend fun getStats(hash: String): Torrent.TorrentStatus = fallbackStatus
        })

        player.torrentEventLooper("11223344556677889900aabb")

        var downloadEvents = events.filterIsInstance<DownloadEvent>()
        var attempts = 0
        while (downloadEvents.isEmpty() && attempts < 25) {
            delay(100)
            downloadEvents = events.filterIsInstance<DownloadEvent>()
            attempts++
        }

        assertTrue(downloadEvents.isNotEmpty())
        val event = downloadEvents.first()
        assertEquals(20, event.connections, "When activePeers is null, must fallback to totalPeers")
        assertEquals(1048576L, event.downloadedBytes, "When bytesRead is null, must fallback to loadedSize")

        player.releaseCallbacks()
    }

    // =========================================================================
    // 4. Media Navigation, Timestamps & Playback Events
    // =========================================================================

    @Test
    fun `test handleEvent dispatching for Play, Pause, Seek, and Episodes`() {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        // Play
        player.handleEvent(CSPlayerEvent.Play)
        assertTrue(player.getIsPlaying())
        assertTrue(events.any { it is PlayEvent })

        // Pause
        player.handleEvent(CSPlayerEvent.Pause)
        assertFalse(player.getIsPlaying())
        assertTrue(events.any { it is PauseEvent })

        // Episode seek forward (+1) and backward (-1)
        player.handleEvent(CSPlayerEvent.NextEpisode)
        val nextEvents = events.filterIsInstance<EpisodeSeekEvent>()
        assertEquals(1, nextEvents.last().offset)

        player.handleEvent(CSPlayerEvent.PrevEpisode)
        val prevEvents = events.filterIsInstance<EpisodeSeekEvent>()
        assertEquals(-1, prevEvents.last().offset)
    }

    @Test
    fun `test video skip timestamps invoke and skip properly`() {
        val player = CS3IPlayer()
        val events = CopyOnWriteArrayList<PlayerEvent>()
        player.initCallbacks({ events.add(it) })

        val stamp = VideoSkipStamp(
            startMs = 10000L,
            endMs = 25000L,
            uiTitle = "Intro"
        )
        player.addTimeStamps(listOf(stamp))

        // Update position inside timestamp window
        player.updatePosition(posMs = 15000L, durationMs = 120000L)
        val invokedEvents = events.filterIsInstance<TimestampInvokedEvent>()
        assertEquals(1, invokedEvents.size, "TimestampInvokedEvent must be emitted when entering timestamp window")
        assertEquals("Intro", invokedEvents[0].timestamp.uiTitle)

        // Trigger SkipCurrentChapter
        player.handleEvent(CSPlayerEvent.SkipCurrentChapter)
        assertEquals(25001L, player.getPosition(), "Skip must advance position to stamp.endMs + 1L")

        val skippedEvents = events.filterIsInstance<TimestampSkippedEvent>()
        assertEquals(1, skippedEvents.size, "TimestampSkippedEvent must be emitted")
        assertEquals(25000L, skippedEvents[0].timestamp.endMs)
    }
}
