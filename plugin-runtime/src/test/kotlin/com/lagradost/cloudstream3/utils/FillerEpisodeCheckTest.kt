package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.ui.result.getId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FillerEpisodeCheckTest {

    private class DummyApi : MainAPI() {
        override var name = "DummyApi"
        override var mainUrl = "https://dummy.local"
        override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    }

    private val dummyApi = DummyApi()

    @Test
    fun `test stripName normalizes title correctly`() {
        assertEquals("narutoshippuuden", FillerEpisodeCheck.stripName("Naruto: Shippuuden"))
        assertEquals("onepiece", FillerEpisodeCheck.stripName("One Piece!"))
        assertEquals("bleach2022", FillerEpisodeCheck.stripName("Bleach - 2022"))
        assertEquals("attackontitan", FillerEpisodeCheck.stripName("Attack.on.Titan"))
    }

    @Test
    fun `test loadJson loads database from AnimeDB dependency`() {
        val database = FillerEpisodeCheck.loadJson()
        assertNotNull(database)
        assertFalse(database.name.isEmpty(), "Anime database names should not be empty")
        assertFalse(database.mal.isEmpty(), "Anime database MAL mappings should not be empty")
    }

    @Test
    fun `test getFillerEpisodes returns null for non-anime type`() = runBlocking {
        val movieResponse: MovieLoadResponse = dummyApi.newMovieLoadResponse(
            name = "Inception",
            url = "https://example.com/movie/inception",
            type = TvType.Movie,
            dataUrl = "https://example.com/movie/inception/watch"
        )

        val filler = FillerEpisodeCheck.getFillerEpisodes(movieResponse)
        assertNull(filler, "Non-anime content must return null for filler episodes")
    }

    @Test
    fun `test getFillerEpisodes and cache for anime response`() = runBlocking {
        // Naruto has MAL ID 20 and known filler episodes
        val animeResponse: AnimeLoadResponse = dummyApi.newAnimeLoadResponse(
            name = "Naruto",
            url = "https://example.com/anime/naruto",
            type = TvType.Anime
        ) {
            this.addMalId(20)
        }

        val filler = FillerEpisodeCheck.getFillerEpisodes(animeResponse)
        assertNotNull(filler, "Naruto should have filler episode information")
        assertTrue(filler!!.contains(26), "Naruto episode 26 is a known filler episode")

        // Test caching in loadCache
        val cached = FillerEpisodeCheck.loadCache[animeResponse.getId()]
        assertNotNull(cached, "Result should be cached in loadCache with anime ID")
        assertEquals(filler, cached)
    }

    @Test
    fun `test data structure serialization and mapping`() {
        val show = FillerEpisodeCheck.Show(
            slug = "test-show",
            title = "Test Show",
            filler = arrayListOf(1, 2, 3),
            mixedCanon = arrayListOf(4),
            mangaCanon = arrayListOf(5, 6),
            animeCanon = arrayListOf(7)
        )

        assertEquals("test-show", show.slug)
        assertEquals("Test Show", show.title)
        assertEquals(listOf(1, 2, 3), show.filler)
        assertEquals(listOf(4), show.mixedCanon)
        assertEquals(listOf(5, 6), show.mangaCanon)
        assertEquals(listOf(7), show.animeCanon)

        val mapping = FillerEpisodeCheck.MappingRoot(
            type = "tv",
            anidbId = 100L,
            anilistId = 200L,
            animecountdownId = null,
            animenewsnetworkId = null,
            animePlanetId = null,
            anisearchId = null,
            imdbId = "tt1234567",
            kitsuId = 300L,
            livechartId = null,
            malId = 400L,
            simklId = null,
            themoviedbId = 500L,
            tvdbId = null,
            season = FillerEpisodeCheck.Season(tvdb = null, tmdb = 1L)
        )

        assertEquals(400L, mapping.malId)
        assertEquals(200L, mapping.anilistId)
        assertEquals("tt1234567", mapping.imdbId)

        val combined = FillerEpisodeCheck.CombinedMedia(mapping, show)
        assertEquals("Test Show", combined.show.title)
        assertEquals(400L, combined.mapping?.malId)
    }
}
