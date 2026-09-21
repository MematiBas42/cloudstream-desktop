package unit

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.services.DEFAULT_SUBSCRIPTION_INTERVAL_HOURS
import com.lagradost.cloudstream3.services.SUBSCRIPTION_CHANNEL_ID
import com.lagradost.cloudstream3.services.SUBSCRIPTION_NOTIFICATION_ID
import com.lagradost.cloudstream3.services.SubscriptionScheduler
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.ui.result.getLoadResponseIdFromUrl
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.common.notifications.*
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

/**
 * Architectural Remediation & Parity Test Suite for [SubscriptionWorkManager] & [SubscriptionScheduler].
 *
 * Validates 1:1 behavioral and contract parity against upstream CloudStream:
 * 1. Cross-platform notification dispatching via [DesktopNotificationBridge] and [FreedesktopNotificationManager].
 * 2. Notification payload encapsulation with action URL, API name, and intent metadata.
 * 3. Fallback notification dispatchers (Windows Toast, macOS, AWT Tray, Logging fallback, Composite).
 * 4. Periodic episode scanning schedule matching upstream 6-hour interval.
 * 5. Episode delta detection and dub/sub preference resolution matching upstream algorithm.
 * 6. Show ID hashing and fallback resolution via [getLoadResponseIdFromUrl].
 * 7. State synchronization with [DataStoreHelper.updateSubscribedData].
 * 8. Auto-download pipeline integration with [DownloadQueueManager].
 * 9. Upstream [SubscriptionWorkManager] companion and instance lifecycle methods.
 */
class SubscriptionWorkManagerRemediationTest {

    private lateinit var testDataFile: File
    private val context = Context()
    private val testDispatcher = TestRecordingNotificationDispatcher()
    private lateinit var testProvider: TestSeriesProvider

    class TestRecordingNotificationDispatcher : DesktopNotificationDispatcher {
        val dispatched = mutableListOf<NotificationPayload>()
        override fun dispatch(payload: NotificationPayload): Boolean {
            dispatched.add(payload)
            return true
        }
    }

    class TestSeriesProvider : MainAPI() {
        override var name = "TestSeriesProvider"
        override var mainUrl = "https://provider.test"
        override var supportedTypes = setOf(TvType.TvSeries, TvType.Anime)

        var episodeResponse: EpisodeResponse? = null

        override suspend fun load(url: String): LoadResponse? {
            return episodeResponse
        }
    }

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore_sub_test.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        CloudStreamApp.context = context
        testProvider = TestSeriesProvider()
        APIHolder.addPluginMapping(testProvider)

        testDispatcher.dispatched.clear()
        DesktopNotificationBridge.dispatcher = testDispatcher
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
        DesktopNotificationBridge.dispatcher = DesktopNotificationBridge.createDefaultDispatcher()
    }

    @Test
    fun `DesktopNotificationBridge dispatches payload and captures actionUrl and apiName`() {
        val success = DesktopNotificationBridge.showNotification(
            title = "Solo Leveling",
            body = "Episode 12 released!",
            icon = "/tmp/poster.png",
            timeoutMs = 6000,
            notificationId = 12345,
            urgency = NotificationUrgency.CRITICAL,
            category = "media",
            appName = "CloudStream Desktop",
            actionUrl = "https://provider.test/solo-leveling",
            actionLabel = "Open Show",
            apiName = testProvider.name
        )

        assertTrue(success, "Bridge must return true on successful dispatch")
        assertEquals(1, testDispatcher.dispatched.size)

        val payload = testDispatcher.dispatched.first()
        assertEquals("CloudStream Desktop", payload.appName)
        assertEquals("Solo Leveling", payload.title)
        assertEquals("Episode 12 released!", payload.body)
        assertEquals("/tmp/poster.png", payload.icon)
        assertEquals(6000, payload.timeoutMs)
        assertEquals(12345, payload.replacesId)
        assertEquals(NotificationUrgency.CRITICAL, payload.urgency)
        assertEquals("https://provider.test/solo-leveling", payload.actionUrl)
        assertEquals("Open Show", payload.actionLabel)
        assertEquals(testProvider.name, payload.apiName)
    }

    @Test
    fun `DesktopNotificationBridge fallback chain via CompositeDesktopNotificationDispatcher`() {
        val failingDispatcher = object : DesktopNotificationDispatcher {
            override fun dispatch(payload: NotificationPayload): Boolean = false
        }
        val recordingFallback = TestRecordingNotificationDispatcher()

        val composite = CompositeDesktopNotificationDispatcher(
            listOf(failingDispatcher, recordingFallback)
        )
        DesktopNotificationBridge.dispatcher = composite

        val success = DesktopNotificationBridge.showNotification(
            title = "Test Fallback",
            body = "Fallback content",
            notificationId = 999
        )

        assertTrue(success, "Composite dispatcher must succeed if second delegate succeeds")
        assertEquals(1, recordingFallback.dispatched.size)
        assertEquals("Test Fallback", recordingFallback.dispatched.first().title)
    }

    @Test
    fun `LoggingFallbackNotificationDispatcher always succeeds and logs`() {
        val loggingDispatcher = LoggingFallbackNotificationDispatcher()
        val payload = NotificationPayload(title = "Log Title", body = "Log Body")
        assertTrue(loggingDispatcher.dispatch(payload), "Logging fallback must always return true")
    }

    @Test
    fun `DesktopNotificationBridge action trigger invokes registered callback`() {
        var invokedUrl: String? = null
        var invokedApi: String? = null

        DesktopNotificationBridge.onNotificationActionInvoked = { url, api ->
            invokedUrl = url
            invokedApi = api
        }

        DesktopNotificationBridge.triggerAction("https://provider.test/anime/42", "TestProvider")
        assertEquals("https://provider.test/anime/42", invokedUrl)
        assertEquals("TestProvider", invokedApi)

        DesktopNotificationBridge.onNotificationActionInvoked = null
    }

    @Test
    fun `Poster path resolution handles file URLs, relative names, and local paths`(@TempDir tempDir: Path) {
        assertNull(FreedesktopNotificationManager.resolvePosterPath(null))
        assertNull(FreedesktopNotificationManager.resolvePosterPath(""))
        assertNull(FreedesktopNotificationManager.resolvePosterPath("   "))

        // Theme icon name
        assertEquals("video-x-generic", FreedesktopNotificationManager.resolvePosterPath("video-x-generic"))

        // Local file
        val localPoster = tempDir.resolve("sample_poster.png").toFile().apply {
            writeText("sample raw data")
        }
        assertEquals(localPoster.absolutePath, FreedesktopNotificationManager.resolvePosterPath(localPoster.absolutePath))
        assertEquals(localPoster.absolutePath, FreedesktopNotificationManager.resolvePosterPath("file://${localPoster.absolutePath}"))
    }

    @Test
    fun `Periodic episode scanning detects new episode, updates DataStore, and routes through DesktopNotificationBridge`() = runBlocking {
        val showId = 2001
        val showName = "Sousou no Frieren"
        val showUrl = "https://provider.test/frieren"

        // Seed initial subscription at episode 8
        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.Subbed to 8),
            id = showId,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.TvSeries,
            posterUrl = "https://provider.test/frieren.jpg",
            year = 2023
        )
        DataStoreHelper.setSubscribedData(showId, initialSub)
        DataStoreHelper.setDub(showId, DubStatus.Subbed)

        // Configure provider to return episodes up to 9
        val episodes = (1..9).map { epNum ->
            testProvider.newEpisode("ep_$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        testProvider.episodeResponse = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        // Execute subscription check
        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(1, updatedCount, "Must detect 1 show with new episode")
        assertTrue(SubscriptionScheduler.lastScanSuccess, "lastScanSuccess must be true")
        assertTrue(SubscriptionScheduler.lastScanTimestamp > 0, "lastScanTimestamp must be recorded")

        // Verify DataStoreHelper.updateSubscribedData updated the record
        val storedData = DataStoreHelper.getSubscribedData(showId)
        assertNotNull(storedData)
        val updatedSeen = storedData?.lastSeenEpisodeCount?.get(DubStatus.None)
            ?: storedData?.lastSeenEpisodeCount?.get(DubStatus.Subbed)
        assertEquals(9, updatedSeen, "Latest seen episode must now be 9")

        // Verify notification routed through DesktopNotificationBridge
        assertEquals(1, testDispatcher.dispatched.size, "Must dispatch exactly 1 notification")
        val notif = testDispatcher.dispatched.first()
        assertEquals(showName, notif.title)
        assertTrue(notif.body.contains("9"), "Notification text must mention episode 9: ${notif.body}")
        assertEquals(showId, notif.replacesId)
        assertEquals(showUrl, notif.actionUrl)
        assertEquals(testProvider.name, notif.apiName)
        assertEquals("Open Show", notif.actionLabel)
    }

    @Test
    fun `Episode scanning without new episodes skips notification and returns 0`() = runBlocking {
        val showId = 2002
        val showName = "Mushoku Tensei"
        val showUrl = "https://provider.test/mushoku"

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
        testProvider.episodeResponse = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(0, updatedCount, "No shows should be marked updated")
        assertEquals(0, testDispatcher.dispatched.size, "No notifications should be dispatched")
    }

    @Test
    fun `Show ID hash fallback recovers show ID when savedData id is null`() = runBlocking {
        val showName = "Legacy Show Without ID"
        val showUrl = "https://provider.test/legacy-show"
        val calculatedId = getLoadResponseIdFromUrl(showUrl, testProvider.name)

        // Seed subscription with id = null
        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.None to 2),
            id = null,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.TvSeries,
            posterUrl = null,
            year = 2022
        )
        // Store under the calculated ID
        DataStoreHelper.setSubscribedData(calculatedId, initialSub)

        val episodes = (1..3).map { epNum ->
            testProvider.newEpisode("ep_$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        testProvider.episodeResponse = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(1, updatedCount, "Should recover ID from URL and process update")
        assertEquals(1, testDispatcher.dispatched.size)
        assertEquals(calculatedId, testDispatcher.dispatched.first().replacesId)
    }

    @Test
    fun `Dub preference resolution tests Dubbed vs Subbed priority`() = runBlocking {
        val showId = 2003
        val showName = "Chainsaw Man"
        val showUrl = "https://provider.test/chainsaw"

        // Sub has 12 episodes, Dub has only 6 episodes.
        // If Dub is preferred and lastSeen is 6, should NOT update even if Sub has 12.
        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.Dubbed to 6, DubStatus.Subbed to 12),
            id = showId,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.Anime,
            posterUrl = null,
            year = 2022
        )
        DataStoreHelper.setSubscribedData(showId, initialSub)
        DataStoreHelper.setDub(showId, DubStatus.Dubbed)

        val subEpisodes = (1..12).map { epNum ->
            testProvider.newEpisode("sub_$epNum") {
                this.name = "Sub Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        val dubEpisodes = (1..6).map { epNum ->
            testProvider.newEpisode("dub_$epNum") {
                this.name = "Dub Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }

        testProvider.episodeResponse = testProvider.newAnimeLoadResponse(
            showName,
            showUrl,
            TvType.Anime
        ) {
            this.episodes = mutableMapOf(
                DubStatus.Subbed to subEpisodes,
                DubStatus.Dubbed to dubEpisodes
            )
        }

        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(0, updatedCount, "Dub is up to date (ep 6), should not trigger update")
        assertEquals(0, testDispatcher.dispatched.size)

        // Now provider releases Dub episode 7
        val updatedDubEpisodes = dubEpisodes + testProvider.newEpisode("dub_7") {
            this.name = "Dub Episode 7"
            this.episode = 7
            this.season = 1
        }
        testProvider.episodeResponse = testProvider.newAnimeLoadResponse(
            showName,
            showUrl,
            TvType.Anime
        ) {
            this.episodes = mutableMapOf(
                DubStatus.Subbed to subEpisodes,
                DubStatus.Dubbed to updatedDubEpisodes
            )
        }

        val countAfterNewDub = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(1, countAfterNewDub, "New Dub episode 7 must trigger update")
        assertEquals(1, testDispatcher.dispatched.size)
        assertTrue(testDispatcher.dispatched.first().body.contains("7"))
    }

    @Test
    fun `Auto-download preference flags and queueing into DownloadQueueManager`() = runBlocking {
        val showId = 2004
        val showName = "Kaiju No 8"
        val showUrl = "https://provider.test/kaiju"

        assertFalse(SubscriptionScheduler.isAutoDownloadEnabled(showId))
        SubscriptionScheduler.setAutoDownloadEnabled(showId, true)
        assertTrue(SubscriptionScheduler.isAutoDownloadEnabled(showId))

        // Global preference test
        assertFalse(SubscriptionScheduler.isGlobalAutoDownloadEnabled())
        SubscriptionScheduler.setGlobalAutoDownloadEnabled(true)
        assertTrue(SubscriptionScheduler.isGlobalAutoDownloadEnabled())
        SubscriptionScheduler.setGlobalAutoDownloadEnabled(false)

        val initialSub = DataStoreHelper.SubscribedData(
            subscribedTime = System.currentTimeMillis(),
            lastSeenEpisodeCount = mapOf(DubStatus.None to 3),
            id = showId,
            latestUpdatedTime = System.currentTimeMillis(),
            name = showName,
            url = showUrl,
            apiName = testProvider.name,
            type = TvType.TvSeries,
            posterUrl = "https://provider.test/kaiju.jpg",
            year = 2024
        )
        DataStoreHelper.setSubscribedData(showId, initialSub)

        val episodes = (1..4).map { epNum ->
            testProvider.newEpisode("ep_$epNum") {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = 1
            }
        }
        testProvider.episodeResponse = testProvider.newTvSeriesLoadResponse(showName, showUrl, TvType.TvSeries, episodes)

        val updatedCount = SubscriptionScheduler.checkSubscribed(context)
        assertEquals(1, updatedCount)

        val queue = DownloadQueueManager.queue.value
        val instances = DownloadQueueService.downloadInstances.value
        val item = queue.firstOrNull()?.downloadItem
            ?: instances.firstOrNull()?.downloadQueueWrapper?.downloadItem
        assertNotNull(item, "Episode must be added to download queue")
        assertEquals(showName, item?.resultName)
        assertEquals(4, item?.episode?.episode)
        assertEquals(showId, item?.resultId)
    }

    @Test
    fun `SubscriptionScheduler periodic timer lifecycle and interval configuration`() {
        assertFalse(SubscriptionScheduler.isScheduled)
        assertEquals(0L, SubscriptionScheduler.currentIntervalHours)

        val job = SubscriptionScheduler.schedulePeriodicCheck(
            intervalHours = 6L,
            context = context,
            initialDelayMs = 3600000L
        )
        assertNotNull(job)
        assertTrue(SubscriptionScheduler.isScheduled)
        assertEquals(DEFAULT_SUBSCRIPTION_INTERVAL_HOURS, SubscriptionScheduler.currentIntervalHours)

        SubscriptionScheduler.cancelPeriodicCheck()
        assertFalse(SubscriptionScheduler.isScheduled)
        assertEquals(0L, SubscriptionScheduler.currentIntervalHours)
    }

    @Test
    fun `SubscriptionWorkManager 1 to 1 companion and instance method delegation`() = runBlocking {
        // Test companion periodic work enqueueing matching upstream schedule
        SubscriptionWorkManager.enqueuePeriodicWork(context)
        assertTrue(SubscriptionScheduler.isScheduled)
        assertEquals(DEFAULT_SUBSCRIPTION_INTERVAL_HOURS, SubscriptionScheduler.currentIntervalHours)

        SubscriptionWorkManager.cancelPeriodicCheck()
        assertFalse(SubscriptionScheduler.isScheduled)

        // Test instance doWork execution
        val wm = SubscriptionWorkManager(context)
        val result = wm.doWork()
        assertTrue(result, "doWork must return true on successful execution")
    }
}
