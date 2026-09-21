// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/LinearListLayout.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.FocusDirection
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

const val FOCUS_SELF = View.NO_ID - 1
const val FOCUS_INHERIT = FOCUS_SELF - 1

fun RecyclerView?.setLinearListLayout(
    isHorizontal: Boolean = true,
    nextLeft: Int = FOCUS_INHERIT,
    nextRight: Int = FOCUS_INHERIT,
    nextUp: Int = FOCUS_INHERIT,
    nextDown: Int = FOCUS_INHERIT
) {
    if (this == null) return
    val ctx = this.context ?: return
    this.layoutManager = (this.layoutManager as? LinearListLayout ?: LinearListLayout(ctx)).apply {
        if (isHorizontal) setHorizontal() else setVertical()
        nextFocusLeft =
            if (nextLeft == FOCUS_INHERIT) this@setLinearListLayout.nextFocusLeftId else nextLeft
        nextFocusRight =
            if (nextRight == FOCUS_INHERIT) this@setLinearListLayout.nextFocusRightId else nextRight
        nextFocusUp =
            if (nextUp == FOCUS_INHERIT) this@setLinearListLayout.nextFocusUpId else nextUp
        nextFocusDown =
            if (nextDown == FOCUS_INHERIT) this@setLinearListLayout.nextFocusDownId else nextDown
    }
}

open class LinearListLayout(context: Context?) :
    LinearLayoutManager(context) {

    var nextFocusLeft: Int = View.NO_ID
    var nextFocusRight: Int = View.NO_ID
    var nextFocusUp: Int = View.NO_ID
    var nextFocusDown: Int = View.NO_ID

    fun setHorizontal() {
        orientation = HORIZONTAL
    }

    fun setVertical() {
        orientation = VERTICAL
    }

    private fun getCorrectParent(focused: View?): View? {
        if (focused == null) return null
        var current: View? = focused
        val last: ArrayList<View> = arrayListOf(focused)
        while (current != null && current !is RecyclerView) {
            current = (current.parent as? View?)?.also { last.add(it) }
        }
        return last.getOrNull(last.count() - 2)
    }

    private fun getPosition(view: View?): Int? {
        return (view?.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition
    }

    private fun getViewFromPos(pos: Int): View? {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if ((child?.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition == pos) {
                return child
            }
        }
        return null
        //return recyclerView.children.firstOrNull { child -> (child.layoutParams as? RecyclerView.LayoutParams?)?.absoluteAdapterPosition == pos) }
    }

    /*
    private fun scrollTo(position: Int) {
        val linearSmoothScroller = LinearSmoothScroller(recyclerView.context)
        linearSmoothScroller.targetPosition = position
        startSmoothScroll(linearSmoothScroller)
    }*/

    /** from the current focus go to a direction */
    private fun getNextDirection(focused: View?, direction: FocusDirection): View? {
        val id = when (direction) {
            FocusDirection.Start -> if (isLayoutRTL) nextFocusRight else nextFocusLeft
            FocusDirection.End -> if (isLayoutRTL) nextFocusLeft else nextFocusRight
            FocusDirection.Up -> nextFocusUp
            FocusDirection.Down -> nextFocusDown
        }

        return when (id) {
            View.NO_ID -> null
            FOCUS_SELF -> focused
            else -> CommonActivity.continueGetNextFocus(
                activity ?: focused,
                focused ?: return null,
                direction,
                id
            )
        }
    }

    fun redirectRecycleToFirstItem(focused: View): View? {
        return when (focused) {
            is RecyclerView -> {
                (focused.layoutManager as? LinearListLayout)?.let { focusedLayoutManager ->
                    val firstPosition = focusedLayoutManager.findFirstVisibleItemPosition()
                    val firstView = focusedLayoutManager.findViewByPosition(firstPosition)
                    firstView
                } ?: focused
            }

            else -> focused
        }
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val dir = if (orientation == HORIZONTAL) {
            if (direction == View.FOCUS_DOWN) getNextDirection(
                focused,
                FocusDirection.Down
            )?.let { newFocus ->
                return redirectRecycleToFirstItem(newFocus)
            }
            if (direction == View.FOCUS_UP) getNextDirection(
                focused,
                FocusDirection.Up
            )?.let { newFocus ->
                return redirectRecycleToFirstItem(newFocus)
            }

            if (direction == View.FOCUS_DOWN || direction == View.FOCUS_UP) {
                // This scrolls the recyclerview before doing focus search, which
                // allows the focus search to work better.

                // Without this the recyclerview focus location on the screen
                // would change when scrolling between recyclerviews.
                (focused.parent as? RecyclerView)?.focusSearch(direction)
                return null
            }
            var ret = if (direction == View.FOCUS_RIGHT) 1 else -1
            // only flip on horizontal layout
            if (isLayoutRTL) {
                ret = -ret
            }
            ret
        } else {
            if (direction == View.FOCUS_RIGHT) getNextDirection(
                focused,
                FocusDirection.End
            )?.let { newFocus ->
                return newFocus
            }
            if (direction == View.FOCUS_LEFT) getNextDirection(
                focused,
                FocusDirection.Start
            )?.let { newFocus ->
                return newFocus
            }

            if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) {
                (focused.parent as? RecyclerView)?.focusSearch(direction)
                return null
            }

            //if (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT) return null
            if (direction == View.FOCUS_DOWN) 1 else -1
        }

        try {
            val position = getPosition(getCorrectParent(focused)) ?: return null
            val lookFor = dir + position

            // if out of bounds then refocus as specified
            return if (lookFor >= itemCount) {
                getNextDirection(
                    focused,
                    if (orientation == HORIZONTAL) FocusDirection.End else FocusDirection.Down
                )
            } else if (lookFor < 0) {
                getNextDirection(
                    focused,
                    if (orientation == HORIZONTAL) FocusDirection.Start else FocusDirection.Up
                )
            } else {
                getViewFromPos(lookFor) ?: run {
                    scrollToPosition(lookFor)
                    null
                }
            }
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        if (isLayout(TV) && orientation == HORIZONTAL) {
            val dx = when {
                isLayoutRTL -> getDecoratedRight(child) - (parent.width - parent.paddingRight)
                else -> getDecoratedLeft(child) - parent.paddingLeft
            }
            return if (dx != 0) {
                when {
                    immediate -> parent.scrollBy(dx, 0)
                    else -> parent.smoothScrollBy(dx, 0)
                }
                true
            } else {
                false
            }
        } else {
            return super.requestChildRectangleOnScreen(
                parent,
                child,
                rect,
                immediate,
                focusedChildVisible
            )
        }
    }
}

/**
 * Adapter bridging Android RecyclerView [LinearListLayout] parameters
 * to Desktop Compose LazyColumn / LazyRow specifications.
 */
data class LinearListLayoutComposeAdapter(
    val isHorizontal: Boolean = true,
    val reverseLayout: Boolean = false,
    val nextFocusLeft: Int = View.NO_ID,
    val nextFocusRight: Int = View.NO_ID,
    val nextFocusUp: Int = View.NO_ID,
    val nextFocusDown: Int = View.NO_ID,
    val initialFirstVisibleItemIndex: Int = 0,
    val initialFirstVisibleItemScrollOffset: Int = 0,
    val isLayoutRtl: Boolean = false,
    val itemCount: Int = 0
) {
    val isVertical: Boolean get() = !isHorizontal

    /**
     * Resolves the next navigation focus target ID or boundary action based on current position and direction,
     * mirroring [LinearListLayout.onInterceptFocusSearch].
     */
    fun resolveNextFocusId(direction: FocusDirection): Int {
        return when (direction) {
            FocusDirection.Start -> if (isLayoutRtl) nextFocusRight else nextFocusLeft
            FocusDirection.End -> if (isLayoutRtl) nextFocusLeft else nextFocusRight
            FocusDirection.Up -> nextFocusUp
            FocusDirection.Down -> nextFocusDown
        }
    }

    /**
     * Calculates whether a focus step at [currentPosition] in [direction] escapes the list bounds.
     * Returns true if navigation moves out of the list to the adjacent container.
     */
    fun isBoundaryNavigation(currentPosition: Int, direction: FocusDirection): Boolean {
        val delta = if (isHorizontal) {
            when (direction) {
                FocusDirection.Start -> if (isLayoutRtl) 1 else -1
                FocusDirection.End -> if (isLayoutRtl) -1 else 1
                else -> return false
            }
        } else {
            when (direction) {
                FocusDirection.Up -> -1
                FocusDirection.Down -> 1
                else -> return false
            }
        }
        val target = currentPosition + delta
        return target < 0 || target >= itemCount
    }

    /**
     * Calculates the target index for directional navigation within the list,
     * or null if navigating out of bounds.
     */
    fun calculateTargetIndex(currentPosition: Int, direction: FocusDirection): Int? {
        val delta = if (isHorizontal) {
            when (direction) {
                FocusDirection.Start -> if (isLayoutRtl) 1 else -1
                FocusDirection.End -> if (isLayoutRtl) -1 else 1
                else -> return null
            }
        } else {
            when (direction) {
                FocusDirection.Up -> -1
                FocusDirection.Down -> 1
                else -> return null
            }
        }
        val target = currentPosition + delta
        return if (target in 0 until itemCount) target else null
    }

    /**
     * Calculates the target scroll delta when requesting child rectangle on screen.
     */
    fun computeScrollDelta(
        childDecoratedLeft: Int,
        childDecoratedRight: Int,
        parentWidth: Int,
        paddingLeft: Int,
        paddingRight: Int
    ): Int {
        return when {
            isLayoutRtl -> childDecoratedRight - (parentWidth - paddingRight)
            else -> childDecoratedLeft - paddingLeft
        }
    }
}

/**
 * Extension to convert an existing [LinearListLayout] instance into a [LinearListLayoutComposeAdapter]
 * for Compose Desktop consumption.
 */
fun LinearListLayout.toComposeAdapter(
    itemCount: Int = this.itemCount,
    initialIndex: Int = this.findFirstVisibleItemPosition().coerceAtLeast(0)
): LinearListLayoutComposeAdapter {
    return LinearListLayoutComposeAdapter(
        isHorizontal = this.orientation == LinearLayoutManager.HORIZONTAL,
        reverseLayout = this.reverseLayout,
        nextFocusLeft = this.nextFocusLeft,
        nextFocusRight = this.nextFocusRight,
        nextFocusUp = this.nextFocusUp,
        nextFocusDown = this.nextFocusDown,
        initialFirstVisibleItemIndex = initialIndex,
        isLayoutRtl = this.isLayoutRTL,
        itemCount = itemCount
    )
}
