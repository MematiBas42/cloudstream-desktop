package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.EpisodeSortType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DataStoreHelperTest {

    private lateinit var testDataFile: File

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        DataStoreHelper.selectedKeyIndex = 0
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
    }

    @Test
    fun testSetViewPosSecondsNormalizationAndAtomicPersistence() {
        val episodeId = 12345
        // pos = 120s, dur = 300s (both in seconds: dur in 1 until 30_000)
        DataStoreHelper.setViewPos(id = episodeId, pos = 120L, dur = 300L)

        // 1. Verify datastore.json exists on disk and is not empty (atomic persistence)
        assertTrue(testDataFile.exists(), "datastore.json must physically exist on disk")
        assertTrue(testDataFile.length() > 0, "datastore.json on disk must contain data")

        // 2. Read directly from disk via DesktopDataStore reload to guarantee atomic disk sync
        DesktopDataStore.reload()

        // 3. Verify getViewPos normalizes to milliseconds
        val readPosDur = DataStoreHelper.getViewPos(episodeId)
        assertNotNull(readPosDur, "PosDur should not be null after persistence")
        assertEquals(120_000L, readPosDur!!.position, "Position must be normalized to 120,000 ms")
        assertEquals(300_000L, readPosDur.duration, "Duration must be normalized to 300,000 ms")

        // 4. Test calling with milliseconds directly (120,000ms, 300,000ms)
        val epMs = 12346
        DataStoreHelper.setViewPos(id = epMs, pos = 120_000L, dur = 300_000L)
        val readPosDurMs = DataStoreHelper.getViewPos(epMs)
        assertNotNull(readPosDurMs)
        assertEquals(120_000L, readPosDurMs!!.position)
        assertEquals(300_000L, readPosDurMs.duration)

        // 5. Test too-short duration filter (< 30s = 30,000ms)
        val epTooShort = 12347
        DataStoreHelper.setViewPos(id = epTooShort, pos = 10L, dur = 20L) // 20s < 30s
        assertNull(DataStoreHelper.getViewPos(epTooShort), "Durations < 30s must not be saved")
    }

    @Test
    fun testPosDurFixVisual() {
        val duration = 100_000L

        // <= 1% clamped to 0
        assertEquals(0L, DataStoreHelper.PosDur(1_000L, duration).fixVisual().position)
        assertEquals(0L, DataStoreHelper.PosDur(500L, duration).fixVisual().position)

        // <= 5% clamped to 5%
        assertEquals(5_000L, DataStoreHelper.PosDur(3_000L, duration).fixVisual().position)
        assertEquals(5_000L, DataStoreHelper.PosDur(5_000L, duration).fixVisual().position)

        // >= 95% clamped to duration
        assertEquals(duration, DataStoreHelper.PosDur(95_000L, duration).fixVisual().position)
        assertEquals(duration, DataStoreHelper.PosDur(98_000L, duration).fixVisual().position)

        // In between stays intact
        assertEquals(50_000L, DataStoreHelper.PosDur(50_000L, duration).fixVisual().position)

        // Duration <= 0
        assertEquals(0L, DataStoreHelper.PosDur(10L, 0L).fixVisual().position)
    }

    @Test
    fun testSetViewPosAndResumeAdvancesToNextEpisode() {
        val parentId = 999
        val ep1 = ResultEpisode(
            headerName = "Ep 1",
            name = "Episode 1",
            poster = null,
            episode = 1,
            season = 1,
            id = 1001,
            parentId = parentId,
            videoWatchState = VideoWatchState.None
        )
        val ep2 = ResultEpisode(
            headerName = "Ep 2",
            name = "Episode 2",
            poster = null,
            episode = 2,
            season = 1,
            id = 1002,
            parentId = parentId,
            videoWatchState = VideoWatchState.None
        )

        // Case A: Watched 50% (< 90%) -> Resume pointer should stay on ep1
        DataStoreHelper.setViewPosAndResume(
            id = ep1.id,
            position = 50_000L,
            duration = 100_000L,
            currentEpisode = ep1,
            nextEpisode = ep2
        )

        var lastWatched = DataStoreHelper.getLastWatched(parentId)
        assertNotNull(lastWatched)
        assertEquals(ep1.id, lastWatched!!.episodeId)
        assertEquals(1, lastWatched.episode)

        // Case B: Watched 92% (>= 90%) -> Resume pointer should advance to ep2
        DataStoreHelper.setViewPosAndResume(
            id = ep1.id,
            position = 92_000L,
            duration = 100_000L,
            currentEpisode = ep1,
            nextEpisode = ep2
        )

        lastWatched = DataStoreHelper.getLastWatched(parentId)
        assertNotNull(lastWatched)
        assertEquals(ep2.id, lastWatched!!.episodeId)
        assertEquals(2, lastWatched.episode)

        // Case C: Watched 95% on the LAST episode (nextEpisode == null) -> Last watched must be removed
        DataStoreHelper.setViewPosAndResume(
            id = ep2.id,
            position = 95_000L,
            duration = 100_000L,
            currentEpisode = ep2,
            nextEpisode = null
        )

        lastWatched = DataStoreHelper.getLastWatched(parentId)
        assertNull(lastWatched, "Last watched must be removed after completing the final episode")
    }

    @Test
    fun testMultiAccountProfileIsolation() {
        val epId = 7777

        // Account 0
        DataStoreHelper.selectedKeyIndex = 0
        assertEquals("0", DataStoreHelper.currentAccount)
        DataStoreHelper.setViewPos(epId, 120_000L, 300_000L)
        assertNotNull(DataStoreHelper.getViewPos(epId))

        // Switch to Account 1
        val account1 = DataStoreHelper.Account(
            keyIndex = 1,
            name = "User 2",
            defaultImageIndex = 2
        )
        DataStoreHelper.setAccount(account1)
        assertEquals("1", DataStoreHelper.currentAccount)

        // Account 1 should NOT see Account 0's viewing progress
        assertNull(DataStoreHelper.getViewPos(epId), "Account 1 must have isolated watch state")

        // Account 1 sets its own progress
        DataStoreHelper.setViewPos(epId, 200_000L, 300_000L)
        assertEquals(200_000L, DataStoreHelper.getViewPos(epId)?.position)

        // Switch back to Account 0
        val account0 = DataStoreHelper.Account(
            keyIndex = 0,
            name = "Default Account",
            defaultImageIndex = 0
        )
        DataStoreHelper.setAccount(account0)
        assertEquals("0", DataStoreHelper.currentAccount)
        assertEquals(120_000L, DataStoreHelper.getViewPos(epId)?.position, "Account 0 must retain original progress")
    }

    @Test
    fun testBookmarksAndWatchStateParity() {
        val titleId = 5555

        // Set watch state to WATCHING
        DataStoreHelper.setResultWatchState(titleId, WatchType.WATCHING.internalId)
        assertEquals(WatchType.WATCHING, DataStoreHelper.getResultWatchState(titleId))

        // Set bookmarked metadata
        val bookmark = DataStoreHelper.BookmarkedData(
            bookmarkedTime = 1000L,
            id = titleId,
            latestUpdatedTime = 1000L,
            name = "Test Movie",
            url = "https://example.com/test",
            apiName = "TestAPI",
            type = TvType.Movie,
            posterUrl = "https://example.com/poster.jpg",
            year = 2024
        )
        DataStoreHelper.setBookmarkedData(titleId, bookmark)

        val retrieved = DataStoreHelper.getBookmarkedData(titleId)
        assertNotNull(retrieved)
        assertEquals("Test Movie", retrieved!!.name)
        assertEquals("TestAPI", retrieved.apiName)
        assertEquals(2024, retrieved.year)

        // All bookmarks
        val all = DataStoreHelper.getAllBookmarkedData()
        assertEquals(1, all.size)
        assertEquals(titleId, all[0].id)

        // Setting watch state to NONE should delete bookmark data
        DataStoreHelper.setResultWatchState(titleId, WatchType.NONE.internalId)
        assertEquals(WatchType.NONE, DataStoreHelper.getResultWatchState(titleId))
        assertNull(DataStoreHelper.getBookmarkedData(titleId), "Setting watch state to NONE must delete bookmark data")
    }

    @Test
    fun testUserPreferenceDelegates() {
        DataStoreHelper.playBackSpeed = 1.5f
        assertEquals(1.5f, DataStoreHelper.playBackSpeed)

        DataStoreHelper.resultsSortingMode = EpisodeSortType.NUMBER_DESC
        assertEquals(EpisodeSortType.NUMBER_DESC, DataStoreHelper.resultsSortingMode)

        DataStoreHelper.pinnedProviders = arrayOf("ProviderA", "ProviderB")
        assertArrayEquals(arrayOf("ProviderA", "ProviderB"), DataStoreHelper.pinnedProviders)

        DataStoreHelper.setDub(101, DubStatus.Dubbed)
        assertEquals(DubStatus.Dubbed, DataStoreHelper.getDub(101))
    }
}
