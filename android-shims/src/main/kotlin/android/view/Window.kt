package android.view

open class Window {
    open var attributes: WindowManager.LayoutParams = WindowManager.LayoutParams()
}

object WindowManager {
    open class LayoutParams {
        open var screenBrightness: Float = -1f
    }
}
