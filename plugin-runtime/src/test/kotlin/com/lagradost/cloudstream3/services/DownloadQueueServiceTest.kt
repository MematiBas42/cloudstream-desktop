package com.lagradost.cloudstream3.services

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.common.download.LinuxDownloadStorage
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class DownloadQueueServiceTest {

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
        MainActivity.lastError = null

        PluginManager.loadedOnlinePlugins = true
        PluginManager.loadedLocalPlugins = true
    }

    @AfterEach
    fun tearDown() {
        DownloadQueueService.stopService()
        VideoDownloadService.stopCoordinator()
        DownloadQueueManager.removeAllFromQueue()
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        LinuxDownloadStorage.customDownloadsDir = null
        CloudStreamApp.context = null
        MainActivity.lastError = null
    }

    private fun createDummyWrapper(id: Int, source: String = "TestProvider"): DownloadQueueWrapper {
        val ep = ResultEpisode(
            headerName = "Show",
            name = "Episode $id",
            poster = null,
            episode = id,
            season = 1,
            id = id,
            parentId = 1000 + id
        )
        val item = DownloadQueueItem(
            episode = ep,
            isMovie = false,
            resultName = "Show",
            resultType = TvType.TvSeries,
            resultPoster = null,
            apiName = source,
            resultId = 1000 + id,
            resultUrl = "https://example.com/show"
        )
        return DownloadQueueWrapper(
            resumePackage = null,
            downloadItem = item
        )
    }

    @Test
    fun testDownloadQueueServiceLifecycleAndStateFlow() {
        assertFalse(DownloadQueueService.isRunning, "Service must start stopped")
        assertTrue(DownloadQueueService.downloadInstances.value.isEmpty(), "Instances must be empty initially")

        DownloadQueueService.startService(context)
        assertTrue(DownloadQueueService.isRunning, "Service must be running after startService")

        val intent = DownloadQueueService.getIntent(context)
        assertNotNull(intent)

        DownloadQueueService.stopService()
        assertFalse(DownloadQueueService.isRunning, "Service must be stopped after stopService")
    }

    @Test
    fun testTotalDownloadFlowComposition() = runBlocking {
        val flow = DownloadQueueService.totalDownloadFlow
        val initial = flow.first()
        assertTrue(initial.first.isEmpty(), "Active instances should be empty")
        assertEquals(0, initial.second.size, "Queue should be empty")
        assertTrue(initial.third.isEmpty(), "Current downloads should be empty")
    }

    @Test
    fun testReactivePluginLoadedStateFlow() = runBlocking {
        assertTrue(PluginManager.pluginsLoaded.value, "Initially plugins must be loaded")

        PluginManager.loadedOnlinePlugins = false
        assertFalse(PluginManager.pluginsLoaded.value, "pluginsLoaded must be false when loadedOnlinePlugins is false")

        PluginManager.loadedOnlinePlugins = true
        assertTrue(PluginManager.pluginsLoaded.value, "pluginsLoaded must be true when both are true")

        PluginManager.loadedLocalPlugins = false
        assertFalse(PluginManager.pluginsLoaded.value, "pluginsLoaded must be false when loadedLocalPlugins is false")

        PluginManager.loadedLocalPlugins = true
        assertTrue(PluginManager.pluginsLoaded.value, "pluginsLoaded must be true again")
    }

    @Test
    fun testDownloadEventListenerCleansResumeKeysOnStop() {
        val testId = 99991
        CloudStreamApp.setKey(KEY_RESUME_PACKAGES, testId.toString(), "dummy_pkg")
        CloudStreamApp.setKey(KEY_RESUME_IN_QUEUE, testId.toString(), "dummy_queue")

        assertNotNull(CloudStreamApp.getKey<String>(KEY_RESUME_PACKAGES, testId.toString()))
        assertNotNull(CloudStreamApp.getKey<String>(KEY_RESUME_IN_QUEUE, testId.toString()))

        DownloadQueueService.downloadEventListener.invoke(Pair(testId, DownloadActionType.Stop))

        assertNull(CloudStreamApp.getKey<String>(KEY_RESUME_PACKAGES, testId.toString()), "KEY_RESUME_PACKAGES must be removed")
        assertNull(CloudStreamApp.getKey<String>(KEY_RESUME_IN_QUEUE, testId.toString()), "KEY_RESUME_IN_QUEUE must be removed")
    }

    @Test
    fun testVideoDownloadServiceActionHandling() = runBlocking {
        val receivedEvents = CopyOnWriteArrayList<Pair<Int, DownloadActionType>>()
        val listener: (Pair<Int, DownloadActionType>) -> Unit = { receivedEvents.add(it) }

        VideoDownloadManager.downloadEvent += listener
        try {
            val testId = 12345

            VideoDownloadService.pause(testId)
            // Small yield to let coroutine execute
            var attempts = 0
            while (receivedEvents.none { it.first == testId && it.second == DownloadActionType.Pause } && attempts++ < 20) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue(receivedEvents.any { it.first == testId && it.second == DownloadActionType.Pause }, "Pause event must be dispatched")

            VideoDownloadService.resume(testId)
            attempts = 0
            while (receivedEvents.none { it.first == testId && it.second == DownloadActionType.Resume } && attempts++ < 20) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue(receivedEvents.any { it.first == testId && it.second == DownloadActionType.Resume }, "Resume event must be dispatched")

            VideoDownloadService.cancel(testId)
            attempts = 0
            while (receivedEvents.none { it.first == testId && it.second == DownloadActionType.Stop } && attempts++ < 20) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue(receivedEvents.any { it.first == testId && it.second == DownloadActionType.Stop }, "Stop event must be dispatched on cancel")
        } finally {
            VideoDownloadManager.downloadEvent -= listener
        }
    }

    @Test
    fun testVideoDownloadServiceIntentHandling() = runBlocking {
        val receivedEvents = CopyOnWriteArrayList<Pair<Int, DownloadActionType>>()
        val listener: (Pair<Int, DownloadActionType>) -> Unit = { receivedEvents.add(it) }

        VideoDownloadManager.downloadEvent += listener
        try {
            val service = VideoDownloadService()
            val testId = 8888

            val pauseIntent = Intent(context, VideoDownloadService::class.java).apply {
                putExtra("id", testId)
                putExtra("type", "pause")
            }
            val startCode = service.onStartCommand(pauseIntent, 0, 0)
            assertEquals(VideoDownloadService.START_NOT_STICKY, startCode)

            var attempts = 0
            while (receivedEvents.none { it.first == testId && it.second == DownloadActionType.Pause } && attempts++ < 20) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue(receivedEvents.any { it.first == testId && it.second == DownloadActionType.Pause }, "Intent pause must dispatch Pause action")

            val resumeIntent = Intent(context, VideoDownloadService::class.java).apply {
                putExtra("id", testId)
                putExtra("type", "resume")
            }
            service.onStartCommand(resumeIntent, 0, 0)

            attempts = 0
            while (receivedEvents.none { it.first == testId && it.second == DownloadActionType.Resume } && attempts++ < 20) {
                kotlinx.coroutines.delay(20)
            }
            assertTrue(receivedEvents.any { it.first == testId && it.second == DownloadActionType.Resume }, "Intent resume must dispatch Resume action")
        } finally {
            VideoDownloadManager.downloadEvent -= listener
        }
    }

    @Test
    fun testVideoDownloadCoordinatorAndFreedesktopNotification() {
        assertFalse(VideoDownloadService.isCoordinatorRunning)
        VideoDownloadService.startCoordinator(context)
        assertTrue(VideoDownloadService.isCoordinatorRunning)

        // Send explicit notification
        VideoDownloadService.sendFreedesktopNotification(
            title = "Test Download Title",
            message = "Download progress 50%",
            notificationId = 7777,
            progress = 50
        )

        val notificationManager = NotificationManagerCompat.from(context)
        val activeNotifs = notificationManager.activeNotifications
        assertTrue(activeNotifs.any { it.id == 7777 }, "Notification 7777 should be registered in NotificationManagerCompat")

        // Test download status event IsDone triggers notification
        VideoDownloadManager.downloadStatusEvent.invoke(Pair(7778, DownloadType.IsDone))
        val activeAfterDone = notificationManager.activeNotifications
        assertTrue(activeAfterDone.any { it.id == 7778 }, "Notification 7778 for completed download should be registered")

        VideoDownloadService.stopCoordinator()
        assertFalse(VideoDownloadService.isCoordinatorRunning)
    }

    @Test
    fun testClearCancelsQueueAndDispatchesStop() = runBlocking {
        val wrapper1 = createDummyWrapper(101)
        val wrapper2 = createDummyWrapper(102)

        DownloadQueueManager.addToQueue(wrapper1)
        DownloadQueueManager.addToQueue(wrapper2)

        assertEquals(2, DownloadQueueManager.queue.value.size, "Queue should contain 2 items")

        VideoDownloadService.clear()

        var attempts = 0
        while (DownloadQueueManager.queue.value.isNotEmpty() && attempts++ < 20) {
            kotlinx.coroutines.delay(20)
        }
        assertTrue(DownloadQueueManager.queue.value.isEmpty(), "Queue must be cleared after clear()")
    }

    @Test
    fun testMainActivityLastErrorHandling() {
        val errorFile = context.filesDir.resolve("last_error")
        errorFile.writeText("Simulated POSIX Crash Stacktrace")

        MainActivity.setLastError(context)
        assertEquals("Simulated POSIX Crash Stacktrace", MainActivity.lastError, "lastError must read error file content")
        assertFalse(errorFile.exists(), "last_error file must be deleted after reading")

        // Calling again should keep the same lastError
        MainActivity.setLastError(context)
        assertEquals("Simulated POSIX Crash Stacktrace", MainActivity.lastError)
    }

    @Test
    fun testVideoDownloadServiceSleepInhibitionLifecycle() {
        assertFalse(VideoDownloadService.isSleepInhibited, "Initially sleep should not be inhibited")

        val acquired = VideoDownloadService.acquireSleepInhibit()
        if (acquired) {
            assertTrue(VideoDownloadService.isSleepInhibited, "isSleepInhibited must be true when acquired")
            VideoDownloadService.releaseSleepInhibit()
            assertFalse(VideoDownloadService.isSleepInhibited, "isSleepInhibited must be false after release")
        }
    }

    @Test
    fun testVideoDownloadServiceSleepInhibitFallbackOnFailure() {
        val originalCmd = VideoDownloadService.inhibitCommandProvider
        try {
            VideoDownloadService.inhibitCommandProvider = { listOf("non_existent_binary_xyz_12345") }
            val acquired = VideoDownloadService.acquireSleepInhibit()
            assertFalse(acquired, "acquireSleepInhibit must return false for missing binary")
            assertFalse(VideoDownloadService.isSleepInhibited, "isSleepInhibited must remain false")
        } finally {
            VideoDownloadService.inhibitCommandProvider = originalCmd
            VideoDownloadService.releaseSleepInhibit()
        }
    }

    @Test
    fun testVideoDownloadServiceReactiveSleepInhibition() = runBlocking {
        VideoDownloadService.startCoordinator(context)
        try {
            assertFalse(VideoDownloadService.isSleepInhibited)

            val wrapper = createDummyWrapper(201)
            DownloadQueueManager.addToQueue(wrapper)

            var attempts = 0
            while (!VideoDownloadService.isSleepInhibited && attempts++ < 30) {
                kotlinx.coroutines.delay(50)
            }
            assertTrue(VideoDownloadService.isSleepInhibited, "isSleepInhibited should be true after adding to queue")

            VideoDownloadService.clear()
            attempts = 0
            while (VideoDownloadService.isSleepInhibited && attempts++ < 30) {
                kotlinx.coroutines.delay(50)
            }
            assertFalse(VideoDownloadService.isSleepInhibited, "isSleepInhibited must be false after clear()")
        } finally {
            VideoDownloadService.stopCoordinator()
            VideoDownloadService.clear()
        }
    }

    @Test
    fun testVideoDownloadServiceResumeInterruptedDownloads() {
        val dummyWrapper = createDummyWrapper(301)
        val resumePkg = DownloadObjects.DownloadResumePackage(
            item = DownloadObjects.DownloadItem("https://example.com", "TestFolder", DownloadObjects.DownloadEpisodeMetadata(301, 1301, "Show", "TestProvider", null, "Episode 301", 1, 301, TvType.TvSeries), emptyList()),
            linkIndex = 0
        )
        CloudStreamApp.setKey(VideoDownloadManager.KEY_RESUME_PACKAGES, "301", resumePkg)
        assertNotNull(CloudStreamApp.getKey<DownloadObjects.DownloadResumePackage>(VideoDownloadManager.KEY_RESUME_PACKAGES, "301"))

        val count = VideoDownloadService.resumeInterruptedDownloads(context)
        assertEquals(1, count, "Must resume 1 interrupted download")
        assertNull(CloudStreamApp.getKey<DownloadObjects.DownloadResumePackage>(VideoDownloadManager.KEY_RESUME_PACKAGES, "301"), "KEY_RESUME_PACKAGES must be cleared")
        assertTrue(DownloadQueueManager.queue.value.any { it.id == 301 }, "Resumed download must be in queue")

        DownloadQueueManager.removeAllFromQueue()
    }
}
