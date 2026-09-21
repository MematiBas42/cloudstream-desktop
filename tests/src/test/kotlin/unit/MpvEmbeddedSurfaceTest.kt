package unit

import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.embedded.NativeWindowHandleResolver
import com.lagradost.player.impl.MpvProcessLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.Dimension
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities

class MpvEmbeddedSurfaceTest {

    private lateinit var testScope: CoroutineScope

    @BeforeEach
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun testNativeWindowHandleResolverReturnsPositiveXidOnDisplayableCanvas() {
        if (System.getenv("DISPLAY").isNullOrBlank()) {
            println("Skipping test: No DISPLAY environment available")
            return
        }

        var wid = 0L
        SwingUtilities.invokeAndWait {
            val frame = JFrame("Test Window")
            val canvas = Canvas()
            canvas.preferredSize = Dimension(800, 600)
            frame.add(canvas)
            frame.pack()
            frame.isVisible = true

            try {
                wid = NativeWindowHandleResolver.getWindowHandle(canvas)
                println("Resolved native window ID (wid): $wid")
            } finally {
                frame.dispose()
            }
        }

        assertTrue(wid > 0L, "Resolved native window ID must be a positive X11 XID (wid > 0)")
    }

    @Test
    fun testMpvProcessLauncherBuildsEmbeddedCommandLineWithWid() {
        val testWid = 10485792L
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/test.sock",
            mediaUrl = "https://cdn.example.com/stream.mp4",
            title = "Test Media",
            startSec = 30L,
            wid = testWid
        )

        // Verify --wid is present with the exact window ID
        assertTrue(args.contains("--wid=$testWid"), "Arguments must contain --wid=$testWid")

        // Verify GPU context is set to X11/EGL when embedding into an X11 window
        assertTrue(args.contains("--gpu-context=x11egl,x11"), "Arguments must contain --gpu-context=x11egl,x11 for embedded X11 surface")

        // Verify input interception is disabled so Compose Desktop retains full keyboard/mouse control
        assertTrue(args.contains("--input-vo-keyboard=no"), "Embedded MPV must disable internal vo keyboard to preserve Compose focus")
        assertTrue(args.contains("--input-default-bindings=no"), "Embedded MPV must disable default bindings")
    }

    @Test
    fun testMpvProcessLauncherDefaultsToWaylandWhenNoWidProvided() {
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/test.sock",
            mediaUrl = "https://cdn.example.com/stream.mp4",
            wid = null
        )

        assertFalse(args.any { it.startsWith("--wid=") }, "Arguments must not contain --wid when wid is null")
        assertTrue(args.contains("--gpu-context=wayland,x11egl"), "Standalone mode must default to wayland,x11egl")
    }

    @Test
    fun testMpvPlayerAttachesAndDetachesNativeWindow() = runBlocking {
        val player = MpvPlayer(testScope)
        assertNull(player.windowId, "Initial windowId must be null")

        val testWid = 41943042L
        player.attachWindow(testWid)
        assertEquals(testWid, player.windowId, "attachWindow must set windowId")

        var launchedWid: Long? = null
        player.socketFactory = { File("/tmp/test_${System.currentTimeMillis()}.sock") }
        player.processLauncher = { _, _, _, _, _, _, _, _, _, _, wid ->
            launchedWid = wid
            ProcessBuilder("sleep", "1").start()
        }

        val testLink = newExtractorLink(
            source = "Test",
            name = "Test Link",
            url = "https://cdn.example.com/video.mp4",
            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
        )

        player.play(
            link = testLink,
            title = "Embedded Test",
            subtitles = emptyList(),
            startPositionMs = 0L
        )

        assertEquals(testWid, launchedWid, "MpvPlayer.play must forward bound windowId to processLauncher")

        player.detachWindow()
        assertNull(player.windowId, "detachWindow must clear windowId")
        player.stop()
    }
}
