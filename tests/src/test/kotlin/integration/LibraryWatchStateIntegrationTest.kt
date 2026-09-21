package integration

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.Bookmark
import com.lagradost.common.storage.BookmarkedData
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.DesktopWatchType
import com.lagradost.common.storage.FavoritesData
import com.lagradost.common.storage.LibraryWatchStateRepository
import com.lagradost.common.storage.SubscriptionData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Anti-Mock Integration Test Suite for Domain 10: Library Architecture & WatchType States.
 *
 * Verifies against real POSIX filesystem I/O:
 * 1. WatchType lifecycle state transitions: NONE -> WATCHING -> COMPLETED -> NONE.
 * 2. Independent favorites management and toggling.
 * 3. Subscription tracking with episode release delta detection.
 * 4. Multi-account isolation under $currentAccount namespaces.
 * 5. Crash-resilient persistence and disk verification on $XDG_DATA_HOME/cloudstream/datastore.json.
 * 6. Reactive libraryUpdatesFlow event emissions on state mutations.
 * 7. Legacy flat bookmark migration to categorized BookmarkedData.
 * 8. Thread-safety under concurrent load.
 */
class LibraryWatchStateIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var testDataFile: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("cs_library_test_${UUID.randomUUID().toString().take(8)}")
        testDataFile = tempDir.resolve("cloudstream").resolve("datastore.json").toFile()
        testDataFile.parentFile?.mkdirs()

        // Configure DesktopDataStore to target temporary file
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        // Reset account to default "0" and clear data
        LibraryWatchStateRepository.currentAccount = "0"
        LibraryWatchStateRepository.clearAll()
    }

    @AfterEach
    fun tearDown() {
        LibraryWatchStateRepository.currentAccount = "0"
        LibraryWatchStateRepository.clearAll()
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        tempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. WatchType Lifecycle & State Transitions
    // =========================================================================

    @Test
    fun testWatchTypeTransitionsLifecycle() {
        val titleId = "show_cyberpunk_2077"

        // 1. Initial state must be NONE
        assertEquals(DesktopWatchType.NONE, LibraryWatchStateRepository.getResultWatchState(titleId))
        assertNull(LibraryWatchStateRepository.getBookmarkedData(titleId))

        // 2. Transition NONE -> WATCHING with BookmarkedData
        val meta = BookmarkedData(
            id = titleId,
            name = "Cyberpunk: Edgerunners",
            url = "https://example.com/show/edgerunners",
            apiName = "NetflixProvider",
            type = "TvSeries",
            posterUrl = "https://example.com/posters/edgerunners.jpg",
            year = 2022,
            tags = listOf("Action", "Sci-Fi", "Anime")
        )
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.WATCHING, meta)

        assertEquals(DesktopWatchType.WATCHING, LibraryWatchStateRepository.getResultWatchState(titleId))
        val retrieved = LibraryWatchStateRepository.getBookmarkedData(titleId)
        assertNotNull(retrieved)
        assertEquals("Cyberpunk: Edgerunners", retrieved?.name)
        assertEquals("NetflixProvider", retrieved?.apiName)
        assertEquals(2022, retrieved?.year)

        // 3. Transition WATCHING -> COMPLETED
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.COMPLETED)
        assertEquals(DesktopWatchType.COMPLETED, LibraryWatchStateRepository.getResultWatchState(titleId))
        // Metadata must persist across category transitions
        assertEquals("Cyberpunk: Edgerunners", LibraryWatchStateRepository.getBookmarkedData(titleId)?.name)

        // 4. Transition COMPLETED -> PLANTOWATCH
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.PLANTOWATCH)
        assertEquals(DesktopWatchType.PLANTOWATCH, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 5. Transition PLANTOWATCH -> ONHOLD
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.ONHOLD)
        assertEquals(DesktopWatchType.ONHOLD, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 6. Transition ONHOLD -> DROPPED
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.DROPPED)
        assertEquals(DesktopWatchType.DROPPED, LibraryWatchStateRepository.getResultWatchState(titleId))

        // 7. Transition DROPPED -> NONE (Upstream contract: setting NONE purges storage keys)
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.NONE)
        assertEquals(DesktopWatchType.NONE, LibraryWatchStateRepository.getResultWatchState(titleId))
        assertNull(LibraryWatchStateRepository.getBookmarkedData(titleId), "Setting NONE must remove BookmarkedData")
    }

    // =========================================================================
    // 2. Real Physical Disk I/O & Persistence Verification
    // =========================================================================

    @Test
    fun testRealDiskPersistenceAndReloadIntegrity() {
        val titleId = "movie_interstellar_2014"

        val bookmark = BookmarkedData(
            id = titleId,
            name = "Interstellar",
            url = "https://example.com/movie/interstellar",
            apiName = "ParamountProvider",
            type = "Movie",
            year = 2014,
            plot = "A team of explorers travel through a wormhole in space."
        )

        val favorite = FavoritesData(
            id = titleId,
            name = "Interstellar",
            url = "https://example.com/movie/interstellar",
            apiName = "ParamountProvider",
            year = 2014
        )

        val sub = SubscriptionData(
            id = titleId,
            name = "Interstellar Extras",
            url = "https://example.com/movie/interstellar/extras",
            apiName = "ParamountProvider",
            lastSeenEpisodeCount = mapOf("None" to 1)
        )

        // Persist records
        LibraryWatchStateRepository.setResultWatchState(titleId, DesktopWatchType.WATCHING, bookmark)
        LibraryWatchStateRepository.setFavorite(titleId, favorite)
        LibraryWatchStateRepository.setSubscription(titleId, sub)

        // 1. Verify physical file exists and has content
        assertTrue(testDataFile.exists(), "datastore.json must physically exist on disk")
        assertTrue(testDataFile.length() > 0, "datastore.json must not be zero bytes")

        // 2. Verify raw disk content contains upstream keys
        val diskJson = testDataFile.readText()
        assertTrue(diskJson.contains("0/result_watch_state/$titleId"), "Disk must contain watch state key")
        assertTrue(diskJson.contains("0/result_watch_state_data/$titleId"), "Disk must contain watch state data key")
        assertTrue(diskJson.contains("0/result_favorites_state_data/$titleId"), "Disk must contain favorites key")
        assertTrue(diskJson.contains("0/result_subscribed_state_data/$titleId"), "Disk must contain subscriptions key")

        // Parse disk JSON as raw map to verify Jackson serialization correctness
        val rawMap: Map<String, String> = DesktopDataStore.mapper.readValue(testDataFile)
        assertTrue(rawMap.containsKey("0/result_watch_state/$titleId"))
        assertEquals("2", rawMap["0/result_watch_state/$titleId"], "WATCHING internalId is 2")

        // 3. Simulate process restart by reloading from disk
        DesktopDataStore.reload()

        // 4. Verify restored state
        assertEquals(DesktopWatchType.WATCHING, LibraryWatchStateRepository.getResultWatchState(titleId))
        val restoredBookmark = LibraryWatchStateRepository.getBookmarkedData(titleId)
        assertNotNull(restoredBookmark)
        assertEquals("Interstellar", restoredBookmark?.name)
        assertEquals("A team of explorers travel through a wormhole in space.", restoredBookmark?.plot)

        assertTrue(LibraryWatchStateRepository.isFavorite(titleId))
        val restoredFav = LibraryWatchStateRepository.getFavorite(titleId)
        assertNotNull(restoredFav)
        assertEquals("Interstellar", restoredFav?.name)

        val restoredSub = LibraryWatchStateRepository.getSubscription(titleId)
        assertNotNull(restoredSub)
        assertEquals(1, restoredSub?.lastSeenEpisodeCount?.get("None"))
    }

    // =========================================================================
    // 3. Favorites Independence & Toggling
    // =========================================================================

    @Test
    fun testFavoritesTogglingAndWatchStateIndependence() {
        val favId = "fav_spirited_away"

        // Initially not favorite
        assertFalse(LibraryWatchStateRepository.isFavorite(favId))

        val favData = FavoritesData(
            id = favId,
            name = "Spirited Away",
            url = "https://example.com/movie/spirited-away",
            apiName = "GhibliProvider",
            type = "AnimeMovie"
        )

        // Set favorite
        LibraryWatchStateRepository.setFavorite(favId, favData)
        assertTrue(LibraryWatchStateRepository.isFavorite(favId))
        assertEquals("Spirited Away", LibraryWatchStateRepository.getFavorite(favId)?.name)

        // Favorite must NOT pollute watch state
        assertEquals(DesktopWatchType.NONE, LibraryWatchStateRepository.getResultWatchState(favId))

        // Set watch state to COMPLETED
        LibraryWatchStateRepository.setResultWatchState(favId, DesktopWatchType.COMPLETED)
        assertEquals(DesktopWatchType.COMPLETED, LibraryWatchStateRepository.getResultWatchState(favId))
        assertTrue(LibraryWatchStateRepository.isFavorite(favId), "Watch state change must not alter favorite status")

        // Remove favorite
        LibraryWatchStateRepository.removeFavorite(favId)
        assertFalse(LibraryWatchStateRepository.isFavorite(favId))
        // Watch state must still be COMPLETED
        assertEquals(DesktopWatchType.COMPLETED, LibraryWatchStateRepository.getResultWatchState(favId))

        // Check getAllFavorites
        assertTrue(LibraryWatchStateRepository.getAllFavorites().none { it.id == favId })
    }

    // =========================================================================
    // 4. Subscriptions Management & Episode Delta Detection
    // =========================================================================

    @Test
    fun testSubscriptionsRetrievalAndEpisodeReleaseDelta() {
        val seriesId = "sub_frieren_journey"

        val initialEpisodes = mapOf(
            "Subbed" to 10,
            "Dubbed" to 8
        )

        val subData = SubscriptionData(
            id = seriesId,
            name = "Frieren: Beyond Journey's End",
            url = "https://example.com/anime/frieren",
            apiName = "AnimeProvider",
            lastSeenEpisodeCount = initialEpisodes
        )

        LibraryWatchStateRepository.setSubscription(seriesId, subData)

        // Verify retrieval
        val retrieved = LibraryWatchStateRepository.getSubscription(seriesId)
        assertNotNull(retrieved)
        assertEquals(10, retrieved?.lastSeenEpisodeCount?.get("Subbed"))
        assertEquals(8, retrieved?.lastSeenEpisodeCount?.get("Dubbed"))

        val allSubs = LibraryWatchStateRepository.getAllSubscriptions()
        assertEquals(1, allSubs.size)
        assertEquals(seriesId, allSubs.first().id)

        // Simulate upstream SubscriptionWorkManager episode delta check
        val incomingEpisodes = mapOf(
            "Subbed" to 11,
            "Dubbed" to 8
        )

        val lastSeenSubbed = retrieved?.lastSeenEpisodeCount?.get("Subbed") ?: 0
        val incomingSubbed = incomingEpisodes["Subbed"] ?: 0
        val hasNewEpisode = incomingSubbed > lastSeenSubbed

        assertTrue(hasNewEpisode, "Episode 11 > Episode 10 must indicate a new release")

        // Update subscription with newly seen count
        val updatedSub = retrieved!!.copy(
            latestUpdatedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = incomingEpisodes
        )
        LibraryWatchStateRepository.setSubscription(seriesId, updatedSub)

        val afterUpdate = LibraryWatchStateRepository.getSubscription(seriesId)
        assertEquals(11, afterUpdate?.lastSeenEpisodeCount?.get("Subbed"))

        // Remove subscription
        LibraryWatchStateRepository.removeSubscription(seriesId)
        assertNull(LibraryWatchStateRepository.getSubscription(seriesId))
        assertTrue(LibraryWatchStateRepository.getAllSubscriptions().isEmpty())
    }

    // =========================================================================
    // 5. Reactive Event Emission (libraryUpdatesFlow)
    // =========================================================================

    @Test
    fun testReactiveLibraryUpdatesFlow() {
        val initialCount = LibraryWatchStateRepository.libraryUpdatesFlow.value

        // 1. Set watch state
        LibraryWatchStateRepository.setResultWatchState(
            "test_reactive_1",
            DesktopWatchType.WATCHING,
            BookmarkedData(id = "test_reactive_1", name = "Test 1", url = "url1", apiName = "api1")
        )
        assertEquals(initialCount + 1, LibraryWatchStateRepository.libraryUpdatesFlow.value)

        // 2. Set favorite
        LibraryWatchStateRepository.setFavorite(
            "test_reactive_1",
            FavoritesData(id = "test_reactive_1", name = "Test 1", url = "url1", apiName = "api1")
        )
        assertEquals(initialCount + 2, LibraryWatchStateRepository.libraryUpdatesFlow.value)

        // 3. Set subscription
        LibraryWatchStateRepository.setSubscription(
            "test_reactive_1",
            SubscriptionData(id = "test_reactive_1", name = "Test 1", url = "url1", apiName = "api1")
        )
        assertEquals(initialCount + 3, LibraryWatchStateRepository.libraryUpdatesFlow.value)

        // 4. Remove favorite
        LibraryWatchStateRepository.removeFavorite("test_reactive_1")
        assertEquals(initialCount + 4, LibraryWatchStateRepository.libraryUpdatesFlow.value)

        // 5. Remove subscription
        LibraryWatchStateRepository.removeSubscription("test_reactive_1")
        assertEquals(initialCount + 5, LibraryWatchStateRepository.libraryUpdatesFlow.value)

        // 6. Reset watch state to NONE
        LibraryWatchStateRepository.setResultWatchState("test_reactive_1", DesktopWatchType.NONE)
        assertEquals(initialCount + 6, LibraryWatchStateRepository.libraryUpdatesFlow.value)
    }

    // =========================================================================
    // 6. Multi-Account Isolation
    // =========================================================================

    @Test
    fun testMultiAccountIsolation() {
        val id = "shared_id"

        // Account 0: Set WATCHING
        LibraryWatchStateRepository.currentAccount = "0"
        LibraryWatchStateRepository.setResultWatchState(
            id,
            DesktopWatchType.WATCHING,
            BookmarkedData(id = id, name = "Account 0 Show", url = "u0", apiName = "a0")
        )
        LibraryWatchStateRepository.setFavorite(
            id,
            FavoritesData(id = id, name = "Account 0 Show", url = "u0", apiName = "a0")
        )

        // Switch to Account 1
        LibraryWatchStateRepository.currentAccount = "1"
        assertEquals(DesktopWatchType.NONE, LibraryWatchStateRepository.getResultWatchState(id))
        assertNull(LibraryWatchStateRepository.getBookmarkedData(id))
        assertFalse(LibraryWatchStateRepository.isFavorite(id))

        // Account 1: Set COMPLETED
        LibraryWatchStateRepository.setResultWatchState(
            id,
            DesktopWatchType.COMPLETED,
            BookmarkedData(id = id, name = "Account 1 Show", url = "u1", apiName = "a1")
        )

        // Switch back to Account 0
        LibraryWatchStateRepository.currentAccount = "0"
        assertEquals(DesktopWatchType.WATCHING, LibraryWatchStateRepository.getResultWatchState(id))
        assertEquals("Account 0 Show", LibraryWatchStateRepository.getBookmarkedData(id)?.name)
        assertTrue(LibraryWatchStateRepository.isFavorite(id))

        // Switch back to Account 1
        LibraryWatchStateRepository.currentAccount = "1"
        assertEquals(DesktopWatchType.COMPLETED, LibraryWatchStateRepository.getResultWatchState(id))
        assertEquals("Account 1 Show", LibraryWatchStateRepository.getBookmarkedData(id)?.name)
        assertFalse(LibraryWatchStateRepository.isFavorite(id))
    }

    // =========================================================================
    // 7. Legacy Flat Bookmark Migration
    // =========================================================================

    @Test
    fun testLegacyBookmarkMigrationToCategorizedData() {
        // Seed legacy flat bookmark
        val legacy = Bookmark(
            id = "legacy_show_42",
            title = "Legacy Breaking Bad",
            url = "https://example.com/show/bb",
            apiName = "LegacyAPI",
            posterUrl = "https://example.com/bb.jpg"
        )
        DesktopDataStore.addBookmark(legacy)
        assertTrue(DesktopDataStore.isBookmarked("legacy_show_42"))

        // Run migration
        LibraryWatchStateRepository.migrateLegacyBookmarks()

        // 1. Legacy key must be purged
        assertFalse(DesktopDataStore.isBookmarked("legacy_show_42"))
        assertTrue(DesktopDataStore.getBookmarks().isEmpty())

        // 2. Item must now exist in PLANTOWATCH
        assertEquals(DesktopWatchType.PLANTOWATCH, LibraryWatchStateRepository.getResultWatchState("legacy_show_42"))

        // 3. Rich metadata must be preserved
        val migratedData = LibraryWatchStateRepository.getBookmarkedData("legacy_show_42")
        assertNotNull(migratedData)
        assertEquals("Legacy Breaking Bad", migratedData?.name)
        assertEquals("https://example.com/show/bb", migratedData?.url)
        assertEquals("LegacyAPI", migratedData?.apiName)
        assertEquals("https://example.com/bb.jpg", migratedData?.posterUrl)
    }

    // =========================================================================
    // 8. Concurrent Thread-Safety Stress Test
    // =========================================================================

    @Test
    fun testConcurrentOperationsThreadSafety() {
        val threadCount = 6
        val operationsPerThread = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (op in 0 until operationsPerThread) {
                        val id = "concurrent_${t}_$op"
                        val type = if (op % 2 == 0) DesktopWatchType.WATCHING else DesktopWatchType.COMPLETED

                        LibraryWatchStateRepository.setResultWatchState(
                            id,
                            type,
                            BookmarkedData(id = id, name = "Title $id", url = "url_$id", apiName = "api_$t")
                        )

                        LibraryWatchStateRepository.setFavorite(
                            id,
                            FavoritesData(id = id, name = "Title $id", url = "url_$id", apiName = "api_$t")
                        )

                        LibraryWatchStateRepository.setSubscription(
                            id,
                            SubscriptionData(id = id, name = "Title $id", url = "url_$id", apiName = "api_$t")
                        )

                        // Verify read consistency
                        assertEquals(type, LibraryWatchStateRepository.getResultWatchState(id))
                        assertTrue(LibraryWatchStateRepository.isFavorite(id))
                        assertNotNull(LibraryWatchStateRepository.getSubscription(id))
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operations must complete within 10 seconds")
        assertEquals(0, errors.get(), "Zero thread concurrency errors allowed")
        executor.shutdown()

        // Verify total keys persisted on disk
        assertTrue(testDataFile.exists())
        assertTrue(testDataFile.length() > 0)
    }
}
