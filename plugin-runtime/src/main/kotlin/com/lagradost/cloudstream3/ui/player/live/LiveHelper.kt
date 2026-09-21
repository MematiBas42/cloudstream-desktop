// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/live/LiveHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player.live

import kotlin.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.ui.player.IPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.WeakHashMap

class IPlayerLiveAdapter(
    val iPlayer: IPlayer,
    override val isCurrentMediaItemDynamic: Boolean = true
) : Player {
    override val duration: Long get() = iPlayer.getDuration() ?: C.TIME_UNSET
    override val currentPosition: Long get() = iPlayer.getPosition() ?: 0L
    override val currentLiveOffset: Long get() = C.TIME_UNSET
    override val currentMediaItemIndex: Int get() = 0

    private val listeners = mutableListOf<Player.Listener>()

    override fun seekTo(positionMs: Long) {
        iPlayer.seekTo(positionMs)
    }

    override fun addListener(listener: Player.Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    override fun removeListener(listener: Player.Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun notifyTimelineChanged(windowDurationMs: Long, isDynamic: Boolean = true) {
        val timeline = object : Timeline() {
            override fun getWindow(mediaItemIndex: Int, window: Window): Window {
                window.isDynamic = isDynamic
                window.durationMs = windowDurationMs
                return window
            }
        }
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { it.onTimelineChanged(timeline, 0) }
    }

    fun notifyPositionDiscontinuity(oldPosMs: Long, newPosMs: Long) {
        val oldInfo = Player.PositionInfo(positionMs = oldPosMs)
        val newInfo = Player.PositionInfo(positionMs = newPosMs)
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { it.onPositionDiscontinuity(oldInfo, newInfo, 0) }
    }
}

object LiveHelper {
    private val liveManagers = WeakHashMap<Player, Pair<LiveManager, Player.Listener>>()
    private val iPlayerAdapters = WeakHashMap<IPlayer, IPlayerLiveAdapter>()

    private val _isAtLiveEdge = MutableStateFlow(false)
    val isAtLiveEdge: StateFlow<Boolean> = _isAtLiveEdge.asStateFlow()

    private val _timeAheadOfLive = MutableStateFlow(0L)
    val timeAheadOfLive: StateFlow<Long> = _timeAheadOfLive.asStateFlow()

    @OptIn(UnstableApi::class)
    fun registerPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper registerPlayer called with null player!" }
            return
        }

        // Prevent duplicates
        if (liveManagers.contains(player)) {
            return
        }

        val liveManager = LiveManager(player)
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val window = Timeline.Window()
                timeline.getWindow(player.currentMediaItemIndex, window)
                if (window.isDynamic) {
                    liveManager.submitLivestreamChunk(LivestreamChunk(window.durationMs))
                }
                _isAtLiveEdge.value = liveManager.isAtLiveEdge()
                super.onTimelineChanged(timeline, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                val timeAheadOfLive = liveManager.getTimeAheadOfLive(newPosition.positionMs)
                _timeAheadOfLive.value = timeAheadOfLive
                _isAtLiveEdge.value = liveManager.isAtLiveEdge()

                // Seek back to the optimal live spot
                if (timeAheadOfLive > 100) {
                    player.seekTo(newPosition.positionMs - timeAheadOfLive)
                }
            }
        }

        synchronized(liveManagers) {
            player.addListener(listener)
            liveManagers[player] = liveManager to listener
        }
        _isAtLiveEdge.value = liveManager.isAtLiveEdge()
    }

    fun registerPlayer(iPlayer: IPlayer?, isDynamic: Boolean = true) {
        if (iPlayer == null) {
            debugWarning { "LiveHelper registerPlayer called with null iPlayer!" }
            return
        }
        val adapter = synchronized(iPlayerAdapters) {
            iPlayerAdapters.getOrPut(iPlayer) { IPlayerLiveAdapter(iPlayer, isDynamic) }
        }
        registerPlayer(adapter)
    }

    fun unregisterPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper unregisterPlayer called with null player!" }
            return
        }

        // Prevent duplicates
        if (!liveManagers.contains(player)) {
            return
        }

        synchronized(liveManagers) {
            liveManagers[player]?.let { (_, listener) ->
                player.removeListener(listener)
            }
            liveManagers.remove(player)
        }

        if (liveManagers.isEmpty()) {
            _isAtLiveEdge.value = false
            _timeAheadOfLive.value = 0L
        }
    }

    fun unregisterPlayer(iPlayer: IPlayer?) {
        if (iPlayer == null) {
            debugWarning { "LiveHelper unregisterPlayer called with null iPlayer!" }
            return
        }
        val adapter = synchronized(iPlayerAdapters) {
            iPlayerAdapters.remove(iPlayer)
        }
        if (adapter != null) {
            unregisterPlayer(adapter)
        }
    }

    fun getLiveManager(player: Player?) = liveManagers[player]?.first

    fun getLiveManager(iPlayer: IPlayer?): LiveManager? {
        val adapter = iPlayerAdapters[iPlayer] ?: return null
        return getLiveManager(adapter)
    }

    fun updateLiveState(player: Player?) {
        val manager = getLiveManager(player)
        if (manager != null) {
            _isAtLiveEdge.value = manager.isAtLiveEdge()
            val position = player?.currentPosition ?: 0L
            _timeAheadOfLive.value = manager.getTimeAheadOfLive(position)
        } else {
            _isAtLiveEdge.value = false
            _timeAheadOfLive.value = 0L
        }
    }
}
