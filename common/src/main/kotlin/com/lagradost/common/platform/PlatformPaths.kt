package com.lagradost.common.platform

import com.lagradost.common.logging.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Enterprise Cross-Platform Path Resolver for CloudStream Desktop.
 *
 * Fully supports:
 * - Linux / BSD: Strict Freedesktop XDG Base Directory Specification ($XDG_*_HOME)
 * - Windows 10/11: Native %APPDATA%, %LOCALAPPDATA%, and %TEMP% standards
 * - macOS: Native Apple ~/Library conventions
 */
object PlatformPaths {

    enum class OS { LINUX, WINDOWS, MACOS, UNKNOWN }

    val currentOS: OS by lazy {
        val osName = System.getProperty("os.name")?.lowercase() ?: ""
        when {
            osName.contains("linux") || osName.contains("nix") || osName.contains("nux") -> OS.LINUX
            osName.contains("win") -> OS.WINDOWS
            osName.contains("mac") -> OS.MACOS
            else -> OS.UNKNOWN
        }
    }

    val userHome: String by lazy {
        resolveUserHome(currentOS, System.getProperty("user.home"), System.getenv())
    }

    fun resolveUserHome(os: OS, sysPropHome: String?, env: Map<String, String>): String {
        return sysPropHome?.takeIf { it.isNotBlank() }
            ?: (if (os == OS.WINDOWS) env["USERPROFILE"] else env["HOME"])
            ?: (if (os == OS.WINDOWS) "C:\\CloudStream" else "/tmp")
    }

    /**
     * Immutable bundle of resolved base application paths.
     */
    data class ResolvedPaths(
        val configDir: Path,
        val dataDir: Path,
        val cacheDir: Path,
        val runtimeDir: Path,
        val downloadsDir: Path,
        val pluginsShadowDir: Path = runtimeDir.resolve("plugins-shadow")
    )

    /**
     * Pure function to resolve base directories based on OS and environment variables.
     * Enables robust unit testing across platforms without environment mocking.
     */
    fun resolvePaths(
        os: OS,
        userHomeDir: String,
        env: Map<String, String>
    ): ResolvedPaths {
        return when (os) {
            OS.WINDOWS -> {
                val appData = env["APPDATA"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Paths.get(it) }
                    ?: Paths.get(userHomeDir, "AppData", "Roaming")

                val localAppData = env["LOCALAPPDATA"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Paths.get(it) }
                    ?: Paths.get(userHomeDir, "AppData", "Local")

                val temp = env["TEMP"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { Paths.get(it) }
                    ?: env["TMP"]?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
                    ?: localAppData.resolve("Temp")

                val downloads = Paths.get(userHomeDir, "Downloads", "CloudStream")

                ResolvedPaths(
                    configDir = appData.resolve("CloudStream"),
                    dataDir = localAppData.resolve("CloudStream").resolve("data"),
                    cacheDir = localAppData.resolve("CloudStream").resolve("cache"),
                    runtimeDir = temp.resolve("CloudStream"),
                    downloadsDir = downloads,
                    pluginsShadowDir = temp.resolve("CloudStream").resolve("plugins-shadow")
                )
            }
            OS.MACOS -> {
                val homePath = Paths.get(userHomeDir)
                val appSupport = homePath.resolve("Library").resolve("Application Support").resolve("CloudStream")
                val caches = homePath.resolve("Library").resolve("Caches").resolve("CloudStream")

                ResolvedPaths(
                    configDir = appSupport,
                    dataDir = appSupport.resolve("data"),
                    cacheDir = caches,
                    runtimeDir = caches.resolve("runtime"),
                    downloadsDir = homePath.resolve("Downloads").resolve("CloudStream"),
                    pluginsShadowDir = caches.resolve("plugins-shadow")
                )
            }
            OS.LINUX, OS.UNKNOWN -> {
                fun resolveXdg(envVar: String, defaultFallback: String): Path {
                    val envVal = env[envVar]?.trim()
                    return if (!envVal.isNullOrEmpty()) {
                        Paths.get(envVal)
                    } else {
                        Paths.get(defaultFallback)
                    }
                }

                val runtime = resolveXdg("XDG_RUNTIME_DIR", "/tmp").resolve("cloudstream")

                ResolvedPaths(
                    configDir = resolveXdg("XDG_CONFIG_HOME", "$userHomeDir/.config").resolve("cloudstream"),
                    dataDir = resolveXdg("XDG_DATA_HOME", "$userHomeDir/.local/share").resolve("cloudstream"),
                    cacheDir = resolveXdg("XDG_CACHE_HOME", "$userHomeDir/.cache").resolve("cloudstream"),
                    runtimeDir = runtime,
                    downloadsDir = Paths.get(userHomeDir, "Downloads", "CloudStream"),
                    pluginsShadowDir = runtime.resolve("plugins-shadow")
                )
            }
        }
    }

    private val resolved: ResolvedPaths by lazy {
        resolvePaths(currentOS, userHome, System.getenv())
    }

    // --- Core Base Directories ---

    val configDir: Path get() = resolved.configDir
    val dataDir: Path get() = resolved.dataDir
    val cacheDir: Path get() = resolved.cacheDir
    val runtimeDir: Path get() = resolved.runtimeDir
    val downloadsDir: Path get() = resolved.downloadsDir

    // --- Subdirectories ---

    val pluginsDir: Path get() = dataDir.resolve("plugins")
    val pluginsShadowDir: Path get() = resolved.pluginsShadowDir
    val pluginPrefsDir: Path get() = configDir.resolve("plugin_prefs")
    val transpiledCacheDir: Path get() = cacheDir.resolve("transpiled-cache")
    val imageCacheDir: Path get() = cacheDir.resolve("images")
    val socketDir: Path get() = runtimeDir.resolve("sockets")

    // Auxiliary directories
    val logsDir: Path get() = cacheDir.resolve("logs")
    val backupsDir: Path get() = dataDir.resolve("backups")

    // Compatibility aliases for legacy shims and storage layers
    val logDir: Path get() = logsDir
    val extensionsDir: Path get() = pluginsDir
    val sharedPrefsDir: Path get() = pluginPrefsDir
    val filesDir: Path get() = dataDir
    val appDataDir: File get() = dataDir.toFile()

    /**
     * Creates all application directories.
     * On POSIX systems, applies 0700 to runtime directory and 0755 to application directories.
     * On Windows (NTFS), relies on native inherited user ACLs without throwing redundant exceptions.
     */
    fun init() {
        val directories = listOf(
            configDir,
            dataDir,
            cacheDir,
            runtimeDir,
            pluginsDir,
            pluginsShadowDir,
            pluginPrefsDir,
            transpiledCacheDir,
            imageCacheDir,
            socketDir,
            logsDir,
            downloadsDir,
            backupsDir
        )

        for (dir in directories) {
            ensureDirectoryCreated(
                path = dir,
                isRuntime = (dir == runtimeDir || dir.startsWith(runtimeDir))
            )
        }

        AppLogger.i(
            "PlatformPaths",
            "Initialized ${currentOS.name} platform directories (config=$configDir, data=$dataDir, cache=$cacheDir, runtime=$runtimeDir)"
        )
    }

    private fun ensureDirectoryCreated(path: Path, isRuntime: Boolean) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path)
            }
        } catch (_: Exception) {
            path.toFile().mkdirs()
        }

        // Apply POSIX permissions only if the underlying filesystem supports them and we are not on Windows
        if (currentOS != OS.WINDOWS) {
            try {
                if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                    val permissions: Set<PosixFilePermission> = if (isRuntime) {
                        PosixFilePermissions.fromString("rwx------")
                    } else {
                        PosixFilePermissions.fromString("rwxr-xr-x")
                    }
                    Files.setPosixFilePermissions(path, permissions)
                }
            } catch (_: UnsupportedOperationException) {
                // Ignore if filesystem does not support POSIX view
            } catch (_: SecurityException) {
                // Ignore if restricted by security manager
            } catch (e: Exception) {
                AppLogger.w("PlatformPaths", "Could not set POSIX permissions on $path: ${e.message}")
            }
        }
    }
}
