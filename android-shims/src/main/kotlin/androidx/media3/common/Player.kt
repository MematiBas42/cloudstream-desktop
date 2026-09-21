package androidx.media3.common

interface Player {
    val isCurrentMediaItemDynamic: Boolean get() = false
    val duration: Long get() = C.TIME_UNSET
    val currentLiveOffset: Long get() = C.TIME_UNSET
    val currentPosition: Long get() = 0L
    val currentMediaItemIndex: Int get() = 0

    fun seekTo(positionMs: Long) {}
    fun addListener(listener: Listener) {}
    fun removeListener(listener: Listener) {}

    interface Listener {
        fun onTimelineChanged(timeline: Timeline, reason: Int) {}
        fun onPositionDiscontinuity(
            oldPosition: PositionInfo,
            newPosition: PositionInfo,
            reason: Int
        ) {}
    }

    class PositionInfo(
        val windowIndex: Int = 0,
        val mediaItemIndex: Int = 0,
        val periodIndex: Int = 0,
        val positionMs: Long = 0L,
        val contentPositionMs: Long = 0L,
        val adGroupIndex: Int = -1,
        val adIndexInAdGroup: Int = -1
    )
}
