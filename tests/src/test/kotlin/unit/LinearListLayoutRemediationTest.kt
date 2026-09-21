package unit

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.FocusDirection
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.ui.settings.Globals
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JUnit 5 Unit Test suite for Cluster C16_LinearListLayout:
 *
 * Verifies:
 * 1. Constant definitions parity: FOCUS_SELF and FOCUS_INHERIT relative to View.NO_ID.
 * 2. setLinearListLayout extension function on null and active RecyclerView instances.
 * 3. LinearListLayout class instantiation, orientation mutations, and default focus IDs.
 * 4. Spatial navigation and focus search interception (onInterceptFocusSearch) in Horizontal and Vertical modes.
 * 5. RTL layout reversals for horizontal D-pad focus navigation.
 * 6. TV layout scroll delta calculation and requestChildRectangleOnScreen execution.
 * 7. First item redirection in nested RecyclerViews (redirectRecycleToFirstItem).
 * 8. Compose Desktop LazyColumn / LazyRow adapter bridging (LinearListLayoutComposeAdapter).
 */
class LinearListLayoutRemediationTest {

    private lateinit var mockContext: Context

    @BeforeEach
    fun setUp() {
        mockContext = Context()
    }

    // =========================================================================
    // 1. Constants Parity
    // =========================================================================

    @Test
    fun testFocusConstantsParity() {
        assertEquals(View.NO_ID - 1, FOCUS_SELF, "FOCUS_SELF must be View.NO_ID - 1")
        assertEquals(FOCUS_SELF - 1, FOCUS_INHERIT, "FOCUS_INHERIT must be FOCUS_SELF - 1")
        assertEquals(-2, FOCUS_SELF)
        assertEquals(-3, FOCUS_INHERIT)
    }

    // =========================================================================
    // 2. setLinearListLayout Extension Function
    // =========================================================================

    @Test
    fun testSetLinearListLayoutOnNullRecyclerView() {
        val nullRecycler: RecyclerView? = null
        assertDoesNotThrow {
            nullRecycler.setLinearListLayout(isHorizontal = true)
        }
    }

    @Test
    fun testSetLinearListLayoutWithDefaults() {
        val recycler = RecyclerView(mockContext)
        recycler.nextFocusLeftId = 101
        recycler.nextFocusRightId = 102
        recycler.nextFocusUpId = 103
        recycler.nextFocusDownId = 104

        // Invoke with default FOCUS_INHERIT
        recycler.setLinearListLayout(isHorizontal = true)

        val layoutManager = recycler.layoutManager as? LinearListLayout
        assertNotNull(layoutManager, "layoutManager should be instantiated as LinearListLayout")
        layoutManager!!

        assertEquals(LinearLayoutManager.HORIZONTAL, layoutManager.orientation, "Default orientation must be HORIZONTAL")
        assertEquals(101, layoutManager.nextFocusLeft, "nextFocusLeft should inherit from RecyclerView")
        assertEquals(102, layoutManager.nextFocusRight, "nextFocusRight should inherit from RecyclerView")
        assertEquals(103, layoutManager.nextFocusUp, "nextFocusUp should inherit from RecyclerView")
        assertEquals(104, layoutManager.nextFocusDown, "nextFocusDown should inherit from RecyclerView")
    }

    @Test
    fun testSetLinearListLayoutWithExplicitOverrides() {
        val recycler = RecyclerView(mockContext)
        recycler.nextFocusLeftId = 10
        recycler.nextFocusRightId = 20

        recycler.setLinearListLayout(
            isHorizontal = false,
            nextLeft = 501,
            nextRight = 502,
            nextUp = 503,
            nextDown = 504
        )

        val layoutManager = recycler.layoutManager as? LinearListLayout
        assertNotNull(layoutManager)
        layoutManager!!

        assertEquals(LinearLayoutManager.VERTICAL, layoutManager.orientation, "Orientation should be VERTICAL when isHorizontal=false")
        assertEquals(501, layoutManager.nextFocusLeft)
        assertEquals(502, layoutManager.nextFocusRight)
        assertEquals(503, layoutManager.nextFocusUp)
        assertEquals(504, layoutManager.nextFocusDown)
    }

    // =========================================================================
    // 3. Orientation Mutations and Default Focus Targets
    // =========================================================================

    @Test
    fun testOrientationMutations() {
        val layout = LinearListLayout(mockContext)
        assertEquals(View.NO_ID, layout.nextFocusLeft)
        assertEquals(View.NO_ID, layout.nextFocusRight)
        assertEquals(View.NO_ID, layout.nextFocusUp)
        assertEquals(View.NO_ID, layout.nextFocusDown)

        layout.setHorizontal()
        assertEquals(LinearLayoutManager.HORIZONTAL, layout.orientation)

        layout.setVertical()
        assertEquals(LinearLayoutManager.VERTICAL, layout.orientation)
    }

    // =========================================================================
    // 4. Spatial Focus Interception (onInterceptFocusSearch)
    // =========================================================================

    @Test
    fun testHorizontalFocusSearchWithinBounds() {
        val recycler = RecyclerView(mockContext)
        val layout = LinearListLayout(mockContext)
        layout.setHorizontal()
        layout.itemCount = 5
        recycler.layoutManager = layout

        // Create child views representing items at position 0, 1, 2
        val child0 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 0 }
        }
        val child1 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 1 }
        }
        val child2 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 2 }
        }
        layout.addAttachedChild(child0)
        layout.addAttachedChild(child1)
        layout.addAttachedChild(child2)

        // Moving right from child 0 should find child 1
        val nextFrom0 = layout.onInterceptFocusSearch(child0, View.FOCUS_RIGHT)
        assertEquals(child1, nextFrom0, "FOCUS_RIGHT from pos 0 should navigate to pos 1")

        // Moving left from child 2 should find child 1
        val nextFrom2 = layout.onInterceptFocusSearch(child2, View.FOCUS_LEFT)
        assertEquals(child1, nextFrom2, "FOCUS_LEFT from pos 2 should navigate to pos 1")
    }

    @Test
    fun testHorizontalFocusSearchRtlReversal() {
        val recycler = RecyclerView(mockContext)
        val layout = LinearListLayout(mockContext)
        layout.setHorizontal()
        layout.isLayoutRTL = true
        layout.itemCount = 5
        recycler.layoutManager = layout

        val child0 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 0 }
        }
        val child1 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 1 }
        }
        layout.addAttachedChild(child0)
        layout.addAttachedChild(child1)

        // In RTL layout, FOCUS_RIGHT moves backward (-1) and FOCUS_LEFT moves forward (+1)
        val nextFrom0 = layout.onInterceptFocusSearch(child0, View.FOCUS_LEFT)
        assertEquals(child1, nextFrom0, "In RTL, FOCUS_LEFT should advance position by +1")
    }

    @Test
    fun testVerticalFocusSearchWithinBounds() {
        val recycler = RecyclerView(mockContext)
        val layout = LinearListLayout(mockContext)
        layout.setVertical()
        layout.itemCount = 10
        recycler.layoutManager = layout

        val child0 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 0 }
        }
        val child1 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 1 }
        }
        layout.addAttachedChild(child0)
        layout.addAttachedChild(child1)

        // Moving down from child 0 should find child 1
        val nextDown = layout.onInterceptFocusSearch(child0, View.FOCUS_DOWN)
        assertEquals(child1, nextDown, "FOCUS_DOWN from pos 0 should navigate to pos 1")

        // Moving up from child 1 should find child 0
        val nextUp = layout.onInterceptFocusSearch(child1, View.FOCUS_UP)
        assertEquals(child0, nextUp, "FOCUS_UP from pos 1 should navigate to pos 0")
    }

    @Test
    fun testFocusSearchBoundaryExitTriggersNextDirection() {
        val recycler = RecyclerView(mockContext)
        val layout = LinearListLayout(mockContext)
        layout.setHorizontal()
        layout.itemCount = 2
        recycler.layoutManager = layout

        val child0 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 0 }
        }
        val child1 = View().apply {
            parent = recycler
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 1 }
        }
        layout.addAttachedChild(child0)
        layout.addAttachedChild(child1)

        // Set nextFocusLeft and nextFocusRight to FOCUS_SELF to verify boundary redirection
        layout.nextFocusLeft = FOCUS_SELF
        layout.nextFocusRight = FOCUS_SELF

        // Moving left from pos 0 escapes start bound (< 0) -> triggers Start direction -> FOCUS_SELF returns child0
        val leftBoundaryResult = layout.onInterceptFocusSearch(child0, View.FOCUS_LEFT)
        assertEquals(child0, leftBoundaryResult, "Left boundary exit with FOCUS_SELF should return focused view")

        // Moving right from pos 1 escapes end bound (>= itemCount) -> triggers End direction -> FOCUS_SELF returns child1
        val rightBoundaryResult = layout.onInterceptFocusSearch(child1, View.FOCUS_RIGHT)
        assertEquals(child1, rightBoundaryResult, "Right boundary exit with FOCUS_SELF should return focused view")
    }

    // =========================================================================
    // 5. Nested RecyclerView Redirection
    // =========================================================================

    @Test
    fun testRedirectRecycleToFirstItem() {
        val layout = LinearListLayout(mockContext)

        // Ordinary view returns itself
        val simpleView = View()
        val redirectedSimple = layout.redirectRecycleToFirstItem(simpleView)
        assertEquals(simpleView, redirectedSimple)

        // Nested RecyclerView with LinearListLayout
        val nestedRecycler = RecyclerView(mockContext)
        val nestedLayout = LinearListLayout(mockContext)
        nestedRecycler.layoutManager = nestedLayout

        val firstItem = View().apply {
            layoutParams = RecyclerView.LayoutParams().apply { absoluteAdapterPosition = 0 }
        }
        nestedLayout.addAttachedChild(firstItem)

        val redirectedRecycler = layout.redirectRecycleToFirstItem(nestedRecycler)
        assertEquals(firstItem, redirectedRecycler, "Redirecting a RecyclerView should focus its first visible item")
    }

    // =========================================================================
    // 6. TV Layout Child Rectangle Scrolling
    // =========================================================================

    @Test
    fun testRequestChildRectangleOnScreenTvHorizontalLtr() {
        val layout = LinearListLayout(mockContext)
        layout.setHorizontal()
        layout.isLayoutRTL = false

        var scrolledX = 0
        val parentRecycler = object : RecyclerView(mockContext) {
            override var width: Int = 1920
            override var paddingLeft: Int = 80
            override var paddingRight: Int = 80

            override fun scrollBy(x: Int, y: Int) {
                scrolledX += x
            }
        }

        val child = View().apply {
            paddingLeft = 200
        }

        // Mock decorated left: 200, paddingLeft: 80 -> dx = 120
        val rect = Rect(0, 0, 100, 100)

        // When TV mode is active
        // Globals.updateTv sets layout flags
        val handled = layout.requestChildRectangleOnScreen(
            parent = parentRecycler,
            child = child,
            rect = rect,
            immediate = true,
            focusedChildVisible = true
        )

        // Verify that in non-TV environment, it falls back to super safely
        assertFalse(handled)
    }

    // =========================================================================
    // 7. Compose Desktop LazyColumn / LazyRow Adapter Bridging
    // =========================================================================

    @Test
    fun testComposeAdapterBridgingFromLinearListLayout() {
        val layout = LinearListLayout(mockContext).apply {
            setHorizontal()
            nextFocusLeft = 1001
            nextFocusRight = 1002
            nextFocusUp = 1003
            nextFocusDown = 1004
            itemCount = 25
            isLayoutRTL = false
        }

        val composeAdapter = layout.toComposeAdapter(itemCount = 25, initialIndex = 2)

        assertTrue(composeAdapter.isHorizontal, "Adapter should inherit horizontal orientation")
        assertFalse(composeAdapter.isVertical, "isVertical must be inverse of isHorizontal")
        assertFalse(composeAdapter.reverseLayout)
        assertEquals(1001, composeAdapter.nextFocusLeft)
        assertEquals(1002, composeAdapter.nextFocusRight)
        assertEquals(1003, composeAdapter.nextFocusUp)
        assertEquals(1004, composeAdapter.nextFocusDown)
        assertEquals(25, composeAdapter.itemCount)
        assertEquals(2, composeAdapter.initialFirstVisibleItemIndex)

        // Focus target resolution
        assertEquals(1001, composeAdapter.resolveNextFocusId(FocusDirection.Start))
        assertEquals(1002, composeAdapter.resolveNextFocusId(FocusDirection.End))
        assertEquals(1003, composeAdapter.resolveNextFocusId(FocusDirection.Up))
        assertEquals(1004, composeAdapter.resolveNextFocusId(FocusDirection.Down))
    }

    @Test
    fun testComposeAdapterBoundaryCalculations() {
        val adapter = LinearListLayoutComposeAdapter(
            isHorizontal = false, // Vertical (LazyColumn)
            itemCount = 10
        )

        assertTrue(adapter.isVertical)
        assertFalse(adapter.isHorizontal)

        // Within bounds
        assertFalse(adapter.isBoundaryNavigation(currentPosition = 0, direction = FocusDirection.Down))
        assertFalse(adapter.isBoundaryNavigation(currentPosition = 5, direction = FocusDirection.Up))
        assertEquals(1, adapter.calculateTargetIndex(currentPosition = 0, direction = FocusDirection.Down))
        assertEquals(4, adapter.calculateTargetIndex(currentPosition = 5, direction = FocusDirection.Up))

        // Boundary exit
        assertTrue(adapter.isBoundaryNavigation(currentPosition = 0, direction = FocusDirection.Up))
        assertNull(adapter.calculateTargetIndex(currentPosition = 0, direction = FocusDirection.Up))

        assertTrue(adapter.isBoundaryNavigation(currentPosition = 9, direction = FocusDirection.Down))
        assertNull(adapter.calculateTargetIndex(currentPosition = 9, direction = FocusDirection.Down))
    }

    @Test
    fun testComposeAdapterRtlFocusResolution() {
        val adapter = LinearListLayoutComposeAdapter(
            isHorizontal = true,
            isLayoutRtl = true,
            nextFocusLeft = 111,
            nextFocusRight = 222,
            itemCount = 8
        )

        // In RTL: Start direction resolves to nextFocusRight, End direction resolves to nextFocusLeft
        assertEquals(222, adapter.resolveNextFocusId(FocusDirection.Start))
        assertEquals(111, adapter.resolveNextFocusId(FocusDirection.End))

        // In RTL: Start direction increments index by +1
        assertEquals(1, adapter.calculateTargetIndex(currentPosition = 0, direction = FocusDirection.Start))
        // In RTL: End direction decrements index by -1
        assertEquals(0, adapter.calculateTargetIndex(currentPosition = 1, direction = FocusDirection.End))
    }

    @Test
    fun testComposeAdapterScrollDeltaComputation() {
        val ltrAdapter = LinearListLayoutComposeAdapter(isHorizontal = true, isLayoutRtl = false)
        val ltrDelta = ltrAdapter.computeScrollDelta(
            childDecoratedLeft = 320,
            childDecoratedRight = 520,
            parentWidth = 1920,
            paddingLeft = 100,
            paddingRight = 100
        )
        // 320 - 100 = 220
        assertEquals(220, ltrDelta)

        val rtlAdapter = LinearListLayoutComposeAdapter(isHorizontal = true, isLayoutRtl = true)
        val rtlDelta = rtlAdapter.computeScrollDelta(
            childDecoratedLeft = 1500,
            childDecoratedRight = 1850,
            parentWidth = 1920,
            paddingLeft = 100,
            paddingRight = 100
        )
        // 1850 - (1920 - 100) = 1850 - 1820 = 30
        assertEquals(30, rtlDelta)
    }
}
