// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt", upstreamCommit = "caeec18")
package com.lagradost.player.impl

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.player.process.ClearKeyDrmHandler
import java.io.File

/**
 * Handles building command-line arguments and launching the system MPV binary
 * with hardware acceleration, Unix domain socket IPC, and resilience flags.
 */
object MpvProcessLauncher {

    fun findMpvExecutable(osName: String = System.getProperty("os.name") ?: ""): String? {
        val os = osName.lowercase()
        val names = if (os.contains("win")) listOf("mpv.exe") else listOf("mpv")
        val pathDirs = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        for (dir in pathDirs) {
            for (name in names) {
                val file = File(dir, name)
                if (file.isFile && file.canExecute()) {
                    return file.absolutePath
                }
            }
        }
        val winFallbacks = listOfNotNull(
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs\\mpv\\mpv.exe" },
            System.getenv("ProgramFiles")?.let { "$it\\mpv\\mpv.exe" },
            System.getenv("ProgramFiles(x86)")?.let { "$it\\mpv\\mpv.exe" },
            System.getenv("USERPROFILE")?.let { "$it\\scoop\\shims\\mpv.exe" },
            "C:\\ProgramData\\chocolatey\\bin\\mpv.exe",
            "C:\\tools\\mpv\\mpv.exe",
            "C:\\mpv\\mpv.exe",
        )
        val posixFallbacks = listOf(
            "/usr/bin/mpv",
            "/usr/local/bin/mpv",
            "/opt/homebrew/bin/mpv",
            "/Applications/mpv.app/Contents/MacOS/mpv",
        )
        val fallbacks = if (os.contains("win")) winFallbacks else posixFallbacks
        return fallbacks.firstOrNull { File(it).isFile && File(it).canExecute() }
    }

    /**
     * Builds the complete MPV CLI command argument list according to architectural specifications.
     * When [wid] is provided (> 0), MPV attaches its video output directly to the existing
     * native window (AWT Canvas/SwingPanel), and suppresses its own keyboard bindings
     * so that Compose Desktop retains full input focus.
     */
    fun buildCommandLine(
        executable: String,
        socketPath: String,
        mediaUrl: String,
        title: String? = null,
        startSec: Long = 0L,
        userAgent: String? = null,
        referrer: String? = null,
        headers: Map<String, String> = emptyMap(),
        subtitles: List<String> = emptyList(),
        link: ExtractorLink? = null,
        extraArgs: List<String> = emptyList(),
        wid: Long? = null,
        osName: String = System.getProperty("os.name") ?: "",
    ): List<String> {
        val isWin = osName.lowercase().contains("win")
        val hwdecFlag = if (isWin) "--hwdec=d3d11va" else "--hwdec=auto-safe"
        val args = mutableListOf(
            executable,
            "--input-ipc-server=$socketPath",
            hwdecFlag,
            "--vo=gpu",
            "--title=CloudStream Player",
            "--keep-open=yes",
            "--osc=no",
            "--osd-level=0",
            "--input-default-bindings=no",
            "--input-vo-keyboard=no",
        )

        if (isWin) {
            args += "--gpu-api=d3d11"
        }

        if (wid != null && wid > 0L) {
            args += "--wid=$wid"
            args += if (isWin) "--gpu-context=d3d11,win" else "--gpu-context=x11egl,x11"
            args += "--input-vo-keyboard=no"
            args += "--input-default-bindings=no"
        } else {
            args += if (isWin) "--gpu-context=d3d11,win" else "--gpu-context=wayland,x11egl"
        }

        if (!title.isNullOrBlank()) {
            args += "--force-media-title=$title"
        }

        if (startSec > 0L) {
            args += "--start=$startSec"
        }

        if (!userAgent.isNullOrBlank()) {
            args += "--user-agent=$userAgent"
        }

        if (!referrer.isNullOrBlank()) {
            args += "--referrer=$referrer"
        }

        // Resilient network reconnection settings for streaming stability (each lavf option must be separate)
        args += "--demuxer-lavf-o-append=reconnect=1"
        args += "--demuxer-lavf-o-append=reconnect_streamed=1"
        args += "--demuxer-lavf-o-append=reconnect_delay_max=5"
        args += "--demuxer-lavf-o-append=reconnect_on_http_error=403,404,429,500,503"

        // Bypass TLS/SSL verification to prevent silent stream crashes on rogue CDN certs
        // (mirrors upstream CS3IPlayer ignoreSSL = true & SSLTrustManager)
        args += "--tls-verify=no"
        args += "--demuxer-lavf-o-append=tls_verify=0"

        // Additional headers via MPV's --http-header-fields
        args.addAll(PlayerLinkHandler.buildHeadersCliArg(headers))

        // External subtitle tracks (deduplicated)
        subtitles.filter { it.isNotBlank() }.distinct().forEach { subUrl ->
            args += "--sub-file=$subUrl"
        }

        // ClearKey DRM decryption arguments for ffmpeg/lavf demuxer
        if (link != null) {
            val drmArgs = ClearKeyDrmHandler.getLavfDrmArgs(link)
            args.addAll(drmArgs)
        }

        // Custom extra parameters
        args.addAll(extraArgs)

        // Stream or file URL (must be last positional argument)
        args += mediaUrl

        return args
    }

    /**
     * Launches the MPV subprocess.
     * When [wid] is provided, MPV embeds into that window surface.
     */
    fun launch(
        socketPath: String,
        mediaUrl: String,
        title: String? = null,
        startSec: Long = 0L,
        userAgent: String? = null,
        referrer: String? = null,
        headers: Map<String, String> = emptyMap(),
        subtitles: List<String> = emptyList(),
        link: ExtractorLink? = null,
        extraArgs: List<String> = emptyList(),
        wid: Long? = null,
        executable: String? = null,
        osName: String = System.getProperty("os.name") ?: "",
    ): Process {
        val isWin = osName.lowercase().contains("win")
        val mpvExec = executable ?: findMpvExecutable(osName)
            ?: error("mpv executable not found on system PATH. Please ensure mpv is installed.")

        val socketFile = File(socketPath)
        socketFile.parentFile?.mkdirs()
        if (socketFile.exists()) {
            socketFile.delete()
        }

        val command = buildCommandLine(
            executable = mpvExec,
            socketPath = socketPath,
            mediaUrl = mediaUrl,
            title = title,
            startSec = startSec,
            userAgent = userAgent,
            referrer = referrer,
            headers = headers,
            subtitles = subtitles,
            link = link,
            extraArgs = extraArgs,
            wid = wid,
            osName = osName,
        )

        AppLogger.i("Launching MPV process: ${command.joinToString(" ")}")
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (!isWin && wid != null && wid > 0L) {
            // In a Wayland session, Java AWT Canvas peer is an X11 window on XWayland.
            // When WAYLAND_DISPLAY is present in the environment, MPV attempts to initialize
            // its Wayland backend where X11 embedding via --wid is impossible by Wayland protocol design.
            // Removing WAYLAND_DISPLAY forces MPV to connect to XWayland (DISPLAY), embedding cleanly into wid!
            pb.environment().remove("WAYLAND_DISPLAY")
        }
        return pb.start()
    }
}
