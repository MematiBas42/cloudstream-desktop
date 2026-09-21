package unit

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.videoskip.AniSkip
import com.lagradost.cloudstream3.utils.videoskip.AnimeSkip
import com.lagradost.cloudstream3.utils.videoskip.IntroDbSkip
import com.lagradost.cloudstream3.utils.videoskip.SkipAPI
import com.lagradost.cloudstream3.utils.videoskip.SkipStamp
import com.lagradost.cloudstream3.utils.videoskip.SkipType
import com.lagradost.cloudstream3.utils.videoskip.TheIntroDBSkip
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import com.lagradost.player.skip.VideoSkipManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SkipAPITest {

    @Test
    fun testProviderRegistrationAndOrder() {
        val field = runCatching {
            SkipAPI::class.java.getDeclaredField("skipApis")
        }.getOrElse {
            SkipAPI.Companion::class.java.getDeclaredField("skipApis")
        }
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val apis = (field.get(null) ?: field.get(SkipAPI.Companion)) as List<SkipAPI>

        assertEquals(4, apis.size, "SkipAPI must register exactly 4 providers")
        assertTrue(apis[0] is AniSkip, "Provider 0 must be AniSkip")
        assertTrue(apis[1] is TheIntroDBSkip, "Provider 1 must be TheIntroDBSkip")
        assertTrue(apis[2] is IntroDbSkip, "Provider 2 must be IntroDbSkip")
        assertTrue(apis[3] is AnimeSkip, "Provider 3 must be AnimeSkip")
    }

    @Test
    fun testProviderMetadataAndSupportedTypes() {
        val aniSkip = AniSkip()
        assertEquals("AniSkip", aniSkip.name)
        assertEquals(setOf(TvType.Anime, TvType.OVA), aniSkip.supportedTypes)

        val introDb = IntroDbSkip()
        assertEquals("IntroDb", introDb.name)
        assertEquals(setOf(TvType.TvSeries, TvType.AsianDrama), introDb.supportedTypes)

        val theIntroDb = TheIntroDBSkip()
        assertEquals("TheIntroDB", theIntroDb.name)
        assertEquals(
            setOf(TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.Movie, TvType.AsianDrama),
            theIntroDb.supportedTypes
        )

        val animeSkip = AnimeSkip()
        assertEquals("AniSkip", animeSkip.name)
        assertEquals(setOf(TvType.Anime, TvType.OVA), animeSkip.supportedTypes)
    }

    @Test
    fun testAnimeSkipNameNormalization() {
        assertEquals("attackontitan", AnimeSkip.stripName("Attack on Titan!"))
        assertEquals("reゼロ", AnimeSkip.stripName("Re: ゼロ"))
        assertEquals("naruto  shippuuden", AnimeSkip.asciiName("Naruto! - Shippuuden?"))
    }

    @Test
    fun testVideoSkipStampNextEpisodeRule() {
        val stampWithin20s = SkipStamp(
            type = SkipType.Ending,
            startMs = 1_400_000L,
            endMs = 1_415_000L
        )
        val episodeDurationMs = 1_420_000L // 5 seconds remaining from endMs

        val videoStampWithNext = VideoSkipStamp(
            timestamp = stampWithin20s,
            skipToNextEpisode = true && (episodeDurationMs - stampWithin20s.endMs < 20_000L),
            source = "AniSkip"
        )
        assertTrue(videoStampWithNext.skipToNextEpisode, "Should skip to next episode if remaining < 20s")

        val videoStampWithoutNext = VideoSkipStamp(
            timestamp = stampWithin20s,
            skipToNextEpisode = false && (episodeDurationMs - stampWithin20s.endMs < 20_000L),
            source = "AniSkip"
        )
        assertFalse(videoStampWithoutNext.skipToNextEpisode, "Should not skip to next episode if hasNextEpisode is false")

        val stampFarFromEnd = SkipStamp(
            type = SkipType.Opening,
            startMs = 60_000L,
            endMs = 150_000L
        )
        val videoStampOpening = VideoSkipStamp(
            timestamp = stampFarFromEnd,
            skipToNextEpisode = true && (episodeDurationMs - stampFarFromEnd.endMs < 20_000L),
            source = "AniSkip"
        )
        assertFalse(videoStampOpening.skipToNextEpisode, "Opening stamp far from end must not skip to next episode")
    }

    @Test
    fun testVideoSkipManagerBridgeFromCanonicalStamps() {
        val manager = VideoSkipManager()
        val stamps = listOf(
            VideoSkipStamp(
                timestamp = SkipStamp(SkipType.Intro, 10_000L, 70_000L),
                skipToNextEpisode = false,
                source = "TheIntroDB"
            ),
            VideoSkipStamp(
                timestamp = SkipStamp(SkipType.Credits, 1_300_000L, 1_380_000L),
                skipToNextEpisode = true,
                source = "TheIntroDB"
            )
        )

        manager.loadFromSkipStamps(stamps)
        assertEquals(2, manager.intervals.size)

        val intro = manager.checkSkip(30_000L)
        assertNotNull(intro)
        assertEquals(com.lagradost.player.skip.SkipType.Intro, intro!!.type)
        assertEquals(10_000L, intro.startMs)
        assertEquals(70_000L, intro.endMs)

        val outro = manager.checkSkip(1_350_000L)
        assertNotNull(outro)
        assertEquals(com.lagradost.player.skip.SkipType.Outro, outro!!.type)
        assertEquals(1_300_000L, outro.startMs)
        assertEquals(1_380_000L, outro.endMs)
    }
}
