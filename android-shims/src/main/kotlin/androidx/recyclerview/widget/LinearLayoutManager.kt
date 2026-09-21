package androidx.recyclerview.widget

import android.content.Context
import android.graphics.Rect
import android.view.View

open class LinearLayoutManager(
    val context: Context? = null,
    orientation: Int = RecyclerView.VERTICAL,
    reverseLayout: Boolean = false
) : RecyclerView.LayoutManager() {

    var orientation: Int = orientation
    var reverseLayout: Boolean = reverseLayout
    open var isLayoutRTL: Boolean = false

    private val attachedChildren = mutableListOf<View>()

    override val childCount: Int
        get() = attachedChildren.size

    override var itemCount: Int = 0

    override fun getChildAt(index: Int): View? {
        return attachedChildren.getOrNull(index)
    }

    fun addAttachedChild(child: View) {
        attachedChildren.add(child)
    }

    fun removeAttachedChild(child: View) {
        attachedChildren.remove(child)
    }

    fun clearAttachedChildren() {
        attachedChildren.clear()
    }

    override fun scrollToPosition(position: Int) {
        // Scroll target update
    }

    open fun findFirstVisibleItemPosition(): Int {
        return 0
    }

    open fun findLastVisibleItemPosition(): Int {
        return (childCount - 1).coerceAtLeast(0)
    }

    open fun findViewByPosition(position: Int): View? {
        return attachedChildren.firstOrNull { child ->
            (child.layoutParams as? RecyclerView.LayoutParams)?.absoluteAdapterPosition == position
        } ?: attachedChildren.getOrNull(position)
    }

    open fun getDecoratedLeft(child: View): Int = child.paddingLeft
    open fun getDecoratedRight(child: View): Int = child.width - child.paddingRight
    open fun getDecoratedTop(child: View): Int = child.paddingTop
    open fun getDecoratedBottom(child: View): Int = child.height - child.paddingBottom

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? = null

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean = false

    // Field/property aliases for unconditional resolution in subclasses (e.g. orientation = HORIZONTAL)
    val HORIZONTAL: Int get() = Companion.HORIZONTAL
    val VERTICAL: Int get() = Companion.VERTICAL

    companion object {
        const val HORIZONTAL: Int = 0
        const val VERTICAL: Int = 1
    }
}
