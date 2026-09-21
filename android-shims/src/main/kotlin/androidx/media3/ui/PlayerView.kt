package androidx.media3.ui

import android.view.View

open class PlayerView : View() {
    open var resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
    open var videoSurfaceView: View? = null
    open var player: Any? = null
}
