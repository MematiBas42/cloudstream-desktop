package unit

import com.lagradost.cloudstream3.loader.PluginShadowManager
import com.lagradost.common.io.SafeFileOperations
import com.lagradost.common.media.BaseDesktopMediaControls
import com.lagradost.common.media.DesktopMediaControls
import com.lagradost.common.media.MediaEventListener
import com.lagradost.common.media.MediaMetadata
import com.lagradost.common.media.MediaPlaybackState
import com.lagradost.common.media.NoOpMediaControls
import com.lagradost.common.media.WindowsSmtcControls
import com.lagradost.common.platform.FileNameSanitizer
import com.lagradost.common.platform.NoOpSleepInhibitor
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.platform.SleepInhibitor
import com.lagradost.common.platform.WindowsSleepInhibitor
import com.lagradost.player.embedded.NativeWindowHandleResolver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Enterprise Windows Full-Stack Integration Test Suite (Walking Skeleton).
 *
 * Direct, zero-mock end-to-end integration validation across all Phase 1 & 2 cross-platform subsystems:
 * 1. PlatformPaths: Windows %APPDATA%, %LOCALAPPDATA%, %TEMP%, Downloads resolution.
 * 2. FileNameSanitizer: Sanitizing illegal NTFS characters, DOS device names (CON, AUX, NUL), question marks.
 * 3. SafeFileOperations: Atomic and staged move operations with byte-level integrity verification.
 * 4. PluginShadowManager: Copy-on-load shadow copy isolation preventing URLClassLoader locks on NTFS.
 * 5. NativeWindowHandleResolver: Reflection and Unsafe Win32 HWND extraction from AWT peer structures.
 * 6. SleepInhibitor: Windows SetThreadExecutionState Win32 flags, state machine, and process lifecycle.
 * 7. DesktopMediaControls: Windows SMTC / MPRIS metadata broadcasting and media event dispatch routing.
 */
@DisplayName("Windows Full-Stack Walking Skeleton Integration Test Suite")
class WindowsFullStackIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var shadowDir: File

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        shadowDir = tempDir.resolve("plugins-shadow").toFile().apply { mkdirs() }
        PluginShadowManager.customShadowDir = shadowDir
    }

    @AfterEach
    fun tearDown() {
        PluginShadowManager.cleanRuntimeCache(shadowDir)
        PluginShadowManager.customShadowDir = null
    }

    // =========================================================================
    // HELPER: Real JAR Construction (Zero Mocks)
    // =========================================================================

    private fun createTestPluginJar(jarFile: File, internalName: String, version: Int): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Plugin-Internal-Name")] = internalName
            mainAttributes[Attributes.Name("Plugin-Version")] = version.toString()
        }
        JarOutputStream(FileOutputStream(jarFile), manifest).use { jos ->
            val manifestJson = """{"internalName":"$internalName","version":$version}"""
            jos.putNextEntry(JarEntry("manifest.json"))
            jos.write(manifestJson.toByteArray())
            jos.closeEntry()

            jos.putNextEntry(JarEntry("res/strings.txt"))
            jos.write("CloudStream Plugin Payload v$version for $internalName".toByteArray())
            jos.closeEntry()
        }
        return jarFile
    }

    // =========================================================================
    // 0. WALKING SKELETON: Chained End-to-End Flow
    // =========================================================================

    @Test
    @DisplayName("Walking Skeleton: Complete continuous end-to-end Windows workflow execution")
    fun testWalkingSkeletonEndToEndPipeline() {
        // ---------------------------------------------------------------------
        // Step 1: PlatformPaths Resolution (Windows Specification)
        // ---------------------------------------------------------------------
        val simulatedHome = tempDir.resolve("Users").resolve("TestArchitect").toString()
        val simulatedAppData = tempDir.resolve("AppData").resolve("Roaming").toString()
        val simulatedLocalAppData = tempDir.resolve("AppData").resolve("Local").toString()
        val simulatedTemp = tempDir.resolve("AppData").resolve("Local").resolve("Temp").toString()

        val windowsEnv = mapOf(
            "USERPROFILE" to simulatedHome,
            "APPDATA" to simulatedAppData,
            "LOCALAPPDATA" to simulatedLocalAppData,
            "TEMP" to simulatedTemp
        )

        val resolvedPaths = PlatformPaths.resolvePaths(
            os = PlatformPaths.OS.WINDOWS,
            userHomeDir = simulatedHome,
            env = windowsEnv
        )

        assertEquals(Paths.get(simulatedAppData, "CloudStream"), resolvedPaths.configDir)
        assertEquals(Paths.get(simulatedLocalAppData, "CloudStream", "data"), resolvedPaths.dataDir)
        assertEquals(Paths.get(simulatedLocalAppData, "CloudStream", "cache"), resolvedPaths.cacheDir)
        assertEquals(Paths.get(simulatedTemp, "CloudStream"), resolvedPaths.runtimeDir)
        assertEquals(Paths.get(simulatedHome, "Downloads", "CloudStream"), resolvedPaths.downloadsDir)
        assertEquals(Paths.get(simulatedTemp, "CloudStream", "plugins-shadow"), resolvedPaths.pluginsShadowDir)

        // ---------------------------------------------------------------------
        // Step 2: FileNameSanitizer (Series, Episode, and Reserved DOS Name)
        // ---------------------------------------------------------------------
        val rawSeriesTitle = "Re:Zero - Starting Life in Another World: Season 2"
        val rawEpisodeTitle = "What If...? [CON].mp4"

        val cleanSeriesFolder = FileNameSanitizer.sanitizeDirectoryName(rawSeriesTitle)
        val cleanEpisodeFileName = FileNameSanitizer.sanitizeFileName(rawEpisodeTitle)

        // Verify illegal NTFS characters (: and ?) and DOS reserved device name (CON) are cleaned
        assertFalse(cleanSeriesFolder.contains(":"), "Series folder name must not contain colon")
        assertFalse(cleanSeriesFolder.contains("?"), "Series folder name must not contain question mark")
        assertEquals("Re_Zero - Starting Life in Another World_ Season 2", cleanSeriesFolder)

        assertFalse(cleanEpisodeFileName.contains("?"), "Episode file name must not contain question mark")
        assertTrue(cleanEpisodeFileName.startsWith("What If"), "Stem must start with sanitized title")
        assertTrue(cleanEpisodeFileName.endsWith(".mp4"), "Extension must be preserved")
        assertFalse(FileNameSanitizer.isReservedWindowsDeviceName(cleanEpisodeFileName), "Must not be a reserved DOS device name")

        // Direct check on CON.mp4 -> _CON.mp4
        val reservedSanitized = FileNameSanitizer.sanitizeFileName("CON.mp4")
        assertEquals("_CON.mp4", reservedSanitized, "CON.mp4 must be safely escaped to _CON.mp4")

        // ---------------------------------------------------------------------
        // Step 3: SafeFileOperations (Download Staging to Final Destination)
        // ---------------------------------------------------------------------
        val stagingDir = resolvedPaths.runtimeDir.resolve("download-staging")
        Files.createDirectories(stagingDir)
        val stagingFile = stagingDir.resolve("in_flight_download.part")

        // Generate 1 MiB deterministic video payload
        val videoPayload = ByteArray(1024 * 1024) { (it % 251).toByte() }
        Files.write(stagingFile, videoPayload)
        assertTrue(Files.exists(stagingFile), "Staged download file must exist")

        val targetDir = resolvedPaths.downloadsDir.resolve(cleanSeriesFolder)
        val finalTargetFile = targetDir.resolve(cleanEpisodeFileName)

        val moveSucceeded = SafeFileOperations.safeMove(
            source = stagingFile,
            target = finalTargetFile,
            verifyIntegrity = true
        )

        assertTrue(moveSucceeded, "SafeFileOperations.safeMove must return true")
        assertFalse(Files.exists(stagingFile), "Staging source file must be removed after move")
        assertTrue(Files.exists(finalTargetFile), "Target download file must exist at final destination")
        assertEquals(videoPayload.size.toLong(), Files.size(finalTargetFile), "File size must match exactly")
        assertArrayEquals(videoPayload, Files.readAllBytes(finalTargetFile), "Byte-for-byte content must match")

        // ---------------------------------------------------------------------
        // Step 4: PluginShadowManager (Plugin Shadow Copy & Lock Freedom)
        // ---------------------------------------------------------------------
        val pluginsDir = resolvedPaths.dataDir.resolve("plugins").toFile().apply { mkdirs() }
        val originalJarFile = File(pluginsDir, "ProviderReZero.jar")
        createTestPluginJar(originalJarFile, "ProviderReZero", 1)
        assertTrue(originalJarFile.exists(), "Original plugin JAR must exist")

        val shadowJarFile = PluginShadowManager.createShadowCopy(originalJarFile, "ProviderReZero")
        assertTrue(shadowJarFile.exists(), "Shadow copy JAR must exist")
        assertTrue(PluginShadowManager.isShadowFile(shadowJarFile), "Shadow file must be identified by manager")
        assertFalse(PluginShadowManager.isShadowFile(originalJarFile), "Original file must not be classified as shadow")

        // Verify ClassLoader can read from shadow copy
        val classLoader = URLClassLoader(arrayOf(shadowJarFile.toURI().toURL()), javaClass.classLoader)
        val resourceText = classLoader.getResourceAsStream("res/strings.txt")?.bufferedReader()?.readText()
        assertNotNull(resourceText, "Resource in shadow JAR must be readable")
        assertTrue(resourceText?.contains("ProviderReZero") == true, "Resource content must match")

        // CRITICAL WINDOWS GUARANTEE: Original JAR can be deleted or overwritten without sharing violation
        val originalDeleted = originalJarFile.delete()
        assertTrue(originalDeleted, "Original JAR in persistent plugins directory must be deletable while shadow is loaded")
        assertFalse(originalJarFile.exists(), "Original JAR must no longer exist")
        assertTrue(shadowJarFile.exists(), "Shadow copy must still exist and remain valid")

        classLoader.close()
        PluginShadowManager.cleanRuntimeCache(shadowDir)

        // ---------------------------------------------------------------------
        // Step 5: NativeWindowHandleResolver (Win32 HWND Extraction)
        // ---------------------------------------------------------------------
        // Win32 peer simulation with field 'hwnd'
        val simulatedWin32Hwnd = 0x00020468L
        val dummyPeerWithHwnd = DummyWin32Peer(hwnd = simulatedWin32Hwnd)
        val extractedHwnd = NativeWindowHandleResolver.getHandleFromPeer(dummyPeerWithHwnd)
        assertEquals(simulatedWin32Hwnd, extractedHwnd, "Resolver must extract HWND from Win32 component peer")

        // Win32 peer simulation with getter method 'getHWnd'
        val dummyPeerWithGetter = DummyWin32GetterPeer(handle = 0x0005ABCDL)
        val extractedFromGetter = NativeWindowHandleResolver.getHandleFromPeer(dummyPeerWithGetter)
        assertEquals(0x0005ABCDL, extractedFromGetter, "Resolver must invoke getHWnd getter on peer")

        // Null and invalid peers must safely yield 0L
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(null))
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer("invalid_object"))

        // ---------------------------------------------------------------------
        // Step 6: SleepInhibitor (Acquire & Release Cycle)
        // ---------------------------------------------------------------------
        val defaultInhibitor = SleepInhibitor.createDefault()
        assertNotNull(defaultInhibitor, "SleepInhibitor.createDefault must not be null")

        // Test WindowsSleepInhibitor command provider and flags
        val winInhibitor = WindowsSleepInhibitor()
        assertEquals(
            WindowsSleepInhibitor.ES_CONTINUOUS or
                WindowsSleepInhibitor.ES_SYSTEM_REQUIRED or
                WindowsSleepInhibitor.ES_DISPLAY_REQUIRED,
            winInhibitor.flags,
            "Windows inhibitor default flags must require continuous system and display"
        )
        assertTrue(
            WindowsSleepInhibitor.defaultCommandProvider().any { it.contains("SetThreadExecutionState") },
            "Windows fallback command must reference SetThreadExecutionState"
        )

        // Test state transitions with simulated alive process command
        val controlledInhibitor = WindowsSleepInhibitor(commandProvider = { listOf("sleep", "60") })
        val acquired = controlledInhibitor.acquire()
        assertTrue(acquired, "Acquiring sleep inhibitor must succeed")
        assertTrue(controlledInhibitor.isInhibited, "Inhibitor must be active")

        val released = controlledInhibitor.release()
        assertTrue(released, "Releasing sleep inhibitor must succeed")
        assertFalse(controlledInhibitor.isInhibited, "Inhibitor must be deactivated")

        // ---------------------------------------------------------------------
        // Step 7: DesktopMediaControls (Metadata, Playback State & Event Routing)
        // ---------------------------------------------------------------------
        val mediaControls = DesktopMediaControls.createDefault()
        assertNotNull(mediaControls, "DesktopMediaControls.createDefault must return an instance")

        val episodeMetadata = MediaMetadata(
            title = cleanEpisodeFileName,
            artist = cleanSeriesFolder,
            album = "Season 2",
            posterUrl = "https://example.com/poster.jpg",
            durationMs = 1440000L
        )
        mediaControls.updateMetadata(episodeMetadata)

        val playbackState = MediaPlaybackState(
            isPlaying = true,
            positionMs = 15000L,
            speed = 1.0f
        )
        mediaControls.updatePlaybackState(playbackState)

        if (mediaControls is BaseDesktopMediaControls) {
            assertEquals(episodeMetadata.title, mediaControls.currentMetadata?.title)
            assertEquals(episodeMetadata.durationMs, mediaControls.currentMetadata?.durationMs)
            assertTrue(mediaControls.currentPlaybackState?.isPlaying == true)
            assertEquals(15000L, mediaControls.currentPlaybackState?.positionMs)
        }

        // Test Windows SMTC Controls event listener routing
        val smtc = WindowsSmtcControls()
        var onPlayDispatched = false
        var onPauseDispatched = false
        var seekPositionMs = -1L

        smtc.setListener(object : MediaEventListener {
            override fun onPlay() { onPlayDispatched = true }
            override fun onPause() { onPauseDispatched = true }
            override fun onToggle() {}
            override fun onNext() {}
            override fun onPrevious() {}
            override fun onSeek(positionMs: Long) { seekPositionMs = positionMs }
        })

        smtc.dispatchEvent("PLAY")
        assertTrue(onPlayDispatched, "PLAY event must be routed to listener")

        smtc.dispatchEvent("SEEK", "45000")
        assertEquals(45000L, seekPositionMs, "SEEK event must route target position")

        smtc.dispatchEvent("PAUSE")
        assertTrue(onPauseDispatched, "PAUSE event must be routed to listener")

        smtc.close()
        mediaControls.close()
    }

    // =========================================================================
    // 1. PlatformPaths: Dedicated Windows Tests
    // =========================================================================

    @Test
    @DisplayName("PlatformPaths: Standard and fallback Windows paths resolution")
    fun testPlatformPathsWindowsResolution() {
        val userHome = "C:\\Users\\DesktopUser"
        val env = mapOf(
            "APPDATA" to "C:\\Users\\DesktopUser\\AppData\\Roaming",
            "LOCALAPPDATA" to "C:\\Users\\DesktopUser\\AppData\\Local",
            "TEMP" to "C:\\Users\\DesktopUser\\AppData\\Local\\Temp"
        )

        val paths = PlatformPaths.resolvePaths(PlatformPaths.OS.WINDOWS, userHome, env)

        assertEquals("C:\\Users\\DesktopUser\\AppData\\Roaming\\CloudStream", paths.configDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Local\\CloudStream\\data", paths.dataDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Local\\CloudStream\\cache", paths.cacheDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Local\\Temp\\CloudStream", paths.runtimeDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\Downloads\\CloudStream", paths.downloadsDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Local\\Temp\\CloudStream\\plugins-shadow", paths.pluginsShadowDir.toString().replace('/', '\\'))

        // Empty environment fallback
        val fallbackPaths = PlatformPaths.resolvePaths(PlatformPaths.OS.WINDOWS, userHome, emptyMap())
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Roaming\\CloudStream", fallbackPaths.configDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Local\\CloudStream\\data", fallbackPaths.dataDir.toString().replace('/', '\\'))
        assertEquals("C:\\Users\\DesktopUser\\AppData\\Local\\Temp\\CloudStream", fallbackPaths.runtimeDir.toString().replace('/', '\\'))
    }

    // =========================================================================
    // 2. FileNameSanitizer: Dedicated Windows Tests
    // =========================================================================

    @Test
    @DisplayName("FileNameSanitizer: Comprehensive Windows NTFS & DOS device names")
    fun testFileNameSanitizerWindowsRules() {
        // Colon handling
        assertEquals("Re_Zero.mp4", FileNameSanitizer.sanitizeFileName("Re:Zero.mp4"))

        // Question mark handling
        assertEquals("What If.mp4", FileNameSanitizer.sanitizeFileName("What If...?.mp4"))

        // DOS Device Names (CON, PRN, AUX, NUL, COM1-9, LPT1-9)
        assertEquals("_CON.mp4", FileNameSanitizer.sanitizeFileName("CON.mp4"))
        assertEquals("_con.mkv", FileNameSanitizer.sanitizeFileName("con.mkv"))
        assertEquals("_AUX.mp4", FileNameSanitizer.sanitizeFileName("AUX.mp4"))
        assertEquals("_NUL.part", FileNameSanitizer.sanitizeFileName("NUL.part"))
        assertEquals("_COM1.txt", FileNameSanitizer.sanitizeFileName("COM1.txt"))
        assertEquals("_LPT1.log", FileNameSanitizer.sanitizeFileName("LPT1.log"))
        assertEquals("_PRN", FileNameSanitizer.sanitizeDirectoryName("PRN"))

        // Embedded device names must NOT be escaped
        assertEquals("Constantine.mp4", FileNameSanitizer.sanitizeFileName("Constantine.mp4"))
        assertEquals("Auxiliary.mp4", FileNameSanitizer.sanitizeFileName("Auxiliary.mp4"))

        // Compound extensions
        val (stem, ext) = FileNameSanitizer.splitNameAndExtension("video.mp4.part")
        assertEquals("video", stem)
        assertEquals(".mp4.part", ext)

        // Directory name sanitization
        assertEquals("Re_Zero_ Season 1", FileNameSanitizer.sanitizeDirectoryName("Re:Zero: Season 1?"))
    }

    // =========================================================================
    // 3. SafeFileOperations: Dedicated Move & Integrity Tests
    // =========================================================================

    @Test
    @DisplayName("SafeFileOperations: Cross-volume staging move and integrity checks")
    fun testSafeFileOperationsMoveAndIntegrity() {
        val srcDir = Files.createDirectory(tempDir.resolve("stage_dir"))
        val dstDir = Files.createDirectory(tempDir.resolve("final_dir"))

        val sourceFile = srcDir.resolve("source_media.dat")
        val targetFile = dstDir.resolve("nested").resolve("target_media.dat")

        val payload = "Critical Media Content Stream 2026".toByteArray()
        Files.write(sourceFile, payload)

        val moved = SafeFileOperations.safeMove(sourceFile, targetFile, verifyIntegrity = true)
        assertTrue(moved)
        assertFalse(Files.exists(sourceFile))
        assertTrue(Files.exists(targetFile))
        assertArrayEquals(payload, Files.readAllBytes(targetFile))

        // Non-existent source throws NoSuchFileException
        val missing = tempDir.resolve("does_not_exist.bin")
        assertThrows(NoSuchFileException::class.java) {
            SafeFileOperations.safeMove(missing, targetFile)
        }

        // Recursive directory deletion
        val testFolder = Files.createDirectory(tempDir.resolve("cleanup_target")).toFile()
        File(testFolder, "subfile.txt").writeText("hello")
        assertTrue(SafeFileOperations.safeDeleteRecursively(testFolder))
        assertFalse(testFolder.exists())
    }

    // =========================================================================
    // 4. PluginShadowManager: Dedicated Shadow Copy Tests
    // =========================================================================

    @Test
    @DisplayName("PluginShadowManager: Lock-free execution and hot update isolation")
    fun testPluginShadowManagerLockFreedom() {
        val originDir = tempDir.resolve("orig_plugins").toFile().apply { mkdirs() }
        val originJar = File(originDir, "ExtensionV1.jar")
        createTestPluginJar(originJar, "ExtensionV1", 1)

        val shadowJar1 = PluginShadowManager.createShadowCopy(originJar, "ExtensionV1")
        assertTrue(shadowJar1.exists())
        assertNotEquals(originJar.absolutePath, shadowJar1.absolutePath)

        // Cache hit test on identical file
        val shadowJarCacheHit = PluginShadowManager.createShadowCopy(originJar, "ExtensionV1")
        assertEquals(shadowJar1.absolutePath, shadowJarCacheHit.absolutePath)

        // Hot update test: overwrite original file with version 2
        createTestPluginJar(originJar, "ExtensionV1", 2)
        val shadowJar2 = PluginShadowManager.createShadowCopy(originJar, "ExtensionV1")
        assertNotEquals(shadowJar1.name, shadowJar2.name, "Modified JAR must yield distinct shadow filename")

        // Deletion of shadow copies by plugin name
        val deletedCount = PluginShadowManager.deleteShadowCopies(originJar, "ExtensionV1")
        assertTrue(deletedCount >= 1, "Should delete existing shadow copies for plugin")
    }

    // =========================================================================
    // 5. NativeWindowHandleResolver: Dedicated HWND Extraction Tests
    // =========================================================================

    @Test
    @DisplayName("NativeWindowHandleResolver: Win32 HWND and X11 Window handle resolution")
    fun testNativeWindowHandleResolverHwndExtraction() {
        // 1. Win32 peer with 'hwnd' field
        val win32Peer = DummyWin32Peer(hwnd = 0x12345678ABCDL)
        assertEquals(0x12345678ABCDL, NativeWindowHandleResolver.getHandleFromPeer(win32Peer))

        // 2. Win32 peer with 'getHWnd' method
        val getterPeer = DummyWin32GetterPeer(handle = 0x9876543210L)
        assertEquals(0x9876543210L, NativeWindowHandleResolver.getHandleFromPeer(getterPeer))

        // 3. Linux X11 peer with 'window' field
        val x11Peer = DummyLinuxPeer(window = 0x5555AAAA5555L)
        assertEquals(0x5555AAAA5555L, NativeWindowHandleResolver.getHandleFromPeer(x11Peer))

        // 4. Invalid handle values (<= 0) return 0L
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(DummyWin32Peer(hwnd = 0L)))
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(DummyWin32Peer(hwnd = -1L)))

        // 5. Unrelated objects and null
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(DummyUnrelatedPeer()))
        assertEquals(0L, NativeWindowHandleResolver.getHandleFromPeer(null))
    }

    // =========================================================================
    // 6. SleepInhibitor: Dedicated Win32 & No-Op Lifecycle Tests
    // =========================================================================

    @Test
    @DisplayName("SleepInhibitor: Win32 constants, state transitions and NoOp contracts")
    fun testSleepInhibitorLifecycleAndContracts() {
        assertEquals(0x00000001, WindowsSleepInhibitor.ES_SYSTEM_REQUIRED)
        assertEquals(0x00000002, WindowsSleepInhibitor.ES_DISPLAY_REQUIRED)
        assertEquals(0x00000040, WindowsSleepInhibitor.ES_AWAYMODE_REQUIRED)
        assertEquals(0x80000000.toInt(), WindowsSleepInhibitor.ES_CONTINUOUS)

        val noOp = NoOpSleepInhibitor(simulatedSuccess = true)
        assertFalse(noOp.isInhibited)
        assertTrue(noOp.acquire())
        assertTrue(noOp.isInhibited)
        assertTrue(noOp.release())
        assertFalse(noOp.isInhibited)
    }

    // =========================================================================
    // 7. DesktopMediaControls: Dedicated Metadata & Event Tests
    // =========================================================================

    @Test
    @DisplayName("DesktopMediaControls: Metadata broadcasting, playback states and event dispatch")
    fun testDesktopMediaControlsStreamingAndEvents() {
        val noOpControls = NoOpMediaControls()

        val meta = MediaMetadata(
            title = "Episode 1: The End of the Beginning",
            artist = "Studio White",
            album = "Season 1",
            durationMs = 1500000L
        )
        noOpControls.updateMetadata(meta)
        assertEquals(meta, noOpControls.currentMetadata)

        val state = MediaPlaybackState(
            isPlaying = true,
            positionMs = 32000L,
            speed = 1.25f
        )
        noOpControls.updatePlaybackState(state)
        assertEquals(state, noOpControls.currentPlaybackState)

        // Test event listener callbacks
        var toggled = false
        var nextClicked = false
        var prevClicked = false
        var seekPos = -1L

        noOpControls.setListener(object : MediaEventListener {
            override fun onPlay() {}
            override fun onPause() {}
            override fun onToggle() { toggled = true }
            override fun onNext() { nextClicked = true }
            override fun onPrevious() { prevClicked = true }
            override fun onSeek(positionMs: Long) { seekPos = positionMs }
        })

        noOpControls.dispatchEvent("TOGGLE")
        assertTrue(toggled)

        noOpControls.dispatchEvent("NEXT")
        assertTrue(nextClicked)

        noOpControls.dispatchEvent("PREVIOUS")
        assertTrue(prevClicked)

        noOpControls.dispatchEvent("SEEK", "64000")
        assertEquals(64000L, seekPos)

        // SEEK_OFFSET dispatch
        noOpControls.dispatchEvent("SEEK_OFFSET", "10000")
        assertEquals(42000L, seekPos, "SEEK_OFFSET must add offset to current playback position (32000 + 10000)")

        noOpControls.close()
    }

    // =========================================================================
    // REAL PEER DUMMY CLASSES FOR REFLECTION / UNSAFE RESOLUTION (Zero Mocks)
    // =========================================================================

    class DummyWin32Peer(val hwnd: Long)
    class DummyWin32GetterPeer(private val handle: Long) {
        fun getHWnd(): Long = handle
    }
    class DummyLinuxPeer(val window: Long)
    class DummyUnrelatedPeer(val info: String = "some_data")
}
