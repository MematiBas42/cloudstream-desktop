package unit

import com.lagradost.player.embedded.NativeWindowHandleResolver
import com.lagradost.player.impl.MpvProcessLauncher
import com.lagradost.player.ipc.MpvIpcClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sun.misc.Unsafe
import java.awt.Canvas
import java.awt.Component
import java.nio.file.Path

class MpvSurfaceAndIpcTest {

    // Dummy peer classes simulating JDK internal peers across platforms
    private class WindowsDummyPeer(
        @JvmField protected val hwnd: Long = 0x7FFF1234ABCDL
    )

    private class LinuxDummyPeer(
        @JvmField protected val window: Long = 0x55AA1122L
    )

    private class EmptyPeer

    @Test
    @DisplayName("NativeWindowHandleResolver resolves Windows HWND from peer")
    fun testResolveWindowsHwndFromPeer() {
        val winPeer = WindowsDummyPeer(0x88776655L)
        val handle = NativeWindowHandleResolver.getHandleFromPeer(winPeer)
        assertEquals(0x88776655L, handle, "Must resolve hwnd field on Windows peer")
    }

    @Test
    @DisplayName("NativeWindowHandleResolver resolves Linux X11 window XID from peer")
    fun testResolveLinuxWindowFromPeer() {
        val linuxPeer = LinuxDummyPeer(0x11223344L)
        val handle = NativeWindowHandleResolver.getHandleFromPeer(linuxPeer)
        assertEquals(0x11223344L, handle, "Must resolve window field on Linux peer")
    }

    @Test
    @DisplayName("NativeWindowHandleResolver returns 0L for unresolvable peer or null")
    fun testResolveEmptyOrNullPeer() {
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(null))
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(EmptyPeer()))
    }

    @Test
    @DisplayName("NativeWindowHandleResolver resolves HWND from Component with Windows peer")
    fun testResolveHwndFromComponent() {
        val canvas = Canvas()
        // Unattached canvas has null peer by default
        assertEquals(0L, NativeWindowHandleResolver.getWindowHandle(canvas))

        // Inject simulated Windows peer into Component.peer via Unsafe
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val u = unsafeField.get(null) as Unsafe
        val peerField = Component::class.java.getDeclaredField("peer")
        val peerOffset = u.objectFieldOffset(peerField)

        val testHwnd = 0xCAFEBABEL
        u.putObject(canvas, peerOffset, WindowsDummyPeer(testHwnd))

        val resolvedHwnd = NativeWindowHandleResolver.getWindowHandle(canvas)
        assertEquals(testHwnd, resolvedHwnd, "NativeWindowHandleResolver must extract HWND from Component peer")
    }

    @Test
    @DisplayName("MpvProcessLauncher builds Windows embedded command line with D3D11 and HWND")
    fun testWindowsEmbeddedCommandLine() {
        val testHwnd = 987654321L
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv.exe",
            socketPath = "C:\\Users\\User\\AppData\\Local\\Temp\\CloudStream\\sockets\\mpv_test.sock",
            mediaUrl = "https://cdn.example.com/stream.mp4",
            title = "Test Windows Stream",
            wid = testHwnd,
            osName = "Windows 11"
        )

        // Windows embedding flags
        assertTrue(args.contains("--wid=$testHwnd"), "Must include --wid with HWND")
        assertTrue(args.contains("--gpu-context=d3d11,win"), "Must include --gpu-context=d3d11,win on Windows")
        assertTrue(args.contains("--gpu-api=d3d11"), "Must include --gpu-api=d3d11 on Windows")
        assertTrue(args.contains("--hwdec=d3d11va"), "Must include --hwdec=d3d11va on Windows")
        assertTrue(args.contains("--vo=gpu"), "Must include --vo=gpu")
        assertTrue(args.contains("--input-vo-keyboard=no"), "Must disable internal vo keyboard")
        assertTrue(args.contains("--input-default-bindings=no"), "Must disable default bindings")
    }

    @Test
    @DisplayName("MpvProcessLauncher builds Windows standalone command line with D3D11")
    fun testWindowsStandaloneCommandLine() {
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv.exe",
            socketPath = "C:\\Users\\User\\AppData\\Local\\Temp\\CloudStream\\sockets\\mpv_test.sock",
            mediaUrl = "https://cdn.example.com/stream.mp4",
            wid = null,
            osName = "Windows 10"
        )

        assertFalse(args.any { it.startsWith("--wid=") }, "Standalone must not include --wid")
        assertTrue(args.contains("--gpu-context=d3d11,win"), "Must include --gpu-context=d3d11,win on Windows")
        assertTrue(args.contains("--gpu-api=d3d11"), "Must include --gpu-api=d3d11 on Windows")
        assertTrue(args.contains("--hwdec=d3d11va"), "Must include --hwdec=d3d11va on Windows")
        assertTrue(args.contains("--vo=gpu"), "Must include --vo=gpu")
    }

    @Test
    @DisplayName("MpvProcessLauncher builds Linux embedded and standalone command lines")
    fun testLinuxCommandLine() {
        val embeddedArgs = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/cloudstream/sockets/mpv_test.sock",
            mediaUrl = "https://cdn.example.com/stream.mp4",
            wid = 445566L,
            osName = "Linux"
        )

        assertTrue(embeddedArgs.contains("--wid=445566"), "Must include --wid on Linux")
        assertTrue(embeddedArgs.contains("--gpu-context=x11egl,x11"), "Must include --gpu-context=x11egl,x11 for Linux embedded")
        assertFalse(embeddedArgs.contains("--gpu-api=d3d11"), "Linux must not include D3D11")
        assertTrue(embeddedArgs.contains("--hwdec=auto-safe"), "Linux defaults to auto-safe hwdec")

        val standaloneArgs = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/cloudstream/sockets/mpv_test.sock",
            mediaUrl = "https://cdn.example.com/stream.mp4",
            wid = null,
            osName = "Linux"
        )

        assertFalse(standaloneArgs.any { it.startsWith("--wid=") }, "Standalone must not include --wid")
        assertTrue(standaloneArgs.contains("--gpu-context=wayland,x11egl"), "Standalone Linux defaults to wayland,x11egl")
        assertTrue(standaloneArgs.contains("--hwdec=auto-safe"), "Linux defaults to auto-safe hwdec")
    }

    @Test
    @DisplayName("MpvIpcClient path resolution normalizes paths with backslashes and forward slashes")
    fun testMpvIpcClientPathResolution(@TempDir tempDir: Path) {
        val sampleSocket = tempDir.resolve("test_mpv.sock")

        val normalized = MpvIpcClient.resolveSocketPath(sampleSocket.toString())
        assertEquals(sampleSocket.toAbsolutePath().normalize(), normalized)

        val address = MpvIpcClient.createSocketAddress(sampleSocket)
        assertNotNull(address)
        assertEquals(sampleSocket.toAbsolutePath().normalize().toString(), address.path.toString())
    }

    @Test
    @DisplayName("MpvIpcClient graceful timeout on non-existent socket without exception")
    fun testMpvIpcClientGracefulTimeout(@TempDir tempDir: Path) = runBlocking {
        val nonExistentSocket = tempDir.resolve("non_existent_mpv.sock")
        val client = MpvIpcClient()

        assertFalse(client.isConnected, "Initially client must not be connected")

        val connected = client.connect(nonExistentSocket, maxRetries = 2, retryDelayMs = 10)
        assertFalse(connected, "Connect to non-existent socket must return false")
        assertFalse(client.isConnected, "Client must remain disconnected")

        client.close()
    }
}
