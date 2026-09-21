// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/services/DownloadQueueService.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.services

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.MainActivity.Companion.setLastError
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadEvent
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DownloadQueueService {

    fun onStartCommand(intent: Intent?, flags: Int = 0, startId: Int = 0): Int {
        val context = CloudStreamApp.context ?: return START_NOT_STICKY
        startService(context)
        return START_STICKY
    }

    fun onDestroy() {
        stopService()
    }

    companion object {
        const val TAG = "DownloadQueueService"
        const val DOWNLOAD_QUEUE_CHANNEL_ID = "cloudstream3.download.queue"
        const val DOWNLOAD_QUEUE_CHANNEL_NAME = "Download queue service"
        const val DOWNLOAD_QUEUE_CHANNEL_DESCRIPTION = "App download queue notification."
        const val DOWNLOAD_QUEUE_NOTIFICATION_ID = 917194232

        const val START_STICKY = 1
        const val START_NOT_STICKY = 2

        @Volatile
        var isRunning = false

        fun getIntent(context: Context): Intent {
            return Intent(context, DownloadQueueService::class.java)
        }

        private val _downloadInstances: MutableStateFlow<List<VideoDownloadManager.EpisodeDownloadInstance>> =
            MutableStateFlow(emptyList())

        /**
         * Flow of all active downloads, not queued.
         * May temporarily contain completed, failed, or cancelled EpisodeDownloadInstances.
         */
        val downloadInstances: StateFlow<List<VideoDownloadManager.EpisodeDownloadInstance>> =
            _downloadInstances

        val totalDownloadFlow: Flow<Triple<List<VideoDownloadManager.EpisodeDownloadInstance>, Array<DownloadQueueWrapper>, Set<Int>>> =
            downloadInstances.combine(DownloadQueueManager.queue) { instances, queue ->
                instances to queue
            }.combine(VideoDownloadManager.currentDownloads) { (instances, queue), currentDownloads ->
                Triple(instances, queue, currentDownloads)
            }

        private var serviceJob: Job? = null
        private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val downloadEventListener = { event: Pair<Int, VideoDownloadManager.DownloadActionType> ->
            when (event.second) {
                VideoDownloadManager.DownloadActionType.Stop -> {
                    removeKey(KEY_RESUME_PACKAGES, event.first.toString())
                    removeKey(KEY_RESUME_IN_QUEUE, event.first.toString())
                    DownloadQueueManager.cancelDownload(event.first)
                    _downloadInstances.update { instances ->
                        instances.filterNot { it.downloadQueueWrapper.id == event.first }
                    }
                }
                else -> {}
            }
        }

        private fun updateNotification(context: Context, downloads: Int, queued: Int) {
            safe {
                val activeDownloads = "$downloads active downloads"
                val activeQueue = "$queued queued"

                val notification = NotificationCompat.Builder(context, DOWNLOAD_QUEUE_CHANNEL_ID)
                    .setContentTitle("CloudStream Downloader")
                    .setContentText(activeDownloads)
                    .setSubText(activeQueue)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

                NotificationManagerCompat.from(context)
                    .notify(DOWNLOAD_QUEUE_NOTIFICATION_ID, notification)
            }
        }

        @OptIn(FlowPreview::class)
        fun startService(context: Context) {
            if (isRunning) return
            isRunning = true
            AppLogger.d(TAG, "Download queue service started (Headless POSIX daemon).")

            downloadEvent += downloadEventListener

            serviceJob?.cancel()
            serviceJob = serviceScope.ioSafe {
                setLastError(context)
                if (lastError != null) return@ioSafe

                // Reactive synchronization: wait for plugins if not loaded yet without artificial polling
                if (!PluginManager.loadedOnlinePlugins || !PluginManager.loadedLocalPlugins) {
                    withTimeoutOrNull(15.seconds) {
                        PluginManager.pluginsLoaded.first { it }
                    }
                }

                totalDownloadFlow
                    .debounce { (instances, queue) ->
                        // Filter away incorrect transient queue states.
                        if (instances.isEmpty() && queue.isEmpty()) {
                            500.milliseconds
                        } else {
                            0.milliseconds
                        }
                    }
                    .takeWhile { (instances, queue) ->
                        isRunning &&
                                (instances.isNotEmpty() || queue.isNotEmpty()) &&
                                lastError == null
                    }
                    .collect { (_, queue, currentDownloads) ->
                        // Filter out completed, failed, or cancelled instances
                        val newInstances = _downloadInstances.updateAndGet { currentInstances ->
                            currentInstances.filterNot { it.isCompleted || it.isFailed || it.isCancelled }
                        }

                        val maxDownloads = VideoDownloadManager.maxConcurrentDownloads(context)
                        val currentInstanceCount = newInstances.size

                        val newDownloads = minOf(
                            maxOf(0, maxDownloads - currentInstanceCount),
                            queue.size
                        )

                        // Full upstream execution loop: repeat(newDownloads) popping next from DownloadQueueManager
                        if (newDownloads > 0) {
                            _downloadInstances.update { instances ->
                                var updated = instances
                                repeat(newDownloads) {
                                    val downloadInstance = DownloadQueueManager.popQueue(context)
                                    if (downloadInstance != null) {
                                        downloadInstance.startDownload()
                                        updated = updated + downloadInstance
                                    }
                                }
                                updated
                            }
                        }

                        // Update Freedesktop visual download notifications
                        val currentVisualDownloads =
                            currentDownloads.size + newInstances.count {
                                !currentDownloads.contains(it.downloadQueueWrapper.id)
                            }
                        val currentVisualQueue = queue.size

                        updateNotification(context, currentVisualDownloads, currentVisualQueue)
                    }
            }

            serviceJob?.invokeOnCompletion { throwable ->
                if (throwable != null) {
                    logError(throwable)
                }
                stopService()
            }
        }

        fun stopService() {
            AppLogger.d(TAG, "Download queue service stopped.")
            downloadEvent -= downloadEventListener
            isRunning = false
            serviceJob?.cancel()
            serviceJob = null
            _downloadInstances.update { emptyList() }
            safe {
                CloudStreamApp.context?.let { ctx ->
                    NotificationManagerCompat.from(ctx).cancel(DOWNLOAD_QUEUE_NOTIFICATION_ID)
                }
            }
        }
    }
}
