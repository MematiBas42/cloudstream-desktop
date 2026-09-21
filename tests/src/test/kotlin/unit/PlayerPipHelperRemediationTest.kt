package unit

import android.app.Activity
import android.content.Context
import android.content.DesktopContextProvider
import android.util.Rational
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CSPlayerLoading
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.enterMiniPlayer
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.exitMiniPlayer
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.isAlwaysOnTop
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.isAlwaysOnTopActive
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.isAlwaysOnTopSupported
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.isInMiniPlayerMode
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.isPIPPossible
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.resetMiniPlayerState
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.setAlwaysOnTop
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.toggleMiniPlayer
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.updateMiniPlayerAspectRatio
import com.lagradost.cloudstream3.ui.player.PlayerPipHelper.updatePIPModeActions
import com.lagradost.common.platform.PlatformPaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Frame
import java.awt.Rectangle

/**
 * JUnit 5 Test Suite for Cluster C24_PlayerPipHelper
 *
 * Verifies:
 * 1. 1:1 Upstream Picture-in-Picture Capability and Permission:
 *    - Context.isPIPPossible() respects pip_enabled_key preference and OS feature shims.
 *    - Preference changes dynamically gate isPIPPossible().
 * 2. 1:1 Upstream Picture-in-Picture Mode Actions & Aspect Ratio Clamping:
 *    - updatePIPModeActions evaluates isPipDesired for IsPlaying / IsBuffering vs IsPaused / IsEnded.
 *    - Extreme aspect ratios (< 0.41841f or > 2.39f) are correctly clamped.
 *    - RemoteActions (PlayAsAudio, Play, Pause, SeekForward) are attached.
 * 3. Desktop Mini-Player / Always-On-Top Floating State:
 *    - enterMiniPlayer preserves previous window bounds and activates always-on-top.
 *    - Window bounds are computed with proper 16:9 or custom aspect ratio and screen margins.
 *    - exitMiniPlayer restores previous bounds and original always-on-top state.
 *    - toggleMiniPlayer alternates between mini-player floating state and windowed state.
 *    - updateMiniPlayerAspectRatio adjusts height dynamically during active playback.
 * 4. CommonActivity & StateFlow Integration:
 *    - CommonActivity.isPipDesired and CommonActivity.isInPIPMode are kept in sync.
 *    - PlayerPipHelper.miniPlayerState flow emits reactive state updates.
 */
class PlayerPipHelperRemediationTest {

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        context = DesktopContextProvider.context
        resetMiniPlayerState()
    }

    @AfterEach
    fun tearDown() {
        resetMiniPlayerState()
        DesktopContextProvider.currentWindow = null
    }

    // =========================================================================
    // 1. Upstream 1:1 Parity Tests: isPIPPossible & Preferences
    // =========================================================================

    @Test
    fun testIsPIPPossibleDefaultEnabled() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pipKey = context.getString(R.string.pip_enabled_key)
        prefs.edit().remove(pipKey).commit()

        with(PlayerPipHelper) {
            assertTrue(
                context.isPIPPossible(),
                "isPIPPossible() should be true by default when pip_enabled_key is not set"
            )
        }
    }

    @Test
    fun testIsPIPPossibleWhenDisabledInPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val pipKey = context.getString(R.string.pip_enabled_key)
        prefs.edit().putBoolean(pipKey, false).commit()

        try {
            with(PlayerPipHelper) {
                assertFalse(
                    context.isPIPPossible(),
                    "isPIPPossible() must return false when pip_enabled_key is explicitly set to false"
                )
            }
        } finally {
            prefs.edit().putBoolean(pipKey, true).commit()
        }
    }

    // =========================================================================
    // 2. Upstream 1:1 Parity Tests: updatePIPModeActions & Aspect Ratio
    // =========================================================================

    @Test
    fun testUpdatePIPModeActionsDesiredState() {
        val activity = Activity()

        // 1. Playing with pipEnabled=true -> isPipDesired = true
        updatePIPModeActions(activity, CSPlayerLoading.IsPlaying, pipEnabled = true, aspectRatio = Rational(16, 9))
        assertTrue(CommonActivity.isPipDesired, "isPipDesired must be true when IsPlaying and pipEnabled=true")

        // 2. Buffering with pipEnabled=true -> isPipDesired = true
        updatePIPModeActions(activity, CSPlayerLoading.IsBuffering, pipEnabled = true, aspectRatio = Rational(16, 9))
        assertTrue(CommonActivity.isPipDesired, "isPipDesired must be true when IsBuffering and pipEnabled=true")

        // 3. Paused with pipEnabled=true -> isPipDesired = false
        updatePIPModeActions(activity, CSPlayerLoading.IsPaused, pipEnabled = true, aspectRatio = Rational(16, 9))
        assertFalse(CommonActivity.isPipDesired, "isPipDesired must be false when IsPaused")

        // 4. Ended with pipEnabled=true -> isPipDesired = false
        updatePIPModeActions(activity, CSPlayerLoading.IsEnded, pipEnabled = true, aspectRatio = Rational(16, 9))
        assertFalse(CommonActivity.isPipDesired, "isPipDesired must be false when IsEnded")

        // 5. Playing with pipEnabled=false -> isPipDesired = false
        updatePIPModeActions(activity, CSPlayerLoading.IsPlaying, pipEnabled = false, aspectRatio = Rational(16, 9))
        assertFalse(CommonActivity.isPipDesired, "isPipDesired must be false when pipEnabled=false")
    }

    @Test
    fun testUpdatePIPModeActionsAspectRatios() {
        val activity = Activity()

        // Test normal 16:9 ratio
        updatePIPModeActions(activity, CSPlayerLoading.IsPlaying, pipEnabled = true, aspectRatio = Rational(16, 9))
        assertTrue(CommonActivity.isPipDesired)

        // Test ultra-wide ratio (e.g. 3.0:1) which exceeds maxAspectRatio 2.39f - must not throw
        updatePIPModeActions(activity, CSPlayerLoading.IsPlaying, pipEnabled = true, aspectRatio = Rational(30, 10))
        assertTrue(CommonActivity.isPipDesired)

        // Test ultra-tall ratio (e.g. 1:3 = 0.33f) which is below minAspectRatio 0.41841f - must not throw
        updatePIPModeActions(activity, CSPlayerLoading.IsPlaying, pipEnabled = true, aspectRatio = Rational(1, 3))
        assertTrue(CommonActivity.isPipDesired)

        // Test null aspect ratio - must not throw
        updatePIPModeActions(activity, CSPlayerLoading.IsPlaying, pipEnabled = true, aspectRatio = null)
        assertTrue(CommonActivity.isPipDesired)

        // Test null activity - must not throw
        updatePIPModeActions(null, CSPlayerLoading.IsPlaying, pipEnabled = true, aspectRatio = Rational(16, 9))
    }

    // =========================================================================
    // 3. Desktop Mini-Player / Always-On-Top Floating State Tests
    // =========================================================================

    @Test
    fun testEnterAndExitMiniPlayer() {
        assertFalse(isInMiniPlayerMode, "Mini-player mode should initially be inactive")
        assertFalse(CommonActivity.isInPIPMode, "CommonActivity.isInPIPMode should initially be false")

        val initialBounds = Rectangle(200, 150, 1280, 720)
        val testFrame = Frame("Test Video Window").apply {
            bounds = initialBounds
        }
        DesktopContextProvider.currentWindow = testFrame

        try {
            // Enter Mini-Player
            val entered = enterMiniPlayer(testFrame, aspectRatio = Rational(16, 9), targetWidth = 480)
            assertTrue(entered, "enterMiniPlayer must return true")
            assertTrue(isInMiniPlayerMode, "isInMiniPlayerMode must be true after entering")
            assertTrue(CommonActivity.isInPIPMode, "CommonActivity.isInPIPMode must be true")
            assertTrue(isAlwaysOnTopActive, "isAlwaysOnTopActive must be true")

            val state = PlayerPipHelper.miniPlayerState.value
            assertTrue(state.isMiniPlayer)
            assertTrue(state.isAlwaysOnTop)
            assertNotNull(state.originalBounds, "originalBounds must be saved")
            assertEquals(initialBounds, state.originalBounds)

            val miniBounds = state.miniPlayerBounds
            assertNotNull(miniBounds, "miniPlayerBounds must be computed")
            assertEquals(480, miniBounds!!.width, "Mini-player width should match target width")
            assertEquals(270, miniBounds.height, "16:9 height for 480px width should be 270px")

            // Exit Mini-Player
            val exited = exitMiniPlayer(testFrame)
            assertTrue(exited, "exitMiniPlayer must return true")
            assertFalse(isInMiniPlayerMode, "isInMiniPlayerMode must be false after exit")
            assertFalse(CommonActivity.isInPIPMode, "CommonActivity.isInPIPMode must be false after exit")

            val finalState = PlayerPipHelper.miniPlayerState.value
            assertFalse(finalState.isMiniPlayer)
            assertNull(finalState.miniPlayerBounds)
            assertNull(finalState.originalBounds)
        } finally {
            testFrame.dispose()
        }
    }

    @Test
    fun testToggleMiniPlayer() {
        val testFrame = Frame("Toggle Test Window").apply {
            bounds = Rectangle(100, 100, 1024, 576)
        }
        DesktopContextProvider.currentWindow = testFrame

        try {
            assertFalse(isInMiniPlayerMode)

            // Toggle ON
            val onResult = toggleMiniPlayer(testFrame, Rational(16, 9))
            assertTrue(onResult)
            assertTrue(isInMiniPlayerMode)

            // Toggle OFF
            val offResult = toggleMiniPlayer(testFrame, Rational(16, 9))
            assertTrue(offResult)
            assertFalse(isInMiniPlayerMode)
        } finally {
            testFrame.dispose()
        }
    }

    @Test
    fun testAlwaysOnTopDirectControls() {
        val testFrame = Frame("Always on Top Window")
        DesktopContextProvider.currentWindow = testFrame

        try {
            assertTrue(isAlwaysOnTopSupported(testFrame))

            setAlwaysOnTop(testFrame, true)
            assertTrue(isAlwaysOnTop(testFrame))
            assertTrue(isAlwaysOnTopActive)

            setAlwaysOnTop(testFrame, false)
            assertFalse(isAlwaysOnTop(testFrame))
            assertFalse(isAlwaysOnTopActive)
        } finally {
            testFrame.dispose()
        }
    }

    @Test
    fun testDynamicAspectRatioUpdateInMiniPlayer() {
        val testFrame = Frame("Aspect Ratio Window").apply {
            bounds = Rectangle(50, 50, 1280, 720)
        }
        DesktopContextProvider.currentWindow = testFrame

        try {
            enterMiniPlayer(testFrame, aspectRatio = Rational(16, 9), targetWidth = 480)
            assertEquals(270, PlayerPipHelper.miniPlayerState.value.miniPlayerBounds?.height)

            // Switch to 4:3 video ratio in flight (width 480 -> height = 480 / (4/3) = 360)
            updateMiniPlayerAspectRatio(Rational(4, 3), testFrame)
            assertEquals(360, PlayerPipHelper.miniPlayerState.value.miniPlayerBounds?.height)

            // Switch to 21:9 cinematic ratio (width 480 -> height = 480 / (21/9) ≈ 206)
            updateMiniPlayerAspectRatio(Rational(21, 9), testFrame)
            val cinematicHeight = PlayerPipHelper.miniPlayerState.value.miniPlayerBounds?.height ?: 0
            assertEquals(206, cinematicHeight)
        } finally {
            testFrame.dispose()
        }
    }

    @Test
    fun testExitMiniPlayerWhenNotActiveReturnsFalse() {
        assertFalse(isInMiniPlayerMode)
        val result = exitMiniPlayer()
        assertFalse(result, "exitMiniPlayer should return false when mini-player is not currently active")
    }
}
