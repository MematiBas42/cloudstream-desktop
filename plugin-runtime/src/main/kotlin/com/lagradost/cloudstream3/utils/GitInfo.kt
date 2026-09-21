// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/GitInfo.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.content.Context
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Simple helper to get the short commit hash from assets.
 * The hash is generated at build and stored as an asset
 * that can be accessed at runtime for Gradle
 * configuration cache support.
 */
object GitInfo {

    /**
     * Upstream 1:1 parity extension on Context.
     */
    fun Context.currentCommitHash(): String {
        try {
            val stream: InputStream? = assets.open("git-hash.txt")
            val text = stream?.bufferedReader()?.readText()?.trim()
            if (!text.isNullOrEmpty()) {
                return text.take(7)
            }
        } catch (e: Throwable) {
            // Fall through to general resolution
        }
        return GitInfo.currentCommitHash()
    }

    /**
     * Resolves short commit hash for desktop / headless environments without requiring a Context.
     */
    fun currentCommitHash(): String {
        // 1. Try reading from classpath resources
        try {
            val resourceStream = GitInfo::class.java.classLoader?.getResourceAsStream("git-hash.txt")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("git-hash.txt")
                ?: ClassLoader.getSystemResourceAsStream("git-hash.txt")

            val text = resourceStream?.bufferedReader()?.readText()?.trim()
            if (!text.isNullOrEmpty()) {
                return text.take(7)
            }
        } catch (e: Throwable) {
            // Ignore and fall through to git directory inspection
        }

        // 2. Try reading from local .git directory directly
        try {
            var dir: File? = File(".").canonicalFile
            while (dir != null) {
                val gitDir = File(dir, ".git")
                if (gitDir.exists()) {
                    val headFile = if (gitDir.isDirectory) File(gitDir, "HEAD") else null
                    if (headFile != null && headFile.exists()) {
                        val headContent = headFile.readText().trim()
                        if (headContent.startsWith("ref:")) {
                            val refPath = headContent.substring(4).trim()
                            val refFile = File(gitDir, refPath)
                            if (refFile.exists()) {
                                val hash = refFile.readText().trim()
                                if (hash.isNotEmpty()) return hash.take(7)
                            }
                        } else if (headContent.isNotEmpty()) {
                            return headContent.take(7)
                        }
                    }
                    break
                }
                dir = dir.parentFile
            }
        } catch (e: Throwable) {
            // Ignore and fall through to ProcessBuilder
        }

        // 3. Try git rev-parse execution
        try {
            val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val text = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && text.isNotEmpty()) {
                return text.take(7)
            }
        } catch (e: Throwable) {
            // Final fallback when no git info can be determined
        }

        return ""
    }

    /**
     * Reads embedded build timestamp in milliseconds.
     */
    fun getBuildDate(): Long {
        try {
            val resourceStream = GitInfo::class.java.classLoader?.getResourceAsStream("build-time.txt")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("build-time.txt")
                ?: ClassLoader.getSystemResourceAsStream("build-time.txt")

            val text = resourceStream?.bufferedReader()?.readText()?.trim()
            val parsed = text?.toLongOrNull()
            if (parsed != null && parsed > 0L) {
                return parsed
            }
        } catch (e: Throwable) {
            // Fall through to system time
        }
        return System.currentTimeMillis()
    }

    /**
     * Returns formatted UTC build timestamp string.
     */
    fun getBuildTimestamp(): String {
        return try {
            val date = Date(getBuildDate())
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.format(date)
        } catch (e: Throwable) {
            ""
        }
    }
}
