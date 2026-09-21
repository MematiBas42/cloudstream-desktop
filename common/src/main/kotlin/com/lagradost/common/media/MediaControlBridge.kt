package com.lagradost.common.media

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Immutable metadata representing the current media item playing in CloudStream Desktop.
 */
data class MediaMetadata(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val posterUrl: String? = null,
    val durationMs: Long = 0L,
)

/**
 * Immutable playback state (playback status, time position, playback speed).
 */
data class MediaPlaybackState(
    val isPlaying: Boolean,
    val positionMs: Long,
    val speed: Float = 1.0f,
)

/**
 * Listener interface for external system media events (media keys, lock screen OSD, MPRIS/SMTC actions).
 */
interface MediaEventListener {
    fun onPlay()
    fun onPause()
    fun onToggle()
    fun onNext()
    fun onPrevious()
    fun onSeek(positionMs: Long)
}

/**
 * Unified cross-platform desktop media controls interface.
 * Exposes media metadata and playback status to the host operating system
 * (Linux D-Bus MPRIS v2, Windows 10/11 SMTC, etc.) and routes media key events.
 */
interface DesktopMediaControls : AutoCloseable {
    fun updateMetadata(metadata: MediaMetadata)
    fun updatePlaybackState(state: MediaPlaybackState)
    fun setListener(listener: MediaEventListener?)
    override fun close()

    companion object {
        fun createDefault(): DesktopMediaControls {
            return when (PlatformPaths.currentOS) {
                PlatformPaths.OS.LINUX -> LinuxMprisControls()
                PlatformPaths.OS.WINDOWS -> WindowsSmtcControls()
                PlatformPaths.OS.MACOS, PlatformPaths.OS.UNKNOWN -> NoOpMediaControls()
            }
        }
    }
}

/**
 * Common base implementation managing in-memory state and event dispatching.
 */
abstract class BaseDesktopMediaControls : DesktopMediaControls {
    @Volatile
    var currentMetadata: MediaMetadata? = null
        protected set

    @Volatile
    var currentPlaybackState: MediaPlaybackState? = null
        protected set

    @Volatile
    protected var activeListener: MediaEventListener? = null

    override fun setListener(listener: MediaEventListener?) {
        this.activeListener = listener
    }

    fun getListener(): MediaEventListener? = activeListener

    /**
     * Dispatches text-based action events from native media adapters to [MediaEventListener].
     */
    fun dispatchEvent(action: String, arg: String? = null) {
        val trimmedAction = action.trim().uppercase()
        val currentListener = activeListener
        if (currentListener == null) {
            AppLogger.d("BaseDesktopMediaControls", "Media event '$trimmedAction' ignored (no listener registered)")
            return
        }

        when (trimmedAction) {
            "PLAY" -> currentListener.onPlay()
            "PAUSE" -> currentListener.onPause()
            "TOGGLE", "PLAY_PAUSE", "PLAYPAUSE" -> currentListener.onToggle()
            "NEXT" -> currentListener.onNext()
            "PREVIOUS", "PREV" -> currentListener.onPrevious()
            "SEEK" -> {
                val targetPosMs = arg?.trim()?.toLongOrNull() ?: 0L
                currentListener.onSeek(targetPosMs.coerceAtLeast(0L))
            }
            "SEEK_OFFSET" -> {
                val offsetMs = arg?.trim()?.toLongOrNull() ?: 0L
                val currentPos = currentPlaybackState?.positionMs ?: 0L
                val targetPosMs = (currentPos + offsetMs).coerceAtLeast(0L)
                currentListener.onSeek(targetPosMs)
            }
            else -> AppLogger.d("BaseDesktopMediaControls", "Unknown media control event: '$trimmedAction' ($arg)")
        }
    }
}

/**
 * Headless or unsupported platform fallback media controls.
 */
open class NoOpMediaControls : BaseDesktopMediaControls() {
    override fun updateMetadata(metadata: MediaMetadata) {
        currentMetadata = metadata
    }

    override fun updatePlaybackState(state: MediaPlaybackState) {
        currentPlaybackState = state
    }

    override fun close() {
        // No native resources allocated
    }
}

/**
 * Linux D-Bus MPRIS v2 (org.mpris.MediaPlayer2.Player) adapter.
 * Bridges playback state, title, duration, and media key events to GNOME, KDE, and playerctl.
 */
open class LinuxMprisControls(
    var commandProvider: () -> List<String> = defaultCommandProvider
) : BaseDesktopMediaControls() {

    companion object {
        private const val TAG = "LinuxMprisControls"

        const val PYTHON_MPRIS_SCRIPT: String = """import sys, os
import dbus, dbus.service, dbus.mainloop.glib
from gi.repository import GLib

try:
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SessionBus()
except Exception as e:
    sys.stderr.write(f"DBus initialization failed: {e}\n")
    sys.exit(1)

loop = GLib.MainLoop()

class MprisRoot(dbus.service.Object):
    def __init__(self, bus):
        super().__init__(bus, '/org/mpris/MediaPlayer2')
        self.status = "Stopped"
        self.title = ""
        self.artist = ""
        self.album = ""
        self.art_url = ""
        self.duration_ms = 0
        self.position_ms = 0
        self.speed = 1.0

    @dbus.service.signal('org.freedesktop.DBus.Properties', signature='sa{sv}as')
    def PropertiesChanged(self, interface_name, changed_properties, invalidated_properties):
        pass

    @dbus.service.signal('org.mpris.MediaPlayer2.Player', signature='x')
    def Seeked(self, Position):
        pass

    def get_metadata(self):
        meta = {
            'mpris:trackid': dbus.ObjectPath('/org/mpris/MediaPlayer2/Track/0'),
            'mpris:length': dbus.Int64(self.duration_ms * 1000),
        }
        if self.title:
            meta['xesam:title'] = dbus.String(self.title)
        if self.artist:
            meta['xesam:artist'] = dbus.Array([dbus.String(self.artist)], signature='s')
        if self.album:
            meta['xesam:album'] = dbus.String(self.album)
        if self.art_url:
            meta['mpris:artUrl'] = dbus.String(self.art_url)
        return dbus.Dictionary(meta, signature='sv')

    def update_metadata(self, title, artist, album, art_url, duration_ms):
        self.title = title
        self.artist = artist
        self.album = album
        self.art_url = art_url
        self.duration_ms = duration_ms
        self.PropertiesChanged('org.mpris.MediaPlayer2.Player', {'Metadata': self.get_metadata()}, [])

    def update_playback_state(self, is_playing, position_ms, speed):
        new_status = "Playing" if is_playing else "Paused"
        changed = {}
        if self.status != new_status:
            self.status = new_status
            changed['PlaybackStatus'] = dbus.String(self.status)
        if abs(self.position_ms - position_ms) > 1000:
            self.position_ms = position_ms
            self.Seeked(dbus.Int64(position_ms * 1000))
        else:
            self.position_ms = position_ms
        if self.speed != speed:
            self.speed = speed
            changed['Rate'] = dbus.Double(self.speed)
        if changed:
            self.PropertiesChanged('org.mpris.MediaPlayer2.Player', changed, [])

    @dbus.service.method('org.freedesktop.DBus.Properties', in_signature='ss', out_signature='v')
    def Get(self, interface_name, property_name):
        props = self.GetAll(interface_name)
        if property_name in props:
            return props[property_name]
        raise dbus.exceptions.DBusException(f"Property {property_name} not found", name='org.freedesktop.DBus.Error.InvalidArgs')

    @dbus.service.method('org.freedesktop.DBus.Properties', in_signature='s', out_signature='a{sv}')
    def GetAll(self, interface_name):
        if interface_name == 'org.mpris.MediaPlayer2':
            return {
                'CanQuit': dbus.Boolean(True),
                'CanRaise': dbus.Boolean(False),
                'HasTrackList': dbus.Boolean(False),
                'Identity': dbus.String('CloudStream'),
                'SupportedUriSchemes': dbus.Array(['http', 'https', 'file'], signature='s'),
                'SupportedMimeTypes': dbus.Array(['video/*', 'audio/*'], signature='s'),
            }
        elif interface_name == 'org.mpris.MediaPlayer2.Player':
            return {
                'PlaybackStatus': dbus.String(self.status),
                'LoopStatus': dbus.String('None'),
                'Rate': dbus.Double(self.speed),
                'Shuffle': dbus.Boolean(False),
                'Metadata': self.get_metadata(),
                'Position': dbus.Int64(self.position_ms * 1000),
                'MinimumRate': dbus.Double(0.25),
                'MaximumRate': dbus.Double(4.0),
                'CanGoNext': dbus.Boolean(True),
                'CanGoPrevious': dbus.Boolean(True),
                'CanPlay': dbus.Boolean(True),
                'CanPause': dbus.Boolean(True),
                'CanSeek': dbus.Boolean(True),
                'CanControl': dbus.Boolean(True),
            }
        return {}

    @dbus.service.method('org.mpris.MediaPlayer2', in_signature='', out_signature='')
    def Raise(self):
        pass

    @dbus.service.method('org.mpris.MediaPlayer2', in_signature='', out_signature='')
    def Quit(self):
        print("EVENT\tQUIT", flush=True)
        loop.quit()

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='', out_signature='')
    def Next(self):
        print("EVENT\tNEXT", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='', out_signature='')
    def Previous(self):
        print("EVENT\tPREVIOUS", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='', out_signature='')
    def Pause(self):
        print("EVENT\tPAUSE", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='', out_signature='')
    def PlayPause(self):
        print("EVENT\tTOGGLE", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='', out_signature='')
    def Stop(self):
        print("EVENT\tPAUSE", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='', out_signature='')
    def Play(self):
        print("EVENT\tPLAY", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='x', out_signature='')
    def Seek(self, Offset):
        print(f"EVENT\tSEEK_OFFSET\t{Offset // 1000}", flush=True)

    @dbus.service.method('org.mpris.MediaPlayer2.Player', in_signature='ox', out_signature='')
    def SetPosition(self, TrackId, Position):
        print(f"EVENT\tSEEK\t{Position // 1000}", flush=True)

try:
    bus_name = dbus.service.BusName('org.mpris.MediaPlayer2.cloudstream', bus)
    root = MprisRoot(bus)
except Exception as e:
    sys.stderr.write(f"Failed to claim D-Bus name: {e}\n")
    sys.exit(1)

def on_stdin(source, condition):
    line = sys.stdin.readline()
    if not line:
        loop.quit()
        return False
    line = line.strip()
    if not line:
        return True
    if line == "QUIT":
        loop.quit()
        return False
    parts = line.split("\t")
    cmd = parts[0]
    if cmd == "META" and len(parts) >= 6:
        root.update_metadata(
            title=parts[1],
            artist=parts[2],
            album=parts[3],
            art_url=parts[4],
            duration_ms=int(parts[5]) if parts[5].isdigit() else 0
        )
    elif cmd == "STATE" and len(parts) >= 4:
        is_playing = (parts[1].lower() == "true")
        pos_ms = int(parts[2]) if parts[2].lstrip('-').isdigit() else 0
        speed = float(parts[3]) if parts[3].replace('.', '', 1).isdigit() else 1.0
        root.update_playback_state(is_playing, pos_ms, speed)
    return True

GLib.io_add_watch(sys.stdin.fileno(), GLib.IO_IN | GLib.IO_HUP, on_stdin)
print("READY", flush=True)
loop.run()
"""

        fun resolveScriptFile(): File {
            val dir = PlatformPaths.runtimeDir.toFile().apply { mkdirs() }
            val file = File(dir, "mpris_bridge.py")
            if (!file.exists() || file.length() == 0L || file.readText() != PYTHON_MPRIS_SCRIPT) {
                try {
                    file.writeText(PYTHON_MPRIS_SCRIPT, StandardCharsets.UTF_8)
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed writing script to runtimeDir, falling back to temp file: ${e.message}")
                    val temp = File.createTempFile("cloudstream_mpris_", ".py")
                    temp.deleteOnExit()
                    temp.writeText(PYTHON_MPRIS_SCRIPT, StandardCharsets.UTF_8)
                    return temp
                }
            }
            return file
        }

        val defaultCommandProvider: () -> List<String> = {
            val scriptFile = resolveScriptFile()
            listOf("python3", "-u", scriptFile.absolutePath)
        }
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    init {
        startDaemon()
    }

    private fun startDaemon() {
        synchronized(lock) {
            if (process?.isAlive == true) return

            try {
                val cmd = commandProvider()
                val pb = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start()
                process = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

                scope.launch {
                    try {
                        val reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
                        var line = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed == "READY") {
                                isConnected = true
                                AppLogger.i(TAG, "Linux MPRIS D-Bus daemon is READY and registered")
                                currentMetadata?.let { sendMetadataCommand(it) }
                                currentPlaybackState?.let { sendPlaybackStateCommand(it) }
                            } else if (trimmed.startsWith("EVENT\t")) {
                                val parts = trimmed.split("\t")
                                val action = parts.getOrNull(1) ?: ""
                                val arg = parts.getOrNull(2)
                                dispatchEvent(action, arg)
                            }
                            line = reader.readLine()
                        }
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "MPRIS stdout reader terminated: ${e.message}")
                    } finally {
                        isConnected = false
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Linux MPRIS D-Bus bridge unavailable: ${t.message}")
                isConnected = false
            }
        }
    }

    private fun sendMetadataCommand(metadata: MediaMetadata) {
        synchronized(lock) {
            val w = writer ?: return
            val p = process ?: return
            if (!p.isAlive) return

            try {
                val safeTitle = metadata.title.replace("\t", " ").replace("\n", " ")
                val safeArtist = (metadata.artist ?: "").replace("\t", " ").replace("\n", " ")
                val safeAlbum = (metadata.album ?: "").replace("\t", " ").replace("\n", " ")
                val safePoster = (metadata.posterUrl ?: "").replace("\t", " ").replace("\n", " ")
                w.write("META\t$safeTitle\t$safeArtist\t$safeAlbum\t$safePoster\t${metadata.durationMs}\n")
                w.flush()
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error writing metadata to MPRIS bridge: ${e.message}")
            }
        }
    }

    private fun sendPlaybackStateCommand(state: MediaPlaybackState) {
        synchronized(lock) {
            val w = writer ?: return
            val p = process ?: return
            if (!p.isAlive) return

            try {
                w.write("STATE\t${state.isPlaying}\t${state.positionMs}\t${state.speed}\n")
                w.flush()
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error writing playback state to MPRIS bridge: ${e.message}")
            }
        }
    }

    override fun updateMetadata(metadata: MediaMetadata) {
        currentMetadata = metadata
        sendMetadataCommand(metadata)
    }

    override fun updatePlaybackState(state: MediaPlaybackState) {
        currentPlaybackState = state
        sendPlaybackStateCommand(state)
    }

    override fun close() {
        synchronized(lock) {
            isConnected = false
            try {
                writer?.write("QUIT\n")
                writer?.flush()
                writer?.close()
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Ignored error closing MPRIS writer: ${t.message}")
            }
            writer = null

            val p = process
            process = null
            if (p != null && p.isAlive) {
                try {
                    if (!p.waitFor(1, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                } catch (t: Throwable) {
                    AppLogger.d(TAG, "Forcibly terminating MPRIS process on error: ${t.message}")
                    p.destroyForcibly()
                }
            }
            scope.cancel()
        }
    }
}

/**
 * Windows System Media Transport Controls (SMTC) adapter for Windows 10/11.
 * Connects to Windows Runtime SMTC via PowerShell WinRT or background bridge.
 */
open class WindowsSmtcControls(
    var commandProvider: () -> List<String> = defaultCommandProvider
) : BaseDesktopMediaControls() {

    companion object {
        private const val TAG = "WindowsSmtcControls"

        const val POWERSHELL_SMTC_SCRIPT: String = """${'$'}ErrorActionPreference = 'SilentlyContinue'
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [Windows.Media.Playback.MediaPlayer, Windows.Media, ContentType = WindowsRuntime] | Out-Null
    [Windows.Media.SystemMediaTransportControls, Windows.Media, ContentType = WindowsRuntime] | Out-Null
    ${'$'}player = New-Object Windows.Media.Playback.MediaPlayer
    ${'$'}player.CommandManager.IsEnabled = ${'$'}false
    ${'$'}smtc = ${'$'}player.SystemMediaTransportControls
    ${'$'}smtc.IsPlayEnabled = ${'$'}true
    ${'$'}smtc.IsPauseEnabled = ${'$'}true
    ${'$'}smtc.IsNextEnabled = ${'$'}true
    ${'$'}smtc.IsPreviousEnabled = ${'$'}true
    ${'$'}smtc.IsEnabled = ${'$'}true

    ${'$'}buttonHandler = [Windows.Foundation.TypedEventHandler[Windows.Media.SystemMediaTransportControls, Windows.Media.SystemMediaTransportControlsButtonPressedEventArgs]]{
        param(${'$'}sender, ${'$'}eventArgs)
        switch (${'$'}eventArgs.Button) {
            ([Windows.Media.SystemMediaTransportControlsButton]::Play) { [Console]::WriteLine("EVENT`tPLAY") }
            ([Windows.Media.SystemMediaTransportControlsButton]::Pause) { [Console]::WriteLine("EVENT`tPAUSE") }
            ([Windows.Media.SystemMediaTransportControlsButton]::Next) { [Console]::WriteLine("EVENT`tNEXT") }
            ([Windows.Media.SystemMediaTransportControlsButton]::Previous) { [Console]::WriteLine("EVENT`tPREVIOUS") }
            ([Windows.Media.SystemMediaTransportControlsButton]::Stop) { [Console]::WriteLine("EVENT`tPAUSE") }
        }
    }
    ${'$'}smtc.add_ButtonPressed(${'$'}buttonHandler)
    [Console]::WriteLine("READY")

    while (${'$'}line = [Console]::ReadLine()) {
        if (${'$'}line -eq "QUIT") { break }
        ${'$'}parts = ${'$'}line.Split("`t")
        if (${'$'}parts[0] -eq "META" -and ${'$'}parts.Length -ge 6) {
            ${'$'}smtc.DisplayUpdater.Type = [Windows.Media.MediaPlaybackType]::Video
            ${'$'}smtc.DisplayUpdater.VideoProperties.Title = ${'$'}parts[1]
            ${'$'}smtc.DisplayUpdater.VideoProperties.Subtitle = ${'$'}parts[2]
            ${'$'}smtc.DisplayUpdater.Update()
        } elseif (${'$'}parts[0] -eq "STATE" -and ${'$'}parts.Length -ge 2) {
            if (${'$'}parts[1] -eq "true") {
                ${'$'}smtc.PlaybackStatus = [Windows.Media.MediaPlaybackStatus]::Playing
            } else {
                ${'$'}smtc.PlaybackStatus = [Windows.Media.MediaPlaybackStatus]::Paused
            }
        }
    }
} catch {
    [Console]::Error.WriteLine("SMTC_ERROR: " + ${'$'}_.Exception.Message)
}
"""

        val defaultCommandProvider: () -> List<String> = {
            listOf(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                POWERSHELL_SMTC_SCRIPT
            )
        }
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    init {
        if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) {
            startDaemon()
        }
    }

    fun startDaemon() {
        synchronized(lock) {
            if (process?.isAlive == true) return

            try {
                val cmd = commandProvider()
                val pb = ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                val p = pb.start()
                process = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

                scope.launch {
                    try {
                        val reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
                        var line = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed == "READY") {
                                isConnected = true
                                AppLogger.i(TAG, "Windows SMTC daemon is READY")
                                currentMetadata?.let { sendMetadataCommand(it) }
                                currentPlaybackState?.let { sendPlaybackStateCommand(it) }
                            } else if (trimmed.startsWith("EVENT\t")) {
                                val parts = trimmed.split("\t")
                                val action = parts.getOrNull(1) ?: ""
                                val arg = parts.getOrNull(2)
                                dispatchEvent(action, arg)
                            }
                            line = reader.readLine()
                        }
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "Windows SMTC stdout reader closed: ${e.message}")
                    } finally {
                        isConnected = false
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Windows SMTC bridge unavailable: ${t.message}")
                isConnected = false
            }
        }
    }

    private fun sendMetadataCommand(metadata: MediaMetadata) {
        synchronized(lock) {
            val w = writer ?: return
            val p = process ?: return
            if (!p.isAlive) return

            try {
                val safeTitle = metadata.title.replace("\t", " ").replace("\n", " ")
                val safeArtist = (metadata.artist ?: "").replace("\t", " ").replace("\n", " ")
                val safeAlbum = (metadata.album ?: "").replace("\t", " ").replace("\n", " ")
                val safePoster = (metadata.posterUrl ?: "").replace("\t", " ").replace("\n", " ")
                w.write("META\t$safeTitle\t$safeArtist\t$safeAlbum\t$safePoster\t${metadata.durationMs}\n")
                w.flush()
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error writing metadata to SMTC bridge: ${e.message}")
            }
        }
    }

    private fun sendPlaybackStateCommand(state: MediaPlaybackState) {
        synchronized(lock) {
            val w = writer ?: return
            val p = process ?: return
            if (!p.isAlive) return

            try {
                w.write("STATE\t${state.isPlaying}\t${state.positionMs}\t${state.speed}\n")
                w.flush()
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error writing playback state to SMTC bridge: ${e.message}")
            }
        }
    }

    override fun updateMetadata(metadata: MediaMetadata) {
        currentMetadata = metadata
        sendMetadataCommand(metadata)
    }

    override fun updatePlaybackState(state: MediaPlaybackState) {
        currentPlaybackState = state
        sendPlaybackStateCommand(state)
    }

    override fun close() {
        synchronized(lock) {
            isConnected = false
            try {
                writer?.write("QUIT\n")
                writer?.flush()
                writer?.close()
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Ignored error closing SMTC writer: ${t.message}")
            }
            writer = null

            val p = process
            process = null
            if (p != null && p.isAlive) {
                try {
                    if (!p.waitFor(1, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                    }
                } catch (t: Throwable) {
                    AppLogger.d(TAG, "Forcibly terminating SMTC process on error: ${t.message}")
                    p.destroyForcibly()
                }
            }
            scope.cancel()
        }
    }
}
