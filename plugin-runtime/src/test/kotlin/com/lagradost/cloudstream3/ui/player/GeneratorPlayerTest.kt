package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Rational
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GeneratorPlayerTest {

    private lateinit var fakePlayer: FakePlayer
    private lateinit var generatorPlayer: GeneratorPlayer
    private lateinit var episodes: List<ResultEpisode>
    private lateinit var testGenerator: TestVideoGenerator

    class FakePlayer : IPlayer {
        var isPlaying = false
        var durationMs: Long = 100_000L
        var positionMs: Long = 0L
        var eventHandler: ((PlayerEvent) -> Unit)? = null
        var loadedLink: ExtractorLink? = null
        var startPosition: Long? = null
        var preferredSubtitles: SubtitleData? = null
        var timeStamps: List<VideoSkipStamp> = emptyList()
        val seekedPositions = mutableListOf<Long>()
        var previewEnabled: Boolean = false
        var mockPreviewBitmap: Bitmap? = null
        var mockAspectRatio: Rational? = null
        private var _subtitleCues: List<SubtitleCue> = emptyList()
        var mockSubtitleCues: List<SubtitleCue> get() = _subtitleCues; set(value) { _subtitleCues = value }
        var isAudioOnly: Boolean = false
        private var _playbackSpeed = 1.0f
        private var _subtitleOffset = 0L
        private var _activeSubtitles: Set<SubtitleData> = emptySet()

        override fun getPlaybackSpeed(): Float = _playbackSpeed
        override fun setPlaybackSpeed(speed: Float) { _playbackSpeed = speed }
        override fun getIsPlaying(): Boolean = isPlaying
        override fun getDuration(): Long? = durationMs
        override fun getPosition(): Long? = positionMs
        override fun seekTime(time: Long, source: PlayerEventSource) { positionMs = time; seekedPositions.add(time) }
        override fun seekTo(time: Long, source: PlayerEventSource) { positionMs = time; seekedPositions.add(time) }
        override fun getSubtitleOffset(): Long = _subtitleOffset
        override fun setSubtitleOffset(offset: Long) { _subtitleOffset = offset }
        override fun initCallbacks(eventHandler: ((PlayerEvent) -> Unit), requestedListeningPercentages: List<Int>?) {
            this.eventHandler = eventHandler
        }
        override fun releaseCallbacks() { this.eventHandler = null }
        override fun updateSubtitleStyle(style: SaveCaptionStyle) {}
        override fun saveData() {}
        override fun addTimeStamps(timeStamps: List<VideoSkipStamp>) { this.timeStamps = timeStamps }
        override fun loadPlayer(
            context: Context,
            sameEpisode: Boolean,
            link: ExtractorLink?,
            data: ExtractorUri?,
            startPosition: Long?,
            subtitles: Set<SubtitleData>,
            subtitle: SubtitleData?,
            autoPlay: Boolean?,
            preview: Boolean
        ) {
            this.loadedLink = link
            this.startPosition = startPosition
            this._activeSubtitles = subtitles
            this.preferredSubtitles = subtitle
            this.isPlaying = autoPlay == true
            this.previewEnabled = preview
            if (preview && (link != null || data != null)) {
                this.mockPreviewBitmap = Bitmap.createBitmap(160, 90)
            }
        }
        override fun reloadPlayer(context: Context) {}
        override fun getPreview(fraction: Float): Bitmap? = if (hasPreview()) mockPreviewBitmap else null
        override fun hasPreview(): Boolean = previewEnabled && mockPreviewBitmap != null
        override fun setActiveSubtitles(subtitles: Set<SubtitleData>) { this._activeSubtitles = subtitles }
        override fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean { this.preferredSubtitles = subtitle; return false }
        override fun getCurrentPreferredSubtitle(): SubtitleData? = preferredSubtitles
        override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
            when (event) {
                CSPlayerEvent.Play -> isPlaying = true
                CSPlayerEvent.Pause -> isPlaying = false
                CSPlayerEvent.PlayAsAudio -> isAudioOnly = true
                CSPlayerEvent.NextEpisode -> eventHandler?.invoke(EpisodeSeekEvent(1, source))
                CSPlayerEvent.PrevEpisode -> eventHandler?.invoke(EpisodeSeekEvent(-1, source))
                else -> Unit
            }
        }
        override fun onStop() { isPlaying = false }
        override fun onPause() { isPlaying = false }
        override fun onResume(context: Context) { isPlaying = true }
        override fun release() {}
        override fun isActive(): Boolean = isPlaying
        override fun getVideoTracks(): CurrentTracks = CurrentTracks(null, null, emptyList(), emptyList(), emptyList(), emptyList())
        override fun getAspectRatio(): Rational? = if (loadedLink != null) mockAspectRatio ?: Rational(16, 9) else null
        override fun setMaxVideoSize(width: Int, height: Int, id: String?) {}
        override fun setPreferredAudioTrack(trackLanguage: String?, id: String?, formatIndex: Int?) {}
        override fun getSubtitleCues(): List<SubtitleCue> = _subtitleCues
    }

    class TestVideoGenerator(episodes: List<ResultEpisode>) : VideoGenerator<ResultEpisode>(episodes) {
        override val hasCache: Boolean = true
        override val canSkipLoading: Boolean = false
        override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

        var generatedLinksCount = 0
        val preloadedOffsets = mutableListOf<Int>()

        override suspend fun generateLinks(
            clearCache: Boolean,
            sourceTypes: Set<ExtractorLinkType>,
            callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
            subtitleCallback: (SubtitleData) -> Unit,
            offset: Int,
            isCasting: Boolean
        ): Boolean {
            generatedLinksCount++
            preloadedOffsets.add(offset)
            callback(
                Pair(
                    ExtractorLink(
                        source = "TestMirror1",
                        name = "TestMirror1",
                        url = "http://test.com/stream1_${offset}.mp4",
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    ),
                    null
                )
            )
            callback(
                Pair(
                    ExtractorLink(
                        source = "TestMirror2",
                        name = "TestMirror2",
                        url = "http://test.com/stream2_${offset}.mp4",
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    ),
                    null
                )
            )
            subtitleCallback(
                SubtitleData(
                    originalName = "English",
                    nameSuffix = "",
                    url = "http://test.com/en_${offset}.srt",
                    origin = SubtitleOrigin.URL,
                    mimeType = "text/vtt",
                    headers = emptyMap(),
                    languageCode = "en"
                )
            )
            return true
        }
    }

    @BeforeEach
    fun setup() {
        episodes = listOf(
            ResultEpisode(
                headerName = "Attack on Titan",
                name = "Episode 1",
                poster = null,
                episode = 1,
                season = 1,
                id = 1001,
                parentId = 9999,
                tvType = TvType.Anime
            ),
            ResultEpisode(
                headerName = "Attack on Titan",
                name = "Episode 2",
                poster = null,
                episode = 2,
                season = 1,
                id = 1002,
                parentId = 9999,
                tvType = TvType.Anime
            )
        )
        testGenerator = TestVideoGenerator(episodes)
        fakePlayer = FakePlayer()
        generatorPlayer = GeneratorPlayer()
        generatorPlayer.attachPlayer(fakePlayer)
        generatorPlayer.viewModel.attachGenerator(testGenerator, 0)
    }

    @AfterEach
    fun tearDown() {
        generatorPlayer.releasePlayer()
    }

    @Test
    fun `test 95 percent reset rule resets watch position to zero`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        val epId = episodes[0].id

        // Set position to 96% (96_000ms / 100_000ms)
        DataStoreHelper.setViewPos(epId, 96_000L, 100_000L)
        val posReset = generatorPlayer.getPos()
        assertEquals(0L, posReset, "Position above 95% must reset to 0L")

        // Set position to 50% (50_000ms / 100_000ms)
        DataStoreHelper.setViewPos(epId, 50_000L, 100_000L)
        val posNormal = generatorPlayer.getPos()
        assertEquals(50_000L, posNormal, "Position at or below 95% must retain current position")
    }

    @Test
    fun `test 50 percent rule toggles anime OP skip button`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        val duration = 100_000L

        // Position at 20% (below 50%) -> OP skip button visible
        generatorPlayer.playerPositionChanged(20_000L, duration)
        assertTrue(generatorPlayer.isOpVisible.value, "OP skip button must be visible when position < 50%")

        // Position at 55% (above 50%) -> OP skip button hidden
        generatorPlayer.playerPositionChanged(55_000L, duration)
        assertFalse(generatorPlayer.isOpVisible.value, "OP skip button must be hidden when position >= 50%")
    }

    @Test
    fun `test 80 percent rule triggers scrobbler and preloads next links`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        val duration = 100_000L

        var syncedEpisodeIndex: Int? = null
        generatorPlayer.onSyncProgress = { ep -> syncedEpisodeIndex = ep }

        // Position at 75% -> not triggered yet
        generatorPlayer.playerPositionChanged(75_000L, duration)
        assertNull(syncedEpisodeIndex, "Scrobbler must not trigger before 80%")

        // Position at 80% -> scrobbler triggers
        generatorPlayer.playerPositionChanged(80_000L, duration)
        assertEquals(1, syncedEpisodeIndex, "Scrobbler must trigger when reaching 80%")
        assertEquals(1, generatorPlayer.maxEpisodeSet)

        // Preload next links triggers offset 1
        kotlinx.coroutines.delay(100)
        assertTrue(testGenerator.preloadedOffsets.contains(1), "Preload next links must be called at 80%")
    }

    @Test
    fun `test 90 percent rule advances next watch episode in DataStoreHelper`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        val duration = 100_000L

        // Progress below 90% (e.g. 50%)
        generatorPlayer.playerPositionChanged(50_000L, duration)
        val resumeMid = DataStoreHelper.getLastWatched(9999)
        assertNotNull(resumeMid)
        assertEquals(1, resumeMid?.episode, "Resume watching should still be episode 1 at 50%")

        // Progress at 91% (above 90%) -> advances resume episode to episode 2
        generatorPlayer.playerPositionChanged(91_000L, duration)
        val resumeAfter90 = DataStoreHelper.getLastWatched(9999)
        assertNotNull(resumeAfter90)
        assertEquals(2, resumeAfter90?.episode, "Resume watching must advance to episode 2 above 90%")
    }

    @Test
    fun `test in-flight mirror fallback advances to next mirror upon error`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        kotlinx.coroutines.delay(50)

        generatorPlayer.startPlayer()
        assertNotNull(generatorPlayer.currentSelectedLink)
        assertEquals("http://test.com/stream1_0.mp4", generatorPlayer.currentSelectedLink?.first?.url)

        assertTrue(generatorPlayer.hasNextMirror(), "Must have candidate mirror available")

        // Trigger error on first mirror
        generatorPlayer.playerError(RuntimeException("Stream playback failed"))

        // Must failover to second mirror
        assertEquals("http://test.com/stream2_0.mp4", generatorPlayer.currentSelectedLink?.first?.url)
    }

    @Test
    fun `test skip stamp intro jump and next episode jump`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        kotlinx.coroutines.delay(50)
        val duration = 100_000L

        val introStamp = VideoSkipStamp(
            timestamp = SkipStamp(type = SkipType.Opening, startMs = 10_000L, endMs = 25_000L),
            skipToNextEpisode = false,
            source = "AniSkip"
        )
        val outroStamp = VideoSkipStamp(
            timestamp = SkipStamp(type = SkipType.Ending, startMs = 85_000L, endMs = 99_000L),
            skipToNextEpisode = true,
            source = "AniSkip"
        )

        generatorPlayer.viewModel.modifyState {
            set(listOf(introStamp, outroStamp))
        }
        generatorPlayer.hasRequestedStamps = true

        // Entering intro stamp range
        generatorPlayer.playerPositionChanged(12_000L, duration)
        assertEquals(introStamp, generatorPlayer.currentActiveStamp.value)

        // Skip intro chapter
        generatorPlayer.skipCurrentChapter()
        assertTrue(fakePlayer.seekedPositions.contains(25_001L), "Must seek to endMs + 1")

        // Entering outro stamp range (skipToNextEpisode = true)
        generatorPlayer.playerPositionChanged(90_000L, duration)
        assertEquals(outroStamp, generatorPlayer.currentActiveStamp.value)

        // Skip outro -> advances to next episode
        generatorPlayer.skipCurrentChapter()
        assertEquals(1, generatorPlayer.viewModel.episodeIndex, "Must advance to episode index 1")
    }

    @Test
    fun `test video ended event advances to next episode`() = runBlocking {
        generatorPlayer.viewModel.loadLinks()
        assertEquals(0, generatorPlayer.viewModel.episodeIndex)

        // Fire VideoEndedEvent
        generatorPlayer.onPlayerEvent(VideoEndedEvent(PlayerEventSource.Player))
        assertEquals(1, generatorPlayer.viewModel.episodeIndex, "Video ended must advance to next episode index")
    }

    @Test
    fun `test loadExtractorJob manages verifier job lifecycle and cancellation`() = runBlocking {
        val linkWithData = ExtractorLink(
            source = "TestHoster",
            name = "TestHoster",
            url = "http://test.com/stream.mp4",
            referer = "",
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.VIDEO,
            extractorData = "sample_session_token"
        )
        val linkWithoutData = ExtractorLink(
            source = "DirectLink",
            name = "DirectLink",
            url = "http://test.com/direct.mp4",
            referer = "",
            quality = Qualities.Unknown.value,
            type = ExtractorLinkType.VIDEO,
            extractorData = null
        )

        // Loading link with extractorData starts verifier job
        generatorPlayer.loadExtractorJob(linkWithData)
        val initialJob = generatorPlayer.currentVerifyLink
        assertNotNull(initialJob, "Verifier job must be instantiated when extractorData is present")

        // Loading next link cancels previous job
        generatorPlayer.loadExtractorJob(linkWithoutData)
        assertTrue(initialJob?.isCancelled == true || initialJob?.isCompleted == true, "Previous verifier job must be cancelled on new link")

        // Loading link through loadLink also triggers loadExtractorJob
        generatorPlayer.loadLink(Pair(linkWithData, null), false)
        val loadLinkJob = generatorPlayer.currentVerifyLink
        assertNotNull(loadLinkJob, "loadLink must invoke loadExtractorJob for link.first")

        // releasePlayer cancels and clears job
        generatorPlayer.releasePlayer()
        assertNull(generatorPlayer.currentVerifyLink, "releasePlayer must nullify currentVerifyLink")
        assertFalse(generatorPlayer.isPlayerActive.get(), "releasePlayer must set isPlayerActive to false")
    }

    @Test
    fun `test FakePlayer preview aspect ratio cues and audio-only contract`() {
        // Initially no video loaded
        assertNull(fakePlayer.getAspectRatio())
        assertFalse(fakePlayer.hasPreview())
        assertNull(fakePlayer.getPreview(0.5f))
        assertTrue(fakePlayer.getSubtitleCues().isEmpty())
        assertFalse(fakePlayer.isAudioOnly)

        // Load player with preview = true
        fakePlayer.loadPlayer(
            context = Context(),
            sameEpisode = false,
            link = ExtractorLink(
                source = "Test",
                name = "Test",
                url = "http://test.com/v.mp4",
                referer = "",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            ),
            data = null,
            startPosition = 0L,
            subtitles = emptySet(),
            subtitle = null,
            autoPlay = true,
            preview = true
        )

        assertTrue(fakePlayer.hasPreview(), "hasPreview must be true when preview generator is loaded")
        assertNotNull(fakePlayer.getPreview(0.5f), "getPreview must return valid frame when preview is available")
        assertNotNull(fakePlayer.getAspectRatio(), "getAspectRatio must return non-null when video is loaded")

        // Play as audio event
        fakePlayer.handleEvent(CSPlayerEvent.PlayAsAudio, PlayerEventSource.UI)
        assertTrue(fakePlayer.isAudioOnly, "PlayAsAudio event must switch player to audio-only mode")

        // Subtitle cues
        val cues = listOf(SubtitleCue(1000L, 2000L, listOf("Hello world")))
        fakePlayer.mockSubtitleCues = cues
        assertEquals(cues, fakePlayer.getSubtitleCues())
    }
}
