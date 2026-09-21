package android.view

open class ViewGroup : View() {
    open var descendantFocusability: Int = FOCUS_BEFORE_DESCENDANTS
    open val children: Sequence<View> get() = emptySequence()
    open val childCount: Int get() = 0
    open fun getChildAt(index: Int): View? = null
    open fun removeView(view: View?) {}
    open fun addView(view: View?) {}
    open fun isNotEmpty(): Boolean {
        return children.iterator().hasNext()
    }

    open class LayoutParams(open var width: Int = 0, open var height: Int = 0)

    companion object {
        const val FOCUS_BEFORE_DESCENDANTS: Int = 0x20000
        const val FOCUS_AFTER_DESCENDANTS: Int = 0x40000
        const val FOCUS_BLOCK_DESCENDANTS: Int = 0x60000
    }
}
