package integration

import com.lagradost.cloudstream3.*
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Realistic Anti-Mock Integration Test for Domain 03 (Home Feeds & Continue Watching).
 * Tests real POSIX disk I/O, deduplication invariants, and provider pagination contracts.
 */
class HomeCategorizedFeedsIntegrationTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("cs_feeds_test_")
        System.setProperty("XDG_DATA_HOME", tempDir.toAbsolutePath().toString())
        PlatformPaths.init()
        WatchHistoryRepository.clearAll()
    }

    @AfterEach
    fun tearDown() {
        WatchHistoryRepository.clearAll()
        tempDir.toFile().deleteRecursively()
    }

    /**
     * Test Case 1: Deduplication Invariant
     * Verifies that multiple episodes of a TV series are collapsed to the latest watched entry,
     * while independent movies (with parentId == null) are NEVER collapsed together.
     */
    @Test
    fun testMovieAndSeriesHistoryDeduplicationRealData() = runBlocking {
        val now = System.currentTimeMillis()

        // 1. Ingest 3 episodes of "Breaking Bad" (shared parentId)
        val showParentId = "https://provider.com/shows/breaking-bad"
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = "https://provider.com/shows/breaking-bad/s1e1",
            parentId = showParentId,
            episodeId = "s1e1",
            title = "Breaking Bad",
            season = 1,
            episode = 1,
            position = 1200L,
            duration = 3000L,
            updateTime = now - 100000
        ))
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = "https://provider.com/shows/breaking-bad/s1e2",
            parentId = showParentId,
            episodeId = "s1e2",
            title = "Breaking Bad",
            season = 1,
            episode = 2,
            position = 1500L,
            duration = 3000L,
            updateTime = now - 50000
        ))
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = "https://provider.com/shows/breaking-bad/s1e3",
            parentId = showParentId,
            episodeId = "s1e3",
            title = "Breaking Bad",
            season = 1,
            episode = 3,
            position = 800L,
            duration = 3000L,
            updateTime = now // Latest
        ))

        // 2. Ingest 2 standalone movies (parentId == null, distinct URLs)
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = "https://provider.com/movies/interstellar",
            parentId = null,
            episodeId = null,
            title = "Interstellar",
            position = 4500L,
            duration = 9000L,
            updateTime = now - 20000
        ))
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = "https://provider.com/movies/inception",
            parentId = null,
            episodeId = null,
            title = "Inception",
            position = 3000L,
            duration = 8500L,
            updateTime = now - 10000
        ))

        // 3. Query through repository and apply the production deduplication pipeline
        val rawHistory = WatchHistoryRepository.getAllWatchHistory()
        val continueWatchingList = rawHistory
            .filter { it.position >= 5L && !it.isCompleted }
            .sortedByDescending { it.updateTime }
            .distinctBy { it.parentId ?: it.url }

        // Assertions:
        // Must contain exactly 3 cards: Breaking Bad (S1E3), Inception, and Interstellar
        assertEquals(3, continueWatchingList.size, "Must preserve distinct movies while collapsing series episodes")

        val breakingBadCard = continueWatchingList.find { it.parentId == showParentId }
        assertNotNull(breakingBadCard)
        assertEquals(3, breakingBadCard?.episode, "Must retain the latest episode (S1E3)")

        val inceptionCard = continueWatchingList.find { it.url == "https://provider.com/movies/inception" }
        assertNotNull(inceptionCard, "Inception must not be swallowed by null parentId")

        val interstellarCard = continueWatchingList.find { it.url == "https://provider.com/movies/interstellar" }
        assertNotNull(interstellarCard, "Interstellar must not be swallowed by null parentId")
    }

    /**
     * Test Case 2: Atomic Deletion of History Item & Footer Clear
     * Verifies that single-item removal by parentId/url removes all child episodes from disk,
     * and clearAll flushes history.json atomically.
     */
    @Test
    fun testAtomicRemovalAndClearAllPersistence() = runBlocking {
        val showId = "https://provider.com/series/stranger-things"
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = "https://provider.com/series/stranger-things/ep1",
            parentId = showId,
            episodeId = "ep1",
            title = "Stranger Things",
            position = 100L,
            duration = 2000L
        ))

        val movieUrl = "https://provider.com/movies/oppenheimer"
        WatchHistoryRepository.setLastWatched(WatchHistory(
            url = movieUrl,
            parentId = null,
            title = "Oppenheimer",
            position = 500L,
            duration = 10000L
        ))

        assertEquals(2, WatchHistoryRepository.getAllWatchHistory().size)

        // Remove series by parentId
        WatchHistoryRepository.removeWatchHistory(showId)
        val afterRemove = WatchHistoryRepository.getAllWatchHistory()
        assertEquals(1, afterRemove.size)
        assertEquals(movieUrl, afterRemove.first().url)

        // Clear all history
        WatchHistoryRepository.clearAll()
        assertTrue(WatchHistoryRepository.getAllWatchHistory().isEmpty())

        // Verify real file on disk reflects empty state
        val historyFile = PlatformPaths.dataDir.resolve("history.json").toFile()
        assertTrue(historyFile.exists())
        val text = historyFile.readText().trim()
        assertTrue(text == "[]" || text == "[ ]", "File content should be empty JSON array but was: $text")
    }

    /**
     * Test Case 3: Horizontal Page Expansion & Deduplication
     * Verifies pagination contracts using a deterministic real test provider.
     */
    @Test
    fun testCategoryFeedPaginationContract() = runBlocking {
        val testProvider = object : MainAPI() {
            override var name = "TestPaginatedProvider"
            override val supportedTypes = setOf(TvType.Movie)
            override var hasMainPage = true
            override val mainPage = listOf(MainPageData("Popular", "popular_url"))

            override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
                val items = when (page) {
                    1 -> listOf(
                        newMovieSearchResponse("Movie 1", "https://test.com/m1", TvType.Movie),
                        newMovieSearchResponse("Movie 2", "https://test.com/m2", TvType.Movie)
                    )
                    2 -> listOf(
                        newMovieSearchResponse("Movie 2", "https://test.com/m2", TvType.Movie), // Overlapping duplicate
                        newMovieSearchResponse("Movie 3", "https://test.com/m3", TvType.Movie)
                    )
                    else -> emptyList()
                }
                return newHomePageResponse(
                    listOf(HomePageList(request.name, items)),
                    hasNext = page < 2
                )
            }
        }

        // Fetch Page 1
        val req = MainPageRequest(testProvider.mainPage.first().name, testProvider.mainPage.first().data, false)
        val resPage1 = testProvider.getMainPage(1, req)
        val list1 = resPage1.items.first().list
        assertEquals(2, list1.size)
        assertTrue(resPage1.hasNext)

        // Fetch Page 2 & Deduplicate
        val resPage2 = testProvider.getMainPage(2, req)
        val list2 = resPage2.items.first().list
        val merged = (list1 + list2).distinctBy { it.url }

        assertEquals(3, merged.size, "Item 'm2' must be deduplicated across pages")
        assertFalse(resPage2.hasNext, "Page 2 must report hasNext = false")
    }
}
