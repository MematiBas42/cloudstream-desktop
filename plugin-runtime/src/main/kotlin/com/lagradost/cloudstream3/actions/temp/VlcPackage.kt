// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/VlcPackage.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.makeTempM3U8Intent
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// https://github.com/videolan/vlc-android/blob/3706c4be2da6800b3d26344fc04fab03ffa4b860/application/vlc-android/src/org/videolan/vlc/gui/video/VideoPlayerActivity.kt#L1898
// https://wiki.videolan.org/Android_Player_Intents/

class VlcNightlyPackage : VlcPackage() {
    override val packageName = "org.videolan.vlc.debug"
    override val appName = txt("VLC Nightly")
    override val executableName = "vlc-nightly"
}

open class VlcPackage : OpenInAppAction(
    appName = txt("VLC"),
    packageName = "org.videolan.vlc",
    intentClass = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        "org.videolan.vlc.gui.video.VideoPlayerActivity"
    } else {
        null
    },
    action = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        "org.videolan.vlc.player.result"
    } else {
        Intent.ACTION_VIEW
    }
) {
    open val executableName: String = "vlc"

    // while VLC supports multi links, it has poor support, so we disable it for now
    override val oneSource = true

    companion object {
        /**
         * Cross-platform resolver that finds the executable file for the given [binary]
         * across Linux, macOS, and Windows.
         *
         * Inspects:
         * 1. Direct path / absolute path if provided.
         * 2. The system PATH environment variable (inspecting .exe on Windows).
         * 3. Linux/Unix standard paths: /usr/bin, /usr/local/bin, /bin, /snap/bin, Flatpak exports, ~/.local/bin.
         * 4. Windows standard paths: Program Files, Program Files (x86), ProgramW6432, LOCALAPPDATA.
         * 5. macOS standard paths: /Applications/VLC.app/Contents/MacOS, ~/Applications/..., /opt/homebrew/bin.
         */
        fun resolveExecutable(
            binary: String,
            osName: String = System.getProperty("os.name") ?: "",
            pathEnv: String? = System.getenv("PATH"),
            env: Map<String, String> = System.getenv()
        ): File? {
            if (binary.isBlank()) return null

            val isWindows = osName.contains("Windows", ignoreCase = true)
            val isMac = osName.contains("Mac", ignoreCase = true)

            // Direct check if binary is an absolute path or already points to a valid file
            val directFile = File(binary)
            if (directFile.isAbsolute) {
                if (directFile.isFile && (isWindows || directFile.canExecute())) {
                    return directFile
                }
                if (isWindows && !binary.endsWith(".exe", ignoreCase = true)) {
                    val exeFile = File("$binary.exe")
                    if (exeFile.isFile) return exeFile
                }
            }

            val lowerBinary = binary.lowercase()
            val candidateNames = if (isWindows) {
                val names = mutableListOf<String>()
                if (binary.endsWith(".exe", ignoreCase = true)) {
                    names.add(binary)
                } else {
                    names.add("$binary.exe")
                    names.add(binary)
                }
                if (lowerBinary == "vlc-nightly") {
                    names.add("vlc.exe")
                }
                names
            } else {
                listOf(binary)
            }

            // 1. Inspect directories in PATH
            val pathDirs = pathEnv?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.map { File(it.trim()) }
                ?: emptyList()

            for (dir in pathDirs) {
                for (name in candidateNames) {
                    val file = File(dir, name)
                    if (file.isFile && (isWindows || file.canExecute())) {
                        return file
                    }
                }
            }

            // 2. Inspect platform-specific well-known directories
            if (isWindows) {
                val programFiles = env["ProgramFiles"] ?: "C:\\Program Files"
                val programFilesX86 = env["ProgramFiles(x86)"] ?: env["ProgramFiles"] ?: "C:\\Program Files (x86)"
                val programW6432 = env["ProgramW6432"] ?: programFiles
                val localAppData = env["LOCALAPPDATA"] ?: ""
                val systemDrive = env["SystemDrive"] ?: "C:"

                val windowsKnownDirs = mutableListOf<File>()

                if (lowerBinary.contains("vlc")) {
                    windowsKnownDirs.add(File(programFiles, "VideoLAN\\VLC"))
                    windowsKnownDirs.add(File(programFilesX86, "VideoLAN\\VLC"))
                    windowsKnownDirs.add(File(programW6432, "VideoLAN\\VLC"))
                    windowsKnownDirs.add(File(programFiles, "VideoLAN\\VLC Nightly"))
                    windowsKnownDirs.add(File(programFilesX86, "VideoLAN\\VLC Nightly"))
                    if (localAppData.isNotBlank()) {
                        windowsKnownDirs.add(File(localAppData, "Programs\\VideoLAN\\VLC"))
                        windowsKnownDirs.add(File(localAppData, "Programs\\VideoLAN\\VLC Nightly"))
                    }
                } else if (lowerBinary.contains("mpv")) {
                    windowsKnownDirs.add(File(programFiles, "mpv"))
                    windowsKnownDirs.add(File(programFilesX86, "mpv"))
                    windowsKnownDirs.add(File("$systemDrive\\mpv"))
                    if (localAppData.isNotBlank()) {
                        windowsKnownDirs.add(File(localAppData, "Programs\\mpv"))
                    }
                }

                windowsKnownDirs.add(File(programFiles))
                windowsKnownDirs.add(File(programFilesX86))

                for (dir in windowsKnownDirs) {
                    for (name in candidateNames) {
                        val file = File(dir, name)
                        if (file.isFile) {
                            return file
                        }
                    }
                }
            } else if (isMac) {
                val userHome = System.getProperty("user.home") ?: ""
                val macKnownDirs = mutableListOf(
                    File("/Applications/VLC.app/Contents/MacOS"),
                    File("$userHome/Applications/VLC.app/Contents/MacOS"),
                    File("/opt/homebrew/bin"),
                    File("/usr/local/bin"),
                    File("/usr/bin"),
                    File("/bin")
                )

                for (dir in macKnownDirs) {
                    for (name in candidateNames) {
                        val file = File(dir, name)
                        if (file.isFile && file.canExecute()) {
                            return file
                        }
                    }
                }
            } else {
                // Linux / Unix well-known directories:
                // /usr/bin/vlc, /usr/local/bin/vlc, /bin, /snap/bin, Flatpak, ~/.local/bin
                val userHome = System.getProperty("user.home") ?: ""
                val unixKnownDirs = listOf(
                    File("/usr/bin"),
                    File("/usr/local/bin"),
                    File("/bin"),
                    File("/snap/bin"),
                    File("/var/lib/flatpak/exports/bin"),
                    File("$userHome/.local/bin"),
                    File("$userHome/.local/share/flatpak/exports/bin")
                )

                for (dir in unixKnownDirs) {
                    for (name in candidateNames) {
                        val file = File(dir, name)
                        if (file.isFile && file.canExecute()) {
                            return file
                        }
                    }
                }
            }

            return null
        }

        fun resolveExecutablePath(
            binary: String,
            osName: String = System.getProperty("os.name") ?: "",
            pathEnv: String? = System.getenv("PATH"),
            env: Map<String, String> = System.getenv()
        ): String? = resolveExecutable(binary, osName, pathEnv, env)?.absolutePath

        fun isBinaryInPath(binary: String): Boolean = resolveExecutable(binary) != null
    }

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        return isBinaryInPath(executableName)
    }

    override suspend fun putExtra(
        context: Context,
        intent: Intent,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (index != null) {
            intent.setDataAndType(result.links[index].url.toUri(), "video/*")
        } else {
            makeTempM3U8Intent(context, intent, result)
        }
        val position = getViewPos(video.id)?.position ?: 0L

        intent.putExtra("from_start", false)
        intent.putExtra("position", position)
        intent.putExtra("secure_uri", true)
        intent.putExtra("title", video.name)

        val subsLang = getKey<String>(SUBTITLE_AUTO_SELECT_KEY) ?: "en"
        result.subs.firstOrNull {
            subsLang == it.languageCode
        }?.let {
            intent.putExtra("subtitles_location", it.url)
        }
    }

    open fun buildVlcArgs(
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?,
        context: Context? = null
    ): List<String> {
        val selectedLink = if (index != null && index in result.links.indices) {
            result.links[index]
        } else {
            result.links.firstOrNull()
        } ?: return emptyList()

        val resolvedExec = resolveExecutablePath(executableName) ?: executableName
        val args = mutableListOf(resolvedExec)

        // Target URL or temporary multi-source M3U8 playlist
        val targetUrl: String = if (index == null && result.links.size > 1) {
            try {
                val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir"))
                val outputFile = File.createTempFile("vlc_mirrorlist_", ".m3u8", cacheDir)
                outputFile.deleteOnExit()
                outputFile.writeText(ViewM3U8Action.buildM3U8Playlist(result))
                outputFile.absolutePath
            } catch (t: Throwable) {
                Log.e("VLC", "Failed to create temp M3U8 playlist, falling back to first link: ${t.message}")
                selectedLink.url
            }
        } else {
            selectedLink.url
        }
        args.add(targetUrl)

        // Extra mappings:
        // Title → --meta-title=
        if (!video.name.isNullOrBlank()) {
            args.add("--meta-title=${video.name}")
        }

        // Resume position → --start-time=<seconds>
        val positionMs = getViewPos(video.id)?.position ?: 0L
        val positionSec = (positionMs / 1000L).coerceAtLeast(0L)
        if (positionSec > 0) {
            args.add("--start-time=$positionSec")
        }

        // Subtitles → --sub-file=
        val subsLang = getKey<String>(SUBTITLE_AUTO_SELECT_KEY) ?: "en"
        val distinctSubs = result.subs.filter { it.url.isNotBlank() }.distinctBy { it.url }
        val selectedSub = distinctSubs.firstOrNull { subsLang == it.languageCode } ?: distinctSubs.firstOrNull()
        if (selectedSub != null) {
            args.add("--sub-file=${selectedSub.url}")
        }
        for (sub in distinctSubs) {
            if (sub != selectedSub) {
                args.add("--sub-file=${sub.url}")
            }
        }

        // HTTP referer → :http-referrer= and --http-referrer=
        val referer = selectedLink.referer.ifBlank {
            selectedLink.headers["referer"] ?: selectedLink.headers["Referer"]
        }
        if (!referer.isNullOrBlank()) {
            args.add(":http-referrer=$referer")
            args.add("--http-referrer=$referer")
        }

        // User-Agent → :http-user-agent= and --http-user-agent=
        val userAgent = selectedLink.headers["user-agent"] ?: selectedLink.headers["User-Agent"]
        if (!userAgent.isNullOrBlank()) {
            args.add(":http-user-agent=$userAgent")
            args.add("--http-user-agent=$userAgent")
        }

        return args
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        setKey("last_opened", video)
        val args = buildVlcArgs(video, result, index, context)
        if (args.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                ProcessBuilder(args)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (t: Throwable) {
                Log.e("VLC", "Failed to start VLC process with args $args: ${t.message}")
            }
        }
    }

    @PlatformQuarantine(
        reason = "VLC CLI process on Linux does not report playback position/duration back via Intent result",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/VlcPackage.kt:71",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    override fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getLongExtra("extra_position", -1) ?: -1
        val duration = intent?.getLongExtra("extra_duration", -1) ?: -1
        Log.d("VLC", "Position: $position, Duration: $duration")
        updateDurationAndPosition(position, duration)
    }
}
