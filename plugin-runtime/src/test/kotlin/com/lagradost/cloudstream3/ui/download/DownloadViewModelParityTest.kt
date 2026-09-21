package com.lagradost.cloudstream3.ui.download

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.common.download.LinuxDownloadStorage
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class DownloadViewModelParityTest {

    private lateinit var testDataFile: File
    private lateinit var testDownloadsDir: File
    private val context = Context()
    private lateinit var viewModel: DownloadViewModel

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        testDownloadsDir = tempDir.resolve("downloads").toFile().apply { mkdirs() }
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        LinuxDownloadStorage.customDownloadsDir = testDownloadsDir.toPath()
        CloudStreamApp.context = context
        viewModel = DownloadViewModel(Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        viewModel.onCleared()
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        LinuxDownloadStorage.customDownloadsDir = null
        CloudStreamApp.context = null
    }

    @Test
    fun testInitialStateFlowParity() {
        assertTrue(viewModel.headerCards.value is Resource.Loading, "headerCards must start with Resource.Loading")
        assertTrue(viewModel.childCards.value is Resource.Loading, "childCards must start with Resource.Loading")
        assertEquals(0L, viewModel.usedBytes.value, "usedBytes must start with 0")
        assertEquals(0L, viewModel.availableBytes.value, "availableBytes must start with 0")
        assertEquals(0L, viewModel.downloadBytes.value, "downloadBytes must start with 0")
        assertEquals(0L, viewModel.selectedBytes.value, "selectedBytes must start with 0")
        assertNull(viewModel.selectedItemIds.value, "selectedItemIds must start with null")
    }

    @Test
    fun testSelectionStateMachine() {
        viewModel.addSelected(101)
        assertEquals(setOf(101), viewModel.selectedItemIds.value)

        viewModel.addSelected(102)
        assertEquals(setOf(101, 102), viewModel.selectedItemIds.value)

        viewModel.removeSelected(101)
        assertEquals(setOf(102), viewModel.selectedItemIds.value)

        viewModel.clearSelectedItems()
        assertEquals(emptySet<Int>(), viewModel.selectedItemIds.value)

        viewModel.cancelSelection()
        assertNull(viewModel.selectedItemIds.value)
    }

    @Test
    fun testPostHeadersAndChildren() {
        val dummyHeader = VisualDownloadCached.Header(
            currentBytes = 1000L,
            totalBytes = 2000L,
            data = DownloadObjects.DownloadHeaderCached(
                apiName = "TestProvider",
                url = "https://example.com/movie",
                type = TvType.Movie,
                name = "Test Movie",
                poster = null,
                cacheTime = System.currentTimeMillis(),
                id = 42
            ),
            isSelected = false,
            child = null,
            currentOngoingDownloads = 0,
            totalDownloads = 1
        )

        viewModel.postHeaders(listOf(dummyHeader))
        val headerResult = viewModel.headerCards.value
        assertTrue(headerResult is Resource.Success)
        val headers = (headerResult as Resource.Success).value
        assertEquals(1, headers.size)
        assertEquals(42, headers[0].data.id)
        assertFalse(headers[0].isSelected)

        // Select and verify propagation
        viewModel.addSelected(42)
        val updatedHeaders = (viewModel.headerCards.value as Resource.Success).value
        assertTrue(updatedHeaders[0].isSelected, "Header must be marked selected after addSelected")

        val dummyChild = VisualDownloadCached.Child(
            currentBytes = 500L,
            totalBytes = 1000L,
            data = DownloadObjects.DownloadEpisodeCached(
                name = "Episode 1",
                poster = null,
                episode = 1,
                season = 1,
                parentId = 42,
                score = null,
                description = "Test description",
                cacheTime = System.currentTimeMillis(),
                id = 1001
            ),
            isSelected = false
        )

        viewModel.postChildren(listOf(dummyChild))
        val childResult = viewModel.childCards.value
        assertTrue(childResult is Resource.Success)
        val children = (childResult as Resource.Success).value
        assertEquals(1, children.size)
        assertEquals(1001, children[0].data.id)

        // Clear children
        viewModel.clearChildren()
        assertTrue(viewModel.childCards.value is Resource.Loading)
    }

    @Test
    fun testUpdateHeaderListAndPOSIXStorageCalculation() = runBlocking {
        val headerId = 200
        val episodeId = 201

        val header = DownloadObjects.DownloadHeaderCached(
            apiName = "TestProvider",
            url = "https://example.com/series",
            type = TvType.TvSeries,
            name = "Awesome Series",
            poster = null,
            cacheTime = System.currentTimeMillis(),
            id = headerId
        )

        val episode = DownloadObjects.DownloadEpisodeCached(
            name = "S1E1",
            poster = null,
            episode = 1,
            season = 1,
            parentId = headerId,
            score = null,
            description = "Pilot",
            cacheTime = System.currentTimeMillis(),
            id = episodeId
        )

        // Store header and episode cache
        with(DataStore) {
            context.setKey(DOWNLOAD_HEADER_CACHE, headerId.toString(), header)
            context.setKey(
                DOWNLOAD_EPISODE_CACHE,
                getFolderName(headerId.toString(), episodeId.toString()),
                episode
            )
        }

        // Store file info with actual physical file
        val targetFile = File(testDownloadsDir, "series_s1e1.mp4")
        targetFile.writeBytes(ByteArray(1024 * 1024)) // 1 MiB

        val info = DownloadObjects.DownloadedFileInfo(
            totalBytes = 1024 * 1024L,
            relativePath = "",
            basePath = testDownloadsDir.absolutePath,
            displayName = targetFile.name
        )
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, episodeId.toString(), info)

        viewModel.updateHeaderList(context).join()

        val headers = (viewModel.headerCards.value as? Resource.Success)?.value
        assertNotNull(headers)
        assertEquals(1, headers!!.size)
        assertEquals("Awesome Series", headers[0].data.name)
        assertEquals(1024 * 1024L, headers[0].totalBytes)

        // Storage stats
        assertTrue(viewModel.availableBytes.value > 0L, "availableBytes must be > 0 via POSIX FileStore")
        assertTrue(viewModel.usedBytes.value >= 0L, "usedBytes must be >= 0 via POSIX FileStore")
        assertEquals(1024 * 1024L, viewModel.downloadBytes.value)
    }

    @Test
    fun testDeleteLifecycleAndEventHandling() = runBlocking {
        val headerId = 300
        val episodeId = 301

        val header = DownloadObjects.DownloadHeaderCached(
            apiName = "TestProvider",
            url = "https://example.com/movie2",
            type = TvType.Movie,
            name = "Single Movie",
            poster = null,
            cacheTime = System.currentTimeMillis(),
            id = headerId
        )
        with(DataStore) {
            context.setKey(DOWNLOAD_HEADER_CACHE, headerId.toString(), header)
        }

        val dummyHeader = VisualDownloadCached.Header(
            currentBytes = 100L,
            totalBytes = 100L,
            data = header,
            isSelected = true,
            child = null,
            currentOngoingDownloads = 0,
            totalDownloads = 1
        )
        viewModel.postHeaders(listOf(dummyHeader))
        viewModel.addSelected(headerId)

        var dialogFired = false
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            viewModel.dialogEvent.first().let { event ->
                when (event) {
                    is DownloadDialogEvent.DeleteConfirmation -> {
                        dialogFired = true
                        assertTrue(event.ids.contains(headerId))
                        // Execute confirmation
                        event.onConfirm()
                    }
                }
            }
        }

        // Handle single delete triggers dialog event
        viewModel.handleSingleDelete(context, headerId)?.join()
        delay(200)
        assertTrue(dialogFired, "DeleteConfirmation event must be emitted over dialogEvent")
        job.cancel()

        // Test downloadDeleteEvent listener reacts
        VideoDownloadManager.downloadDeleteEvent.invoke(headerId)
        assertFalse(viewModel.selectedItemIds.value?.contains(headerId) == true)
    }
}
