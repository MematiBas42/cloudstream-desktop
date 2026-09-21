// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadQueueManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.services.DownloadQueueService.Companion.downloadInstances
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatusEvent
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadQueuePackage
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadResumePackage
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

object DownloadQueueManager {
    private const val TAG = "DownloadQueueManager"
    const val QUEUE_KEY = "download_queue_key"

    private val _queue: MutableStateFlow<Array<DownloadQueueWrapper>> by lazy {
        val currentValue = getKey<Array<DownloadQueueWrapper>>(QUEUE_KEY) ?: emptyArray()
        MutableStateFlow(currentValue)
    }

    val queue: StateFlow<Array<DownloadQueueWrapper>> by lazy { _queue }

    fun init(context: Context) {
        ioSafe {
            _queue.collect { queue ->
                setKey(QUEUE_KEY, queue)
            }
        }

        ioSafe startQueue@{
            if (PluginManager.isSafeMode()) {
                VideoDownloadManager.cancelAllDownloadNotifications(context)
                return@startQueue
            }

            val resumeQueue =
                getPreResumeIds().filterNot {
                    VideoDownloadManager.currentDownloads.value.contains(it)
                }
                    .mapNotNull { id ->
                        getDownloadResumePackage(context, id)?.toWrapper()
                            ?: getDownloadQueuePackage(context, id)
                    }

            val newQueue = _queue.updateAndGet { localQueue ->
                (resumeQueue + localQueue).distinctBy { it.id }.toTypedArray()
            }

            removeKeys(KEY_RESUME_IN_QUEUE)

            newQueue.forEach { obj ->
                setQueueStatus(obj.id, VideoDownloadManager.DownloadType.IsPending)
            }

            if (newQueue.any()) {
                startQueueService(context)
            }
        }
    }

    private fun getPreResumeIds(): Set<Int> {
        return getKeys(KEY_RESUME_IN_QUEUE)?.mapNotNull {
            it.substringAfter("$KEY_RESUME_IN_QUEUE/").toIntOrNull()
        }?.toSet()
            ?: emptySet()
    }

    private fun add(downloadQueueWrapper: DownloadQueueWrapper): Boolean {
        AppLogger.d(TAG, "Download added to queue: $downloadQueueWrapper")
        val newQueue = _queue.updateAndGet { localQueue ->
            if (downloadQueueWrapper.isCurrentlyDownloading() || localQueue.any { it.id == downloadQueueWrapper.id }) {
                return@updateAndGet localQueue
            }
            localQueue + downloadQueueWrapper
        }
        return newQueue.any { it.id == downloadQueueWrapper.id }
    }

    internal fun remove(id: Int) {
        AppLogger.d(TAG, "Download removed from the queue: $id")
        _queue.update { localQueue ->
            if (!localQueue.any { it.id == id }) {
                return@update localQueue
            }

            localQueue.filter { it.id != id }.toTypedArray()
        }
    }

    internal fun removeAll(): Array<DownloadQueueWrapper> {
        AppLogger.d(TAG, "Removed everything from queue")
        return _queue.getAndUpdate {
            emptyArray()
        }
    }

    internal fun reorder(downloadQueueWrapper: DownloadQueueWrapper, newPosition: Int) {
        _queue.update { localQueue ->
            val newIndex = newPosition.coerceIn(0, localQueue.size)
            val id = downloadQueueWrapper.id

            val newQueue = localQueue.filter { it.id != id }.toMutableList().apply {
                this.add(newIndex, downloadQueueWrapper)
            }.toTypedArray()

            newQueue
        }
    }

    fun popQueue(context: Context): VideoDownloadManager.EpisodeDownloadInstance? {
        val first = queue.value.firstOrNull() ?: return null

        remove(first.id)

        val downloadInstance = VideoDownloadManager.EpisodeDownloadInstance(context, first)

        return downloadInstance
    }

    private fun setQueueStatus(id: Int, status: VideoDownloadManager.DownloadType) {
        downloadStatusEvent.invoke(
            Pair(
                id,
                status
            )
        )
        downloadStatus[id] = status
    }

    @Volatile
    var autoStartService: Boolean = true

    private fun startQueueService(context: Context?) {
        if (!autoStartService) return
        if (context == null) {
            AppLogger.d(TAG, "Cannot start download queue service, null context.")
            return
        }
        if (DownloadQueueService.isRunning) {
            return
        }
        DownloadQueueService.startService(context)
    }

    fun cancelDownload(id: Int) {
        AppLogger.d(TAG, "Cancelling download: $id")

        val currentInstance = downloadInstances.value.find { it.downloadQueueWrapper.id == id }

        if (currentInstance != null) {
            currentInstance.cancelDownload()
        } else {
            removeFromQueue(id)
        }
    }

    fun removeAllFromQueue() {
        removeAll().forEach { wrapper ->
            setQueueStatus(wrapper.id, VideoDownloadManager.DownloadType.IsStopped)
        }
    }

    fun removeFromQueue(id: Int) {
        ioSafe {
            remove(id)
            setQueueStatus(id, VideoDownloadManager.DownloadType.IsStopped)
        }
    }

    fun reorderItem(downloadQueueWrapper: DownloadQueueWrapper, newPosition: Int) {
        ioSafe {
            reorder(downloadQueueWrapper, newPosition)
        }
    }

    fun addToQueue(downloadQueueWrapper: DownloadQueueWrapper) = safe {
        val context = CloudStreamApp.context ?: return@safe
        val fileInfo = getDownloadFileInfo(context, downloadQueueWrapper.id)
        val isComplete = fileInfo != null &&
                fileInfo.totalBytes > 0 &&
                (fileInfo.fileLength.toFloat() / fileInfo.totalBytes.toFloat()) > 0.98f
        if (isComplete) return@safe

        if (add(downloadQueueWrapper)) {
            setQueueStatus(downloadQueueWrapper.id, VideoDownloadManager.DownloadType.IsPending)
            startQueueService(context)
        }
    }

    fun forceRefreshQueue() {
        _queue.update { localQueue ->
            localQueue.copyOf()
        }
    }
}
