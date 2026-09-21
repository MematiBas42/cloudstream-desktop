package unit

import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.services.MacOsSleepInhibitor
import com.lagradost.cloudstream3.services.NoOpSleepInhibitor
import com.lagradost.cloudstream3.services.SleepInhibitor
import com.lagradost.cloudstream3.services.SystemdSleepInhibitor
import com.lagradost.cloudstream3.services.VideoDownloadService
import com.lagradost.cloudstream3.services.WindowsSleepInhibitor
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Architectural Remediation & Parity Test for [VideoDownloadService] and [SleepInhibitor] abstractions.
 *
 * Validates 1:1 behavioral and contract parity against upstream CloudStream & desktop requirements:
 * 1. Headless service lifecycle and Android Intent command delegation.
 * 2. Cross-platform [SleepInhibitor] interface contract and default factory.
 * 3. [SystemdSleepInhibitor] with `--what=idle:sleep` command contract and failure tolerance.
 * 4. [WindowsSleepInhibitor] with Win32 SetThreadExecutionState constants and process fallback.
 * 5. [NoOpSleepInhibitor] behavior for headless/mock environments.
 * 6. [MacOsSleepInhibitor] with caffeinate command provider.
 * 7. Reactive download state flow observation and coordinator state machine.
 */
class VideoDownloadServiceRemediationTest {

    private val originalInhibitor = VideoDownloadService.sleepInhibitor

    @BeforeEach
    fun setUp() {
        VideoDownloadService.stopCoordinator()
        VideoDownloadService.sleepInhibitor = NoOpSleepInhibitor(simulatedSuccess = false)
    }

    @AfterEach
    fun tearDown() {
        VideoDownloadService.stopCoordinator()
        VideoDownloadService.sleepInhibitor = originalInhibitor
    }

    // =========================================================================
    // 1. Service Lifecycle and Contract Parity
    // =========================================================================

    @Test
    fun `onBind always returns null matching upstream Android contract`() {
        val service = VideoDownloadService()
        val intent = Intent()
        assertNull(service.onBind(intent), "onBind must return null per upstream VideoDownloadService contract")
        assertNull(service.onBind(null), "onBind with null intent must also return null")
    }

    @Test
    fun `onStartCommand with null intent returns START_NOT_STICKY`() {
        val service = VideoDownloadService()
        val result = service.onStartCommand(null, 0, 0)
        assertEquals(VideoDownloadService.START_NOT_STICKY, result)
    }

    @Test
    fun `onStartCommand routes resume action and returns START_NOT_STICKY`() {
        val service = VideoDownloadService()
        val intent = Intent().apply {
            putExtra("id", 101)
            putExtra("type", "resume")
        }

        val result = service.onStartCommand(intent)
        assertEquals(VideoDownloadService.START_NOT_STICKY, result)
    }

    @Test
    fun `onStartCommand routes pause action and returns START_NOT_STICKY`() {
        val service = VideoDownloadService()
        val intent = Intent().apply {
            putExtra("id", 102)
            putExtra("type", "pause")
        }

        val result = service.onStartCommand(intent)
        assertEquals(VideoDownloadService.START_NOT_STICKY, result)
    }

    @Test
    fun `onStartCommand routes stop action and returns START_NOT_STICKY`() {
        val service = VideoDownloadService()
        val intent = Intent().apply {
            putExtra("id", 103)
            putExtra("type", "stop")
        }

        val result = service.onStartCommand(intent)
        assertEquals(VideoDownloadService.START_NOT_STICKY, result)
    }

    @Test
    fun `onStartCommand routes unknown action gracefully and returns START_NOT_STICKY`() {
        val service = VideoDownloadService()
        val intent = Intent().apply {
            putExtra("id", 104)
            putExtra("type", "unknown_unsupported_action")
        }

        val result = service.onStartCommand(intent)
        assertEquals(VideoDownloadService.START_NOT_STICKY, result)
    }

    @Test
    fun `service instance lifecycle methods onCreate and onDestroy manage coordinator`() {
        val service = VideoDownloadService()
        assertFalse(VideoDownloadService.isCoordinatorRunning)

        service.onCreate()
        assertTrue(VideoDownloadService.isCoordinatorRunning, "onCreate must start coordinator")

        service.onDestroy()
        assertFalse(VideoDownloadService.isCoordinatorRunning, "onDestroy must stop coordinator")
    }

    @Test
    fun `getIntent returns Intent targeting VideoDownloadService`() {
        val context = Context()
        val intent = VideoDownloadService.getIntent(context)
        assertNotNull(intent)
        assertEquals(VideoDownloadService::class.java.name, intent.component?.className)
    }

    // =========================================================================
    // 2. SleepInhibitor Abstraction & Factory Parity
    // =========================================================================

    @Test
    fun `SleepInhibitor createDefault creates appropriate platform inhibitor`() {
        val inhibitor = SleepInhibitor.createDefault()
        assertNotNull(inhibitor, "createDefault must return a non-null SleepInhibitor instance")

        when (PlatformPaths.currentOS) {
            PlatformPaths.OS.LINUX -> assertTrue(inhibitor is SystemdSleepInhibitor, "Linux must use SystemdSleepInhibitor")
            PlatformPaths.OS.WINDOWS -> assertTrue(inhibitor is WindowsSleepInhibitor, "Windows must use WindowsSleepInhibitor")
            PlatformPaths.OS.MACOS -> assertTrue(inhibitor is MacOsSleepInhibitor, "macOS must use MacOsSleepInhibitor")
            PlatformPaths.OS.UNKNOWN -> assertTrue(inhibitor is NoOpSleepInhibitor, "Unknown OS must use NoOpSleepInhibitor")
        }
    }

    @Test
    fun `NoOpSleepInhibitor adheres to contract with simulatedSuccess toggle`() {
        val failingInhibitor = NoOpSleepInhibitor(simulatedSuccess = false)
        assertFalse(failingInhibitor.isInhibited)
        assertFalse(failingInhibitor.acquire(), "acquire must return false when simulatedSuccess=false")
        assertFalse(failingInhibitor.isInhibited)
        assertTrue(failingInhibitor.release())

        val successfulInhibitor = NoOpSleepInhibitor(simulatedSuccess = true)
        assertFalse(successfulInhibitor.isInhibited)
        assertTrue(successfulInhibitor.acquire(), "acquire must return true when simulatedSuccess=true")
        assertTrue(successfulInhibitor.isInhibited)
        assertTrue(successfulInhibitor.release())
        assertFalse(successfulInhibitor.isInhibited)
    }

    @Test
    fun `SystemdSleepInhibitor command contains what=idle sleep flag`() {
        val defaultCmd = SystemdSleepInhibitor.defaultCommandProvider()
        assertTrue(defaultCmd.contains("systemd-inhibit"), "Must invoke systemd-inhibit binary")
        assertTrue(defaultCmd.contains("--what=idle:sleep"), "Must contain exact flag --what=idle:sleep")
        assertTrue(defaultCmd.contains("--who=CloudStream"))
        assertTrue(defaultCmd.contains("--mode=block"))
    }

    @Test
    fun `SystemdSleepInhibitor handles missing binary gracefully without throwing`() {
        val inhibitor = SystemdSleepInhibitor {
            listOf("non_existent_systemd_inhibit_executable_99999")
        }

        assertFalse(inhibitor.isInhibited)
        val acquired = inhibitor.acquire()
        assertFalse(acquired, "acquire must return false when binary is missing")
        assertFalse(inhibitor.isInhibited, "isInhibited must remain false")

        val released = inhibitor.release()
        assertTrue(released, "release must succeed cleanly even if never acquired")
    }

    @Test
    fun `WindowsSleepInhibitor defines correct Win32 SetThreadExecutionState constants`() {
        assertEquals(0x00000001, WindowsSleepInhibitor.ES_SYSTEM_REQUIRED)
        assertEquals(0x00000002, WindowsSleepInhibitor.ES_DISPLAY_REQUIRED)
        assertEquals(0x00000040, WindowsSleepInhibitor.ES_AWAYMODE_REQUIRED)
        assertEquals(0x80000000.toInt(), WindowsSleepInhibitor.ES_CONTINUOUS)
    }

    @Test
    fun `WindowsSleepInhibitor command provider generates valid PowerShell invocation`() {
        val cmd = WindowsSleepInhibitor.defaultCommandProvider()
        assertTrue(cmd.contains("powershell"))
        assertTrue(cmd.contains("-NoProfile"))
        assertTrue(cmd.contains("-NonInteractive"))
        assertTrue(cmd.any { it.contains("SetThreadExecutionState") })
    }

    @Test
    fun `WindowsSleepInhibitor handles missing powershell binary gracefully`() {
        val inhibitor = WindowsSleepInhibitor {
            listOf("non_existent_powershell_executable_99999")
        }

        assertFalse(inhibitor.isInhibited)
        val acquired = inhibitor.acquire()
        assertFalse(acquired, "acquire must return false when binary missing and JNA absent")
        assertFalse(inhibitor.isInhibited)

        val released = inhibitor.release()
        assertTrue(released)
    }

    @Test
    fun `MacOsSleepInhibitor default command uses caffeinate`() {
        val cmd = MacOsSleepInhibitor.defaultCommandProvider()
        assertTrue(cmd.contains("caffeinate"))
        assertTrue(cmd.contains("-i"))
    }

    @Test
    fun `MacOsSleepInhibitor handles missing caffeinate gracefully`() {
        val inhibitor = MacOsSleepInhibitor {
            listOf("non_existent_caffeinate_binary_99999")
        }

        assertFalse(inhibitor.isInhibited)
        val acquired = inhibitor.acquire()
        assertFalse(acquired)
        assertFalse(inhibitor.isInhibited)

        val released = inhibitor.release()
        assertTrue(released)
    }

    // =========================================================================
    // 3. VideoDownloadService Integration with SleepInhibitor
    // =========================================================================

    @Test
    fun `VideoDownloadService delegates acquire and release to active sleepInhibitor`() {
        val mockInhibitor = NoOpSleepInhibitor(simulatedSuccess = true)
        VideoDownloadService.sleepInhibitor = mockInhibitor

        assertFalse(VideoDownloadService.isSleepInhibited)
        val acquired = VideoDownloadService.acquireSleepInhibit()
        assertTrue(acquired)
        assertTrue(VideoDownloadService.isSleepInhibited)

        val released = VideoDownloadService.releaseSleepInhibit()
        assertTrue(released)
        assertFalse(VideoDownloadService.isSleepInhibited)
    }

    @Test
    fun `inhibitCommandProvider getter and setter interoperate with SystemdSleepInhibitor`() {
        val customCmd = listOf("test-inhibit", "--flag")
        VideoDownloadService.inhibitCommandProvider = { customCmd }

        val provider = VideoDownloadService.inhibitCommandProvider
        assertEquals(customCmd, provider())
        assertTrue(VideoDownloadService.sleepInhibitor is SystemdSleepInhibitor)
    }

    @Test
    fun `hasActiveDownloadsFlow reports false when no downloads are active`() = runBlocking {
        val hasActive = VideoDownloadService.hasActiveDownloadsFlow.first()
        assertFalse(hasActive, "Initially hasActiveDownloadsFlow should be false")
    }

    @Test
    fun `coordinator start and stop manage event listeners and inhibitor collector`() {
        val context = Context()
        assertFalse(VideoDownloadService.isCoordinatorRunning)

        VideoDownloadService.startCoordinator(context)
        assertTrue(VideoDownloadService.isCoordinatorRunning)

        // Calling startCoordinator again while already running should be idempotent
        VideoDownloadService.startCoordinator(context)
        assertTrue(VideoDownloadService.isCoordinatorRunning)

        VideoDownloadService.stopCoordinator()
        assertFalse(VideoDownloadService.isCoordinatorRunning)

        // Calling stopCoordinator again while stopped should be idempotent
        VideoDownloadService.stopCoordinator()
        assertFalse(VideoDownloadService.isCoordinatorRunning)
    }

    @Test
    fun `resumeInterruptedDownloads returns zero when no resume packages exist`() {
        val count = VideoDownloadService.resumeInterruptedDownloads(Context())
        assertEquals(0, count, "Should resume 0 downloads when cache is empty")
    }

    @Test
    fun `sendFreedesktopNotification runs safely without throwing on any platform`() {
        // Test with context
        VideoDownloadService.sendFreedesktopNotification(
            title = "Test Notification",
            message = "Testing desktop notifications",
            notificationId = 9999,
            progress = 50
        )

        // Test without progress
        VideoDownloadService.sendFreedesktopNotification(
            title = "Download Completed",
            message = "File saved successfully",
            notificationId = 10000
        )
    }
}
