package unit

import android.content.Context
import android.graphics.Bitmap
import android.util.Rational
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GeneratorPlayerRemediationTest {

    private lateinit var testPlayer: RecordingPlayer
    private lateinit var generatorPlayer: GeneratorPlayer
    private lateinit var episodes: List<ResultEpisode>

    class RecordingPlayer : IPlayer {
        var isPlaying: Boolean = false
        var durationMs: Long = 120_000L
        var positionMs: Long = 0L
        var playbackSpeed: Float = 1.0f
        var subtitleOffset: Long = 0L

        var eventHandler: ((PlayerEvent) -> Unit)? = null
        var loadedLink: ExtractorLink? = null
        var loadedUri: ExtractorUri? = null
        var startPosition: Long? = null
        var activeSubtitles: Set<SubtitleData> = emptySet()
        var preferredSubtitle: SubtitleData? = null
        var reloadPlayerCalledCount: Int = 0
        var saveDataCalledCount: Int = 0
        var releaseCalledCount: Int = 0
        var timeStamps: List<VideoSkipStamp> = emptyList()

        override fun getPlaybackSpeed(): Float = playbackSpeed
        override fun setPlaybackSpeed(speed: Float) { playbackSpeed = speed }
        override fun getIsPlaying(): Boolean = isPlaying
        override fun getDuration(): Long? = durationMs
        override fun getPosition(): Long? = positionMs

        override fun seekTime(time: Long, source: PlayerEventSource) { positionMs = time }
        override fun seekTo(time: Long, source: PlayerEventSource) { positionMs = time }

        override fun getSubtitleOffset(): Long = subtitleOffset
        override fun setSubtitleOffset(offset: Long) { subtitleOffset = offset }

        override fun initCallbacks(eventHandler: ((PlayerEvent) -> Unit), requestedListeningPercentages: List<Int>?) {
            this.eventHandler = eventHandler
        }

        override fun releaseCallbacks() {
            this.eventHandler = null
        }

        override fun updateSubtitleStyle(style: SaveCaptionStyle) {}

        override fun saveData() {
            saveDataCalledCount++
        }

        override fun addTimeStamps(timeStamps: List<VideoSkipStamp>) {
            this.timeStamps = timeStamps
        }

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
            this.loadedUri = data
            this.startPosition = startPosition
            this.activeSubtitles = subtitles
            this.preferredSubtitle = subtitle
            this.isPlaying = autoPlay == true
        }

        override fun reloadPlayer(context: Context) {
            reloadPlayerCalledCount++
        }

        override fun getPreview(fraction: Float): Bitmap? = null
        override fun hasPreview(): Boolean = false

        override fun setActiveSubtitles(subtitles: Set<SubtitleData>) {
            this.activeSubtitles = subtitles
        }

        override fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean {
            this.preferredSubtitle = subtitle
            return true // signals reload is required to test reload behavior
        }

        override fun getCurrentPreferredSubtitle(): SubtitleData? = preferredSubtitle

        override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
            when (event) {
                CSPlayerEvent.Play -> isPlaying = true
                CSPlayerEvent.Pause -> isPlaying = false
                else -> Unit
            }
        }

        override fun onStop() { isPlaying = false }
        override fun onPause() { isPlaying = false }
        override fun onResume(context: Context) { isPlaying = true }
        override fun release() {
            releaseCalledCount++
            isPlaying = false
        }

        override fun isActive(): Boolean = isPlaying
        override fun getVideoTracks(): CurrentTracks = CurrentTracks(null, null, emptyList(), emptyList(), emptyList(), emptyList())
        override fun getAspectRatio(): Rational? = Rational(16, 9)
        override fun setMaxVideoSize(width: Int, height: Int, id: String?) {}
        override fun setPreferredAudioTrack(trackLanguage: String?, id: String?, formatIndex: Int?) {}
        override fun getSubtitleCues(): List<SubtitleCue> = emptyList()
    }

    class MultiMirrorGenerator(
        episodes: List<ResultEpisode>,
        val mirrors: List<ExtractorLink> = emptyList(),
        val initialSubtitles: List<SubtitleData> = emptyList(),
        val shouldFail: Boolean = false
    ) : VideoGenerator<ResultEpisode>(episodes) {
        override val hasCache: Boolean = false
        override val canSkipLoading: Boolean = false
        override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

        override suspend fun generateLinks(
            clearCache: Boolean,
            sourceTypes: Set<ExtractorLinkType>,
            callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
            subtitleCallback: (SubtitleData) -> Unit,
            offset: Int,
            isCasting: Boolean
        ): Boolean {
            if (shouldFail) {
                throw RuntimeException("Network timeout resolving extractors")
            }

            for (m in mirrors) {
                callback(m to null)
            }
            for (s in initialSubtitles) {
                subtitleCallback(s)
            }
            return true
        }
    }

    @BeforeEach
    fun setUp() {
        episodes = listOf(
            ResultEpisode(
                headerName = "Cyberpunk: Edgerunners",
                name = "Episode 1",
                poster = null,
                episode = 1,
                season = 1,
                id = 2001,
                parentId = 5555,
                tvType = TvType.Anime
            ),
            ResultEpisode(
                headerName = "Cyberpunk: Edgerunners",
                name = "Episode 2",
                poster = null,
                episode = 2,
                season = 1,
                id = 2002,
                parentId = 5555,
                tvType = TvType.Anime
            )
        )
        testPlayer = RecordingPlayer()
        generatorPlayer = GeneratorPlayer()
        generatorPlayer.attachPlayer(testPlayer)
    }

    @Test
    fun `test extractor resolution error fallback triggers next mirror seamlessly`() = runBlocking {
        val mirror1 = ExtractorLink(
            source = "AlphaSource",
            name = "Alpha 1080p",
            url = "https://cdn.alpha.org/stream1.mp4",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val mirror2 = ExtractorLink(
            source = "BetaSource",
            name = "Beta 720p",
            url = "https://cdn.beta.org/stream2.mp4",
            referer = "",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )

        val generator = MultiMirrorGenerator(episodes, listOf(mirror1, mirror2))
        generatorPlayer.viewModel.attachGenerator(generator, 0)
        generatorPlayer.viewModel.loadLinks()
        delay(60)

        // Ensure playback started on primary mirror
        generatorPlayer.startPlayer()
        assertNotNull(generatorPlayer.currentSelectedLink)
        assertEquals("https://cdn.alpha.org/stream1.mp4", generatorPlayer.currentSelectedLink?.first?.url)
        assertTrue(generatorPlayer.hasNextMirror(), "Second mirror should be available as fallback")

        // Trigger extractor / playback error on current selected mirror
        generatorPlayer.playerError(IllegalStateException("HTTP 403 Forbidden on stream1"))

        // Verify seamless transition to mirror2 without restarting the episode
        assertNotNull(generatorPlayer.currentSelectedLink)
        assertEquals("https://cdn.beta.org/stream2.mp4", generatorPlayer.currentSelectedLink?.first?.url)
        assertEquals(mirror2, testPlayer.loadedLink)
    }

    @Test
    fun `test extractor exhaustion sets forceClearCache and triggers noLinksFound`() = runBlocking {
        val singleMirror = ExtractorLink(
            source = "FlakySource",
            name = "Flaky 480p",
            url = "https://cdn.flaky.org/stream_flaky.mp4",
            referer = "",
            quality = Qualities.P480.value,
            type = ExtractorLinkType.VIDEO
        )

        val generator = MultiMirrorGenerator(episodes, listOf(singleMirror))
        generatorPlayer.viewModel.attachGenerator(generator, 0)
        generatorPlayer.viewModel.loadLinks()
        delay(60)

        generatorPlayer.startPlayer()
        assertNotNull(generatorPlayer.currentSelectedLink)
        assertFalse(generatorPlayer.hasNextMirror(), "Single mirror generator should have no extra mirrors")

        var noLinksFoundInvoked = false
        generatorPlayer.onNoLinksFound = { noLinksFoundInvoked = true }

        // Trigger error on the only available mirror
        generatorPlayer.playerError(RuntimeException("Connection reset by peer"))

        assertTrue(generatorPlayer.viewModel.forceClearCache, "Cache must be cleared when all mirrors fail")
        assertTrue(noLinksFoundInvoked, "onNoLinksFound callback must be invoked upon mirror exhaustion")
    }

    @Test
    fun `test loading links failure triggers fallback startPlayer`() = runBlocking {
        val failingGenerator = MultiMirrorGenerator(episodes, shouldFail = true)
        generatorPlayer.viewModel.attachGenerator(failingGenerator, 0)

        var noLinksInvoked = false
        generatorPlayer.onNoLinksFound = { noLinksInvoked = true }

        generatorPlayer.viewModel.loadLinks()
        delay(80)

        // When loadLinks fails, Resource.Failure must trigger startPlayer() which calls noLinksFound()
        assertTrue(noLinksInvoked, "Failure to extract links should gracefully trigger noLinksFound")
        assertTrue(generatorPlayer.viewModel.forceClearCache)
    }

    @Test
    fun `test addAndSelectSubtitles attaches to player and triggers reload`() {
        val subtitle1 = SubtitleData(
            originalName = "Turkish",
            nameSuffix = "[CC]",
            url = "https://subtitles.org/tr.vtt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "tr"
        )
        val subtitle2 = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://subtitles.org/en.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "en"
        )

        generatorPlayer.addAndSelectSubtitles(subtitle1, subtitle2)

        // Verify subtitles added to view model state
        assertTrue(generatorPlayer.viewModel.state.subtitles.contains(subtitle1))
        assertTrue(generatorPlayer.viewModel.state.subtitles.contains(subtitle2))

        // Verify active subtitles passed to player
        assertTrue(testPlayer.activeSubtitles.contains(subtitle1))
        assertTrue(testPlayer.activeSubtitles.contains(subtitle2))

        // Verify player state saved, player reloaded, and subtitle selected
        assertEquals(1, testPlayer.saveDataCalledCount, "Player position must be saved before reloading")
        assertEquals(1, testPlayer.reloadPlayerCalledCount, "Player must be reloaded with new subtitles")
        assertEquals(subtitle1, generatorPlayer.currentSelectedSubtitles)
        assertEquals(subtitle1, testPlayer.preferredSubtitle)
    }

    @Test
    fun `test loadSubtitleUrl and loadSubtitleFile attach with proper origin and mime type`() {
        // Online subtitle URL attachment with query parameters
        generatorPlayer.loadSubtitleUrl(
            url = "https://subs.org/french.vtt?token=xyz123&expiry=9999",
            name = "Français",
            languageCode = "fr"
        )
        val attachedOnline = generatorPlayer.viewModel.state.subtitles.find { it.url.startsWith("https://subs.org/french.vtt") }
        assertNotNull(attachedOnline)
        assertEquals(SubtitleOrigin.URL, attachedOnline?.origin)
        assertEquals("text/vtt", attachedOnline?.mimeType)
        assertEquals("Français", attachedOnline?.originalName)
        assertEquals("fr", attachedOnline?.languageCode)

        // Downloaded file attachment
        generatorPlayer.loadSubtitleFile(
            name = "Spanish.srt",
            url = "file:///home/user/Spanish.srt"
        )
        val attachedLocal = generatorPlayer.viewModel.state.subtitles.find { it.url == "file:///home/user/Spanish.srt" }
        assertNotNull(attachedLocal)
        assertEquals(SubtitleOrigin.DOWNLOADED_FILE, attachedLocal?.origin)
        assertEquals("application/x-subrip", attachedLocal?.mimeType)
        assertEquals("Spanish.srt", attachedLocal?.originalName)
    }

    @Test
    fun `test embeddedSubtitlesFetched updates viewModel and active subtitles`() {
        val embeddedTrack1 = SubtitleData(
            originalName = "Track 1 [und]",
            nameSuffix = "",
            url = "0",
            origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "und"
        )
        val embeddedTrack2 = SubtitleData(
            originalName = "Track 2 [jpn]",
            nameSuffix = "",
            url = "1",
            origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "ja"
        )

        generatorPlayer.embeddedSubtitlesFetched(listOf(embeddedTrack1, embeddedTrack2))

        assertTrue(generatorPlayer.viewModel.state.subtitles.contains(embeddedTrack1))
        assertTrue(generatorPlayer.viewModel.state.subtitles.contains(embeddedTrack2))
    }

    @Test
    fun `test autoSelectSubtitles selects language matching user preference`() {
        generatorPlayer.preferredAutoSelectSubtitles = "tr"

        val subEn = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://sub.org/en.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "en"
        )
        val subTr = SubtitleData(
            originalName = "Turkish",
            nameSuffix = "",
            url = "https://sub.org/tr.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "tr"
        )

        generatorPlayer.viewModel.addSubtitles(setOf(subEn, subTr))
        generatorPlayer.autoSelectSubtitles()

        assertEquals(subTr, generatorPlayer.currentSelectedSubtitles, "Turkish subtitle should be auto-selected")
        assertEquals(subTr, testPlayer.preferredSubtitle)
    }

    @Test
    fun `test noSubtitles clears selection and persists preference`() {
        val sub = SubtitleData(
            originalName = "German",
            nameSuffix = "",
            url = "https://sub.org/de.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "de"
        )
        generatorPlayer.setSubtitles(sub, userInitiated = true)
        assertEquals(sub, generatorPlayer.currentSelectedSubtitles)
        assertEquals("de", generatorPlayer.preferredAutoSelectSubtitles)

        // User turns subtitles off
        val changed = generatorPlayer.noSubtitles()
        assertTrue(changed)
        assertNull(generatorPlayer.currentSelectedSubtitles)
        assertEquals("", generatorPlayer.preferredAutoSelectSubtitles, "Empty string indicates subtitles explicitly turned off")
    }

    @Test
    fun `test getName formats entity name with language name prefix`() {
        val entityWithLang = AbstractSubtitleEntities.SubtitleEntity(
            id = "101",
            name = "Complete Subtitles",
            lang = "tr",
            data = "sub_data",
            source = "OpenSubtitles",
            headers = emptyMap()
        )
        val entityWithoutLang = AbstractSubtitleEntities.SubtitleEntity(
            id = "102",
            name = "Default Subtitles",
            lang = "",
            data = "sub_data",
            source = "OpenSubtitles",
            headers = emptyMap()
        )

        val formattedWithLang = generatorPlayer.getName(entityWithLang, withLanguage = true)
        assertTrue(formattedWithLang.contains("Complete Subtitles"))

        val formattedWithoutLang = generatorPlayer.getName(entityWithoutLang, withLanguage = true)
        assertEquals("Default Subtitles", formattedWithoutLang)
    }
}
