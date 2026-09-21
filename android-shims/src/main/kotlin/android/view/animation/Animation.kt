package android.view.animation

import android.content.Context

open class Animation {
    open var duration: Long = 0L
    open var fillAfter: Boolean = false
    private var listener: AnimationListener? = null

    interface AnimationListener {
        fun onAnimationStart(animation: Animation?)
        fun onAnimationEnd(animation: Animation?)
        fun onAnimationRepeat(animation: Animation?)
    }

    open fun setAnimationListener(listener: AnimationListener?) {
        this.listener = listener
    }

    open fun triggerEnd() {
        listener?.onAnimationEnd(this)
    }
}

open class AlphaAnimation(val fromAlpha: Float, val toAlpha: Float) : Animation()

object AnimationUtils {
    fun loadAnimation(context: Context?, id: Int): Animation {
        return Animation()
    }
}
