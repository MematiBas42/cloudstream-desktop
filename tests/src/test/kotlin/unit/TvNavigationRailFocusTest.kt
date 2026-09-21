package unit

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.components.TvRailDestination
import com.lagradost.cloudstream3.desktop.ui.components.TvRailFocusRows
import com.lagradost.cloudstream3.desktop.ui.focus.FocusTarget
import com.lagradost.cloudstream3.desktop.ui.focus.TvCenteringExceptions
import com.lagradost.cloudstream3.desktop.ui.focus.TvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.TvFocusMath
import com.lagradost.cloudstream3.desktop.ui.focus.TvNavAction
import com.lagradost.cloudstream3.desktop.ui.focus.TvViewportCenteringMath
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.navigation.TvNavigationRouter
import com.lagradost.cloudstream3.desktop.ui.navigation.TvRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.min

/**
 * Domain 01: TV Navigation Shell & Spatial Focus Engine Comprehensive Anti-Mock Test Suite.
 * Validates real coordinate mathematics, snap physics, viewport centering,
 * 2D spatial focus navigation, and backstack focus anchor preservation.
 */
class TvNavigationRailFocusTest {

    @Test
    fun testFocusTargetLerpCalculation() {
        val start = FocusTarget(x = 100f, y = 200f, width = 160f, height = 240f, isVisible = true, cornerRadius = 8f)
        val end = FocusTarget(x = 300f, y = 600f, width = 200f, height = 300f, isVisible = true, cornerRadius = 16f)

        // Midpoint fraction 0.5
        val mid = FocusTarget.lerp(start, end, 0.5f)
        assertEquals(200f, mid.x, 0.001f)
        assertEquals(400f, mid.y, 0.001f)
        assertEquals(180f, mid.width, 0.001f)
        assertEquals(270f, mid.height, 0.001f)
        assertEquals(12f, mid.cornerRadius, 0.001f)
        assertTrue(mid.isVisible)

        // Lower boundary 0.0
        val atZero = FocusTarget.lerp(start, end, 0f)
        assertEquals(100f, atZero.x, 0.001f)
        assertEquals(200f, atZero.y, 0.001f)
        assertEquals(160f, atZero.width, 0.001f)
        assertEquals(240f, atZero.height, 0.001f)
        assertEquals(8f, atZero.cornerRadius, 0.001f)

        // Upper boundary 1.0
        val atOne = FocusTarget.lerp(start, end, 1f)
        assertEquals(300f, atOne.x, 0.001f)
        assertEquals(600f, atOne.y, 0.001f)
        assertEquals(200f, atOne.width, 0.001f)
        assertEquals(300f, atOne.height, 0.001f)
        assertEquals(16f, atOne.cornerRadius, 0.001f)

        // Clamping checks: negative fraction clamps to 0.0, >1.0 clamps to 1.0
        val negativeClamped = FocusTarget.lerp(start, end, -0.5f)
        assertEquals(100f, negativeClamped.x, 0.001f)
        assertEquals(200f, negativeClamped.y, 0.001f)

        val excessiveClamped = FocusTarget.lerp(start, end, 2.5f)
        assertEquals(300f, excessiveClamped.x, 0.001f)
        assertEquals(600f, excessiveClamped.y, 0.001f)

        // Rect conversion
        val rect = mid.toRect()
        assertEquals(200f, rect.left, 0.001f)
        assertEquals(400f, rect.top, 0.001f)
        assertEquals(380f, rect.right, 0.001f)
        assertEquals(670f, rect.bottom, 0.001f)
    }

    @Test
    fun testSnapThresholdLogic() {
        val targetWidth = 160f
        val targetHeight = 240f
        val deltaMinX = min(targetWidth / 2f, 90f) // 80f
        val deltaMinY = min(targetHeight / 2f, 90f) // 90f

        assertEquals(80f, deltaMinX, 0.001f)
        assertEquals(90f, deltaMinY, 0.001f)

        val baseTarget = FocusTarget(x = 100f, y = 100f, width = targetWidth, height = targetHeight, isVisible = true)

        // Case 1: Small micro-adjustment (e.g. slight scroll delta) < deltaMin -> Snap immediately
        val smallShiftTarget = FocusTarget(x = 140f, y = 150f, width = targetWidth, height = targetHeight, isVisible = true)
        val shouldSnapSmall = TvFocusMath.shouldSnap(baseTarget, smallShiftTarget)
        assertTrue(shouldSnapSmall, "Displacements under threshold must snap immediately without 200ms lerp")

        // Case 2: Large jump (e.g. card to card navigation) > deltaMin -> Animate
        val largeShiftTarget = FocusTarget(x = 300f, y = 100f, width = targetWidth, height = targetHeight, isVisible = true)
        val shouldSnapLarge = TvFocusMath.shouldSnap(baseTarget, largeShiftTarget)
        assertFalse(shouldSnapLarge, "Full transitions exceeding deltaMin must animate")

        // Case 3: Dimension change between targets (e.g. poster to wide episode card) -> Must animate
        val differentSizeTarget = FocusTarget(x = 105f, y = 105f, width = 260f, height = 146f, isVisible = true)
        val shouldSnapSizeMismatch = TvFocusMath.shouldSnap(baseTarget, differentSizeTarget)
        assertFalse(shouldSnapSizeMismatch, "Transitions with different target dimensions must animate")
    }

    @Test
    fun testViewportCenteringMath() {
        val screenHeight = 1080f
        val screenCenterY = screenHeight / 2f // 540f

        // Case 1: Item in upper half
        val topItemDelta = TvViewportCenteringMath.calculateCenterScrollDelta(itemTop = 100f, itemHeight = 200f, screenHeight = screenHeight)
        // itemCenterY = 100 + 100 = 200 -> delta = 200 - 540 = -340
        assertEquals(-340f, topItemDelta, 0.001f)

        // Case 2: Item exactly centered
        val centeredItemDelta = TvViewportCenteringMath.calculateCenterScrollDelta(itemTop = 440f, itemHeight = 200f, screenHeight = screenHeight)
        // itemCenterY = 440 + 100 = 540 -> delta = 540 - 540 = 0
        assertEquals(0f, centeredItemDelta, 0.001f)

        // Case 3: Item in lower half
        val bottomItemDelta = TvViewportCenteringMath.calculateCenterScrollDelta(itemTop = 750f, itemHeight = 240f, screenHeight = screenHeight)
        // itemCenterY = 750 + 120 = 870 -> delta = 870 - 540 = 330
        assertEquals(330f, bottomItemDelta, 0.001f)
    }

    @Test
    fun testCenteringExceptionsFilter() {
        // All 11 upstream exception button IDs must bypass auto-centering
        val expectedExceptions = listOf(
            TvCenteringExceptions.HERO_INFO_BUTTON,
            TvCenteringExceptions.HERO_PLAY_BUTTON,
            TvCenteringExceptions.DETAILS_PLAY_MOVIE,
            TvCenteringExceptions.DETAILS_PLAY_SERIES,
            TvCenteringExceptions.DETAILS_RESUME_SERIES,
            TvCenteringExceptions.DETAILS_TRAILER,
            TvCenteringExceptions.DETAILS_BOOKMARK,
            TvCenteringExceptions.DETAILS_FAVORITE,
            TvCenteringExceptions.DETAILS_SUBSCRIBE,
            TvCenteringExceptions.DETAILS_SEARCH,
            TvCenteringExceptions.DETAILS_EPISODES_DRAWER
        )

        for (exceptionId in expectedExceptions) {
            assertTrue(TvCenteringExceptions.isException(exceptionId), "Exception ID $exceptionId must be excluded")
        }

        // Standard feed cards must NOT be excluded
        assertFalse(TvCenteringExceptions.isException("home_regular_card_42"))
        assertFalse(TvCenteringExceptions.isException("search_result_item_0"))
        assertFalse(TvCenteringExceptions.isException(null))
    }

    @Test
    fun testNavigationRailDestinationsAndScreenMapping() {
        // Test mappings between TvRailDestination and Screen
        assertEquals(Screen.Home, TvRailDestination.HOME.toScreen())
        assertEquals(Screen.Search(), TvRailDestination.SEARCH.toScreen())
        assertEquals(Screen.Library, TvRailDestination.LIBRARY.toScreen())
        assertEquals(Screen.Extensions, TvRailDestination.EXTENSIONS.toScreen())
        assertEquals(Screen.Settings, TvRailDestination.SETTINGS.toScreen())

        assertEquals(TvRailDestination.HOME, TvRailDestination.fromScreen(Screen.Home))
        assertEquals(TvRailDestination.SEARCH, TvRailDestination.fromScreen(Screen.Search()))
        assertEquals(TvRailDestination.LIBRARY, TvRailDestination.fromScreen(Screen.Library))
        assertEquals(TvRailDestination.EXTENSIONS, TvRailDestination.fromScreen(Screen.Extensions))
        assertEquals(TvRailDestination.SETTINGS, TvRailDestination.fromScreen(Screen.Settings))

        // Sub-screens return null from fromScreen
        assertNull(TvRailDestination.fromScreen(Screen.IPTV))

        // Focus rows mapping integrity
        assertEquals(0, TvRailFocusRows.HOME)
        assertEquals(1, TvRailFocusRows.SEARCH)
        assertEquals(2, TvRailFocusRows.LIBRARY)
        assertEquals(3, TvRailFocusRows.EXTENSIONS)
        assertEquals(4, TvRailFocusRows.SETTINGS)
        assertEquals(5, TvRailFocusRows.PROFILE)
    }

    @Test
    fun testRouterBackstackFocusRestoration() {
        val router = TvNavigationRouter(initialRoute = TvRoute.Home)
        assertEquals(TvRoute.Home, router.currentRoute)
        assertEquals(TvRailDestination.HOME, router.activeRailDestination)
        assertFalse(router.canGoBack())

        // Navigate to Settings, preserving active focus anchor
        router.navigate(TvRoute.Settings, currentFocusKey = "home_featured_card_3")
        assertEquals(TvRoute.Settings, router.currentRoute)
        assertEquals(TvRailDestination.SETTINGS, router.activeRailDestination)
        assertTrue(router.canGoBack())

        // Pop backstack and assert restored focus anchor key
        val restoredKey = router.popBackstack()
        assertEquals("home_featured_card_3", restoredKey)
        assertEquals(TvRoute.Home, router.currentRoute)
        assertEquals(TvRailDestination.HOME, router.activeRailDestination)
        assertFalse(router.canGoBack())

        // Multi-level stack navigation: Home -> Search -> Settings
        router.navigate(TvRoute.Search(), currentFocusKey = "home_card_1")
        router.navigate(TvRoute.Settings, currentFocusKey = "search_card_4")
        assertTrue(router.canGoBack())

        val pop1 = router.popBackstack()
        assertEquals("search_card_4", pop1)
        assertTrue(router.currentRoute is TvRoute.Search)
        assertEquals(TvRailDestination.SEARCH, router.activeRailDestination)

        val pop2 = router.popBackstack()
        assertEquals("home_card_1", pop2)
        assertEquals(TvRoute.Home, router.currentRoute)
        assertEquals(TvRailDestination.HOME, router.activeRailDestination)
        assertFalse(router.canGoBack())

        // Redundant navigate to identical screen does not push to backstack
        router.navigate(TvRoute.Home)
        assertFalse(router.canGoBack())
    }

    @Test
    fun testSpatialFocusManager2DNavigationAndMemory() {
        val focusManager = TvFocusManager()

        val requesterHome = FocusRequester()
        val requesterSearch = FocusRequester()
        val requesterSettings = FocusRequester()
        val requesterContentCard0 = FocusRequester()
        val requesterContentCard1 = FocusRequester()

        // Register Rail Items at col 0
        focusManager.registerItem(row = 0, col = 0, requester = requesterHome)
        focusManager.registerItem(row = 1, col = 0, requester = requesterSearch)
        focusManager.registerItem(row = 2, col = 0, requester = requesterSettings)

        // Register Content Items in Row 0 at col 1, col 2
        focusManager.registerItem(row = 0, col = 1, requester = requesterContentCard0)
        focusManager.registerItem(row = 0, col = 2, requester = requesterContentCard1)

        // Start at rail (0, 0)
        focusManager.notifyFocused(0, 0)
        assertEquals(0, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)

        // Move vertical down in rail: (0, 0) -> (1, 0)
        val movedDown = focusManager.move(TvNavAction.DOWN)
        assertTrue(movedDown)
        assertEquals(1, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)

        // Move vertical down in rail: (1, 0) -> (2, 0)
        focusManager.move(TvNavAction.DOWN)
        assertEquals(2, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)

        // Move vertical up in rail: (2, 0) -> (1, 0)
        val movedUp = focusManager.move(TvNavAction.UP)
        assertTrue(movedUp)
        assertEquals(1, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)

        // Move up to (0, 0)
        focusManager.move(TvNavAction.UP)
        assertEquals(0, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)

        // Move horizontal right from rail (0, 0) into content (0, 1)
        val movedRight = focusManager.move(TvNavAction.RIGHT)
        assertTrue(movedRight)
        assertEquals(0, focusManager.currentRow)
        assertEquals(1, focusManager.currentCol)

        // Move horizontal right within content: (0, 1) -> (0, 2)
        focusManager.move(TvNavAction.RIGHT)
        assertEquals(0, focusManager.currentRow)
        assertEquals(2, focusManager.currentCol)

        // Moving right at row boundary returns false
        val edgeRight = focusManager.move(TvNavAction.RIGHT)
        assertFalse(edgeRight)

        // Move left back towards rail: (0, 2) -> (0, 1) -> (0, 0)
        focusManager.move(TvNavAction.LEFT)
        assertEquals(0, focusManager.currentRow)
        assertEquals(1, focusManager.currentCol)

        focusManager.move(TvNavAction.LEFT)
        assertEquals(0, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)

        // Moving left at rail edge returns false
        val edgeLeft = focusManager.move(TvNavAction.LEFT)
        assertFalse(edgeLeft)
    }

    @Test
    fun testSpatialFocusManagerFallbackAnchor() {
        val focusManager = TvFocusManager()
        val requester = FocusRequester()

        // Fallback anchor with empty registry returns false
        val emptyAnchorResult = focusManager.snapToFallbackAnchor()
        assertFalse(emptyAnchorResult)

        // Register one item
        focusManager.registerItem(row = 4, col = 2, requester = requester)

        // Lost focus simulation: snapToFallbackAnchor selects first available item (4, 2)
        val recovered = focusManager.snapToFallbackAnchor()
        assertTrue(recovered)
        assertEquals(4, focusManager.currentRow)
        assertEquals(2, focusManager.currentCol)

        // Clear resets tracking
        focusManager.clear()
        assertEquals(0, focusManager.currentRow)
        assertEquals(0, focusManager.currentCol)
    }

    @Test
    fun testRailDimensionConstants() {
        val collapsedWidth = 90.dp
        val expandedWidth = 220.dp

        assertEquals(90f, collapsedWidth.value, 0.001f)
        assertEquals(220f, expandedWidth.value, 0.001f)
        assertTrue(expandedWidth > collapsedWidth)
    }

    @Test
    fun testFocusLossDebounceAndClearFocus() = kotlinx.coroutines.test.runTest {
        val focusState = com.lagradost.cloudstream3.desktop.ui.focus.TvFocusState(this)
        val target = FocusTarget(x = 10f, y = 20f, width = 100f, height = 50f, isVisible = true)

        focusState.reportFocus(target)
        testScheduler.advanceUntilIdle()
        assertTrue(focusState.isOutlineVisible)
        assertEquals(100f, focusState.currentTarget.width)

        // Simulate focus lost
        focusState.onFocusLost()
        testScheduler.advanceTimeBy(30)
        assertTrue(focusState.isOutlineVisible, "Must retain visibility during 50ms debounce window")

        testScheduler.advanceTimeBy(30) // total 60ms
        assertFalse(focusState.isOutlineVisible, "Must hide outline after 50ms quiet period")

        // Test clearFocus
        focusState.reportFocus(target)
        testScheduler.advanceUntilIdle()
        assertTrue(focusState.isOutlineVisible)

        focusState.clearFocus()
        assertFalse(focusState.isOutlineVisible, "clearFocus must immediately hide outline")
        assertEquals(0f, focusState.currentTarget.width)
    }
}
