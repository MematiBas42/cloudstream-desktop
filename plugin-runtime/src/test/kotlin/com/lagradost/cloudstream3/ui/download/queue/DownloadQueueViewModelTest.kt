package com.lagradost.cloudstream3.ui.download.queue

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DownloadQueueViewModelTest {

    private lateinit var testDataFile: File
    private val context = Context()
    private lateinit var viewModel: DownloadQueueViewModel

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        CloudStreamApp.context = context
        viewModel = DownloadQueueViewModel(Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        viewModel.onCleared()
        DesktopDataStore.customDataFile = null
    }

    @Test
    fun `test initial state is not null and exposes childCards`() {
        val state = viewModel.childCards.value
        assertNotNull(state)
        assertNotNull(state.currentDownloads)
        assertNotNull(state.queue)
    }

    @Test
    fun `test updateChildList updates state directly`() {
        val ep = ResultEpisode(
            headerName = "Test Show",
            name = "Episode 1",
            poster = null,
            episode = 1,
            id = 12345,
            parentId = 100
        )
        val dummyItem = DownloadObjects.DownloadQueueItem(
            episode = ep,
            isMovie = false,
            resultName = "Test Show",
            resultType = TvType.TvSeries,
            resultPoster = null,
            apiName = "TestApi",
            resultId = 100,
            resultUrl = "https://example.com/show"
        )
        val dummyWrapper = dummyItem.toWrapper()
        val customQueue = DownloadAdapterQueue(
            currentDownloads = listOf(dummyWrapper),
            queue = listOf(dummyWrapper)
        )

        viewModel.updateChildList(customQueue)

        val updated = viewModel.childCards.value
        assertEquals(1, updated.currentDownloads.size)
        assertEquals(1, updated.queue.size)
        assertEquals(12345, updated.currentDownloads[0].id)
        assertEquals(12345, updated.queue[0].id)
    }

    @Test
    fun `test lifecycle onCleared cancels viewModelScope`() {
        assertTrue(viewModel.viewModelScope.isActive)
        viewModel.onCleared()
        assertFalse(viewModel.viewModelScope.isActive)
    }
}
