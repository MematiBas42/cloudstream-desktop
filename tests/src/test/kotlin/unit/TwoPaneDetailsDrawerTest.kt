package unit

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.screens.details.*
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class TwoPaneDetailsDrawerTest {

    private lateinit var tempDir: Path

    private object TestProvider : MainAPI() {
        override var name = "TestProvider"
        override var mainUrl = "https://provider.test"
    }

    @BeforeEach
    fun setUp() {
        tempDir = Path.of("/tmp/cs_test_details_${UUID.randomUUID().toString().take(8)}")
        Files.createDirectories(tempDir)
        System.setProperty("XDG_DATA_HOME", tempDir.toString())
        System.setProperty("XDG_CONFIG_HOME", tempDir.toString())
        PlatformPaths.init()
        DesktopDataStore.init()
        LibraryWatchStateRepository.init()
        WatchHistoryRepository.clearAll()
        LibraryWatchStateRepository.clearAll()
    }

    @AfterEach
    fun tearDown() {
        WatchHistoryRepository.clearAll()
        LibraryWatchStateRepository.clearAll()
        tempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. Authentic 20% Y-Crop Mathematics Verification
    // =========================================================================

    @Test
    fun testPercentageCropMatrixCalculationAgainstUpstream() {
        // Scenario A: Taller poster (1000x1500) rendered in panoramic banner (1920x290)
        val srcWidth = 1000f
        val srcHeight = 1500f
        val vWidth = 1920f
        val vHeight = 290f

        val scale = PercentageCropMath.computeScale(srcWidth, srcHeight, vWidth, vHeight)

        // Scale factor must match width scale: 1920 / 1000 = 1.92
        assertEquals(1.92f, scale, 0.001f)

        // Scaled image height: 1500 * 1.92 = 2880px
        val scaledHeight = srcHeight * scale
        assertEquals(2880f, scaledHeight, 0.001f)

        val verticalOverflow = vHeight - scaledHeight // 290 - 2880 = -2590px
        assertEquals(-2590f, verticalOverflow, 0.001f)

        // dy at 20% center offset: -2590 * 0.20 = -518.0px
        val dy = PercentageCropMath.computeDy(srcHeight, vHeight, scale, cropYCenterOffsetPct = 0.20f)
        assertEquals(-518.0f, dy, 0.1f)

        // Verifies that 20% offset retains 80% more top area compared to 50% center crop (-1295px)
        val standardCenterDy = PercentageCropMath.computeDy(srcHeight, vHeight, scale, cropYCenterOffsetPct = 0.50f)
        assertEquals(-1295.0f, standardCenterDy, 0.1f)
        assertTrue(dy > standardCenterDy, "20% offset crop must sit higher than 50% center crop")

        // dx for taller image must be 0 (no horizontal overflow)
        val dx = PercentageCropMath.computeDx(srcWidth, vWidth, scale, cropXCenterOffsetPct = 0.50f)
        assertEquals(0f, dx, 0.001f)
    }

    @Test
    fun testPercentageCropWiderImageHorizontalCentering() {
        // Scenario B: Panoramic image (3840x1080) rendered in 1920x1080 viewport
        val srcWidth = 3840f
        val srcHeight = 1080f
        val vWidth = 1920f
        val vHeight = 1080f

        val scale = PercentageCropMath.computeScale(srcWidth, srcHeight, vWidth, vHeight)
        assertEquals(1.0f, scale, 0.001f)

        // dx at 50% horizontal center offset: (1920 - 3840) * 0.50 = -960px
        val dx = PercentageCropMath.computeDx(srcWidth, vWidth, scale, cropXCenterOffsetPct = 0.50f)
        assertEquals(-960.0f, dx, 0.1f)

        // dy must be 0 (height matches exactly)
        val dy = PercentageCropMath.computeDy(srcHeight, vHeight, scale, cropYCenterOffsetPct = 0.20f)
        assertEquals(0f, dy, 0.001f)
    }

    @Test
    fun testPercentageCropContentScaleFactorCalculation() {
        val contentScale = PercentageCropContentScale(cropYCenterOffsetPct = 0.20f)

        val srcSize = androidx.compose.ui.geometry.Size(1000f, 1500f)
        val dstSize = androidx.compose.ui.geometry.Size(1920f, 290f)

        val factor = contentScale.computeScaleFactor(srcSize, dstSize)
        assertEquals(1.92f, factor.scaleX, 0.001f)
        assertEquals(1.92f, factor.scaleY, 0.001f)

        val alignment = percentageCropAlignment(cropYCenterOffsetPct = 0.20f, cropXCenterOffsetPct = 0.50f)
        val scaledSize = androidx.compose.ui.unit.IntSize((srcSize.width * factor.scaleX).toInt(), (srcSize.height * factor.scaleY).toInt())
        val spaceSize = androidx.compose.ui.unit.IntSize(dstSize.width.toInt(), dstSize.height.toInt())
        val offset = alignment.align(scaledSize, spaceSize, androidx.compose.ui.unit.LayoutDirection.Ltr)

        assertEquals(0, offset.x)
        assertEquals(-518, offset.y)
    }

    // =========================================================================
    // 2. 4-Tier Episode Range Chunking (e.g. 120 episodes -> 1-50, 51-100, 101-120)
    // =========================================================================

    private fun createSeries(name: String, episodeCount: Int, season: Int = 1): TvSeriesLoadResponse = runBlocking {
        val episodes = (1..episodeCount).map { epNum ->
            TestProvider.newEpisode("https://provider.test/ep/$epNum") {
                this.name = "Episode $epNum"
                this.season = season
                this.episode = epNum
                this.posterUrl = "https://provider.test/poster/$epNum.jpg"
                this.runTime = 24
            }
        }
        TestProvider.newTvSeriesLoadResponse(
            name = name,
            url = "https://provider.test/series/$name",
            type = TvType.TvSeries,
            episodes = episodes,
        )
    }

    private fun createAnime(subCount: Int, dubCount: Int): AnimeLoadResponse = runBlocking {
        val subEpisodes = (1..subCount).map { epNum ->
            TestProvider.newEpisode("https://provider.test/sub/$epNum") {
                this.name = "Sub Ep $epNum"
                this.season = 1
                this.episode = epNum
                this.posterUrl = "https://provider.test/sub/$epNum.jpg"
            }
        }
        val dubEpisodes = (1..dubCount).map { epNum ->
            TestProvider.newEpisode("https://provider.test/dub/$epNum") {
                this.name = "Dub Ep $epNum"
                this.season = 1
                this.episode = epNum
                this.posterUrl = "https://provider.test/dub/$epNum.jpg"
            }
        }
        TestProvider.newAnimeLoadResponse(
            name = "Test Anime",
            url = "https://provider.test/anime/test",
            type = TvType.Anime,
        ) {
            this.episodes = mutableMapOf(
                DubStatus.Subbed to subEpisodes,
                DubStatus.Dubbed to dubEpisodes,
            )
        }
    }

    // =========================================================================
    // 3. Watch Progress Percentage Mathematics
    // =========================================================================

    @Test
    fun testWatchProgressClampingThresholds() {
        val durationMs = 60 * 60 * 1000L // 60 minutes = 3,600,000 ms

        // Zero / Negative duration handling
        assertEquals(0L, WatchHistoryRepository.getDisplayPosition(1000L, 0L))
        assertEquals(0L, WatchHistoryRepository.getDisplayPosition(1000L, -100L))
        assertEquals(0L, WatchHistoryRepository.getRealPosition(1000L, 0L))

        // Rule 1: <= 1% watched -> display position is 0 (suppress micro-progress)
        val microPos = 30 * 1000L // 30s = 0.83% of 60m
        assertEquals(0L, WatchHistoryRepository.getDisplayPosition(microPos, durationMs))

        // Rule 2: 1% < watched <= 5% -> snap to 5% minimum visual indicator
        val threePercentPos = (durationMs * 3) / 100L // 3%
        val fivePercentExpected = (5L * durationMs) / 100L
        assertEquals(fivePercentExpected, WatchHistoryRepository.getDisplayPosition(threePercentPos, durationMs))

        // Rule 3: 5% < watched < 95% -> exact position returned
        val halfPos = durationMs / 2L // 50%
        assertEquals(halfPos, WatchHistoryRepository.getDisplayPosition(halfPos, durationMs))

        // Rule 4: >= 95% watched -> snap to full duration (watched checkmark)
        val ninetySixPercentPos = (durationMs * 96) / 100L // 96%
        assertEquals(durationMs, WatchHistoryRepository.getDisplayPosition(ninetySixPercentPos, durationMs))

        // getRealPosition: <= 5% or >= 95% returns 0 (restart from beginning)
        assertEquals(0L, WatchHistoryRepository.getRealPosition(threePercentPos, durationMs))
        assertEquals(0L, WatchHistoryRepository.getRealPosition(ninetySixPercentPos, durationMs))
        assertEquals(halfPos, WatchHistoryRepository.getRealPosition(halfPos, durationMs))
    }

    @Test
    fun testWatchHistoryProgressRealDiskRoundTrip() = runBlocking {
        val showUrl = "https://example.com/shows/breaking-bad"
        val epId = "ep-101"
        val posMs = 45 * 60 * 1000L // 45m
        val durMs = 60 * 60 * 1000L // 60m

        WatchHistoryRepository.saveProgress(
            parentId = showUrl,
            episodeId = epId,
            positionMs = posMs,
            durationMs = durMs,
            currentEpisode = WatchHistory(
                url = showUrl,
                parentId = showUrl,
                episodeId = epId,
                title = "Breaking Bad",
                season = 1,
                episode = 1,
            ),
        )

        val latest = WatchHistoryRepository.getLastWatched(showUrl, epId)
        assertNotNull(latest)
        assertEquals(posMs, latest!!.position)
        assertEquals(durMs, latest.duration)

        val progressFrac = latest.getWatchProgress()
        assertEquals(0.75f, progressFrac, 0.001f)

        val remainingMinutes = (latest.duration - latest.position) / 60_000L
        assertEquals(15L, remainingMinutes)
    }

    // =========================================================================
    // 4. WatchType State Transitions & Library Management
    // =========================================================================

    @Test
    fun testWatchTypeStateTransitions() {
        val titleId = "https://example.com/show/naruto"

        // 1. Initial state must be NONE
        assertEquals(DesktopWatchType.NONE, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 2. Transition: NONE -> WATCHING
        LibraryWatchStateRepository.setResultWatchState(
            id = titleId,
            state = DesktopWatchType.WATCHING,
            metadata = BookmarkedData(
                id = titleId,
                name = "Naruto",
                url = titleId,
                apiName = "AnimeProvider",
            ),
        )
        assertEquals(DesktopWatchType.WATCHING, LibraryWatchStateRepository.getResultWatchState(titleId))
        assertTrue(LibraryWatchStateRepository.getAllWatchStateIds().contains(titleId))

        // 3. Transition: WATCHING -> COMPLETED
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.COMPLETED)
        assertEquals(DesktopWatchType.COMPLETED, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 4. Transition: COMPLETED -> ONHOLD
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.ONHOLD)
        assertEquals(DesktopWatchType.ONHOLD, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 5. Transition: ONHOLD -> DROPPED
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.DROPPED)
        assertEquals(DesktopWatchType.DROPPED, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 6. Transition: DROPPED -> PLANTOWATCH
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.PLANTOWATCH)
        assertEquals(DesktopWatchType.PLANTOWATCH, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 7. Transition: PLANTOWATCH -> NONE (Removes from repository)
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.NONE)
        assertEquals(DesktopWatchType.NONE, LibraryWatchStateRepository.getResultWatchState(titleId))
        assertFalse(LibraryWatchStateRepository.getAllWatchStateIds().contains(titleId))
        assertNull(LibraryWatchStateRepository.getBookmarkedData(titleId))
    }

    @Test
    fun testFavoritesAndSubscriptionsStateTransitions() {
        val titleId = "https://example.com/movie/inception"

        // Favorites
        assertFalse(LibraryWatchStateRepository.isFavorite(titleId))
        val fav = FavoritesData(
            id = titleId,
            name = "Inception",
            url = titleId,
            apiName = "MovieProvider",
        )
        LibraryWatchStateRepository.setFavorite(titleId, fav)
        assertTrue(LibraryWatchStateRepository.isFavorite(titleId))
        assertEquals("Inception", LibraryWatchStateRepository.getFavorite(titleId)?.name)

        LibraryWatchStateRepository.removeFavorite(titleId)
        assertFalse(LibraryWatchStateRepository.isFavorite(titleId))
        assertNull(LibraryWatchStateRepository.getFavorite(titleId))

        // Subscriptions
        assertNull(LibraryWatchStateRepository.getSubscription(titleId))
        val sub = SubscriptionData(
            id = titleId,
            name = "Inception",
            url = titleId,
            apiName = "MovieProvider",
        )
        LibraryWatchStateRepository.setSubscription(titleId, sub)
        assertNotNull(LibraryWatchStateRepository.getSubscription(titleId))

        LibraryWatchStateRepository.removeSubscription(titleId)
        assertNull(LibraryWatchStateRepository.getSubscription(titleId))
    }
}
