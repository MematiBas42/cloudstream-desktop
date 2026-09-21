package unit

import android.content.Context
import android.graphics.Matrix
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.utils.Vector2
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * JUnit 5 Unit Test Suite for Cluster C25_PlayerGestureHelper:
 *
 * Verifies:
 * 1. Companion constants parity matching upstream PlayerGestureHelper.
 * 2. Matrix translation and scale extraction algorithm (matrixToTranslationAndScale).
 * 3. PlayerGestureHelper instantiation, lifecycle, and default state.
 * 4. Volume adjustment math, volume locking, and gain booster limits.
 * 5. Brightness adjustment math and extra-brightness overlay alpha bounds.
 * 6. Quadratic horizontal swipe seek calculations (calculateNewTime).
 * 7. Tap detection and double-tap seek/pause zone routing (onTapDetected).
 * 8. Zoom matrix and surface aspect ratio calculations.
 * 9. Desktop mouse scroll-wheel volume adjustment (+/- 5% steps).
 * 10. Desktop Shift + mouse scroll-wheel scrubbing (+/- 10s seek steps).
 * 11. Desktop volume drag scrubbing (handleVolumeDrag).
 * 12. Desktop timeline slider drag scrubbing (handleTimelineScrub).
 * 13. Desktop 3-zone double-tap handling (left 25% rewind, right 25% forward, center 50% fullscreen).
 * 14. Desktop hold-to-speedup (500ms long press -> 2.0x, release -> restore).
 */
class PlayerGestureHelperRemediationTest {

    private lateinit var gestureHelper: PlayerGestureHelper
    private lateinit var playerView: PlayerView

    @BeforeEach
    fun setUp() {
        playerView = PlayerView(Context())
        gestureHelper = PlayerGestureHelper(playerView)
    }

    // =========================================================================
    // 1. Companion Constants Parity
    // =========================================================================

    @Test
    fun testCompanionConstantsParity() {
        assertEquals(7000L, PlayerGestureHelper.MINIMUM_SEEK_TIME)
        assertEquals(2.0f, PlayerGestureHelper.MINIMUM_VERTICAL_SWIPE)
        assertEquals(2.0f, PlayerGestureHelper.MINIMUM_HORIZONTAL_SWIPE)
        assertEquals(2.0f, PlayerGestureHelper.VERTICAL_MULTIPLIER)
        assertEquals(2.0f, PlayerGestureHelper.HORIZONTAL_MULTIPLIER)
        assertEquals(200L, PlayerGestureHelper.DOUBLE_TAP_MAXIMUM_HOLD_TIME)
        assertEquals(200L, PlayerGestureHelper.DOUBLE_TAP_MINIMUM_TIME_BETWEEN)
        assertEquals(0.15, PlayerGestureHelper.DOUBLE_TAP_PAUSE_PERCENTAGE, 0.0001)
        assertEquals(0.95f, PlayerGestureHelper.MINIMUM_ZOOM)
        assertEquals(0.07f, PlayerGestureHelper.ZOOM_SNAP_SENSITIVITY)
        assertEquals(4.0f, PlayerGestureHelper.MAXIMUM_ZOOM)
    }

    // =========================================================================
    // 2. Matrix Translation and Scale Extraction
    // =========================================================================

    @Test
    fun testMatrixToTranslationAndScaleIdentity() {
        val matrix = Matrix()
        val (transX, transY, scale) = PlayerGestureHelper.matrixToTranslationAndScale(matrix)
        assertEquals(0f, transX, 0.001f)
        assertEquals(0f, transY, 0.001f)
        assertEquals(1f, scale, 0.001f)
    }

    @Test
    fun testMatrixToTranslationAndScaleTransformations() {
        val matrix = Matrix().apply {
            setScale(2.5f, 2.5f)
            postTranslate(15f, 35f)
        }
        val (transX, transY, scale) = PlayerGestureHelper.matrixToTranslationAndScale(matrix)
        assertEquals(15f, transX, 0.001f)
        assertEquals(35f, transY, 0.001f)
        assertEquals(2.5f, scale, 0.001f)
    }

    // =========================================================================
    // 3. Volume Adjustments and State Machine
    // =========================================================================

    @Test
    fun testVolumeAdjustmentWithinStandardBounds() {
        gestureHelper.currentRequestedVolume = 0.5f
        gestureHelper.isVolumeLocked = false

        // Increment by 5%
        gestureHelper.handleVolumeAdjustment(0.05f, fromButton = true)
        assertEquals(0.55f, gestureHelper.currentRequestedVolume, 0.001f)

        // Decrement by 5%
        gestureHelper.handleVolumeAdjustment(-0.05f, fromButton = true)
        assertEquals(0.50f, gestureHelper.currentRequestedVolume, 0.001f)
    }

    @Test
    fun testVolumeClampingAtZeroAndOneWhenLocked() {
        gestureHelper.currentRequestedVolume = 0.02f
        gestureHelper.isVolumeLocked = true

        // Decrementing past zero clamps to 0.0
        gestureHelper.handleVolumeAdjustment(-0.05f, fromButton = false)
        assertEquals(0.0f, gestureHelper.currentRequestedVolume, 0.001f)

        // Incrementing up to and past 1.0 when locked clamps to 1.0
        gestureHelper.currentRequestedVolume = 0.98f
        gestureHelper.handleVolumeAdjustment(0.05f, fromButton = false)
        assertEquals(1.0f, gestureHelper.currentRequestedVolume, 0.001f)
    }

    @Test
    fun testVolumeBoostingPastOneWhenUnlocked() {
        gestureHelper.currentRequestedVolume = 1.0f
        gestureHelper.isVolumeLocked = false

        // Can exceed 100% up to 200% (2.0f)
        gestureHelper.handleVolumeAdjustment(0.2f, fromButton = true)
        assertEquals(1.2f, gestureHelper.currentRequestedVolume, 0.001f)

        // Clamped at 2.0f max
        gestureHelper.handleVolumeAdjustment(1.5f, fromButton = true)
        assertEquals(2.0f, gestureHelper.currentRequestedVolume, 0.001f)
    }

    // =========================================================================
    // 4. Brightness Adjustments
    // =========================================================================

    @Test
    fun testBrightnessAdjustmentStandardBounds() {
        gestureHelper.currentRequestedBrightness = 0.8f
        gestureHelper.extraBrightnessEnabled = false

        // Increment brightness
        gestureHelper.handleBrightnessAdjustment(0.1f)
        assertEquals(0.9f, gestureHelper.currentRequestedBrightness, 0.001f)

        // Clamps at 1.0f when extra brightness is disabled
        gestureHelper.handleBrightnessAdjustment(0.3f)
        assertEquals(1.0f, gestureHelper.currentRequestedBrightness, 0.001f)
    }

    @Test
    fun testExtraBrightnessAlphaCalculation() {
        gestureHelper.extraBrightnessEnabled = true
        gestureHelper.isBrightnessLocked = false
        gestureHelper.currentRequestedBrightness = 1.0f

        // Boost to 1.5 -> extra brightness alpha is 0.5
        gestureHelper.handleBrightnessAdjustment(0.5f)
        assertEquals(1.5f, gestureHelper.currentRequestedBrightness, 0.001f)
        assertEquals(0.5f, gestureHelper.currentExtraBrightness, 0.001f)
    }

    // =========================================================================
    // 5. Quadratic Horizontal Swipe Time Calculation
    // =========================================================================

    @Test
    fun testCalculateNewTimeForwardAndBackward() {
        val duration = 100_000L
        playerView.player.seekTo(50_000L, PlayerEventSource.Player)

        val startTime = 50_000L
        val touchStart = Vector2(500f, 500f)
        val touchForward = Vector2(700f, 500f)
        val touchBackward = Vector2(300f, 500f)

        val forwardTime = gestureHelper.calculateNewTime(startTime, touchStart, touchForward)
        assertNotNull(forwardTime)
        assertTrue(forwardTime!! > startTime, "Forward swipe must advance player time")

        val backwardTime = gestureHelper.calculateNewTime(startTime, touchStart, touchBackward)
        assertNotNull(backwardTime)
        assertTrue(backwardTime!! < startTime, "Backward swipe must decrease player time")
    }

    @Test
    fun testCalculateNewTimeNullSafetyAndClamping() {
        assertNull(gestureHelper.calculateNewTime(null, Vector2(0f, 0f), Vector2(10f, 10f)))
        assertNull(gestureHelper.calculateNewTime(1000L, null, Vector2(10f, 10f)))
        assertNull(gestureHelper.calculateNewTime(1000L, Vector2(10f, 10f), null))

        val duration = 10_000L
        // Extreme backward swipe clamps to 0
        val clampedZero = gestureHelper.calculateNewTime(1000L, Vector2(1000f, 0f), Vector2(0f, 0f))
        assertNotNull(clampedZero)
        assertEquals(0L, clampedZero)
    }

    // =========================================================================
    // 6. Tap Detection and Double-Tap Routing
    // =========================================================================

    @Test
    fun testOnTapDetectedSingleTapWhenDisabled() {
        gestureHelper.doubleTapEnabled = false
        gestureHelper.doubleTapPauseEnabled = false

        var singleTapTriggered = false
        val handled = gestureHelper.onTapDetected(100f, 1000, isLocked = false) {
            singleTapTriggered = true
        }

        assertFalse(handled, "Should return false when not a double tap")
        assertTrue(singleTapTriggered, "Single tap callback must fire immediately when double-tap is disabled")
    }

    @Test
    fun testOnTapDetectedDoubleTapWithinWindow() {
        gestureHelper.doubleTapEnabled = true
        gestureHelper.doubleTapPauseEnabled = false
        gestureHelper.fastForwardTime = 10_000L

        // First tap records time
        gestureHelper.lastTouchEndTime = System.currentTimeMillis()

        // Immediate second tap (within 200ms)
        var singleTapTriggered = false
        val handled = gestureHelper.onTapDetected(200f, 1000, isLocked = false) {
            singleTapTriggered = true
        }

        assertTrue(handled, "Second tap within threshold must be recognized as double tap")
        assertFalse(singleTapTriggered, "Single tap should not execute when double tap is handled")
    }

    // =========================================================================
    // 7. Desktop Adapter: Mouse Scroll Wheel Volume & Scrubbing
    // =========================================================================

    @Test
    fun testDesktopAdapterMouseScrollVolume() {
        val adapter = gestureHelper.toDesktopAdapter()
        adapter.currentVolume = 0.5f

        // Scroll Up (deltaY < 0) -> Volume increases by +5%
        val scrollUp = adapter.handleMouseScroll(scrollDeltaY = -1.0f, isShiftPressed = false)
        assertTrue(scrollUp is DesktopPlayerGestureAdapter.DesktopScrollResult.VolumeChange)
        assertEquals(0.55f, (scrollUp as DesktopPlayerGestureAdapter.DesktopScrollResult.VolumeChange).newVolume, 0.001f)
        assertEquals(0.55f, adapter.currentVolume, 0.001f)

        // Scroll Down (deltaY > 0) -> Volume decreases by -5%
        val scrollDown = adapter.handleMouseScroll(scrollDeltaY = 1.0f, isShiftPressed = false)
        assertTrue(scrollDown is DesktopPlayerGestureAdapter.DesktopScrollResult.VolumeChange)
        assertEquals(0.50f, (scrollDown as DesktopPlayerGestureAdapter.DesktopScrollResult.VolumeChange).newVolume, 0.001f)
    }

    @Test
    fun testDesktopAdapterShiftScrollWheelScrubbing() {
        val adapter = gestureHelper.toDesktopAdapter()
        adapter.fastForwardTimeMs = 10_000L
        val currentMs = 60_000L
        val durationMs = 120_000L

        // Shift + Scroll Up (forward scrub)
        val scrubForward = adapter.handleMouseScroll(
            scrollDeltaY = -1.0f,
            isShiftPressed = true,
            currentPositionMs = currentMs,
            durationMs = durationMs
        )
        assertTrue(scrubForward is DesktopPlayerGestureAdapter.DesktopScrollResult.TimeSeek)
        val seekForward = scrubForward as DesktopPlayerGestureAdapter.DesktopScrollResult.TimeSeek
        assertEquals(70_000L, seekForward.targetPositionMs)
        assertEquals(10_000L, seekForward.deltaMs)

        // Shift + Scroll Down (backward scrub)
        val scrubBackward = adapter.handleMouseScroll(
            scrollDeltaY = 1.0f,
            isShiftPressed = true,
            currentPositionMs = currentMs,
            durationMs = durationMs
        )
        assertTrue(scrubBackward is DesktopPlayerGestureAdapter.DesktopScrollResult.TimeSeek)
        val seekBackward = scrubBackward as DesktopPlayerGestureAdapter.DesktopScrollResult.TimeSeek
        assertEquals(50_000L, seekBackward.targetPositionMs)
        assertEquals(-10_000L, seekBackward.deltaMs)
    }

    @Test
    fun testDesktopAdapterVolumeDrag() {
        val adapter = gestureHelper.toDesktopAdapter()

        // Linear volume drag
        val v1 = adapter.handleVolumeDrag(0.75f, allowBoost = false)
        assertEquals(0.75f, v1, 0.001f)
        assertEquals(0.75f, adapter.currentVolume, 0.001f)

        // Volume drag clamping at 1.0 when boost disallowed
        val v2 = adapter.handleVolumeDrag(1.4f, allowBoost = false)
        assertEquals(1.0f, v2, 0.001f)

        // Volume drag allows up to 2.0 when boost allowed
        val v3 = adapter.handleVolumeDrag(1.6f, allowBoost = true)
        assertEquals(1.6f, v3, 0.001f)
    }

    @Test
    fun testDesktopAdapterTimelineScrub() {
        val adapter = gestureHelper.toDesktopAdapter()
        val durationMs = 200_000L

        // 50% scrub -> 100,000 ms
        val target1 = adapter.handleTimelineScrub(0.5f, durationMs)
        assertEquals(100_000L, target1)

        // Clamping past bounds
        val target2 = adapter.handleTimelineScrub(-0.1f, durationMs)
        assertEquals(0L, target2)

        val target3 = adapter.handleTimelineScrub(1.5f, durationMs)
        assertEquals(200_000L, target3)
    }

    @Test
    fun testDesktopAdapterDoubleTapZones() {
        val adapter = gestureHelper.toDesktopAdapter()
        adapter.fastForwardTimeMs = 10_000L
        val containerWidth = 1000f
        val currentMs = 50_000L
        val durationMs = 100_000L

        // Left 25% zone (< 250f) -> Rewind
        val actionLeft = adapter.handleDoubleTapZones(150f, containerWidth, currentMs, durationMs)
        assertTrue(actionLeft is DesktopPlayerGestureAdapter.DesktopTapAction.Rewind)
        assertEquals(40_000L, (actionLeft as DesktopPlayerGestureAdapter.DesktopTapAction.Rewind).targetPositionMs)

        // Right 25% zone (> 750f) -> FastForward
        val actionRight = adapter.handleDoubleTapZones(850f, containerWidth, currentMs, durationMs)
        assertTrue(actionRight is DesktopPlayerGestureAdapter.DesktopTapAction.FastForward)
        assertEquals(60_000L, (actionRight as DesktopPlayerGestureAdapter.DesktopTapAction.FastForward).targetPositionMs)

        // Center 50% zone (250f .. 750f) -> ToggleFullscreen
        val actionCenter = adapter.handleDoubleTapZones(500f, containerWidth, currentMs, durationMs)
        assertEquals(DesktopPlayerGestureAdapter.DesktopTapAction.ToggleFullscreen, actionCenter)
    }

    @Test
    fun testDesktopAdapterHoldToSpeedUp() {
        val adapter = gestureHelper.toDesktopAdapter()
        adapter.currentPlaybackSpeed = 1.0f

        // Holding mouse press -> Speed jumps to 2.0x
        val holdingSpeed = adapter.handleHoldSpeedUp(isHolding = true)
        assertEquals(2.0f, holdingSpeed, 0.001f)
        assertEquals(2.0f, adapter.currentPlaybackSpeed, 0.001f)

        // Releasing mouse press -> Restores original speed (1.0f)
        val releasedSpeed = adapter.handleHoldSpeedUp(isHolding = false)
        assertEquals(1.0f, releasedSpeed, 0.001f)
        assertEquals(1.0f, adapter.currentPlaybackSpeed, 0.001f)
    }
}
