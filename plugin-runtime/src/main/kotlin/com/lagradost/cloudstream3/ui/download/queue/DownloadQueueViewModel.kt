// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/download/queue/DownloadQueueViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.download.queue

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.services.DownloadQueueService.Companion.downloadInstances
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class DownloadAdapterQueue(
    val currentDownloads: List<DownloadObjects.DownloadQueueWrapper>,
    val queue: List<DownloadObjects.DownloadQueueWrapper>,
)

class DownloadQueueViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    private val _childCards = MutableStateFlow(
        DownloadAdapterQueue(
            currentDownloads = emptyList(),
            queue = emptyList()
        )
    )
    val childCards: StateFlow<DownloadAdapterQueue> = _childCards.asStateFlow()

    private val totalDownloadFlow =
        downloadInstances.combine(DownloadQueueManager.queue) { instances, queue ->
            val current = instances.map { it.downloadQueueWrapper }
            DownloadAdapterQueue(current, queue.toList())
        }.combine(VideoDownloadManager.currentDownloads) { total, _ ->
            // We want to update the flow when currentDownloads updates, but we do not care about its value
            total
        }

    init {
        viewModelScope.launch {
            totalDownloadFlow.collect { queue ->
                updateChildList(queue)
            }
        }
    }

    fun updateChildList(downloads: DownloadAdapterQueue) {
        _childCards.value = downloads
    }

    /**
     * Cancels or removes a queued or active download item and triggers partial file cleanup
     * via [DownloadFileManagement.deletePartial] to avoid leaving orphaned .part files on disk.
     */
    fun deleteQueueItem(id: Int, context: Context? = null): Boolean {
        DownloadQueueManager.cancelDownload(id)
        val ctx = context ?: CloudStreamApp.context
        return if (ctx != null) {
            DownloadFileManagement.deletePartial(ctx, id)
        } else false
    }

    /**
     * Convenience overload to delete a queue item wrapper with partial file cleanup.
     */
    fun deleteQueueItem(item: DownloadObjects.DownloadQueueWrapper, context: Context? = null): Boolean {
        return deleteQueueItem(item.id, context)
    }

    /**
     * Asynchronously cancels or removes a queue item on [viewModelScope].
     */
    fun deleteQueueItemAsync(id: Int, context: Context? = null): Job = viewModelScope.launch {
        deleteQueueItem(id, context)
    }

    /**
     * Cancels an active or queued download with partial file cleanup.
     */
    fun cancelDownload(id: Int, context: Context? = null): Boolean {
        return deleteQueueItem(id, context)
    }

    /**
     * Removes an item from the waiting queue and triggers partial file cleanup.
     */
    fun removeFromQueue(id: Int, context: Context? = null): Boolean {
        DownloadQueueManager.removeFromQueue(id)
        val ctx = context ?: CloudStreamApp.context
        return if (ctx != null) {
            DownloadFileManagement.deletePartial(ctx, id)
        } else false
    }

    /**
     * Cancels all active and queued downloads and removes all orphaned partial (.part) files on disk.
     */
    fun removeAllFromQueue(context: Context? = null): Int {
        val ctx = context ?: CloudStreamApp.context
        val currentCards = _childCards.value
        val allIds = (currentCards.currentDownloads + currentCards.queue).map { it.id }.toSet()
        DownloadQueueManager.removeAllFromQueue()
        var deletedCount = 0
        if (ctx != null) {
            allIds.forEach { id ->
                if (DownloadFileManagement.deletePartial(ctx, id)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    /**
     * Cancels all downloads with partial file cleanup.
     */
    fun cancelAll(context: Context? = null): Int {
        return removeAllFromQueue(context)
    }

    /**
     * Asynchronously cancels all downloads on [viewModelScope].
     */
    fun cancelAllAsync(context: Context? = null): Job = viewModelScope.launch {
        removeAllFromQueue(context)
    }

    /**
     * Reorders an item within the pending download queue.
     */
    fun reorderItem(downloadQueueWrapper: DownloadObjects.DownloadQueueWrapper, newPosition: Int) {
        DownloadQueueManager.reorderItem(downloadQueueWrapper, newPosition)
    }

    /**
     * Pauses an active download by its ID.
     */
    fun pauseDownload(id: Int) {
        VideoDownloadManager.pause(id)
    }

    /**
     * Resumes a paused download by its ID.
     */
    fun resumeDownload(id: Int) {
        VideoDownloadManager.resume(id)
    }

    /**
     * Pauses all currently active downloads in the queue.
     */
    fun pauseAll() {
        _childCards.value.currentDownloads.forEach { wrapper ->
            VideoDownloadManager.pause(wrapper.id)
        }
    }

    /**
     * Resumes all paused downloads in the active queue.
     */
    fun resumeAll() {
        _childCards.value.currentDownloads.forEach { wrapper ->
            VideoDownloadManager.resume(wrapper.id)
        }
    }
}
