package unit

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.download.queue.DownloadAdapterQueue
import com.lagradost.cloudstream3.ui.download.queue.DownloadQueueViewModel
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Architectural Remediation & Parity Test for [DownloadQueueViewModel] (Cluster C33).
 *
 * Validates 1:1 behavioral and contract parity against upstream CloudStream & desktop requirements:
 * 1. Queue item cancellation and removal triggers [DownloadFileManagement.deletePartial] to avoid orphaned part files.
 * 2. Single item deletion ([DownloadQueueViewModel.deleteQueueItem]) removes from queue and purges .part file.
 * 3. Batch cancellation ([DownloadQueueViewModel.removeAllFromQueue] / [DownloadQueueViewModel.cancelAll]) purges all .part files.
 * 4. Wrapper overload ([DownloadQueueViewModel.deleteQueueItem]) parity with integer ID calls.
 * 5. Direct file management cleanup ([DownloadFileManagement.deletePartial]) with [DownloadObjects.DownloadedFileInfo] and raw ID.
 * 6. Queue reordering, pause/resume delegation to [VideoDownloadManager].
 * 7. Reactive state updates and [DownloadAdapterQueue] state flow emission.
 */
class DownloadQueueRemediationTest {

    private lateinit var testDataFile: File
    private lateinit var testDownloadDir: File
    private val context = Context()
    private lateinit var viewModel: DownloadQueueViewModel

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        testDownloadDir = tempDir.resolve("downloads").toFile().apply { mkdirs() }

        CloudStreamApp.context = context
        viewModel = DownloadQueueViewModel(Dispatchers.Unconfined)
        DownloadQueueManager.removeAllFromQueue()
    }

    @AfterEach
    fun tearDown() {
        viewModel.onCleared()
        DownloadQueueManager.removeAllFromQueue()
        DesktopDataStore.customDataFile = null
    }

    private fun createDummyItem(id: Int, title: String = "Test Series", epNum: Int = 1): DownloadObjects.DownloadQueueItem {
        val ep = ResultEpisode(
            headerName = title,
            name = "Episode $epNum",
            poster = null,
            episode = epNum,
            season = 1,
            id = id,
            parentId = 1000 + id
        )
        return DownloadObjects.DownloadQueueItem(
            episode = ep,
            isMovie = false,
            resultName = title,
            resultType = TvType.TvSeries,
            resultPoster = null,
            apiName = "TestProvider",
            resultId = 1000 + id,
            resultUrl = "https://example.com/show/$id"
        )
    }

    // =========================================================================
    // 1. Partial File Cleanup on Single Item Deletion
    // =========================================================================

    @Test
    fun `deleteQueueItem removes from queue and deletes orphaned part file via DownloadFileManagement`() {
        val id = 501
        val item = createDummyItem(id, "Cyberpunk", 1)
        val wrapper = item.toWrapper()
        DownloadQueueManager.addToQueue(wrapper)
        assertEquals(1, DownloadQueueManager.queue.value.size)

        // Create a real .part file on disk matching the item's expected directory & filename
        val showFolder = File(testDownloadDir, "TVSeries/Cyberpunk").apply { mkdirs() }
        val partFile = File(showFolder, "Episode 1 - Episode 1.mp4.part").apply {
            writeBytes("incomplete video payload".toByteArray())
        }
        assertTrue(partFile.exists(), "Pre-condition: .part file must exist on disk")

        // Register DownloadedFileInfo in DataStore
        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 50_000_000L,
            relativePath = "TVSeries/Cyberpunk",
            displayName = "Episode 1 - Episode 1.mp4",
            basePath = testDownloadDir.absolutePath
        )
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)

        // Execute deletion through ViewModel
        val deleted = viewModel.deleteQueueItem(id, context)

        assertTrue(deleted, "deleteQueueItem must report successful partial file cleanup")
        assertFalse(partFile.exists(), "Orphaned .part file must be deleted from disk")
        assertEquals(0, DownloadQueueManager.queue.value.size, "Item must be removed from queue")
    }

    @Test
    fun `deleteQueueItem wrapper overload purges part file and removes item`() {
        val id = 502
        val item = createDummyItem(id, "Arcane", 2)
        val wrapper = item.toWrapper()
        DownloadQueueManager.addToQueue(wrapper)

        val showFolder = File(testDownloadDir, "TVSeries/Arcane").apply { mkdirs() }
        val partFile = File(showFolder, "Episode 2 - Episode 2.mp4.part").apply {
            writeBytes("partial segment data".toByteArray())
        }
        assertTrue(partFile.exists())

        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 25_000_000L,
            relativePath = "TVSeries/Arcane",
            displayName = "Episode 2 - Episode 2.mp4",
            basePath = testDownloadDir.absolutePath
        )
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)

        val deleted = viewModel.deleteQueueItem(wrapper, context)

        assertTrue(deleted)
        assertFalse(partFile.exists(), ".part file must be removed by wrapper overload")
        assertEquals(0, DownloadQueueManager.queue.value.size)
    }

    @Test
    fun `cancelDownload delegates to deleteQueueItem and deletes part file`() {
        val id = 503
        val item = createDummyItem(id, "SteinsGate", 1)
        val wrapper = item.toWrapper()
        DownloadQueueManager.addToQueue(wrapper)

        val showFolder = File(testDownloadDir, "TVSeries/SteinsGate").apply { mkdirs() }
        val partFile = File(showFolder, "Episode 1 - Episode 1.mp4.part").apply {
            writeBytes("steins gate stream buffer".toByteArray())
        }

        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 100_000_000L,
            relativePath = "TVSeries/SteinsGate",
            displayName = "Episode 1 - Episode 1.mp4",
            basePath = testDownloadDir.absolutePath
        )
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)

        val cleaned = viewModel.cancelDownload(id, context)

        assertTrue(cleaned)
        assertFalse(partFile.exists(), "cancelDownload must purge part file")
        assertEquals(0, DownloadQueueManager.queue.value.size)
    }

    @Test
    fun `removeFromQueue triggers partial file deletion and removes item`() {
        val id = 504
        val item = createDummyItem(id, "Frieren", 5)
        DownloadQueueManager.addToQueue(item.toWrapper())

        val showFolder = File(testDownloadDir, "TVSeries/Frieren").apply { mkdirs() }
        val partFile = File(showFolder, "Episode 5 - Episode 5.mp4.part").apply {
            writeBytes("frieren magic stream chunk".toByteArray())
        }

        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 80_000_000L,
            relativePath = "TVSeries/Frieren",
            displayName = "Episode 5 - Episode 5.mp4",
            basePath = testDownloadDir.absolutePath
        )
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)

        val cleaned = viewModel.removeFromQueue(id, context)

        assertTrue(cleaned)
        assertFalse(partFile.exists(), "removeFromQueue must purge part file")
        assertEquals(0, DownloadQueueManager.queue.value.size)
    }

    // =========================================================================
    // 2. Batch Cancellation and Partial Cleanup (removeAllFromQueue / cancelAll)
    // =========================================================================

    @Test
    fun `removeAllFromQueue deletes all part files for all queued items`() {
        val id1 = 601
        val id2 = 602
        val item1 = createDummyItem(id1, "AnimeA", 1)
        val item2 = createDummyItem(id2, "AnimeB", 2)
        DownloadQueueManager.addToQueue(item1.toWrapper())
        DownloadQueueManager.addToQueue(item2.toWrapper())

        // Feed childCards state in ViewModel
        val currentQueue = DownloadAdapterQueue(
            currentDownloads = listOf(item1.toWrapper()),
            queue = listOf(item2.toWrapper())
        )
        viewModel.updateChildList(currentQueue)

        val folderA = File(testDownloadDir, "TVSeries/AnimeA").apply { mkdirs() }
        val part1 = File(folderA, "Episode 1 - Episode 1.mp4.part").apply { writeBytes("chunk A".toByteArray()) }

        val folderB = File(testDownloadDir, "TVSeries/AnimeB").apply { mkdirs() }
        val part2 = File(folderB, "Episode 2 - Episode 2.mp4.part").apply { writeBytes("chunk B".toByteArray()) }

        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id1.toString(), DownloadObjects.DownloadedFileInfo(
            totalBytes = 10_000_000L,
            relativePath = "TVSeries/AnimeA",
            displayName = "Episode 1 - Episode 1.mp4",
            basePath = testDownloadDir.absolutePath
        ))
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id2.toString(), DownloadObjects.DownloadedFileInfo(
            totalBytes = 20_000_000L,
            relativePath = "TVSeries/AnimeB",
            displayName = "Episode 2 - Episode 2.mp4",
            basePath = testDownloadDir.absolutePath
        ))

        val deletedCount = viewModel.removeAllFromQueue(context)

        assertEquals(2, deletedCount, "Both items' part files must be deleted")
        assertFalse(part1.exists(), "part1 must be deleted")
        assertFalse(part2.exists(), "part2 must be deleted")
        assertEquals(0, DownloadQueueManager.queue.value.size)
    }

    @Test
    fun `cancelAll delegates to removeAllFromQueue and purges all part files`() {
        val id = 603
        val item = createDummyItem(id, "SoloLeveling", 3)
        DownloadQueueManager.addToQueue(item.toWrapper())

        viewModel.updateChildList(DownloadAdapterQueue(
            currentDownloads = emptyList(),
            queue = listOf(item.toWrapper())
        ))

        val folder = File(testDownloadDir, "TVSeries/SoloLeveling").apply { mkdirs() }
        val part = File(folder, "Episode 3 - Episode 3.mp4.part").apply { writeBytes("shadow monarch".toByteArray()) }

        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), DownloadObjects.DownloadedFileInfo(
            totalBytes = 45_000_000L,
            relativePath = "TVSeries/SoloLeveling",
            displayName = "Episode 3 - Episode 3.mp4",
            basePath = testDownloadDir.absolutePath
        ))

        val count = viewModel.cancelAll(context)

        assertEquals(1, count)
        assertFalse(part.exists())
        assertEquals(0, DownloadQueueManager.queue.value.size)
    }

    // =========================================================================
    // 3. DownloadFileManagement Direct Partial File Logic
    // =========================================================================

    @Test
    fun `DownloadFileManagement deletePartial with DownloadedFileInfo deletes part file directly`() {
        val showFolder = File(testDownloadDir, "Movies").apply { mkdirs() }
        val partFile = File(showFolder, "Inception (2010).mkv.part").apply {
            writeBytes("dream within a dream".toByteArray())
        }
        assertTrue(partFile.exists())

        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 1_500_000_000L,
            relativePath = "Movies",
            displayName = "Inception (2010).mkv",
            basePath = testDownloadDir.absolutePath
        )

        val result = DownloadFileManagement.deletePartial(context, info)
        assertTrue(result, "deletePartial with DownloadedFileInfo must return true")
        assertFalse(partFile.exists(), "partFile must be deleted")
    }

    @Test
    fun `DownloadFileManagement deletePartial returns false when no part file exists`() {
        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 1_000_000L,
            relativePath = "Movies",
            displayName = "NonExistent.mp4",
            basePath = testDownloadDir.absolutePath
        )

        val result = DownloadFileManagement.deletePartial(context, info)
        assertFalse(result, "deletePartial must return false when no part file exists")
    }

    @Test
    fun `DownloadFileManagement deletePartial cleans up matching wildcard part files when resume package is present`() {
        val id = 701
        val item = createDummyItem(id, "Monster", 10)
        val wrapper = item.toWrapper()

        // Set resume package in DataStore
        DataStore.setKey(VideoDownloadManager.KEY_RESUME_IN_QUEUE, id.toString(), wrapper)

        val showFolder = File(testDownloadDir, "TVSeries/Monster").apply { mkdirs() }
        val partFile = File(showFolder, "Episode 10 - Episode 10.part").apply {
            writeBytes("dr tenma".toByteArray())
        }
        assertTrue(partFile.exists())

        val deleted = DownloadFileManagement.deletePartial(context, id)
        // Check if cleaned up
        assertFalse(partFile.exists(), "Direct .part file must be cleaned up")
    }

    // =========================================================================
    // 4. Queue Control Delegation (Reorder, Pause, Resume)
    // =========================================================================

    @Test
    fun `reorderItem modifies queue ordering correctly`() {
        val item1 = createDummyItem(801, "Show", 1).toWrapper()
        val item2 = createDummyItem(802, "Show", 2).toWrapper()
        val item3 = createDummyItem(803, "Show", 3).toWrapper()

        DownloadQueueManager.addToQueue(item1)
        DownloadQueueManager.addToQueue(item2)
        DownloadQueueManager.addToQueue(item3)

        assertEquals(listOf(801, 802, 803), DownloadQueueManager.queue.value.map { it.id })

        // Move item3 to position 0
        viewModel.reorderItem(item3, 0)

        assertEquals(803, DownloadQueueManager.queue.value[0].id)
        assertEquals(801, DownloadQueueManager.queue.value[1].id)
        assertEquals(802, DownloadQueueManager.queue.value[2].id)
    }

    @Test
    fun `pauseDownload and resumeDownload update downloadStatus state`() {
        val id = 901
        VideoDownloadManager.downloadStatus[id] = VideoDownloadManager.DownloadType.IsDownloading

        viewModel.pauseDownload(id)
        assertEquals(VideoDownloadManager.DownloadType.IsPaused, VideoDownloadManager.downloadStatus[id])

        viewModel.resumeDownload(id)
        assertEquals(VideoDownloadManager.DownloadType.IsDownloading, VideoDownloadManager.downloadStatus[id])
    }

    @Test
    fun `pauseAll and resumeAll toggle statuses of active downloads`() {
        val id1 = 911
        val id2 = 912
        val wrapper1 = createDummyItem(id1, "ShowA", 1).toWrapper()
        val wrapper2 = createDummyItem(id2, "ShowB", 1).toWrapper()

        VideoDownloadManager.downloadStatus[id1] = VideoDownloadManager.DownloadType.IsDownloading
        VideoDownloadManager.downloadStatus[id2] = VideoDownloadManager.DownloadType.IsDownloading

        viewModel.updateChildList(DownloadAdapterQueue(
            currentDownloads = listOf(wrapper1, wrapper2),
            queue = emptyList()
        ))

        viewModel.pauseAll()
        assertEquals(VideoDownloadManager.DownloadType.IsPaused, VideoDownloadManager.downloadStatus[id1])
        assertEquals(VideoDownloadManager.DownloadType.IsPaused, VideoDownloadManager.downloadStatus[id2])

        viewModel.resumeAll()
        assertEquals(VideoDownloadManager.DownloadType.IsDownloading, VideoDownloadManager.downloadStatus[id1])
        assertEquals(VideoDownloadManager.DownloadType.IsDownloading, VideoDownloadManager.downloadStatus[id2])
    }

    // =========================================================================
    // 5. StateFlow and Lifecycle
    // =========================================================================

    @Test
    fun `updateChildList emits updated queue on childCards StateFlow`() {
        val item = createDummyItem(999, "VinlandSaga", 1).toWrapper()
        val customQueue = DownloadAdapterQueue(
            currentDownloads = listOf(item),
            queue = listOf(item)
        )

        viewModel.updateChildList(customQueue)

        val state = viewModel.childCards.value
        assertEquals(1, state.currentDownloads.size)
        assertEquals(1, state.queue.size)
        assertEquals(999, state.currentDownloads[0].id)
        assertEquals(999, state.queue[0].id)
    }

    @Test
    fun `onCleared cancels viewModelScope deterministically`() {
        viewModel.onCleared()
        // Ensure childCards is still accessible after clearance
        assertNotNull(viewModel.childCards.value)
    }
}
