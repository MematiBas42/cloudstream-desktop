package unit

import android.content.Context
import android.util.Rational
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import com.lagradost.player.impl.MpvPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class IPlayerAndGeneratorPlayerTest {

    class SampleGenerator(episodes: List<ResultEpisode>) : VideoGenerator<ResultEpisode>(episodes) {
        override val hasCache: Boolean = true
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
            callback(
                Pair(
                    ExtractorLink(
                        source = "PrimaryStream",
                        name = "PrimaryStream",
                        url = "http://localhost/test_stream1_${offset}.mp4",
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
                        source = "FallbackStream",
                        name = "FallbackStream",
                        url = "http://localhost/test_stream2_${offset}.mp4",
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO
                    ),
                    null
                )
            )
            return true
        }
    }

    @Test
    fun `test MpvPlayer conforms to IPlayer contract`() {
        val mpvPlayer = MpvPlayer()

        assertEquals(1.0f, mpvPlayer.getPlaybackSpeed())
        mpvPlayer.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, mpvPlayer.getPlaybackSpeed())

        assertFalse(mpvPlayer.getIsPlaying())
        assertFalse(mpvPlayer.hasPreview())
        assertNull(mpvPlayer.getPreview(0.5f))
        assertEquals(0L, mpvPlayer.getSubtitleOffset())
        assertNull(mpvPlayer.getAspectRatio(), "getAspectRatio must return null when no video is loaded")

        // Test aspect ratio dynamic calculation
        mpvPlayer.ipcClient.processIpcLine("""{"event":"property-change","id":10,"name":"dwidth","data":1920}""")
        mpvPlayer.ipcClient.processIpcLine("""{"event":"property-change","id":11,"name":"dheight","data":1080}""")
        assertEquals(Rational(1920, 1080), mpvPlayer.getAspectRatio())

        // Test PlayAsAudio event
        mpvPlayer.handleEvent(CSPlayerEvent.PlayAsAudio, PlayerEventSource.UI)
        assertTrue(mpvPlayer.isAudioOnly(), "PlayAsAudio must set audio-only mode")

        // Test subtitle cues extraction
        mpvPlayer.ipcClient.processIpcLine("""{"event":"property-change","id":12,"name":"sub-text","data":"Subtitle sample"}""")
        val cues = mpvPlayer.getSubtitleCues()
        assertEquals(1, cues.size)
        assertEquals("Subtitle sample", cues[0].text[0])

        var callbackFired = false
        mpvPlayer.initCallbacks({ callbackFired = true }, listOf(50, 80, 90))
        assertNotNull(mpvPlayer)
        mpvPlayer.releaseCallbacks()
    }

    @Test
    fun `test GeneratorPlayer orchestration with 4 percentage thresholds and intro skip`() = runBlocking {
        val episodes = listOf(
            ResultEpisode(
                headerName = "Frieren",
                name = "Journey End",
                poster = null,
                episode = 1,
                season = 1,
                id = 2001,
                parentId = 5001,
                tvType = TvType.Anime
            ),
            ResultEpisode(
                headerName = "Frieren",
                name = "It Didn't Have to Be Magic",
                poster = null,
                episode = 2,
                season = 1,
                id = 2002,
                parentId = 5001,
                tvType = TvType.Anime
            )
        )

        val generator = SampleGenerator(episodes)
        val generatorPlayer = GeneratorPlayer()
        val mpvPlayer = MpvPlayer()
        mpvPlayer.socketFactory = { File("/tmp/test_gen_${System.currentTimeMillis()}.sock") }
        mpvPlayer.processLauncher = { _, _, _, _, _, _, _, _, _, _, _ ->
            ProcessBuilder("sleep", "10").start()
        }

        generatorPlayer.attachPlayer(mpvPlayer)
        generatorPlayer.viewModel.attachGenerator(generator, 0)
        generatorPlayer.viewModel.loadLinks()
        delay(150)

        val duration = 120_000L

        // 1. Check 95% rule (pos > 95% resets to 0L)
        DataStoreHelper.setViewPos(episodes[0].id, 116_000L, duration) // ~96.6%
        assertEquals(0L, generatorPlayer.getPos(), "Position above 95% must reset to 0L")

        DataStoreHelper.setViewPos(episodes[0].id, 60_000L, duration) // 50%
        assertEquals(60_000L, generatorPlayer.getPos(), "Position at 50% must remain 60000L")

        // 2. Check 50% rule (OP skip button visibility)
        generatorPlayer.playerPositionChanged(20_000L, duration) // ~16.6%
        assertTrue(generatorPlayer.isOpVisible.value, "OP skip button should be visible before 50%")

        generatorPlayer.playerPositionChanged(65_000L, duration) // ~54%
        assertFalse(generatorPlayer.isOpVisible.value, "OP skip button must be hidden after 50%")

        // 3. Check 80% rule (scrobbler trigger & preloading next episode)
        var scrobbledEpisode = -1
        generatorPlayer.onSyncProgress = { ep -> scrobbledEpisode = ep }

        generatorPlayer.playerPositionChanged(96_000L, duration) // 80%
        assertEquals(1, scrobbledEpisode, "Scrobbler must be triggered at 80%")

        // 4. Check 90% rule (advance resume episode in DataStoreHelper)
        generatorPlayer.playerPositionChanged(110_000L, duration) // ~91.6%
        val lastWatched = DataStoreHelper.getLastWatched(5001)
        assertNotNull(lastWatched)
        assertEquals(2, lastWatched?.episode, "Last watched resume pointer must advance to episode 2 above 90%")

        // 5. Intro skip stamp & jump
        val stamp = VideoSkipStamp(
            timestamp = SkipStamp(type = SkipType.Intro, startMs = 10_000L, endMs = 25_000L),
            skipToNextEpisode = false,
            source = "AniSkip"
        )
        generatorPlayer.viewModel.modifyState { set(listOf(stamp)) }

        generatorPlayer.playerPositionChanged(15_000L, duration)
        assertEquals(stamp, generatorPlayer.currentActiveStamp.value)

        generatorPlayer.skipCurrentChapter()

        // 6. In-flight mirror fallback
        generatorPlayer.startPlayer()
        assertTrue(generatorPlayer.hasNextMirror())
        val firstUrl = generatorPlayer.currentSelectedLink?.first?.url
        generatorPlayer.playerError(RuntimeException("First mirror 404"))
        val nextUrl = generatorPlayer.currentSelectedLink?.first?.url
        assertNotEquals(firstUrl, nextUrl, "In-flight mirror fallback must switch to alternative candidate mirror")

        mpvPlayer.destroy()
    }

    @Test
    fun `test GeneratorPlayer newInstance Bundle serialization parity`() {
        val episodes = listOf(
            ResultEpisode(
                headerName = "Test Show",
                name = "Pilot",
                poster = null,
                episode = 1,
                id = 3001
            )
        )
        val gen = SampleGenerator(episodes)
        val syncMap = hashMapOf("anilist" to "12345")

        val bundle = GeneratorPlayer.newInstance(gen, 0, syncMap)
        assertNotNull(bundle.getString("uuid"))
        assertEquals(0, bundle.getInt("index", -1))

        val retrievedGen = GeneratorPlayer.getGenerator(bundle.getString("uuid")!!)
        assertSame(gen, retrievedGen, "Generator must be retrievable via uuid from Bundle")
    }
}
