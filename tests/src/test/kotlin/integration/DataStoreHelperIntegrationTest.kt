package integration

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.EpisodeSortType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper
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

class DataStoreHelperIntegrationTest {

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
    fun testSetViewPos120s300sAtomicPersistenceAndNormalization() {
        val episodeId = 98765

        // Call setViewPos(pos = 120s, dur = 300s)
        DataStoreHelper.setViewPos(id = episodeId, pos = 120L, dur = 300L)

        // Verify physical file on disk exists and contains non-empty bytes
        assertTrue(testDataFile.exists(), "Target datastore.json must exist on disk")
        assertTrue(testDataFile.length() > 0, "Target datastore.json must contain written bytes")

        // Reload DesktopDataStore from disk to simulate process restart
        DesktopDataStore.reload()

        // Verify that getViewPos returns PosDur normalized to milliseconds
        val posDur = DataStoreHelper.getViewPos(episodeId)
        assertNotNull(posDur, "PosDur should be readable after disk reload")
        assertEquals(120_000L, posDur!!.position, "120s must normalize to 120,000ms")
        assertEquals(300_000L, posDur.duration, "300s must normalize to 300,000ms")

        // Verify fixVisual() clamping
        val visual = posDur.fixVisual()
        assertEquals(120_000L, visual.position, "40% should remain unchanged")
        assertEquals(300_000L, visual.duration)
    }

    @Test
    fun testSetViewPosAndResumeProgression() {
        val seriesId = 444
        val ep1 = ResultEpisode(
            headerName = "Season 1 Ep 1",
            name = "Episode 1",
            poster = null,
            episode = 1,
            season = 1,
            id = 4401,
            parentId = seriesId,
            videoWatchState = VideoWatchState.None
        )
        val ep2 = ResultEpisode(
            headerName = "Season 1 Ep 2",
            name = "Episode 2",
            poster = null,
            episode = 2,
            season = 1,
            id = 4402,
            parentId = seriesId,
            videoWatchState = VideoWatchState.None
        )

        // 1. Under 90% -> keeps ep1 as last watched
        DataStoreHelper.setViewPosAndResume(
            id = ep1.id,
            position = 80_000L,
            duration = 100_000L,
            currentEpisode = ep1,
            nextEpisode = ep2
        )

        val lw1 = DataStoreHelper.getLastWatched(seriesId)
        assertNotNull(lw1)
        assertEquals(ep1.id, lw1!!.episodeId)

        // 2. >= 90% -> advances to ep2
        DataStoreHelper.setViewPosAndResume(
            id = ep1.id,
            position = 91_000L,
            duration = 100_000L,
            currentEpisode = ep1,
            nextEpisode = ep2
        )

        val lw2 = DataStoreHelper.getLastWatched(seriesId)
        assertNotNull(lw2)
        assertEquals(ep2.id, lw2!!.episodeId)
    }

    @Test
    fun testAccountSwitchingAndIsolation() {
        val id = 3333
        DataStoreHelper.setViewPos(id, 60_000L, 120_000L)

        // Switch to account 2
        val acc2 = DataStoreHelper.Account(keyIndex = 2, name = "Account 2", defaultImageIndex = 1)
        DataStoreHelper.setAccount(acc2)
        assertEquals("2", DataStoreHelper.currentAccount)
        assertNull(DataStoreHelper.getViewPos(id), "Account 2 must not see Account 0 data")

        // Switch back to account 0
        val acc0 = DataStoreHelper.Account(keyIndex = 0, name = "Account 0", defaultImageIndex = 0)
        DataStoreHelper.setAccount(acc0)
        assertEquals("0", DataStoreHelper.currentAccount)
        assertNotNull(DataStoreHelper.getViewPos(id), "Account 0 data must be preserved")
    }
}
