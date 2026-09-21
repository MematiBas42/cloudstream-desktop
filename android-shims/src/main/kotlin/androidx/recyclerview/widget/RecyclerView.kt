package androidx.recyclerview.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

open class RecyclerView(context: Context? = null) : ViewGroup() {
    init {
        this.context = context
    }

    open var layoutManager: LayoutManager? = null
    open var adapter: Adapter<*>? = null

    open fun smoothScrollBy(dx: Int, dy: Int) {
        scrollBy(dx, dy)
    }

    open fun scrollToPosition(position: Int) {
        layoutManager?.scrollToPosition(position)
    }

    open fun focusSearch(direction: Int): View? {
        return null
    }

    open class LayoutParams(width: Int = 0, height: Int = 0) : ViewGroup.LayoutParams(width, height) {
        var absoluteAdapterPosition: Int = 0
        var bindingAdapterPosition: Int = 0
    }

    abstract class LayoutManager {
        open val childCount: Int get() = 0
        open val itemCount: Int get() = 0

        open fun getChildAt(index: Int): View? = null
        open fun scrollToPosition(position: Int) {}
        open fun onInterceptFocusSearch(focused: View, direction: Int): View? = null
        open fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean
        ): Boolean = false
    }

    abstract class Adapter<VH : ViewHolder> {
        abstract fun getItemCount(): Int
    }

    abstract class ViewHolder(open val itemView: View) {
        var layoutPosition: Int = 0
        var absoluteAdapterPosition: Int = 0
    }

    class State {
        var itemCount: Int = 0
    }

    companion object {
        const val HORIZONTAL: Int = 0
        const val VERTICAL: Int = 1
        const val NO_POSITION: Int = -1
    }
}
