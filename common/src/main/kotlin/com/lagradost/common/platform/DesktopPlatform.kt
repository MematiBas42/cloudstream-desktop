package com.lagradost.common.platform

import com.lagradost.common.logging.AppLogger
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Cross-platform desktop integration utilities for CloudStream Desktop.
 * Provides safe URL opening, browser launching, and executable resolution
 * across Linux, Windows, and macOS.
 */
object DesktopPlatform {
    private const val TAG = "DesktopPlatform"

    /**
     * Cross-platform URL opener supporting Linux, Windows, and macOS.
     * Uses AWT Desktop.browse() first, falling back to OS-specific command line launchers:
     * - Windows: cmd.exe /c start "" <url>
     * - macOS: open <url>
     * - Linux / POSIX: xdg-open <url>
     *
     * @param url The URL or URI string to open.
     * @return true if the URL was successfully dispatched, false otherwise.
     */
    fun openUrl(url: String): Boolean {
        if (url.isBlank()) return false

        // 1. Try Java AWT Desktop.browse() / open()
        try {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (url.startsWith("file:/", ignoreCase = true)) {
                    val file = try {
                        File(URI.create(url))
                    } catch (_: Throwable) {
                        File(url.removePrefix("file://"))
                    }
                    if (file.exists() && desktop.isSupported(Desktop.Action.OPEN)) {
                        desktop.open(file)
                        return true
                    }
                }
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI.create(url))
                    return true
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "AWT Desktop open/browse failed for $url: ${e.message}")
        }

        // 2. OS-specific shell fallback
        return try {
            val process = when (PlatformPaths.currentOS) {
                PlatformPaths.OS.WINDOWS -> {
                    ProcessBuilder("cmd.exe", "/c", "start", "\"\"", url)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }
                PlatformPaths.OS.MACOS -> {
                    ProcessBuilder("open", url)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }
                PlatformPaths.OS.LINUX, PlatformPaths.OS.UNKNOWN -> {
                    ProcessBuilder("xdg-open", url)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                }
            }
            process != null
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Cross-platform shell openUrl failed for $url", t)
            false
        }
    }

    /**
     * Resolves an executable name to a runnable command or absolute path across platforms.
     * On Windows, appends .exe if missing and inspects standard directories.
     */
    fun resolveExecutable(binaryName: String): String {
        if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) {
            val exeName = if (binaryName.endsWith(".exe", ignoreCase = true)) binaryName else "$binaryName.exe"
            val winCandidates = listOf(
                "C:\\Program Files\\VideoLAN\\VLC\\$exeName",
                "C:\\Program Files (x86)\\VideoLAN\\VLC\\$exeName",
                "C:\\Program Files\\mpv\\$exeName",
                "C:\\Program Files (x86)\\mpv\\$exeName"
            )
            for (path in winCandidates) {
                val file = File(path)
                if (file.exists() && file.canExecute()) {
                    return file.absolutePath
                }
            }
            return exeName
        }
        return binaryName
    }

    /**
     * Checks if a binary exists in system PATH or common paths across Linux, macOS, and Windows.
     */
    fun isBinaryAvailable(binary: String): Boolean {
        val resolved = resolveExecutable(binary)
        if (File(resolved).isAbsolute) {
            return File(resolved).canExecute()
        }
        val pathEnv = System.getenv("PATH") ?: return false
        val exeVariants = if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) {
            listOf(resolved, "$resolved.exe", "$resolved.cmd", "$resolved.bat")
        } else {
            listOf(resolved)
        }
        return pathEnv.split(File.pathSeparator).any { dir ->
            exeVariants.any { variant ->
                val file = File(dir, variant)
                file.exists() && file.canExecute()
            }
        }
    }
}
