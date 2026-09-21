// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/services/VideoDownloadService.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.services

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Headless Linux background download coordinator.
 * Handles notification actions (pause, resume, cancel, clear), manages
 * systemd-inhibit sleep inhibition while downloads are active, and integrates
 * with Freedesktop notifications for download progress and completion.
 */
class VideoDownloadService {

    fun onBind(intent: Intent?): Any? {
        return null
    }

    fun onStartCommand(intent: Intent?, flags: Int = 0, startId: Int = 0): Int {
        return Companion.onStartCommand(intent, flags, startId)
    }

    fun onDestroy() {
        Companion.stopCoordinator()
    }

    fun onCreate() {
        Companion.startCoordinator()
    }

    fun pause(id: Int) = Companion.pause(id)
    fun resume(id: Int) = Companion.resume(id)
    fun cancel(id: Int) = Companion.cancel(id)
    fun clear() = Companion.clear()

    companion object {
        const val TAG = "VideoDownloadService"
        const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "cloudstream3.download.notifications"
        const val DOWNLOAD_NOTIFICATION_CHANNEL_NAME = "Download Notifications"
        const val DOWNLOAD_NOTIFICATION_CHANNEL_DESCRIPTION = "Linux Freedesktop download notifications."

        const val START_STICKY = 1
        const val START_NOT_STICKY = 2

        const val ACTION_RESUME = "resume"
        const val ACTION_PAUSE = "pause"
        const val ACTION_STOP = "stop"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_CLEAR = "clear"

        private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val lastProgressNotified = ConcurrentHashMap<Int, Long>()

        @Volatile
        var isCoordinatorRunning: Boolean = false
            private set

        @Volatile
        var sleepInhibitor: SleepInhibitor = SleepInhibitor.createDefault()

        val isSleepInhibited: Boolean
            get() = sleepInhibitor.isInhibited

        private val inhibitLock = Any()
        private var inhibitorJob: Job? = null

        /**
         * Command provider for systemd-inhibit.
         * Configurable to allow testing or platform-specific adjustments.
         */
        var inhibitCommandProvider: () -> List<String>
            get() = (sleepInhibitor as? SystemdSleepInhibitor)?.commandProvider ?: SystemdSleepInhibitor.defaultCommandProvider
            set(value) {
                val current = sleepInhibitor
                if (current is SystemdSleepInhibitor) {
                    current.commandProvider = value
                } else {
                    sleepInhibitor = SystemdSleepInhibitor(value)
                }
            }

        /**
         * Reactive flow indicating whether any downloads or queue items are currently active.
         * Emits true if either active downloads, queue items, or download instances exist.
         */
        val hasActiveDownloadsFlow: Flow<Boolean> = combine(
            VideoDownloadManager.currentDownloads,
            DownloadQueueManager.queue,
            DownloadQueueService.downloadInstances
        ) { current, queue, instances ->
            current.isNotEmpty() || queue.isNotEmpty() || instances.any { !it.isCompleted && !it.isFailed && !it.isCancelled }
        }.distinctUntilChanged()

        init {
            try {
                Runtime.getRuntime().addShutdownHook(Thread({
                    try {
                        releaseSleepInhibit()
                    } catch (e: Throwable) {
                        AppLogger.w(TAG, "Shutdown hook failed to release sleep inhibitor: ${e.message}")
                    }
                }, "CloudStream-SleepInhibitor-ShutdownHook"))
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Failed to register shutdown hook: ${e.message}")
            }
        }

        fun getIntent(context: Context): Intent {
            return Intent(context, VideoDownloadService::class.java)
        }

        fun onStartCommand(intent: Intent?, flags: Int = 0, startId: Int = 0): Int {
            if (intent != null) {
                handleIntent(intent)
            }
            return START_NOT_STICKY
        }

        fun handleIntent(intent: Intent) {
            val id = intent.getIntExtra("id", -1)
            val type = intent.getStringExtra("type") ?: intent.action
            if (type != null) {
                handleAction(id, type)
            }
        }

        fun handleAction(id: Int, action: String) {
            AppLogger.d(TAG, "Handling download action: $action for id: $id")
            when (action.lowercase()) {
                ACTION_RESUME -> if (id != -1) resume(id)
                ACTION_PAUSE -> if (id != -1) pause(id)
                ACTION_STOP, ACTION_CANCEL -> if (id != -1) cancel(id)
                ACTION_CLEAR -> clear()
                else -> AppLogger.w(TAG, "Unknown action: $action")
            }
        }

        fun pause(id: Int) {
            AppLogger.d(TAG, "Pausing download: $id")
            downloadScope.launch {
                VideoDownloadManager.downloadEvent.invoke(Pair(id, DownloadActionType.Pause))
            }
        }

        fun resume(id: Int) {
            AppLogger.d(TAG, "Resuming download: $id")
            downloadScope.launch {
                VideoDownloadManager.downloadEvent.invoke(Pair(id, DownloadActionType.Resume))
            }
        }

        fun cancel(id: Int) {
            AppLogger.d(TAG, "Cancelling download: $id")
            downloadScope.launch {
                VideoDownloadManager.downloadEvent.invoke(Pair(id, DownloadActionType.Stop))
                DownloadQueueManager.cancelDownload(id)
                safe {
                    CloudStreamApp.context?.let { ctx ->
                        NotificationManagerCompat.from(ctx).cancel(id)
                    }
                }
            }
        }

        fun clear() {
            AppLogger.d(TAG, "Clearing all downloads and queue")
            downloadScope.launch {
                DownloadQueueManager.removeAllFromQueue()
                val activeInstances = DownloadQueueService.downloadInstances.value
                for (instance in activeInstances) {
                    val id = instance.downloadQueueWrapper.id
                    instance.cancelDownload()
                    DownloadQueueManager.cancelDownload(id)
                    VideoDownloadManager.downloadEvent.invoke(Pair(id, DownloadActionType.Stop))
                    safe {
                        CloudStreamApp.context?.let { ctx ->
                            NotificationManagerCompat.from(ctx).cancel(id)
                        }
                    }
                }
                safe {
                    CloudStreamApp.context?.let { ctx ->
                        VideoDownloadManager.cancelAllDownloadNotifications(ctx)
                    }
                }
            }
        }

        /**
         * Acquires sleep inhibition lock via configured [sleepInhibitor].
         * Prevents system suspension/idling while active downloads are in progress.
         *
         * @return True if inhibitor was successfully acquired, false otherwise.
         */
        fun acquireSleepInhibit(): Boolean {
            return sleepInhibitor.acquire()
        }

        /**
         * Releases the sleep inhibitor lock if held.
         *
         * @return True if release succeeded, false otherwise.
         */
        fun releaseSleepInhibit(): Boolean {
            return sleepInhibitor.release()
        }

        /**
         * Starts reactive sleep inhibitor collection.
         * Binds sleep inhibition directly to download activity StateFlows (CLAUDE.md Section 2.2: Zero Polling).
         */
        fun startSleepInhibitorCollector() {
            synchronized(inhibitLock) {
                inhibitorJob?.cancel()
                inhibitorJob = downloadScope.launch {
                    hasActiveDownloadsFlow.collect { hasActiveDownloads ->
                        AppLogger.d(TAG, "Download activity state changed: hasActiveDownloads=$hasActiveDownloads")
                        if (hasActiveDownloads) {
                            acquireSleepInhibit()
                        } else {
                            releaseSleepInhibit()
                        }
                    }
                }
            }
        }

        /**
         * Stops reactive sleep inhibitor collection and releases any held lock.
         */
        fun stopSleepInhibitorCollector() {
            synchronized(inhibitLock) {
                inhibitorJob?.cancel()
                inhibitorJob = null
                releaseSleepInhibit()
            }
        }

        /**
         * Rehydrates interrupted downloads from [KEY_RESUME_PACKAGES].
         * In upstream CloudStream, MainActivity inspected KEY_RESUME_PACKAGES on startup
         * to resume downloads interrupted by app exit or crash.
         *
         * @return Count of resumed downloads.
         */
        fun resumeInterruptedDownloads(context: Context = CloudStreamApp.context ?: Context()): Int {
            return safe {
                if (PluginManager.isSafeMode()) {
                    AppLogger.d(TAG, "Safe mode active, skipping interrupted downloads recovery.")
                    return@safe 0
                }
                val keys = CloudStreamApp.getKeys(VideoDownloadManager.KEY_RESUME_PACKAGES) ?: return@safe 0
                val resumePkgs = keys.mapNotNull { key ->
                    CloudStreamApp.getKey<DownloadResumePackage>(key)
                }
                if (resumePkgs.isEmpty()) return@safe 0

                CloudStreamApp.removeKeys(VideoDownloadManager.KEY_RESUME_PACKAGES)
                for (pkg in resumePkgs) {
                    DownloadQueueManager.addToQueue(pkg.toWrapper())
                }
                AppLogger.i(TAG, "Resumed ${resumePkgs.size} interrupted downloads from ${VideoDownloadManager.KEY_RESUME_PACKAGES}")
                resumePkgs.size
            } ?: 0
        }

        private val statusEventListener: (Pair<Int, DownloadType>) -> Unit = { event ->
            val (id, status) = event
            when (status) {
                DownloadType.IsDone -> {
                    val title = getDownloadTitle(id) ?: "Download #$id"
                    sendFreedesktopNotification(
                        title = "Download Complete",
                        message = "$title finished downloading.",
                        notificationId = id
                    )
                }
                DownloadType.IsFailed -> {
                    val title = getDownloadTitle(id) ?: "Download #$id"
                    sendFreedesktopNotification(
                        title = "Download Failed",
                        message = "$title failed to download.",
                        notificationId = id
                    )
                }
                DownloadType.IsStopped -> {
                    safe {
                        CloudStreamApp.context?.let { ctx ->
                            NotificationManagerCompat.from(ctx).cancel(id)
                        }
                    }
                }
                else -> {}
            }
        }

        private val progressEventListener: (Triple<Int, Long, Long>) -> Unit = { event ->
            val (id, bytesDownloaded, totalBytes) = event
            if (totalBytes > 0) {
                val progressPercent = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                val now = System.currentTimeMillis()
                val lastTime = lastProgressNotified[id] ?: 0L
                // Throttle desktop progress notifications to once every 3 seconds or on completion
                if (now - lastTime >= 3000L || progressPercent == 100) {
                    lastProgressNotified[id] = now
                    val title = getDownloadTitle(id) ?: "Download #$id"
                    val downloadedMb = bytesDownloaded / (1024 * 1024)
                    val totalMb = totalBytes / (1024 * 1024)
                    sendFreedesktopNotification(
                        title = "Downloading $title",
                        message = "$progressPercent% ($downloadedMb MB / $totalMb MB)",
                        notificationId = id,
                        progress = progressPercent
                    )
                }
            }
        }

        fun sendFreedesktopNotification(
            title: String,
            message: String,
            notificationId: Int? = null,
            progress: Int? = null
        ) {
            safe {
                val context = CloudStreamApp.context
                if (context != null) {
                    val builder = NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)

                    if (progress != null) {
                        builder.setProgress(100, progress, false)
                    }

                    NotificationManagerCompat.from(context).notify(
                        notificationId ?: 1000,
                        builder.build()
                    )
                } else if (PlatformPaths.currentOS == PlatformPaths.OS.LINUX) {
                    val cmd = mutableListOf("notify-send", "-a", "CloudStream", title, message)
                    if (progress != null) {
                        cmd.add("-h")
                        cmd.add("int:value:$progress")
                    }
                    if (notificationId != null) {
                        cmd.add("-r")
                        cmd.add(notificationId.toString())
                    }
                    try {
                        ProcessBuilder(cmd)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start()
                    } catch (e: Throwable) {
                        AppLogger.w(TAG, "notify-send execution failed: ${e.message}")
                    }
                }
            }
        }

        private fun getDownloadTitle(id: Int): String? {
            return safe {
                val info = DataStore.getKey<DownloadedFileInfo>(
                    VideoDownloadManager.KEY_DOWNLOAD_INFO,
                    id.toString()
                )
                if (info != null && info.displayName.isNotBlank()) {
                    return@safe info.displayName
                }
                val ep = DataStore.getKey<DownloadEpisodeCached>(
                    DOWNLOAD_EPISODE_CACHE,
                    id.toString()
                )
                if (ep != null) {
                    val epNum = ep.episode
                    val seasonNum = ep.season
                    val prefix = if (seasonNum != null || epNum > 0) {
                        "${seasonNum?.let { "S$it" } ?: ""}${if (epNum > 0) "E$epNum" else ""}: "
                    } else ""
                    return@safe "$prefix${ep.name ?: "Episode $epNum"}"
                }
                null
            }
        }

        fun startCoordinator(context: Context? = CloudStreamApp.context) {
            if (isCoordinatorRunning) return
            isCoordinatorRunning = true
            AppLogger.d(TAG, "Starting VideoDownloadService coordinator (Headless Linux daemon).")

            VideoDownloadManager.downloadStatusEvent += statusEventListener
            VideoDownloadManager.downloadProgressEvent += progressEventListener

            startSleepInhibitorCollector()

            context?.let { ctx ->
                resumeInterruptedDownloads(ctx)
            }
        }

        fun stopCoordinator() {
            if (!isCoordinatorRunning) return
            isCoordinatorRunning = false
            AppLogger.d(TAG, "Stopping VideoDownloadService coordinator.")

            VideoDownloadManager.downloadStatusEvent -= statusEventListener
            VideoDownloadManager.downloadProgressEvent -= progressEventListener
            lastProgressNotified.clear()

            stopSleepInhibitorCollector()
        }
    }
}

/**
 * Cross-platform sleep inhibitor interfaces forwarded to :common platform service.
 * Retained as typealiases for backward compatibility.
 */
typealias SleepInhibitor = com.lagradost.common.platform.SleepInhibitor
typealias SystemdSleepInhibitor = com.lagradost.common.platform.SystemdSleepInhibitor
typealias WindowsSleepInhibitor = com.lagradost.common.platform.WindowsSleepInhibitor
typealias NoOpSleepInhibitor = com.lagradost.common.platform.NoOpSleepInhibitor
typealias MacOsSleepInhibitor = com.lagradost.common.platform.MacOsSleepInhibitor
