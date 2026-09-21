package android.view

open class View {
    open var id: Int = NO_ID
    open var isShown: Boolean = true
    open var isFocusable: Boolean = true
    open var parent: Any? = null
    open var layoutParams: Any? = null
    open var context: android.content.Context? = null
    open var width: Int = 0
    open var height: Int = 0
    open var paddingLeft: Int = 0
    open var paddingRight: Int = 0
    open var paddingTop: Int = 0
    open var paddingBottom: Int = 0
    open var nextFocusLeftId: Int = NO_ID
    open var nextFocusRightId: Int = NO_ID
    open var nextFocusUpId: Int = NO_ID
    open var nextFocusDownId: Int = NO_ID
    open var nextFocusForwardId: Int = NO_ID
    open var layoutDirection: Int = LAYOUT_DIRECTION_LTR
    open val rootView: View get() = this

    open var x: Float = 0f
    open var y: Float = 0f
    open var scaleX: Float = 1f
    open var scaleY: Float = 1f
    open var translationX: Float = 0f
    open var translationY: Float = 0f
    open var pivotX: Float = 0f
    open var pivotY: Float = 0f
    open var alpha: Float = 1f
    open var visibility: Int = VISIBLE

    open var progressTintList: android.content.res.ColorStateList? = null
    open var max: Int = 100
    open var progress: Int = 0
    open var text: CharSequence? = null

    open val rootWindowInsets: WindowInsets get() = WindowInsets()

    open fun isRtl(): Boolean {
        return layoutDirection == LAYOUT_DIRECTION_RTL
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T : View> findViewById(id: Int): T? {
        return null
    }

    open fun requestFocus(): Boolean {
        return false
    }

    open fun findFocus(): View? {
        return null
    }

    open fun scrollBy(x: Int, y: Int) {}

    open fun clearAnimation() {}
    open fun startAnimation(animation: Any?) {}
    open fun animate(): ViewPropertyAnimator = ViewPropertyAnimator(this)

    open fun post(action: Runnable?): Boolean {
        action?.run()
        return true
    }

    open fun postDelayed(action: Runnable?, delayMillis: Long): Boolean {
        action?.run()
        return true
    }

    open fun removeCallbacks(action: Runnable?): Boolean = true

    open fun setOnTouchListener(listener: ((View, MotionEvent) -> Boolean)?) {}

    open fun setImageResource(resId: Int) {}

    fun interface OnLayoutChangeListener {
        fun onLayoutChange(
            v: View,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int
        )
    }

    private val layoutChangeListeners = mutableListOf<OnLayoutChangeListener>()

    open fun addOnLayoutChangeListener(listener: OnLayoutChangeListener) {
        layoutChangeListeners.add(listener)
    }

    open fun removeOnLayoutChangeListener(listener: OnLayoutChangeListener) {
        layoutChangeListeners.remove(listener)
    }

    companion object {
        const val NO_ID: Int = -1
        const val VISIBLE: Int = 0
        const val INVISIBLE: Int = 4
        const val GONE: Int = 8
        const val LAYOUT_DIRECTION_LTR: Int = 0
        const val LAYOUT_DIRECTION_RTL: Int = 1
        const val FOCUS_BACKWARD: Int = 0x00000001
        const val FOCUS_FORWARD: Int = 0x00000002
        const val FOCUS_LEFT: Int = 0x00000011
        const val FOCUS_UP: Int = 0x00000021
        const val FOCUS_RIGHT: Int = 0x00000042
        const val FOCUS_DOWN: Int = 0x00000082
    }
}

var View.isVisible: Boolean
    get() = visibility == View.VISIBLE
    set(value) {
        visibility = if (value) View.VISIBLE else View.GONE
    }

var View.isGone: Boolean
    get() = visibility == View.GONE
    set(value) {
        visibility = if (value) View.GONE else View.VISIBLE
    }

