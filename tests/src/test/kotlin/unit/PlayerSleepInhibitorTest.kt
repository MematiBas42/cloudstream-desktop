package unit

import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import com.lagradost.common.platform.MacOsSleepInhibitor
import com.lagradost.common.platform.NoOpSleepInhibitor
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.platform.SleepInhibitor
import com.lagradost.common.platform.SystemdSleepInhibitor
import com.lagradost.common.platform.WindowsSleepInhibitor
import com.lagradost.player.impl.MpvPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end integration and contract test suite for [SleepInhibitor] and its
 * integration with [MpvPlayer].
 *
 * Verifies:
 * 1. Factory contract of [SleepInhibitor.createDefault] according to [PlatformPaths.currentOS].
 * 2. Platform-specific inhibitor command contracts: Linux (systemd-inhibit), Windows (Win32 constants), macOS (caffeinate).
 * 3. [MpvPlayer] lifecycle and state transition coordination with [SleepInhibitor] (play, resume, pause, stop, destroy, IPC events).
 */
class PlayerSleepInhibitorTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
    }

    // =========================================================================
    // 1. SleepInhibitor Platform Factory & Contract
    // =========================================================================

    @Test
    fun testSleepInhibitorCreateDefaultResolvesHostPlatform() {
        val inhibitor = SleepInhibitor.createDefault()
        assertNotNull(inhibitor, "SleepInhibitor.createDefault() must return a non-null instance")

        when (PlatformPaths.currentOS) {
            PlatformPaths.OS.LINUX -> assertTrue(inhibitor is SystemdSleepInhibitor, "Linux host must instantiate SystemdSleepInhibitor")
            PlatformPaths.OS.WINDOWS -> assertTrue(inhibitor is WindowsSleepInhibitor, "Windows host must instantiate WindowsSleepInhibitor")
            PlatformPaths.OS.MACOS -> assertTrue(inhibitor is MacOsSleepInhibitor, "macOS host must instantiate MacOsSleepInhibitor")
            PlatformPaths.OS.UNKNOWN -> assertTrue(inhibitor is NoOpSleepInhibitor, "Unknown host must instantiate NoOpSleepInhibitor")
        }
    }

    @Test
    fun testSystemdSleepInhibitorDefaultCommandSpecification() {
        val cmd = SystemdSleepInhibitor.defaultCommandProvider()
        assertTrue(cmd.contains("systemd-inhibit"), "Command must invoke systemd-inhibit")
        assertTrue(cmd.contains("--what=idle:sleep"), "Command must specify --what=idle:sleep")
        assertTrue(cmd.contains("--who=CloudStream"), "Command must specify --who=CloudStream")
        assertTrue(cmd.contains("--mode=block"), "Command must specify --mode=block")
    }

    @Test
    fun testWindowsSleepInhibitorConstantsAndDefaultFlags() {
        assertEquals(0x00000001, WindowsSleepInhibitor.ES_SYSTEM_REQUIRED)
        assertEquals(0x00000002, WindowsSleepInhibitor.ES_DISPLAY_REQUIRED)
        assertEquals(0x00000040, WindowsSleepInhibitor.ES_AWAYMODE_REQUIRED)
        assertEquals(0x80000000.toInt(), WindowsSleepInhibitor.ES_CONTINUOUS)

        val expectedFlags = WindowsSleepInhibitor.ES_CONTINUOUS or
            WindowsSleepInhibitor.ES_SYSTEM_REQUIRED or
            WindowsSleepInhibitor.ES_DISPLAY_REQUIRED

        val inhibitor = WindowsSleepInhibitor()
        assertEquals(expectedFlags, inhibitor.flags, "Windows inhibitor default flags must require system and display")

        val defaultCmd = WindowsSleepInhibitor.defaultCommandProvider()
        assertTrue(defaultCmd.contains("powershell"), "Fallback command must use powershell")
        assertTrue(defaultCmd.any { it.contains("SetThreadExecutionState") }, "Command must call SetThreadExecutionState")
    }

    @Test
    fun testMacOsSleepInhibitorDefaultCommand() {
        val cmd = MacOsSleepInhibitor.defaultCommandProvider()
        assertTrue(cmd.contains("caffeinate"), "macOS inhibitor must invoke caffeinate")
        assertTrue(cmd.contains("-i"), "macOS inhibitor must pass -i to prevent idle sleep")
    }

    @Test
    fun testNoOpSleepInhibitorLifecycleContract() {
        val failingInhibitor = NoOpSleepInhibitor(simulatedSuccess = false)
        assertFalse(failingInhibitor.isInhibited)
        assertFalse(failingInhibitor.acquire())
        assertFalse(failingInhibitor.isInhibited)
        assertTrue(failingInhibitor.release())

        val successfulInhibitor = NoOpSleepInhibitor(simulatedSuccess = true)
        assertFalse(successfulInhibitor.isInhibited)
        assertTrue(successfulInhibitor.acquire())
        assertTrue(successfulInhibitor.isInhibited)
        assertTrue(successfulInhibitor.release())
        assertFalse(successfulInhibitor.isInhibited)
    }

    // =========================================================================
    // 2. MpvPlayer Integration & SleepInhibitor Coordination
    // =========================================================================

    @Test
    fun testMpvPlayerInitializesWithDefaultSleepInhibitor() {
        val player = MpvPlayer()
        try {
            assertNotNull(player.sleepInhibitor, "MpvPlayer must initialize with a non-null sleepInhibitor")
        } finally {
            player.destroy()
        }
    }

    @Test
    fun testMpvPlayerPlaybackLifecycleCoordination() {
        val player = MpvPlayer()
        val inhibitor = NoOpSleepInhibitor(simulatedSuccess = true)
        player.sleepInhibitor = inhibitor

        try {
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must initially be inactive")

            // 1. Resume -> acquire
            player.resume()
            assertTrue(player.sleepInhibitor.isInhibited, "Inhibitor must be acquired on resume()")

            // 2. Pause -> release
            player.pause()
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must be released on pause()")

            // 3. handleEvent Play -> resume -> acquire
            player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.UI)
            assertTrue(player.sleepInhibitor.isInhibited, "Inhibitor must be acquired on Play event")

            // 4. handleEvent Pause -> pause -> release
            player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.UI)
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must be released on Pause event")

            // 5. onPause -> release
            player.resume()
            assertTrue(player.sleepInhibitor.isInhibited)
            player.onPause()
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must be released on onPause()")

            // 6. onStop -> release
            player.resume()
            assertTrue(player.sleepInhibitor.isInhibited)
            player.onStop()
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must be released on onStop()")

            // 7. stop -> release
            player.resume()
            assertTrue(player.sleepInhibitor.isInhibited)
            player.stop()
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must be released on stop()")
        } finally {
            player.destroy()
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor must be released on destroy()")
        }
    }

    @Test
    fun testMpvPlayerIpcStateTransitionsCoordination() = runBlocking(Dispatchers.IO) {
        val player = MpvPlayer()
        val inhibitor = NoOpSleepInhibitor(simulatedSuccess = true)
        player.sleepInhibitor = inhibitor

        try {
            assertFalse(player.sleepInhibitor.isInhibited)

            // Simulate MPV IPC property-change for pause: false (Playing)
            player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"pause","data":false}""")

            withTimeoutOrNull(2000L) {
                while (!player.sleepInhibitor.isInhibited) {
                    delay(20L)
                }
            }
            assertTrue(player.sleepInhibitor.isInhibited, "Inhibitor should be acquired when MPV emits playing state")

            // Simulate MPV IPC property-change for pause: true (Paused)
            player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"pause","data":true}""")

            withTimeoutOrNull(2000L) {
                while (player.sleepInhibitor.isInhibited) {
                    delay(20L)
                }
            }
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor should be released when MPV emits paused state")

            // Simulate playing again, then EOF reached
            player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"pause","data":false}""")
            withTimeoutOrNull(2000L) {
                while (!player.sleepInhibitor.isInhibited) {
                    delay(20L)
                }
            }
            assertTrue(player.sleepInhibitor.isInhibited)

            player.ipcClient.processIpcLine("""{"event":"property-change","id":1,"name":"eof-reached","data":true}""")
            withTimeoutOrNull(2000L) {
                while (player.sleepInhibitor.isInhibited) {
                    delay(20L)
                }
            }
            assertFalse(player.sleepInhibitor.isInhibited, "Inhibitor should be released when MPV reaches EOF")
        } finally {
            player.destroy()
        }
    }
}
