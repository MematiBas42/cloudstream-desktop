package androidx.media3.common

open class Timeline {
    open fun getWindow(mediaItemIndex: Int, window: Window): Window {
        return window
    }

    class Window {
        var isDynamic: Boolean = false
        var durationMs: Long = 0L
    }
}
