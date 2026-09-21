package unit

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.utils.FillerEpisodeCheck
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Concrete parity and remediation unit tests for [FillerEpisodeCheck] (Cluster C35).
 *
 * Verifies 1:1 upstream architectural parity:
 * 1. Title Normalization:
 *    - Regex pattern [ :\\-.!] stripping spaces, colons, hyphens, dots, exclamation marks.
 *    - Lowercase conversion and Unicode character preservation.
 * 2. AnimeDB Database Loading:
 *    - Gömülü AnimeDB classpath resource stream parsing into Array<CombinedMedia>.
 *    - Exact 6-map indexing: name (lowercase), mal, anilist, kitsu, imdb, tmdb (season.tmdb).
 *    - Singleton caching and thread-safe synchronized loading contract.
 * 3. TvType Guard Contract:
 *    - Strict TvType.Anime gate: Movies, TV series, Cartoons immediately return null.
 *    - Non-anime queries do NOT pollute or corrupt loadCache.
 * 4. Deterministic 6-Tier Fallback Priority Chain:
 *    - Priority 1: MAL ID match (highest priority, overrides lower IDs and title).
 *    - Priority 2: AniList ID fallback (active when MAL ID is absent).
 *    - Priority 3: Kitsu ID fallback (active when MAL and AniList are absent).
 *    - Priority 4: IMDB ID fallback (active when MAL, AniList, Kitsu are absent).
 *    - Priority 5: TMDB ID fallback (season.tmdb matching).
 *    - Priority 6: Normalized Title fallback (ultimate fallback when all IDs are missing).
 * 5. In-Memory Caching (loadCache):
 *    - Hash code key based on LoadResponse.getId().
 *    - Successful cache hit returns identical HashSet instance without database re-query.
 *    - Null response caching for non-matching anime.
 * 6. Data Model Serialization Integrity:
 *    - Show, MappingRoot, Season, CombinedMedia, Database field mapping parity.
 * 7. Non-Numeric & Malformed ID Tolerance:
 *    - Graceful toLongOrNull() parsing avoiding NumberFormatException on malformed IDs.
 */
class FillerEpisodeCheckRemediationTest {

    private class TestAnimeApi : MainAPI() {
        override var name = "TestAnimeApi"
        override var mainUrl = "https://test.anime.local"
        override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries, TvType.Cartoon)
    }

    private val api = TestAnimeApi()

    @BeforeEach
    fun setUp() {
        FillerEpisodeCheck.loadCache.clear()
    }

    @AfterEach
    fun tearDown() {
        FillerEpisodeCheck.loadCache.clear()
    }

    @Nested
    @DisplayName("1. Title Normalization Parity (stripName)")
    inner class TitleNormalizationTests {

        @Test
        fun testStripPunctuationAndWhitespace() {
            assertEquals("narutoshippuuden", FillerEpisodeCheck.stripName("Naruto: Shippuuden"))
            assertEquals("onepiece", FillerEpisodeCheck.stripName("One Piece!"))
            assertEquals("bleach2022", FillerEpisodeCheck.stripName("Bleach - 2022"))
            assertEquals("attackontitan", FillerEpisodeCheck.stripName("Attack.on.Titan"))
            assertEquals("kon", FillerEpisodeCheck.stripName("K-On!"))
        }

        @Test
        fun testMixedCaseAndCompoundPunctuation() {
            assertEquals(
                "fullmetalalchemistbrotherhood",
                FillerEpisodeCheck.stripName("Fullmetal Alchemist: Brotherhood!")
            )
            assertEquals("dragonballz", FillerEpisodeCheck.stripName("  Dragon   Ball   Z  ! "))
            assertEquals("bLeAcH".lowercase(), FillerEpisodeCheck.stripName("bLeAcH"))
        }

        @Test
        fun testRetainedCharacters() {
            // Forward slashes and underscores are not in [ :\\-.!], so they are retained
            assertEquals("fate/zero", FillerEpisodeCheck.stripName("Fate/Zero"))
            assertEquals("code_geass", FillerEpisodeCheck.stripName("Code_Geass"))
            // CJK characters are preserved
            assertEquals("進撃の巨人", FillerEpisodeCheck.stripName("進撃の巨人"))
        }

        @Test
        fun testEmptyAndWhitespaceOnlyStrings() {
            assertEquals("", FillerEpisodeCheck.stripName(""))
            assertEquals("", FillerEpisodeCheck.stripName("   "))
            assertEquals("", FillerEpisodeCheck.stripName(": - . !"))
        }
    }

    @Nested
    @DisplayName("2. AnimeDB Loading & Caching Parity (loadJson)")
    inner class DatabaseLoadingTests {

        @Test
        fun testDatabaseLoadsSuccessfully() {
            val db = FillerEpisodeCheck.loadJson()
            assertNotNull(db, "loadJson must return non-null Database instance")
            assertFalse(db.name.isEmpty(), "Database name lookup map must not be empty")
            assertFalse(db.mal.isEmpty(), "Database MAL lookup map must not be empty")
            assertFalse(db.anilist.isEmpty(), "Database AniList lookup map must not be empty")
            assertFalse(db.kitsu.isEmpty(), "Database Kitsu lookup map must not be empty")
            assertFalse(db.imdb.isEmpty(), "Database IMDB lookup map must not be empty")
            assertFalse(db.tmdb.isEmpty(), "Database TMDB lookup map must not be empty")
        }

        @Test
        fun testDatabaseSingletonReferenceEquality() {
            val dbFirst = FillerEpisodeCheck.loadJson()
            val dbSecond = FillerEpisodeCheck.loadJson()
            assertSame(dbFirst, dbSecond, "Repeated loadJson calls must return identical singleton instance")
        }

        @Test
        fun testWellKnownAnimePresentInDatabase() {
            val db = FillerEpisodeCheck.loadJson()
            // Naruto has MAL ID 20, AniList ID 20, Kitsu ID 11, IMDB ID "tt0409591"
            val narutoByMal = db.mal[20L]
            assertNotNull(narutoByMal, "Naruto must exist in MAL map with ID 20")
            assertEquals("Naruto", narutoByMal?.show?.title)

            val narutoByAniList = db.anilist[20L]
            assertNotNull(narutoByAniList, "Naruto must exist in AniList map with ID 20")
            assertEquals(narutoByMal, narutoByAniList)

            val narutoByKitsu = db.kitsu[11L]
            assertNotNull(narutoByKitsu, "Naruto must exist in Kitsu map with ID 11")
            assertEquals(narutoByMal, narutoByKitsu)

            val narutoByImdb = db.imdb["tt0409591"]
            assertNotNull(narutoByImdb, "Naruto must exist in IMDB map with ID tt0409591")
            assertEquals(narutoByMal, narutoByImdb)

            val narutoByName = db.name["naruto"]
            assertNotNull(narutoByName, "Naruto must exist in Name map with normalized key 'naruto'")
            assertEquals(narutoByMal, narutoByName)
        }
    }

    @Nested
    @DisplayName("3. Content Type Guard Parity (Non-Anime Return Null)")
    inner class TvTypeGuardTests {

        @Test
        fun testMovieReturnsNull() = runBlocking {
            val movie: MovieLoadResponse = api.newMovieLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/movie/naruto",
                type = TvType.Movie,
                dataUrl = "https://test.anime.local/movie/naruto/watch"
            )
            val result = FillerEpisodeCheck.getFillerEpisodes(movie)
            assertNull(result, "TvType.Movie must return null even if name matches an anime")
            assertFalse(
                FillerEpisodeCheck.loadCache.containsKey(movie.getId()),
                "loadCache must not cache non-anime responses"
            )
        }

        @Test
        fun testTvSeriesReturnsNull() = runBlocking {
            val tvSeries: TvSeriesLoadResponse = api.newTvSeriesLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/series/naruto",
                type = TvType.TvSeries
            )
            val result = FillerEpisodeCheck.getFillerEpisodes(tvSeries)
            assertNull(result, "TvType.TvSeries must return null even if name matches an anime")
            assertFalse(
                FillerEpisodeCheck.loadCache.containsKey(tvSeries.getId()),
                "loadCache must not cache non-anime responses"
            )
        }

        @Test
        fun testCartoonReturnsNull() = runBlocking {
            val cartoon = AnimeLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/cartoon/naruto",
                apiName = api.name,
                type = TvType.Cartoon
            )
            val result = FillerEpisodeCheck.getFillerEpisodes(cartoon)
            assertNull(result, "TvType.Cartoon must return null")
        }
    }

    @Nested
    @DisplayName("4. Deterministic 6-Tier Fallback Priority Chain")
    inner class FallbackPriorityTests {

        @Test
        fun testPriority1MalIdMatch() = runBlocking {
            // Anime with valid MAL ID (20 = Naruto), but intentionally wrong name
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "CompletelyWrongAnimeTitle",
                url = "https://test.anime.local/anime/naruto-mal",
                type = TvType.Anime
            ) {
                addMalId(20)
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "MAL ID match must successfully locate filler episodes")
            assertTrue(fillers!!.contains(26), "Naruto filler list must contain episode 26")
            assertTrue(fillers.contains(101), "Naruto filler list must contain episode 101")
            assertFalse(fillers.contains(1), "Naruto episode 1 is canon, not filler")
        }

        @Test
        fun testPriority2AniListIdFallback() = runBlocking {
            // No MAL ID, but AniList ID (20 = Naruto), intentionally wrong name
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "ArbitraryNonExistentName",
                url = "https://test.anime.local/anime/naruto-anilist",
                type = TvType.Anime
            ) {
                addAniListId(20)
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "AniList ID fallback must locate filler episodes when MAL ID is absent")
            assertTrue(fillers!!.contains(26))
        }

        @Test
        fun testPriority3KitsuIdFallback() = runBlocking {
            // No MAL ID, no AniList ID, but Kitsu ID (11 = Naruto)
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "UnknownTitle12345",
                url = "https://test.anime.local/anime/naruto-kitsu",
                type = TvType.Anime
            ) {
                addKitsuId(11)
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "Kitsu ID fallback must locate filler episodes when MAL and AniList are absent")
            assertTrue(fillers!!.contains(26))
        }

        @Test
        fun testPriority4ImdbIdFallback() = runBlocking {
            // No MAL, AniList, or Kitsu IDs, but valid IMDB ID (tt0409591 = Naruto)
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "NonMatchingAnimeName",
                url = "https://test.anime.local/anime/naruto-imdb",
                type = TvType.Anime
            ) {
                addImdbId("tt0409591")
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "IMDB ID fallback must locate filler episodes when higher IDs are absent")
            assertTrue(fillers!!.contains(26))
        }

        @Test
        fun testPriority5TmdbIdFallback() = runBlocking {
            // 86 EIGHTY-SIX has season.tmdb = 1
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "UnknownEightySixTitle",
                url = "https://test.anime.local/anime/86-tmdb",
                type = TvType.Anime
            ) {
                addTMDbId("1")
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "TMDB ID fallback must locate media matching season.tmdb")
            // 86 has 0 filler episodes in the database (filler: [])
            assertTrue(fillers!!.isEmpty(), "86 EIGHTY-SIX filler list should be empty HashSet")
        }

        @Test
        fun testPriority6NormalizedTitleFallback() = runBlocking {
            // No external IDs provided at all - relies strictly on stripName(data.name)
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "Naruto: Shippuuden",
                url = "https://test.anime.local/anime/naruto-shippuuden",
                type = TvType.Anime
            )

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "Title normalization fallback must locate filler episodes without IDs")
            // Naruto Shippuuden has filler episodes (e.g. ep 57-71, 91-112, etc.)
            assertTrue(fillers!!.contains(57), "Naruto Shippuuden episode 57 is known filler")
            assertFalse(fillers.contains(1), "Naruto Shippuuden episode 1 is canon")
        }

        @Test
        fun testPrecedenceMalOverTitle() = runBlocking {
            // MAL ID points to Naruto (has fillers), Name is '86 EIGHTY-SIX' (0 fillers)
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "86 EIGHTY-SIX",
                url = "https://test.anime.local/anime/conflict-test-1",
                type = TvType.Anime
            ) {
                addMalId(20) // Naruto
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers)
            // MAL ID must take precedence over name
            assertTrue(
                fillers!!.contains(26),
                "MAL ID (Naruto) must take precedence over data.name (86 EIGHTY-SIX)"
            )
        }

        @Test
        fun testPrecedenceAniListOverKitsu() = runBlocking {
            // AniList ID points to Naruto (ID 20), Kitsu ID points to non-existent anime (99999999)
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "ArbitraryConflict",
                url = "https://test.anime.local/anime/conflict-test-2",
                type = TvType.Anime
            ) {
                addAniListId(20)
                addKitsuId(99999999)
            }

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers)
            assertTrue(fillers!!.contains(26), "AniList ID must resolve before Kitsu ID")
        }

        @Test
        fun testNonExistentAnimeReturnsNull() = runBlocking {
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "ZzzDefinitelyNonExistentAnimeTitle987654321",
                url = "https://test.anime.local/anime/non-existent",
                type = TvType.Anime
            )

            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNull(fillers, "Non-existent anime must return null")
        }
    }

    @Nested
    @DisplayName("5. In-Memory Caching Parity (loadCache)")
    inner class CachingTests {

        @Test
        fun testResultCachedUnderLoadResponseId() = runBlocking {
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/anime/naruto-cache",
                type = TvType.Anime
            ) {
                addMalId(20)
            }

            val id = response.getId()
            assertFalse(FillerEpisodeCheck.loadCache.containsKey(id), "Cache must initially be empty for this anime")

            val firstCall = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(firstCall)
            assertTrue(FillerEpisodeCheck.loadCache.containsKey(id), "Cache must contain key after first call")
            assertSame(firstCall, FillerEpisodeCheck.loadCache[id], "Cache entry must equal returned set")

            val secondCall = FillerEpisodeCheck.getFillerEpisodes(response)
            assertSame(firstCall, secondCall, "Second call must return cached HashSet without recreation")
        }

        @Test
        fun testSyntheticCacheHitBypassesDatabaseQuery() = runBlocking {
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/anime/naruto-synthetic",
                type = TvType.Anime
            ) {
                addMalId(20)
            }

            val syntheticFillers = hashSetOf(999, 1000)
            // Pre-populate loadCache with synthetic set
            FillerEpisodeCheck.loadCache[response.getId()] = syntheticFillers

            val result = FillerEpisodeCheck.getFillerEpisodes(response)
            assertSame(
                syntheticFillers,
                result,
                "getFillerEpisodes must honor existing loadCache entry unconditionally"
            )
            assertTrue(result!!.contains(999))
            assertFalse(result.contains(26), "Real database results must not be loaded when cache hits")
        }

        @Test
        fun testCacheIsolationAcrossDifferentAnime() = runBlocking {
            val naruto: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/anime/naruto-iso",
                type = TvType.Anime
            ) {
                addMalId(20)
            }

            val railgun: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "A Certain Scientific Railgun",
                url = "https://test.anime.local/anime/railgun-iso",
                type = TvType.Anime
            ) {
                addMalId(6213)
            }

            assertNotEquals(naruto.getId(), railgun.getId(), "Distinct anime must produce distinct response IDs")

            val narutoFillers = FillerEpisodeCheck.getFillerEpisodes(naruto)
            val railgunFillers = FillerEpisodeCheck.getFillerEpisodes(railgun)

            assertNotNull(narutoFillers)
            assertNotNull(railgunFillers)
            assertNotEquals(narutoFillers, railgunFillers, "Distinct anime must have distinct filler sets")

            assertSame(narutoFillers, FillerEpisodeCheck.loadCache[naruto.getId()])
            assertSame(railgunFillers, FillerEpisodeCheck.loadCache[railgun.getId()])
        }
    }

    @Nested
    @DisplayName("6. Non-Numeric ID Tolerance & Edge Cases")
    inner class RobustnessAndEdgeCaseTests {

        @Test
        fun testMalformedNonNumericIdDoesNotCrash() = runBlocking {
            val response: AnimeLoadResponse = api.newAnimeLoadResponse(
                name = "Naruto",
                url = "https://test.anime.local/anime/naruto-malformed",
                type = TvType.Anime
            )
            // Inject non-numeric string into syncData for MAL
            response.syncData["mal"] = "not_a_number_abc"

            // toLongOrNull() should safely return null and fall back to the name "Naruto"
            val fillers = FillerEpisodeCheck.getFillerEpisodes(response)
            assertNotNull(fillers, "Malformed non-numeric MAL ID should fall back to valid title match")
            assertTrue(fillers!!.contains(26))
        }

        @Test
        fun testDataClassContracts() {
            val show = FillerEpisodeCheck.Show(
                slug = "/shows/test",
                title = "Test Anime",
                filler = arrayListOf(1, 3, 5),
                mixedCanon = arrayListOf(2),
                mangaCanon = arrayListOf(4),
                animeCanon = arrayListOf()
            )
            assertEquals("/shows/test", show.slug)
            assertEquals("Test Anime", show.title)
            assertEquals(arrayListOf(1, 3, 5), show.filler)
            assertEquals(arrayListOf(2), show.mixedCanon)
            assertEquals(arrayListOf(4), show.mangaCanon)
            assertTrue(show.animeCanon.isEmpty())

            val season = FillerEpisodeCheck.Season(tvdb = 10L, tmdb = 20L)
            assertEquals(10L, season.tvdb)
            assertEquals(20L, season.tmdb)

            val mapping = FillerEpisodeCheck.MappingRoot(
                type = "TV",
                anidbId = 1L,
                anilistId = 2L,
                animecountdownId = 3L,
                animenewsnetworkId = 4L,
                animePlanetId = "test-planet",
                anisearchId = 5L,
                imdbId = "tt999999",
                kitsuId = 6L,
                livechartId = 7L,
                malId = 8L,
                simklId = 9L,
                themoviedbId = 10L,
                tvdbId = 11L,
                season = season
            )
            assertEquals(8L, mapping.malId)
            assertEquals(2L, mapping.anilistId)
            assertEquals("tt999999", mapping.imdbId)
            assertEquals(season, mapping.season)

            val combined = FillerEpisodeCheck.CombinedMedia(mapping = mapping, show = show)
            assertEquals(mapping, combined.mapping)
            assertEquals(show, combined.show)

            val emptyDb = FillerEpisodeCheck.Database()
            assertTrue(emptyDb.mal.isEmpty())
            assertTrue(emptyDb.anilist.isEmpty())
            assertTrue(emptyDb.kitsu.isEmpty())
            assertTrue(emptyDb.imdb.isEmpty())
            assertTrue(emptyDb.tmdb.isEmpty())
            assertTrue(emptyDb.name.isEmpty())
        }
    }
}
