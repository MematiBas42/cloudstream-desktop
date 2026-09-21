package com.lagradost.player.history

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import com.lagradost.player.api.PlayerState
import com.lagradost.player.ipc.MpvIpcClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Synchronizes MPV IPC playback telemetry with WatchHistoryRepository using MILLISECOND units.
 * - Flushes periodically every 5 seconds and on pause, seek, stop, or socket disconnect (EOF).
 * - Rejects durations under 30 seconds (duration < 30_000L).
 * - Automatically advances parent resume entry to nextEpisode when progress crosses 90%.
 * - Dispatches onAutoNextTriggered callback when 90% threshold is reached.
 */
class WatchHistoryCoordinator(
    private val ipcClient: MpvIpcClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    currentItem: WatchHistoryItem? = null,
    var nextItem: WatchHistoryItem? = null,
    var onAutoNextTriggered: ((WatchHistoryItem) -> Unit)? = null,
    var onProgressUpdate: ((positionMs: Long, durationMs: Long, isCompleted: Boolean) -> Unit)? = null,
) {
    var currentItem: WatchHistoryItem? = currentItem
        set(value) {
            if (field?.url != value?.url || field?.episodeId != value?.episodeId) {
                hasFired90PercentHook = false
                lastObservedPositionSec = 0.0
            }
            field = value
        }

    data class WatchHistoryItem(
        val url: String,
        val parentId: String? = null,
        val episodeId: String? = null,
        val title: String? = null,
        val episode: Int? = null,
        val season: Int? = null,
        val posterUrl: String? = null,
        val apiName: String? = null,
    ) {
        fun toWatchHistory(positionMs: Long, durationMs: Long, isCompleted: Boolean): WatchHistory {
            return WatchHistory(
                url = url,
                parentId = parentId,
                episodeId = episodeId,
                position = positionMs,
                duration = durationMs,
                isCompleted = isCompleted,
                title = title,
                episode = episode,
                season = season,
                posterUrl = posterUrl,
                apiName = apiName,
            )
        }
    }

    private var tickerJob: Job? = null
    private var observerJob: Job? = null
    private val isStarted = AtomicBoolean(false)
    private var hasFired90PercentHook = false

    private var lastObservedPositionSec: Double = 0.0
    private var lastObservedPaused: Boolean = false
    private var lastObservedFinished: Boolean = false

    private val disconnectListener: () -> Unit = {
        flush()
    }

    init {
        start()
    }

    fun start() {
        if (!isStarted.compareAndSet(false, true)) return
        hasFired90PercentHook = false
        ipcClient.addOnDisconnectListener(disconnectListener)

        // 5-second periodic checkpoint saver
        tickerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5000)
                val state = ipcClient.state.value
                val posMs = (state.positionSec * 1000.0).toLong()
                val durMs = (state.durationSec * 1000.0).toLong()
                if (state.isPlaying && !state.isPaused && posMs > 0L && durMs >= 30_000L) {
                    flushInternal(state)
                }
            }
        }

        // Event-driven watcher for instant flushes on pause, seek, stop, EOF
        observerJob = scope.launch(Dispatchers.IO) {
            ipcClient.state.collectLatest { state ->
                val posMs = (state.positionSec * 1000.0).toLong()
                val durMs = (state.durationSec * 1000.0).toLong()

                val becamePaused = state.isPaused && !lastObservedPaused
                val wasSeek = lastObservedPositionSec > 0.0 && abs(state.positionSec - lastObservedPositionSec) > 3.0
                val becameFinished = state.isFinished && !lastObservedFinished

                lastObservedPositionSec = state.positionSec
                lastObservedPaused = state.isPaused
                lastObservedFinished = state.isFinished

                // Check 90% threshold for auto-next dispatch
                if (!hasFired90PercentHook && durMs >= 30_000L) {
                    val percentage = (posMs * 100L) / durMs
                    if (percentage >= 90L) {
                        hasFired90PercentHook = true
                        AppLogger.i("WatchHistoryCoordinator", "90% completion threshold reached for ${currentItem?.title}")
                        flushInternal(state)
                        nextItem?.let { next -> onAutoNextTriggered?.invoke(next) }
                    }
                }

                if (becamePaused || wasSeek || becameFinished) {
                    if (durMs >= 30_000L || state.isFinished) {
                        flushInternal(state)
                    }
                }
            }
        }
    }

    /**
     * Immediately flushes the latest playback position to WatchHistoryRepository.
     */
    fun flush() {
        flushInternal(ipcClient.state.value)
    }

    private fun flushInternal(state: PlayerState) {
        val curr = currentItem ?: state.currentUrl?.let { WatchHistoryItem(url = it) } ?: return
        val posMs = (state.positionSec * 1000.0).toLong()
        val durMs = (state.durationSec * 1000.0).toLong()

        // Reject durations under 30 seconds (duration < 30_000L)
        if (durMs < 30_000L) {
            return
        }

        val percentage = if (durMs > 0L) ((posMs * 100L) / durMs).toInt() else 0
        val isCompleted = state.isCompleted || percentage >= WatchHistoryRepository.NEXT_WATCH_EPISODE_PERCENTAGE

        val currentRecord = curr.toWatchHistory(posMs, durMs, isCompleted)
        val nextRecord = nextItem?.toWatchHistory(0L, 0L, false)

        WatchHistoryRepository.setViewPosAndResume(
            parentId = curr.parentId,
            episodeId = curr.episodeId,
            positionMs = posMs,
            durationMs = durMs,
            currentEpisode = currentRecord,
            nextEpisode = nextRecord,
        )

        onProgressUpdate?.invoke(posMs, durMs, isCompleted)

        AppLogger.d("WatchHistoryCoordinator", "Committed ms watch progress for ${curr.url}: ${posMs}ms / ${durMs}ms (completed=$isCompleted)")
    }

    fun stop() {
        flush()
        tickerJob?.cancel()
        tickerJob = null
        observerJob?.cancel()
        observerJob = null
        ipcClient.removeOnDisconnectListener(disconnectListener)
        isStarted.set(false)
    }

    fun close() = stop()
}
