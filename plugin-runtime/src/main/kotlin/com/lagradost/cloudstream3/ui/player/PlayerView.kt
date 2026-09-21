// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/PlayerView.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp

open class PlayerView(
    context: Context = Context()
) : View() {
    override var context: android.content.Context? = context
    var player: IPlayer = CS3IPlayer()
    var callbacks: Callbacks? = null
    var exoPlayerView: androidx.media3.ui.PlayerView? = null
    var playerSpeedupButton: View? = null
    var playerProgressbarLeftHolder: View? = null
    var playerProgressbarLeftIcon: ImageView? = null
    var playerProgressbarLeftLevel1: ProgressBar? = null
    var playerProgressbarLeftLevel2: ProgressBar? = null
    var playerProgressbarRightHolder: View? = null
    var playerProgressbarRightIcon: ImageView? = null
    var playerProgressbarRightLevel1: ProgressBar? = null
    var playerProgressbarRightLevel2: ProgressBar? = null
    var playerRewHolder: View? = null
    var playerRew: View? = null
    var exoRewText: TextView? = null
    var playerFfwdHolder: View? = null
    var playerFfwd: View? = null
    var exoFfwdText: TextView? = null
    var playerCenterMenu: View? = null
    var playerVideoHolder: View? = null
    var playerPausePlay: View? = null
    var playerHolder: View? = null

    val gestureHelper: PlayerGestureHelper = PlayerGestureHelper(this)

    var isFullScreen: Boolean
        get() = gestureHelper.isFullScreen
        set(value) { gestureHelper.isFullScreen = value }

    var isLocked: Boolean
        get() = gestureHelper.isLocked
        set(value) { gestureHelper.isLocked = value }

    var videoOutline: View?
        get() = gestureHelper.videoOutline
        set(value) { gestureHelper.videoOutline = value }

    open fun resize(resize: PlayerResize, showToast: Boolean) {
        exoPlayerView?.resizeMode = when (resize) {
            PlayerResize.Fit -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            PlayerResize.Fill -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            PlayerResize.Zoom -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    interface Callbacks {
        fun nextEpisode() {}
        fun prevEpisode() {}
        fun playerPositionChanged(position: Long, duration: Long) {}
        fun playerStatusChanged() {}
        fun playerDimensionsLoaded(width: Int, height: Int) {}
        fun subtitlesChanged() {}
        fun embeddedSubtitlesFetched(subtitles: List<SubtitleData>) {}
        fun onTracksInfoChanged() {}
        fun onTimestamp(timestamp: VideoSkipStamp?) {}
        fun onTimestampSkipped(timestamp: VideoSkipStamp) {}
        fun exitedPipMode() {}
        fun hasNextMirror(): Boolean = false
        fun nextMirror() {}
        fun onDownload(event: Any?) {}
        fun playerError(exception: Throwable) {}
        fun playerUpdated(player: Any?) {}
        fun onSingleTap() {}
        fun onHoldSpeedUp(show: Boolean) {}
        fun onBrightnessExtra(alpha: Float) {}
        fun isUIShowing(): Boolean = false
        fun onTouchDown() {}
        fun onSeekPreviewText(text: String?) {}
        fun onHidePlayerUI() {}
        fun onGestureEnd(hadSwipe: Boolean, wasUiShowing: Boolean) {}
        fun onAutoHideUI() {}
    }
}
