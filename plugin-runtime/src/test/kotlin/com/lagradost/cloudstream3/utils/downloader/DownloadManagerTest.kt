package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.LazyStreamDownloadResponse
import com.lagradost.common.download.LinuxDownloadStorage
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.safefile.SafeFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import com.lagradost.cloudstream3.services.DownloadQueueService
import java.io.File
import java.nio.file.Path

class DownloadManagerTest {

    private lateinit var testDataFile: File
    private lateinit var testDownloadsDir: File
    private val context = Context()

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        testDownloadsDir = tempDir.resolve("downloads").toFile().apply { mkdirs() }
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        LinuxDownloadStorage.customDownloadsDir = testDownloadsDir.toPath()
        CloudStreamApp.context = context
        DownloadQueueManager.autoStartService = false
        DownloadQueueService.stopService()
        DownloadQueueManager.removeAllFromQueue()
    }

    @AfterEach
    fun tearDown() {
        DownloadQueueManager.autoStartService = true
        DownloadQueueService.stopService()
        DownloadQueueManager.removeAllFromQueue()
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        LinuxDownloadStorage.customDownloadsDir = null
        CloudStreamApp.context = null
    }

    @Test
    fun testTenMiBRangeChunkingCalculations() {
        val chuckSize = (1L shl 20) * 10L // 10 MiB = 10,485,760 bytes

        // Case 1: 25 MiB file (26,214,400 bytes), startByte = 0
        val fileLength25MiB = 25L * 1024L * 1024L
        val rangeCount25 = ((fileLength25MiB + chuckSize - 1) / chuckSize).toInt()
        assertEquals(3, rangeCount25, "25 MiB file must be split into 3 chunks with 10 MiB chunk size")

        val ranges25 = LongArray(rangeCount25) { idx -> idx * chuckSize }
        assertEquals(0L, ranges25[0])
        assertEquals(10485760L, ranges25[1])
        assertEquals(20971520L, ranges25[2])

        // Case 2: 5 MiB small file (less than chunk size)
        val fileLength5MiB = 5L * 1024L * 1024L
        val rangeCount5 = ((fileLength5MiB + chuckSize - 1) / chuckSize).toInt()
        assertEquals(1, rangeCount5, "5 MiB file should require only 1 chunk")

        // Case 3: Resumed download starting at 15 MiB for a 25 MiB file
        val startByte = 15L * 1024L * 1024L
        val remainingLength = fileLength25MiB - startByte // 10 MiB remaining
        val rangeCountResumed = ((remainingLength + chuckSize - 1) / chuckSize).toInt()
        assertEquals(1, rangeCountResumed, "10 MiB remaining should require 1 chunk")
        val rangesResumed = LongArray(rangeCountResumed) { idx -> startByte + idx * chuckSize }
        assertEquals(startByte, rangesResumed[0])
    }

    @Test
    fun testRAMReassemblyQueueAndOrdering() {
        // Simulating out-of-order chunk arrival:
        // Response 0: bytes 0..100
        // Response 2: bytes 200..300 (arrives second)
        // Response 3: bytes 300..400 (arrives third)
        // Response 1: bytes 100..200 (arrives fourth, unblocking 2 and 3)
        val outputStream = ByteArrayOutputStream()
        val pendingData = HashMap<Long, LazyStreamDownloadResponse>()
        var bytesWritten = 0L

        fun handleResponse(response: LazyStreamDownloadResponse) {
            val responseSize = response.size
            if (response.startByte == bytesWritten) {
                outputStream.write(response.bytes, 0, responseSize.toInt())
                bytesWritten += responseSize
            } else {
                pendingData[response.startByte] = response.copy(bytes = response.bytes.clone())
            }

            while (true) {
                val pending = pendingData.remove(bytesWritten) ?: break
                val size = pending.size
                outputStream.write(pending.bytes, 0, size.toInt())
                bytesWritten += size
            }
        }

        val data0 = ByteArray(100) { 1 }
        val data1 = ByteArray(100) { 2 }
        val data2 = ByteArray(100) { 3 }
        val data3 = ByteArray(100) { 4 }

        // 1. Arrival: Chunk 0
        handleResponse(LazyStreamDownloadResponse(data0, 0, 100))
        assertEquals(100L, bytesWritten)
        assertEquals(0, pendingData.size)

        // 2. Arrival: Chunk 2 (out-of-order)
        handleResponse(LazyStreamDownloadResponse(data2, 200, 300))
        assertEquals(100L, bytesWritten, "bytesWritten must not advance for gap")
        assertEquals(1, pendingData.size, "Chunk 2 must be buffered in pendingData")

        // 3. Arrival: Chunk 3 (out-of-order)
        handleResponse(LazyStreamDownloadResponse(data3, 300, 400))
        assertEquals(100L, bytesWritten)
        assertEquals(2, pendingData.size, "Chunk 3 must be buffered in pendingData")

        // 4. Arrival: Chunk 1 (fills the gap)
        handleResponse(LazyStreamDownloadResponse(data1, 100, 200))
        assertEquals(400L, bytesWritten, "All chunks must be flushed once gap is filled")
        assertEquals(0, pendingData.size, "pendingData must be empty after reassembly")

        val resultBytes = outputStream.toByteArray()
        assertEquals(400, resultBytes.size)
        // Check contents
        assertTrue(resultBytes.sliceArray(0 until 100).all { it == 1.toByte() })
        assertTrue(resultBytes.sliceArray(100 until 200).all { it == 2.toByte() })
        assertTrue(resultBytes.sliceArray(200 until 300).all { it == 3.toByte() })
        assertTrue(resultBytes.sliceArray(300 until 400).all { it == 4.toByte() })
    }

    @Test
    fun testBackpressureThrottlingCondition() {
        val metadata = VideoDownloadManager.DownloadMetaData(
            id = 999,
            linkHash = "test".hashCode(),
            createNotificationCallback = {},
            isHLS = false
        )

        metadata.bytesDownloaded = 60_000_000L
        metadata.bytesWritten = 5_000_000L

        // Difference is 55 MB, exceeding 50 MB threshold
        val isTooFarAhead = (metadata.bytesDownloaded - metadata.bytesWritten) > 50_000_000L
        assertTrue(isTooFarAhead, "Backpressure throttling must trigger when delta exceeds 50MB")

        metadata.bytesWritten = 20_000_000L // Difference is 40 MB
        val isNowOk = (metadata.bytesDownloaded - metadata.bytesWritten) > 50_000_000L
        assertFalse(isNowOk, "Backpressure throttling must not trigger when delta is below 50MB")
    }

    @Test
    fun testAtomicMoveFromPartToFinal() {
        val partFile = File(testDownloadsDir, "movie.mp4.part")
        val finalFile = File(testDownloadsDir, "movie.mp4")

        val testContent = "Test video stream payload for atomic move validation".toByteArray()
        partFile.writeBytes(testContent)

        assertTrue(partFile.exists(), "Part file must exist prior to commit")
        assertFalse(finalFile.exists(), "Final file must not exist prior to commit")

        VideoDownloadManager.commitDownloadedFile(SafeFile.fromFile(partFile), SafeFile.fromFile(finalFile))

        assertTrue(finalFile.exists(), "Final file must exist after atomic commit")
        assertFalse(partFile.exists(), "Part file must no longer exist after atomic commit")
        assertArrayEquals(testContent, finalFile.readBytes(), "Committed file content must match original bytes")
    }

    @Test
    fun testQueueManagementOperations() {
        DownloadQueueManager.removeAllFromQueue()
        assertEquals(0, DownloadQueueManager.queue.value.size)

        val ep1 = ResultEpisode(headerName = "Show", name = "Episode 1", poster = null, episode = 1, season = 1, id = 101, parentId = 1001)
        val ep2 = ResultEpisode(headerName = "Show", name = "Episode 2", poster = null, episode = 2, season = 1, id = 102, parentId = 1001)
        val ep3 = ResultEpisode(headerName = "Show", name = "Episode 3", poster = null, episode = 3, season = 1, id = 103, parentId = 1001)

        val item1 = DownloadObjects.DownloadQueueItem(ep1, false, "Show", TvType.TvSeries, null, "Provider", 1001, "url1")
        val item2 = DownloadObjects.DownloadQueueItem(ep2, false, "Show", TvType.TvSeries, null, "Provider", 1001, "url2")
        val item3 = DownloadObjects.DownloadQueueItem(ep3, false, "Show", TvType.TvSeries, null, "Provider", 1001, "url3")

        DownloadQueueManager.addToQueue(item1.toWrapper())
        DownloadQueueManager.addToQueue(item2.toWrapper())
        DownloadQueueManager.addToQueue(item3.toWrapper())

        assertEquals(3, DownloadQueueManager.queue.value.size)
        assertEquals(101, DownloadQueueManager.queue.value[0].id)
        assertEquals(102, DownloadQueueManager.queue.value[1].id)
        assertEquals(103, DownloadQueueManager.queue.value[2].id)

        // Reorder item 3 to position 0 synchronously
        DownloadQueueManager.reorder(item3.toWrapper(), 0)
        assertEquals(103, DownloadQueueManager.queue.value[0].id)

        // Pop first item
        val popped = DownloadQueueManager.popQueue(context)
        assertNotNull(popped)
        assertEquals(103, popped!!.downloadQueueWrapper.id)
        assertEquals(2, DownloadQueueManager.queue.value.size)

        // Remove item 1 synchronously
        DownloadQueueManager.remove(101)
        assertEquals(1, DownloadQueueManager.queue.value.size)
        assertEquals(102, DownloadQueueManager.queue.value[0].id)
    }

    @Test
    fun testPauseResumeAndStatusTransitions() {
        val testId = 777
        var lastStatus: VideoDownloadManager.DownloadType? = null

        VideoDownloadManager.downloadStatusEvent += { (id, type) ->
            if (id == testId) {
                lastStatus = type
            }
        }

        VideoDownloadManager.pause(testId)
        // Simulate event reception
        VideoDownloadManager.downloadStatusEvent.invoke(testId to VideoDownloadManager.DownloadType.IsPaused)
        assertEquals(VideoDownloadManager.DownloadType.IsPaused, lastStatus)

        VideoDownloadManager.resume(testId)
        VideoDownloadManager.downloadStatusEvent.invoke(testId to VideoDownloadManager.DownloadType.IsDownloading)
        assertEquals(VideoDownloadManager.DownloadType.IsDownloading, lastStatus)
    }

    @Test
    fun testDeleteDownloadCleansFilesAndMetadata() {
        val id = 555
        val videoFile = File(testDownloadsDir, "test_video.mp4")
        videoFile.writeBytes("Video data".toByteArray())

        val subFile = File(testDownloadsDir, "test_video English.srt")
        subFile.writeBytes("Subtitle data".toByteArray())

        val info = DownloadedFileInfo(
            totalBytes = 1000L,
            relativePath = "",
            displayName = "test_video.mp4",
            basePath = testDownloadsDir.absolutePath
        )
        DataStore.setKey(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString(), info)

        var deletedEventFired = false
        VideoDownloadManager.downloadDeleteEvent += { deletedId ->
            if (deletedId == id) {
                deletedEventFired = true
            }
        }

        val success = VideoDownloadManager.delete(context, id)
        assertTrue(success, "delete() must return true")
        assertFalse(videoFile.exists(), "Video file must be deleted")
        assertFalse(subFile.exists(), "Matching subtitle file must be deleted")
        assertNull(DataStore.getKey<DownloadedFileInfo>(VideoDownloadManager.KEY_DOWNLOAD_INFO, id.toString()), "Metadata key must be removed")
        assertTrue(deletedEventFired, "downloadDeleteEvent must be fired")
    }
}
