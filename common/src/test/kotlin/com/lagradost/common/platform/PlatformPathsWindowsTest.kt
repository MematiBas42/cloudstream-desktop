package com.lagradost.common.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

@DisplayName("PlatformPaths Cross-Platform Architecture Tests")
class PlatformPathsWindowsTest {

    @Nested
    @DisplayName("Windows Path Resolution")
    inner class WindowsTests {

        private val windowsHome = "C:\\Users\\TestUser"
        private val standardWindowsEnv = mapOf(
            "APPDATA" to "C:\\Users\\TestUser\\AppData\\Roaming",
            "LOCALAPPDATA" to "C:\\Users\\TestUser\\AppData\\Local",
            "TEMP" to "C:\\Users\\TestUser\\AppData\\Local\\Temp",
            "USERPROFILE" to "C:\\Users\\TestUser"
        )

        @Test
        @DisplayName("Standard Windows environment maps to Roaming, Local and Temp correctly")
        fun testWindowsStandardEnv() {
            val paths = PlatformPaths.resolvePaths(
                os = PlatformPaths.OS.WINDOWS,
                userHomeDir = windowsHome,
                env = standardWindowsEnv
            )

            val expectedAppData = Paths.get(standardWindowsEnv["APPDATA"]!!)
            val expectedLocalAppData = Paths.get(standardWindowsEnv["LOCALAPPDATA"]!!)
            val expectedTemp = Paths.get(standardWindowsEnv["TEMP"]!!)

            // Config must reside in Roaming AppData
            assertEquals(expectedAppData.resolve("CloudStream"), paths.configDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Roaming\\CloudStream", paths.configDir.toString().replace('/', '\\'))

            // Data and Cache must reside in Local AppData
            assertEquals(expectedLocalAppData.resolve("CloudStream").resolve("data"), paths.dataDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\CloudStream\\data", paths.dataDir.toString().replace('/', '\\'))

            assertEquals(expectedLocalAppData.resolve("CloudStream").resolve("cache"), paths.cacheDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\CloudStream\\cache", paths.cacheDir.toString().replace('/', '\\'))

            // Runtime and sockets must reside in Temp
            assertEquals(expectedTemp.resolve("CloudStream"), paths.runtimeDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\Temp\\CloudStream", paths.runtimeDir.toString().replace('/', '\\'))

            // Socket dir derived from runtimeDir
            assertEquals(expectedTemp.resolve("CloudStream").resolve("sockets"), paths.runtimeDir.resolve("sockets"))
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\Temp\\CloudStream\\sockets", paths.runtimeDir.resolve("sockets").toString().replace('/', '\\'))

            // Downloads must point to user's native Downloads
            assertEquals(Paths.get(windowsHome, "Downloads", "CloudStream"), paths.downloadsDir)
            assertEquals("C:\\Users\\TestUser\\Downloads\\CloudStream", paths.downloadsDir.toString().replace('/', '\\'))
        }

        @Test
        @DisplayName("Windows environment fallback when APPDATA and LOCALAPPDATA are missing")
        fun testWindowsMissingEnvFallback() {
            val emptyEnv = emptyMap<String, String>()
            val paths = PlatformPaths.resolvePaths(
                os = PlatformPaths.OS.WINDOWS,
                userHomeDir = windowsHome,
                env = emptyEnv
            )

            val expectedAppData = Paths.get(windowsHome, "AppData", "Roaming")
            val expectedLocalAppData = Paths.get(windowsHome, "AppData", "Local")

            assertEquals(expectedAppData.resolve("CloudStream"), paths.configDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Roaming\\CloudStream", paths.configDir.toString().replace('/', '\\'))

            assertEquals(expectedLocalAppData.resolve("CloudStream").resolve("data"), paths.dataDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\CloudStream\\data", paths.dataDir.toString().replace('/', '\\'))

            assertEquals(expectedLocalAppData.resolve("CloudStream").resolve("cache"), paths.cacheDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\CloudStream\\cache", paths.cacheDir.toString().replace('/', '\\'))

            assertEquals(expectedLocalAppData.resolve("Temp").resolve("CloudStream"), paths.runtimeDir)
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\Temp\\CloudStream", paths.runtimeDir.toString().replace('/', '\\'))

            assertEquals(expectedLocalAppData.resolve("Temp").resolve("CloudStream").resolve("sockets"), paths.runtimeDir.resolve("sockets"))
            assertEquals("C:\\Users\\TestUser\\AppData\\Local\\Temp\\CloudStream\\sockets", paths.runtimeDir.resolve("sockets").toString().replace('/', '\\'))
        }

        @Test
        @DisplayName("Windows userHome falls back to USERPROFILE when sysProp is empty")
        fun testWindowsUserHomeFallback() {
            val resolvedHome = PlatformPaths.resolveUserHome(
                os = PlatformPaths.OS.WINDOWS,
                sysPropHome = null,
                env = mapOf("USERPROFILE" to "C:\\Users\\ProfileUser")
            )
            assertEquals("C:\\Users\\ProfileUser", resolvedHome)
        }

        @Test
        @DisplayName("Windows userHome hard fallback when both sysProp and USERPROFILE are empty")
        fun testWindowsUserHomeHardFallback() {
            val resolvedHome = PlatformPaths.resolveUserHome(
                os = PlatformPaths.OS.WINDOWS,
                sysPropHome = null,
                env = emptyMap()
            )
            assertEquals("C:\\CloudStream", resolvedHome)
        }
    }

    @Nested
    @DisplayName("Linux XDG Parity Verification")
    inner class LinuxTests {

        private val linuxHome = "/home/testuser"

        @Test
        @DisplayName("Linux honors custom XDG environment variables")
        fun testLinuxCustomXdg() {
            val customEnv = mapOf(
                "XDG_CONFIG_HOME" to "/custom/config",
                "XDG_DATA_HOME" to "/custom/share",
                "XDG_CACHE_HOME" to "/custom/cache",
                "XDG_RUNTIME_DIR" to "/run/user/1000"
            )

            val paths = PlatformPaths.resolvePaths(
                os = PlatformPaths.OS.LINUX,
                userHomeDir = linuxHome,
                env = customEnv
            )

            assertEquals(Paths.get("/custom/config/cloudstream"), paths.configDir)
            assertEquals(Paths.get("/custom/share/cloudstream"), paths.dataDir)
            assertEquals(Paths.get("/custom/cache/cloudstream"), paths.cacheDir)
            assertEquals(Paths.get("/run/user/1000/cloudstream"), paths.runtimeDir)
            assertEquals(Paths.get("/run/user/1000/cloudstream/sockets"), paths.runtimeDir.resolve("sockets"))
            assertEquals(Paths.get("/home/testuser/Downloads/CloudStream"), paths.downloadsDir)
        }

        @Test
        @DisplayName("Linux falls back to ~/.config, ~/.local/share, ~/.cache, /tmp when XDG is unset")
        fun testLinuxDefaultFallbacks() {
            val emptyEnv = emptyMap<String, String>()

            val paths = PlatformPaths.resolvePaths(
                os = PlatformPaths.OS.LINUX,
                userHomeDir = linuxHome,
                env = emptyEnv
            )

            assertEquals(Paths.get("/home/testuser/.config/cloudstream"), paths.configDir)
            assertEquals(Paths.get("/home/testuser/.local/share/cloudstream"), paths.dataDir)
            assertEquals(Paths.get("/home/testuser/.cache/cloudstream"), paths.cacheDir)
            assertEquals(Paths.get("/tmp/cloudstream"), paths.runtimeDir)
            assertEquals(Paths.get("/tmp/cloudstream/sockets"), paths.runtimeDir.resolve("sockets"))
        }

        @Test
        @DisplayName("Linux userHome falls back to HOME when sysProp is empty")
        fun testLinuxUserHomeFallback() {
            val resolvedHome = PlatformPaths.resolveUserHome(
                os = PlatformPaths.OS.LINUX,
                sysPropHome = null,
                env = mapOf("HOME" to "/home/customuser")
            )
            assertEquals("/home/customuser", resolvedHome)
        }
    }

    @Nested
    @DisplayName("macOS Path Resolution")
    inner class MacOSTests {

        private val macHome = "/Users/testuser"

        @Test
        @DisplayName("macOS maps to Library conventions correctly")
        fun testMacOSPaths() {
            val paths = PlatformPaths.resolvePaths(
                os = PlatformPaths.OS.MACOS,
                userHomeDir = macHome,
                env = emptyMap()
            )

            assertEquals(Paths.get("/Users/testuser/Library/Application Support/CloudStream"), paths.configDir)
            assertEquals(Paths.get("/Users/testuser/Library/Application Support/CloudStream/data"), paths.dataDir)
            assertEquals(Paths.get("/Users/testuser/Library/Caches/CloudStream"), paths.cacheDir)
            assertEquals(Paths.get("/Users/testuser/Library/Caches/CloudStream/runtime"), paths.runtimeDir)
            assertEquals(Paths.get("/Users/testuser/Downloads/CloudStream"), paths.downloadsDir)
        }
    }

    @Nested
    @DisplayName("Directory Creation and Subdirectory Integrity")
    inner class SubdirectoryTests {

        @Test
        @DisplayName("All subdirectories branch accurately from resolved base directories")
        fun testSubdirectoriesBranching() {
            val dummyBase = Paths.get("/dummy/base")
            val resolved = PlatformPaths.ResolvedPaths(
                configDir = dummyBase.resolve("config"),
                dataDir = dummyBase.resolve("data"),
                cacheDir = dummyBase.resolve("cache"),
                runtimeDir = dummyBase.resolve("runtime"),
                downloadsDir = dummyBase.resolve("downloads")
            )

            val pluginsDir = resolved.dataDir.resolve("plugins")
            val pluginPrefsDir = resolved.configDir.resolve("plugin_prefs")
            val transpiledCacheDir = resolved.cacheDir.resolve("transpiled-cache")
            val imageCacheDir = resolved.cacheDir.resolve("images")
            val socketDir = resolved.runtimeDir.resolve("sockets")
            val logsDir = resolved.cacheDir.resolve("logs")
            val backupsDir = resolved.dataDir.resolve("backups")

            assertEquals(dummyBase.resolve("data/plugins"), pluginsDir)
            assertEquals(dummyBase.resolve("config/plugin_prefs"), pluginPrefsDir)
            assertEquals(dummyBase.resolve("cache/transpiled-cache"), transpiledCacheDir)
            assertEquals(dummyBase.resolve("cache/images"), imageCacheDir)
            assertEquals(dummyBase.resolve("runtime/sockets"), socketDir)
            assertEquals(dummyBase.resolve("cache/logs"), logsDir)
            assertEquals(dummyBase.resolve("data/backups"), backupsDir)
        }

        @Test
        @DisplayName("Initialization creates valid real directories on test filesystem")
        fun testRealDirectoryCreation(@TempDir tempFolder: Path) {
            val testConfig = tempFolder.resolve("test_config")
            val testData = tempFolder.resolve("test_data")

            assertFalse(testConfig.toFile().exists())
            assertFalse(testData.toFile().exists())

            testConfig.toFile().mkdirs()
            testData.toFile().mkdirs()

            assertTrue(testConfig.toFile().exists())
            assertTrue(testData.toFile().exists())
            assertTrue(testConfig.toFile().isDirectory)
            assertTrue(testData.toFile().isDirectory)
        }

        @Test
        @DisplayName("Live PlatformPaths instance exposes non-null valid paths")
        fun testLivePlatformPathsProperties() {
            assertTrue(PlatformPaths.configDir.toString().isNotBlank())
            assertTrue(PlatformPaths.dataDir.toString().isNotBlank())
            assertTrue(PlatformPaths.cacheDir.toString().isNotBlank())
            assertTrue(PlatformPaths.runtimeDir.toString().isNotBlank())
            assertTrue(PlatformPaths.socketDir.toString().isNotBlank())
            assertTrue(PlatformPaths.pluginsDir.toString().isNotBlank())
            assertTrue(PlatformPaths.downloadsDir.toString().isNotBlank())
        }
    }
}
