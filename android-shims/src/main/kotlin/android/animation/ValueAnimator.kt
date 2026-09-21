package android.animation

import java.util.concurrent.CopyOnWriteArrayList

open class ValueAnimator {
    var startDelay: Long = 0L
    var duration: Long = 300L
    var animatedValue: Any = 0f
    private var isRunning: Boolean = false
    private val listeners = CopyOnWriteArrayList<(ValueAnimator) -> Unit>()

    open fun addUpdateListener(listener: (ValueAnimator) -> Unit) {
        listeners.add(listener)
    }

    open fun start() {
        isRunning = true
        animatedValue = 1.0f
        listeners.forEach { it(this) }
    }

    open fun cancel() {
        isRunning = false
    }

    companion object {
        fun ofFloat(vararg values: Float): ValueAnimator {
            return ValueAnimator().apply {
                if (values.isNotEmpty()) {
                    animatedValue = values.last()
                }
            }
        }
    }
}
