// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/services/SubscriptionWorkManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.services

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ui.result.buildResultEpisode
import com.lagradost.cloudstream3.ui.result.getLoadResponseIdFromUrl
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllSubscriptions
import com.lagradost.cloudstream3.utils.DataStoreHelper.getDub
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.notifications.DesktopNotificationBridge
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val SUBSCRIPTION_CHANNEL_ID = "cloudstream3.subscriptions"
const val SUBSCRIPTION_WORK_NAME = "work_subscription"
const val SUBSCRIPTION_CHANNEL_NAME = "Subscriptions"
const val SUBSCRIPTION_CHANNEL_DESCRIPTION = "Notifications for new episodes on subscribed shows"
const val SUBSCRIPTION_NOTIFICATION_ID = 938712897 // Random unique
const val SUBSCRIPTION_AUTO_DOWNLOAD_KEY = "subscription_auto_download"
const val GLOBAL_AUTO_DOWNLOAD_KEY = "global_auto_download_subscriptions"
const val DEFAULT_SUBSCRIPTION_INTERVAL_HOURS = 6L

/**
 * Linux-native headless background daemon scheduler for subscribed shows.
 *
 * Implements:
 * 1. Periodic coroutine daemon timer (schedulePeriodicCheck / cancelPeriodicCheck).
 * 2. Subscription state diffing against provider latest episodes (checkSubscribed).
 * 3. Rich Freedesktop D-Bus desktop notifications with poster resolution.
 * 4. Automatic episode download queuing into DownloadQueueManager.
 */
object SubscriptionScheduler {
    private const val TAG = "SubscriptionScheduler"
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scanMutex = Mutex()

    @Volatile
    private var scheduledJob: Job? = null

    val isScheduled: Boolean
        get() = scheduledJob?.isActive == true

    @Volatile
    var currentIntervalHours: Long = 0L
        private set

    @Volatile
    var lastScanTimestamp: Long = 0L
        private set

    @Volatile
    var lastScanSuccess: Boolean = false
        private set

    @Volatile
    var lastUpdatedCount: Int = 0
        private set

    @Volatile
    var isScanning: Boolean = false
        private set

    /**
     * Checks if auto-download is enabled for a given show ID or globally.
     */
    fun isAutoDownloadEnabled(id: Int?): Boolean {
        if (id == null) return false
        val account = DataStoreHelper.currentAccount
        return getKey<Boolean>("$account/$SUBSCRIPTION_AUTO_DOWNLOAD_KEY", id.toString())
            ?: getKey<Boolean>(GLOBAL_AUTO_DOWNLOAD_KEY)
            ?: false
    }

    /**
     * Enables or disables auto-download for a specific show ID.
     */
    fun setAutoDownloadEnabled(id: Int?, enabled: Boolean) {
        if (id == null) return
        val account = DataStoreHelper.currentAccount
        setKey("$account/$SUBSCRIPTION_AUTO_DOWNLOAD_KEY", id.toString(), enabled)
    }

    /**
     * Checks if global auto-download for subscribed shows is enabled.
     */
    fun isGlobalAutoDownloadEnabled(): Boolean {
        return getKey<Boolean>(GLOBAL_AUTO_DOWNLOAD_KEY) ?: false
    }

    /**
     * Sets the global auto-download preference for subscribed shows.
     */
    fun setGlobalAutoDownloadEnabled(enabled: Boolean) {
        setKey(GLOBAL_AUTO_DOWNLOAD_KEY, enabled)
    }

    /**
     * Schedules periodic background checking of subscribed shows at the specified hourly interval.
     */
    fun schedulePeriodicCheck(
        intervalHours: Long = DEFAULT_SUBSCRIPTION_INTERVAL_HOURS,
        context: Context = Context(),
        initialDelayMs: Long = intervalHours * 3600 * 1000L
    ): Job? {
        if (intervalHours <= 0L) {
            cancelPeriodicCheck()
            return null
        }

        cancelPeriodicCheck()
        currentIntervalHours = intervalHours

        val job = schedulerScope.launch {
            AppLogger.i(TAG, "Scheduled periodic subscription check every $intervalHours hours (initial delay: ${initialDelayMs}ms)")
            if (initialDelayMs > 0) {
                delay(initialDelayMs)
            }
            while (isActive) {
                try {
                    checkSubscribed(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "Periodic subscription check encountered error", e)
                }
                delay(intervalHours * 3600 * 1000L)
            }
        }
        scheduledJob = job
        return job
    }

    /**
     * Cancels any active periodic subscription checking daemon.
     */
    fun cancelPeriodicCheck() {
        scheduledJob?.cancel()
        scheduledJob = null
        currentIntervalHours = 0L
        AppLogger.i(TAG, "Cancelled periodic subscription scheduler.")
    }

    /**
     * Triggers an immediate subscription check and returns the count of updated shows.
     */
    suspend fun checkNow(context: Context = Context()): Int {
        return checkSubscribed(context)
    }

    /**
     * Triggers an asynchronous subscription check in the background.
     */
    fun triggerCheckNowAsync(context: Context = Context()): Job {
        return schedulerScope.launch {
            checkSubscribed(context)
        }
    }

    /**
     * Scans all subscribed shows, detects newly released episodes, dispatches Freedesktop
     * notifications, and queues auto-downloads if configured.
     *
     * @param context Application context for notification channels and localizations.
     * @return The number of shows with newly released episodes.
     */
    suspend fun checkSubscribed(context: Context = Context()): Int = withContext(Dispatchers.IO) {
        if (CloudStreamApp.context == null) {
            CloudStreamApp.context = context
        }
        scanMutex.withLock {
            isScanning = true
            try {
                context.createNotificationChannel(
                    SUBSCRIPTION_CHANNEL_ID,
                    SUBSCRIPTION_CHANNEL_NAME,
                    SUBSCRIPTION_CHANNEL_DESCRIPTION
                )

                val subscriptions = getAllSubscriptions()
                if (subscriptions.isEmpty()) {
                    AppLogger.d(TAG, "No subscriptions registered. Skipping check.")
                    lastScanTimestamp = System.currentTimeMillis()
                    lastScanSuccess = true
                    lastUpdatedCount = 0
                    return@withContext 0
                }

                // Rescan and ensure extensions are loaded
                try {
                    ExtensionLoader.rescanAndLoadNewPlugins()
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Warning while reloading plugins for subscription sync: ${t.message}")
                }

                val max = subscriptions.size
                var progress = 0
                var updatedCount = 0

                for (savedData in subscriptions) {
                    try {
                        val id = savedData.id ?: (if (savedData.url.isNotBlank()) getLoadResponseIdFromUrl(savedData.url, savedData.apiName) else null) ?: continue
                        val api = getApiFromNameNull(savedData.apiName)
                        if (api == null) {
                            AppLogger.w(TAG, "Provider '${savedData.apiName}' not found for '${savedData.name}'")
                            continue
                        }

                        val response = withTimeoutOrNull(60_000) {
                            api.load(savedData.url) as? EpisodeResponse
                        }
                        if (response == null) {
                            AppLogger.w(TAG, "Could not load episode data for '${savedData.name}' from ${api.name}")
                            continue
                        }

                        val dubPreference = getDub(id) ?: if (
                            context.getApiDubstatusSettings().contains(DubStatus.Dubbed)
                        ) {
                            DubStatus.Dubbed
                        } else {
                            DubStatus.Subbed
                        }

                        val latestEpisodes = response.getLatestEpisodes()
                        val latestPreferredEpisode = latestEpisodes[dubPreference]

                        val (shouldUpdate, latestEpisode) = if (latestPreferredEpisode != null) {
                            val latestSeenEpisode =
                                savedData.lastSeenEpisodeCount[dubPreference] ?: Int.MIN_VALUE
                            val shouldUpdate = latestPreferredEpisode > latestSeenEpisode
                            shouldUpdate to latestPreferredEpisode
                        } else {
                            val latestEpisode = latestEpisodes[DubStatus.None] ?: Int.MIN_VALUE
                            val latestSeenEpisode =
                                savedData.lastSeenEpisodeCount[DubStatus.None] ?: Int.MIN_VALUE
                            val shouldUpdate = latestEpisode > latestSeenEpisode
                            shouldUpdate to latestEpisode
                        }

                        // Update subscribed data with latest episode counts
                        DataStoreHelper.updateSubscribedData(
                            id,
                            savedData,
                            response
                        )

                        if (shouldUpdate) {
                            updatedCount++
                            val updateHeader = savedData.name
                            val updateDescription = txt(
                                R.string.subscription_episode_released,
                                latestEpisode,
                                savedData.name
                            ).asString(context)

                            AppLogger.i(TAG, "New episode released for '${savedData.name}': Episode $latestEpisode")

                            // 1. Dispatch rich cross-platform desktop notification with poster via DesktopNotificationBridge
                            DesktopNotificationBridge.showNotificationWithPoster(
                                title = updateHeader,
                                body = updateDescription,
                                posterUrl = savedData.posterUrl,
                                posterHeaders = savedData.posterHeaders,
                                notificationId = id,
                                actionUrl = savedData.url,
                                actionLabel = "Open Show",
                                apiName = savedData.apiName
                            )

                            // 2. Auto-download episode if configured
                            if (isAutoDownloadEnabled(id)) {
                                enqueueAutoDownload(savedData, id, response, dubPreference, latestEpisode)
                            }
                        }

                        progress++
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error checking subscription '${savedData.name}'", t)
                    }
                }

                lastScanTimestamp = System.currentTimeMillis()
                lastScanSuccess = true
                lastUpdatedCount = updatedCount
                AppLogger.i(TAG, "Subscription check completed. Checked $max shows, found $updatedCount update(s).")
                updatedCount
            } catch (t: Throwable) {
                lastScanSuccess = false
                AppLogger.e(TAG, "Subscription check failed", t)
                0
            } finally {
                isScanning = false
            }
        }
    }

    private fun enqueueAutoDownload(
        savedData: DataStoreHelper.SubscribedData,
        showId: Int,
        response: EpisodeResponse,
        dubPreference: DubStatus,
        targetEpisodeNum: Int
    ) {
        try {
            val candidateList: List<Episode> = when (response) {
                is TvSeriesLoadResponse -> response.episodes
                is AnimeLoadResponse -> {
                    response.episodes[dubPreference]
                        ?: response.episodes[DubStatus.None]
                        ?: response.episodes.values.firstOrNull()
                        ?: emptyList()
                }
                else -> emptyList()
            }

            val episodeToDownload = candidateList.find { it.episode == targetEpisodeNum }
                ?: candidateList.lastOrNull()
                ?: return

            val episodeId = (savedData.url + "_" + episodeToDownload.season + "_" + episodeToDownload.episode + "_" + episodeToDownload.data).hashCode()
            val effectiveTvType = savedData.type ?: TvType.TvSeries

            val resultEp = buildResultEpisode(
                headerName = savedData.name,
                name = episodeToDownload.name,
                poster = episodeToDownload.posterUrl ?: savedData.posterUrl,
                episode = episodeToDownload.episode ?: targetEpisodeNum,
                seasonIndex = episodeToDownload.season,
                season = episodeToDownload.season,
                data = episodeToDownload.data,
                apiName = savedData.apiName,
                id = episodeId,
                index = episodeToDownload.episode ?: targetEpisodeNum,
                rating = episodeToDownload.score,
                description = episodeToDownload.description,
                tvType = effectiveTvType,
                parentId = showId
            )

            val queueItem = DownloadObjects.DownloadQueueItem(
                episode = resultEp,
                isMovie = effectiveTvType.isMovieType(),
                resultName = savedData.name,
                resultType = effectiveTvType,
                resultPoster = savedData.posterUrl,
                apiName = savedData.apiName,
                resultId = showId,
                resultUrl = savedData.url
            )

            DownloadQueueManager.addToQueue(queueItem.toWrapper())
            AppLogger.i(TAG, "Enqueued auto-download for '${savedData.name}' Episode $targetEpisodeNum (id=$episodeId)")
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed to enqueue auto-download for '${savedData.name}'", t)
        }
    }
}

/**
 * Headless Linux adaptation of upstream SubscriptionWorkManager.
 * Preserves 1:1 API compatibility with upstream callers while delegating
 * scheduling and execution to SubscriptionScheduler.
 */
class SubscriptionWorkManager(val context: Context, val workerParams: Any? = null) {
    companion object {
        fun enqueuePeriodicWork(context: Context?) {
            if (context == null) return
            SubscriptionScheduler.schedulePeriodicCheck(DEFAULT_SUBSCRIPTION_INTERVAL_HOURS, context)
        }

        fun schedulePeriodicCheck(
            intervalHours: Long = DEFAULT_SUBSCRIPTION_INTERVAL_HOURS,
            context: Context = Context(),
            initialDelayMs: Long = intervalHours * 3600 * 1000L
        ): Job? = SubscriptionScheduler.schedulePeriodicCheck(intervalHours, context, initialDelayMs)

        fun cancelPeriodicCheck() = SubscriptionScheduler.cancelPeriodicCheck()

        suspend fun checkSubscribed(context: Context = Context()): Int =
            SubscriptionScheduler.checkSubscribed(context)

        suspend fun checkNow(context: Context = Context()): Int =
            SubscriptionScheduler.checkNow(context)
    }

    private val progressNotificationBuilder =
        NotificationCompat.Builder(context, SUBSCRIPTION_CHANNEL_ID)
            .setAutoCancel(false)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setContentTitle(context.getString(R.string.subscription_in_progress_notification))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)
            .setProgress(0, 0, true)

    private val updateNotificationBuilder =
        NotificationCompat.Builder(context, SUBSCRIPTION_CHANNEL_ID)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: NotificationManager()

    private fun updateProgress(max: Int, progress: Int, indeterminate: Boolean) {
        try {
            notificationManager.notify(
                SUBSCRIPTION_NOTIFICATION_ID,
                progressNotificationBuilder
                    .setProgress(max, progress, indeterminate)
                    .build()
            )
        } catch (t: Throwable) {
            AppLogger.d("SubscriptionWorkManager", "Failed to update notification progress: ${t.message}")
        }
    }

    suspend fun doWork(): Boolean {
        context.createNotificationChannel(
            SUBSCRIPTION_CHANNEL_ID,
            SUBSCRIPTION_CHANNEL_NAME,
            SUBSCRIPTION_CHANNEL_DESCRIPTION
        )
        updateProgress(0, 0, true)
        return checkSubscribed(context) >= 0
    }
}
