package com.lagradost.common.media

import com.lagradost.common.platform.PlatformPaths
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MediaControlBridgeTest {

    @Test
    fun testCreateDefaultReturnsValidInstance() {
        val controls = DesktopMediaControls.createDefault()
        assertNotNull(controls, "DesktopMediaControls.createDefault() must return non-null instance")

        when (PlatformPaths.currentOS) {
            PlatformPaths.OS.LINUX -> {
                assertTrue(
                    controls is LinuxMprisControls,
                    "On Linux, DesktopMediaControls.createDefault() must return LinuxMprisControls"
                )
            }
            PlatformPaths.OS.WINDOWS -> {
                assertTrue(
                    controls is WindowsSmtcControls,
                    "On Windows, DesktopMediaControls.createDefault() must return WindowsSmtcControls"
                )
            }
            else -> {
                assertTrue(
                    controls is NoOpMediaControls,
                    "On macOS or unknown OS, DesktopMediaControls.createDefault() must return NoOpMediaControls"
                )
            }
        }
        controls.close()
    }

    @Test
    fun testMetadataUpdateStoresPropertiesCorrectly() {
        val controls = NoOpMediaControls()
        val metadata = MediaMetadata(
            title = "Solo Leveling",
            artist = "A-1 Pictures",
            album = "Season 1",
            posterUrl = "https://example.com/poster.jpg",
            durationMs = 1420000L
        )

        controls.updateMetadata(metadata)

        val stored = controls.currentMetadata
        assertNotNull(stored)
        assertEquals("Solo Leveling", stored?.title)
        assertEquals("A-1 Pictures", stored?.artist)
        assertEquals("Season 1", stored?.album)
        assertEquals("https://example.com/poster.jpg", stored?.posterUrl)
        assertEquals(1420000L, stored?.durationMs)
        controls.close()
    }

    @Test
    fun testPlaybackStateUpdateStoresPropertiesCorrectly() {
        val controls = NoOpMediaControls()
        val state = MediaPlaybackState(
            isPlaying = true,
            positionMs = 45000L,
            speed = 1.25f
        )

        controls.updatePlaybackState(state)

        val stored = controls.currentPlaybackState
        assertNotNull(stored)
        assertTrue(stored?.isPlaying == true)
        assertEquals(45000L, stored?.positionMs)
        assertEquals(1.25f, stored?.speed)
        controls.close()
    }

    @Test
    fun testMediaEventListenerCallbacks() {
        val controls = NoOpMediaControls()

        var playCount = 0
        var pauseCount = 0
        var toggleCount = 0
        var nextCount = 0
        var prevCount = 0
        var seekPositionMs: Long? = null

        val testListener = object : MediaEventListener {
            override fun onPlay() { playCount++ }
            override fun onPause() { pauseCount++ }
            override fun onToggle() { toggleCount++ }
            override fun onNext() { nextCount++ }
            override fun onPrevious() { prevCount++ }
            override fun onSeek(positionMs: Long) { seekPositionMs = positionMs }
        }

        controls.setListener(testListener)
        assertEquals(testListener, controls.getListener())

        controls.dispatchEvent("PLAY")
        assertEquals(1, playCount)

        controls.dispatchEvent("PAUSE")
        assertEquals(1, pauseCount)

        controls.dispatchEvent("TOGGLE")
        assertEquals(1, toggleCount)

        controls.dispatchEvent("NEXT")
        assertEquals(1, nextCount)

        controls.dispatchEvent("PREVIOUS")
        assertEquals(1, prevCount)

        controls.dispatchEvent("SEEK", "60000")
        assertEquals(60000L, seekPositionMs)

        // Test relative seek offset
        controls.updatePlaybackState(MediaPlaybackState(isPlaying = true, positionMs = 60000L))
        controls.dispatchEvent("SEEK_OFFSET", "10000")
        assertEquals(70000L, seekPositionMs)

        controls.close()
    }

    @Test
    fun testLinuxMprisControlsCommandProvider() {
        val cmd = LinuxMprisControls.defaultCommandProvider()
        assertTrue(cmd.isNotEmpty())
        assertEquals("python3", cmd[0])
        assertEquals("-u", cmd[1])
        assertTrue(cmd[2].endsWith(".py"))

        val scriptFile = LinuxMprisControls.resolveScriptFile()
        assertTrue(scriptFile.exists())
        assertTrue(scriptFile.length() > 0)
    }

    @Test
    fun testWindowsSmtcControlsCommandProvider() {
        val cmd = WindowsSmtcControls.defaultCommandProvider()
        assertTrue(cmd.isNotEmpty())
        assertEquals("powershell", cmd[0])
        assertTrue(cmd.contains("-NoProfile"))
        assertTrue(cmd.contains("-NonInteractive"))
        assertTrue(cmd.contains("-ExecutionPolicy"))
    }

    @Test
    fun testWindowsSmtcControlsStateUpdatesWithoutActiveDaemon() {
        val controls = WindowsSmtcControls(commandProvider = { listOf("non_existent_command") })
        val meta = MediaMetadata(title = "Windows SMTC Test", durationMs = 5000L)
        controls.updateMetadata(meta)
        assertEquals("Windows SMTC Test", controls.currentMetadata?.title)

        val state = MediaPlaybackState(isPlaying = false, positionMs = 1000L)
        controls.updatePlaybackState(state)
        assertEquals(1000L, controls.currentPlaybackState?.positionMs)

        controls.close()
        assertFalse(controls.isConnected)
    }

    @Test
    fun testLinuxMprisControlsStateUpdates() {
        val controls = LinuxMprisControls(commandProvider = { listOf("non_existent_command") })
        val meta = MediaMetadata(title = "Linux MPRIS Test", artist = "CloudStream", durationMs = 10000L)
        controls.updateMetadata(meta)
        assertEquals("Linux MPRIS Test", controls.currentMetadata?.title)
        assertEquals("CloudStream", controls.currentMetadata?.artist)

        val state = MediaPlaybackState(isPlaying = true, positionMs = 2500L, speed = 1.0f)
        controls.updatePlaybackState(state)
        assertTrue(controls.currentPlaybackState?.isPlaying == true)
        assertEquals(2500L, controls.currentPlaybackState?.positionMs)

        controls.close()
        assertFalse(controls.isConnected)
    }
}
