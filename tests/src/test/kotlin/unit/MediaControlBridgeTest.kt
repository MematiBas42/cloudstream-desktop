package unit

import com.lagradost.common.media.*
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.player.impl.MpvPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MediaControlBridgeTest {

    @Test
    fun `testCreateDefaultReturnsPlatformControls`() {
        val controls = DesktopMediaControls.createDefault()
        assertNotNull(controls)

        when (PlatformPaths.currentOS) {
            PlatformPaths.OS.LINUX -> assertTrue(controls is LinuxMprisControls)
            PlatformPaths.OS.WINDOWS -> assertTrue(controls is WindowsSmtcControls)
            else -> assertTrue(controls is NoOpMediaControls)
        }

        controls.close()
    }

    @Test
    fun `testMetadataAndPlaybackStateUpdates`() {
        val controls = DesktopMediaControls.createDefault()

        val meta = MediaMetadata(
            title = "Frieren: Beyond Journey's End",
            artist = "Madhouse",
            album = "Season 1",
            posterUrl = "https://example.com/frieren.jpg",
            durationMs = 1440000L
        )
        controls.updateMetadata(meta)

        if (controls is BaseDesktopMediaControls) {
            assertEquals("Frieren: Beyond Journey's End", controls.currentMetadata?.title)
            assertEquals("Madhouse", controls.currentMetadata?.artist)
            assertEquals("Season 1", controls.currentMetadata?.album)
            assertEquals("https://example.com/frieren.jpg", controls.currentMetadata?.posterUrl)
            assertEquals(1440000L, controls.currentMetadata?.durationMs)
        }

        val state = MediaPlaybackState(
            isPlaying = true,
            positionMs = 30000L,
            speed = 1.0f
        )
        controls.updatePlaybackState(state)

        if (controls is BaseDesktopMediaControls) {
            assertTrue(controls.currentPlaybackState?.isPlaying == true)
            assertEquals(30000L, controls.currentPlaybackState?.positionMs)
            assertEquals(1.0f, controls.currentPlaybackState?.speed)
        }

        controls.close()
    }

    @Test
    fun `testMediaEventListenerDispatchRouting`() {
        val controls = NoOpMediaControls()

        var played = false
        var paused = false
        var toggled = false
        var nextClicked = false
        var prevClicked = false
        var seekTargetMs = -1L

        controls.setListener(object : MediaEventListener {
            override fun onPlay() { played = true }
            override fun onPause() { paused = true }
            override fun onToggle() { toggled = true }
            override fun onNext() { nextClicked = true }
            override fun onPrevious() { prevClicked = true }
            override fun onSeek(positionMs: Long) { seekTargetMs = positionMs }
        })

        controls.dispatchEvent("PLAY")
        assertTrue(played)

        controls.dispatchEvent("PAUSE")
        assertTrue(paused)

        controls.dispatchEvent("TOGGLE")
        assertTrue(toggled)

        controls.dispatchEvent("NEXT")
        assertTrue(nextClicked)

        controls.dispatchEvent("PREV")
        assertTrue(prevClicked)

        controls.dispatchEvent("SEEK", "45000")
        assertEquals(45000L, seekTargetMs)

        controls.close()
    }

    @Test
    fun `testMpvPlayerMediaControlsIntegration`() {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val player = MpvPlayer(testScope)

        assertNotNull(player.mediaControls, "MpvPlayer must initialize DesktopMediaControls")

        // Verify media controls have a registered listener
        if (player.mediaControls is BaseDesktopMediaControls) {
            val base = player.mediaControls as BaseDesktopMediaControls
            assertNotNull(base.getListener(), "MpvPlayer must register MediaEventListener on mediaControls")

            // Test dispatching seek event through media controls
            base.dispatchEvent("SEEK", "15000")
            // Player seek was called without throwing

            // Test dispatching play and pause
            base.dispatchEvent("PLAY")
            base.dispatchEvent("PAUSE")
            base.dispatchEvent("TOGGLE")
        }

        player.destroy()
    }

    @Test
    fun `testWindowsSmtcControlsCommandProviderFormat`() {
        val command = WindowsSmtcControls.defaultCommandProvider()
        assertTrue(command.contains("powershell"))
        assertTrue(command.any { it.contains("SystemMediaTransportControls") })
    }

    @Test
    fun `testLinuxMprisControlsCommandProviderFormat`() {
        val command = LinuxMprisControls.defaultCommandProvider()
        assertEquals("python3", command.first())
        assertTrue(command.last().endsWith("mpris_bridge.py"))
    }
}
