package android.view

open class ViewPropertyAnimator(private val view: View) {
    open fun alpha(value: Float): ViewPropertyAnimator {
        view.alpha = value
        return this
    }

    open fun setDuration(duration: Long): ViewPropertyAnimator = this

    open fun withEndAction(action: Runnable): ViewPropertyAnimator {
        action.run()
        return this
    }

    open fun start(): ViewPropertyAnimator = this

    open fun cancel(): ViewPropertyAnimator = this
}
