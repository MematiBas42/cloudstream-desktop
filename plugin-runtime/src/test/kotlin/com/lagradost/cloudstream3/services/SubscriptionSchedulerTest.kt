package com.lagradost.cloudstream3.services

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.common.notifications.DesktopNotificationDispatcher
import com.lagradost.common.notifications.FreedesktopNotificationManager
import com.lagradost.common.notifications.NotificationPayload
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SubscriptionSchedulerTest {

    private lateinit var testDataFile: File
    private val context = Context()
    private val testDispatcher = RecordingNotificationDispatcher()
    private lateinit var testProvider: TestSubscriptionProvider

    class RecordingNotificationDispatcher : DesktopNotificationDispatcher {
        val notifications = mutableListOf<NotificationPayload>()
        override fun dispatch(payload: NotificationPayload): Boolean {
            notifications.add(payload)
            return true
        }
    }

    class TestSubscriptionProvider : MainAPI() {
        override var name = "TestSubscriptionProvider"
        override var mainUrl = "https://example.com"
        override var supportedTypes = setOf(TvType.TvSeries)

        var loadResult: LoadResponse? = null

        override suspend fun load(url: String): LoadResponse? {
            return loadResult
        }
    }

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        CloudStreamApp.context = context
        testProvider = TestSubscriptionProvider()
        APIHolder.addPluginMapping(testProvider)

        testDispatcher.notifications.clear()
        FreedesktopNotificationManager.dispatcher = testDispatcher
        DownloadQueueService.stopService()
        DownloadQueueManager.removeAllFromQueue()
    }

    @AfterEach
    fun tearDown() {
        SubscriptionScheduler.cancelPeriodicCheck()
        APIHolder.removePluginMapping(testProvider)
        DownloadQueueService.stopService()
        DownloadQueueManager.removeAllFromQueue()
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        CloudStreamApp.context = null
    }

    @Test
    fun testCheckSubscribedDetectsNewEpisodeAndDispatchesNotification() = runBlocking {
        val showId = 1001
        val showName = "Frieren: Beyond Journey's End"
        val showUrl = "https://example.com/frieren"

        // Seed initial subscription at episode 10
        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.Subbed to 10),
            id = showId,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.TvSeries,
            posterUrl = "https://example.com/frieren_poster.jpg",
            year = 2023
        )
        DataStoreHelper.setSubscribedData(showId, initialSub)
        DataStoreHelper.setDub(showId, DubStatus.Subbed)

        // Configure provider to return episodes up to 11
        val episodes = (1..11).map { epNum ->
            testProvider.newEpisode("ep_$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        testProvider.loadResult = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        // Execute subscription check
        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(1, updatedCount, "Must detect 1 show with new episodes")
        assertTrue(SubscriptionScheduler.lastScanSuccess, "lastScanSuccess must be true")
        assertTrue(SubscriptionScheduler.lastScanTimestamp > 0, "lastScanTimestamp must be updated")

        // Validate that SubscribedData was updated in DataStore
        val updatedData = DataStoreHelper.getSubscribedData(showId)
        assertNotNull(updatedData)
        assertEquals(11, updatedData?.lastSeenEpisodeCount?.get(DubStatus.None) ?: updatedData?.lastSeenEpisodeCount?.get(DubStatus.Subbed))

        // Validate desktop notification
        assertEquals(1, testDispatcher.notifications.size, "Must dispatch exactly 1 desktop notification")
        val notification = testDispatcher.notifications.first()
        assertEquals(showName, notification.title)
        assertTrue(notification.body.contains("11"), "Notification body must reference episode 11: ${notification.body}")
        assertEquals(showId, notification.replacesId)
    }

    @Test
    fun testCheckSubscribedWithoutNewEpisodesSkipsNotification() = runBlocking {
        val showId = 1002
        val showName = "Dungeon Meshi"
        val showUrl = "https://example.com/meshi"

        // Seed subscription where last seen is already episode 12
        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.Subbed to 12, DubStatus.None to 12),
            id = showId,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.TvSeries,
            posterUrl = null,
            year = 2024
        )
        DataStoreHelper.setSubscribedData(showId, initialSub)

        val episodes = (1..12).map { epNum ->
            testProvider.newEpisode("ep_$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        testProvider.loadResult = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(0, updatedCount, "No new episodes should be found")
        assertEquals(0, testDispatcher.notifications.size, "No notification should be dispatched")
    }

    @Test
    fun testAutoDownloadConfigurationEnqueuesIntoDownloadQueueManager() = runBlocking {
        val showId = 1003
        val showName = "Solo Leveling"
        val showUrl = "https://example.com/solo"

        // Enable auto-download for this show
        SubscriptionScheduler.setAutoDownloadEnabled(showId, true)
        assertTrue(SubscriptionScheduler.isAutoDownloadEnabled(showId), "Auto download must be enabled")

        // Seed subscription at episode 5
        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.None to 5),
            id = showId,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.TvSeries,
            posterUrl = "https://example.com/poster.jpg",
            year = 2024
        )
        DataStoreHelper.setSubscribedData(showId, initialSub)

        // Provider has episode 6
        val episodes = (1..6).map { epNum ->
            testProvider.newEpisode("ep_$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        testProvider.loadResult = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        assertEquals(0, DownloadQueueManager.queue.value.size, "Queue must be initially empty")

        // Run check
        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(1, updatedCount)

        // Verify DownloadQueueManager has the item enqueued or already picked up by DownloadQueueService
        val queue = DownloadQueueManager.queue.value
        val instances = DownloadQueueService.downloadInstances.value
        val item = queue.firstOrNull()?.downloadItem
            ?: instances.firstOrNull()?.downloadQueueWrapper?.downloadItem
        assertNotNull(item, "Episode must be enqueued or actively downloading")
        assertEquals(showName, item?.resultName)
        assertEquals(6, item?.episode?.episode)
        assertEquals(showId, item?.resultId)
    }

    @Test
    fun testPeriodicSchedulingLifecycle() {
        assertFalse(SubscriptionScheduler.isScheduled, "Must not be scheduled initially")

        val job = SubscriptionScheduler.schedulePeriodicCheck(intervalHours = 12, context = context)
        assertNotNull(job, "Scheduling must return active Job")
        assertTrue(SubscriptionScheduler.isScheduled, "isScheduled must be true")
        assertEquals(12L, SubscriptionScheduler.currentIntervalHours, "Interval must be 12 hours")

        SubscriptionScheduler.cancelPeriodicCheck()
        assertFalse(SubscriptionScheduler.isScheduled, "isScheduled must be false after cancel")
        assertEquals(0L, SubscriptionScheduler.currentIntervalHours, "currentIntervalHours must reset to 0")
    }

    @Test
    fun testManualCheckNowAndWorkManagerParity() = runBlocking {
        // Test manual checkNow
        val count = SubscriptionScheduler.checkNow(context)
        assertEquals(0, count, "Empty subscriptions should yield 0 count")
        assertTrue(SubscriptionScheduler.lastScanSuccess)

        // Test SubscriptionWorkManager companion methods
        SubscriptionWorkManager.enqueuePeriodicWork(context)
        assertTrue(SubscriptionScheduler.isScheduled)
        assertEquals(DEFAULT_SUBSCRIPTION_INTERVAL_HOURS, SubscriptionScheduler.currentIntervalHours)

        SubscriptionWorkManager.cancelPeriodicCheck()
        assertFalse(SubscriptionScheduler.isScheduled)

        val wm = SubscriptionWorkManager(context)
        assertTrue(wm.doWork(), "doWork must return true on successful execution")
    }
}
