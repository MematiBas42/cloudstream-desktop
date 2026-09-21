package unit

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.temp.VlcNightlyPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * High-fidelity unit tests for Domain 22 / Cluster C07: VlcPackage
 * Verifies cross-platform executable resolution, intent extras to CLI argument conversion,
 * M3U8 multi-mirror playlist fallback, and 1:1 upstream parity.
 */
class VlcPackageRemediationTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
    }

    // =========================================================================
    // 1. Inheritance & Upstream Contracts
    // =========================================================================

    @Test
    fun testInheritanceAndContracts() {
        val vlc = VlcPackage()
        assertTrue(vlc is OpenInAppAction, "VlcPackage must inherit from OpenInAppAction")
        assertEquals("org.videolan.vlc", vlc.packageName)
        assertEquals("vlc", vlc.executableName)
        assertTrue(vlc.oneSource, "VLC oneSource must be true")
        assertTrue(vlc.isPlayer, "VLC isPlayer must be true")

        val nightly = VlcNightlyPackage()
        assertTrue(nightly is VlcPackage, "VlcNightlyPackage must inherit from VlcPackage")
        assertEquals("org.videolan.vlc.debug", nightly.packageName)
        assertEquals("vlc-nightly", nightly.executableName)
        assertTrue(nightly.oneSource, "VLC Nightly oneSource must be true")
    }

    @Test
    fun testPlatformQuarantineOnResult() {
        val method = VlcPackage::class.java.declaredMethods.firstOrNull { it.name == "onResult" }
        assertNotNull(method, "onResult method must exist on VlcPackage")

        val annotation = method!!.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(annotation, "onResult must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, annotation.status)
        assertTrue(
            annotation.reason.contains("Linux") || annotation.reason.contains("VLC CLI"),
            "Quarantine reason must describe headless CLI desktop constraints"
        )
        assertEquals(
            "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/VlcPackage.kt:71",
            annotation.upstreamRef
        )
    }

    // =========================================================================
    // 2. Upstream putExtra Parity
    // =========================================================================

    @Test
    fun testPutExtraUpstreamParity() = runBlocking {
        val vlc = VlcPackage()
        val context = Context()
        val intent = Intent()

        val video = ResultEpisode(
            headerName = "Cyberpunk Series",
            name = "Episode 01",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/ep1",
            apiName = "TestProvider",
            id = 998877,
            index = 0,
            tvType = TvType.TvSeries
        )

        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p Stream",
            url = "https://cdn.example.com/video.mp4",
            referer = "https://example.com/embed",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )

        val sub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://cdn.example.com/sub.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "en"
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(sub),
            syncData = hashMapOf()
        )

        vlc.putExtra(context, intent, video, result, 0)

        assertEquals("Episode 01", intent.getStringExtra("title"))
        assertEquals("https://cdn.example.com/video.mp4", intent.data?.toString())
        assertEquals(false, intent.getBooleanExtra("from_start", true))
        assertEquals(true, intent.getBooleanExtra("secure_uri", false))
        assertEquals("https://cdn.example.com/sub.srt", intent.getStringExtra("subtitles_location"))
    }

    // =========================================================================
    // 3. Cross-Platform Executable Resolution (PATH & Linux Paths)
    // =========================================================================

    @Test
    fun testResolveExecutableInPath() {
        // "sh" and "ls" are standard POSIX binaries that exist on Unix systems
        assertTrue(VlcPackage.isBinaryInPath("sh"), "sh must be found on system")
        assertTrue(VlcPackage.isBinaryInPath("ls"), "ls must be found on system")

        val shFile = VlcPackage.resolveExecutable("sh")
        assertNotNull(shFile, "resolveExecutable('sh') must return non-null File")
        assertTrue(shFile!!.exists(), "Resolved 'sh' file must exist")

        val shPath = VlcPackage.resolveExecutablePath("sh")
        assertNotNull(shPath, "resolveExecutablePath('sh') must return non-null String")
        assertTrue(shPath!!.isNotBlank(), "Resolved 'sh' path must be non-blank")

        assertFalse(
            VlcPackage.isBinaryInPath("non_existent_binary_99999_xyz"),
            "Non-existent binary should return false"
        )
        assertNull(
            VlcPackage.resolveExecutable("non_existent_binary_99999_xyz"),
            "Non-existent binary should resolve to null"
        )
    }

    @Test
    fun testLinuxStandardPathsResolution() {
        // When PATH is blank, Linux standard paths (/usr/bin, /bin, etc.) should still resolve standard utilities
        val file = VlcPackage.resolveExecutable(
            binary = "sh",
            osName = "Linux",
            pathEnv = ""
        )
        assertNotNull(file, "Standard binary 'sh' should resolve from Linux standard fallback directories")
        assertTrue(file!!.exists())
        assertTrue(file.absolutePath.startsWith("/usr/bin") || file.absolutePath.startsWith("/bin"))
    }

    // =========================================================================
    // 4. Windows Platform Resolution (.exe & Program Files)
    // =========================================================================

    @Test
    fun testWindowsExecutableResolutionWithExeExtension(@TempDir tempDir: Path) {
        // Simulate Windows Program Files structure: C:\Program Files\VideoLAN\VLC\vlc.exe
        val programFilesDir = tempDir.toFile()
        val vlcDir = File(programFilesDir, "VideoLAN/VLC")
        vlcDir.mkdirs()
        val vlcExe = File(vlcDir, "vlc.exe")
        vlcExe.writeText("fake-vlc-binary")

        val env = mapOf("ProgramFiles" to programFilesDir.absolutePath)

        // Searching for bare "vlc" on Windows should find "vlc.exe" in Program Files
        val resolved = VlcPackage.resolveExecutable(
            binary = "vlc",
            osName = "Windows 11",
            pathEnv = "",
            env = env
        )
        assertNotNull(resolved, "Resolver must find vlc.exe in Windows Program Files\\VideoLAN\\VLC")
        assertEquals(vlcExe.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun testWindowsVlcNightlyResolution(@TempDir tempDir: Path) {
        val programFilesDir = tempDir.toFile()
        val vlcNightlyDir = File(programFilesDir, "VideoLAN/VLC Nightly")
        vlcNightlyDir.mkdirs()
        val vlcExe = File(vlcNightlyDir, "vlc.exe")
        vlcExe.writeText("fake-vlc-nightly-binary")

        val env = mapOf("ProgramFiles" to programFilesDir.absolutePath)

        val resolved = VlcPackage.resolveExecutable(
            binary = "vlc-nightly",
            osName = "Windows 10",
            pathEnv = "",
            env = env
        )
        assertNotNull(resolved, "Resolver must find vlc.exe in Windows Program Files\\VideoLAN\\VLC Nightly")
        assertEquals(vlcExe.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun testWindowsMpvResolution(@TempDir tempDir: Path) {
        val programFilesDir = tempDir.toFile()
        val mpvDir = File(programFilesDir, "mpv")
        mpvDir.mkdirs()
        val mpvExe = File(mpvDir, "mpv.exe")
        mpvExe.writeText("fake-mpv-binary")

        val env = mapOf("ProgramFiles" to programFilesDir.absolutePath)

        val resolved = VlcPackage.resolveExecutable(
            binary = "mpv",
            osName = "Windows 10",
            pathEnv = "",
            env = env
        )
        assertNotNull(resolved, "Resolver must find mpv.exe in Windows Program Files\\mpv")
        assertEquals(mpvExe.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun testDirectAbsolutePathResolution(@TempDir tempDir: Path) {
        val customExec = File(tempDir.toFile(), "custom_player")
        customExec.writeText("#!/bin/sh\nexit 0\n")
        customExec.setExecutable(true)

        val resolved = VlcPackage.resolveExecutable(customExec.absolutePath)
        assertNotNull(resolved)
        assertEquals(customExec.canonicalPath, resolved!!.canonicalPath)
    }

    @Test
    fun testDirectoryNamedBinaryIsNotResolved(@TempDir tempDir: Path) {
        // A directory named "vlc" should NOT be resolved as an executable file
        val fakeDir = File(tempDir.toFile(), "vlc")
        fakeDir.mkdirs()
        fakeDir.setExecutable(true)

        val resolved = VlcPackage.resolveExecutable(
            binary = "vlc",
            pathEnv = tempDir.toFile().absolutePath
        )
        assertNull(resolved, "Directories named after the binary must not be returned as executable files")
    }

    @Test
    fun testBlankBinaryReturnsNull() {
        assertNull(VlcPackage.resolveExecutable(""))
        assertNull(VlcPackage.resolveExecutable("   "))
        assertFalse(VlcPackage.isBinaryInPath(""))
    }

    // =========================================================================
    // 5. VLC Command-Line Arguments Formatting
    // =========================================================================

    @Test
    fun testCommandLineArgsFormattingWithHeadersAndSubtitles() {
        val vlc = VlcPackage()
        val episodeId = 445566
        val video = ResultEpisode(
            headerName = "Attack on Titan",
            name = "To You, in 2000 Years",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/ep1",
            apiName = "AnimeProvider",
            id = episodeId,
            index = 0,
            tvType = TvType.TvSeries
        )

        // Store playback position: 75 seconds (75_000 ms)
        DataStoreHelper.setViewPos(episodeId, 75_000L, 1_400_000L)

        val link = ExtractorLink(
            source = "AnimeProvider",
            name = "1080p",
            url = "https://stream.example.com/titan_ep1.mp4",
            referer = "https://anime-host.com/player",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO,
            headers = mapOf(
                "referer" to "https://anime-host.com/player",
                "user-agent" to "CloudStreamDesktop/1.0"
            )
        )

        val subEn = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://subtitles.com/titan_ep1_en.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "en"
        )
        val subTr = SubtitleData(
            originalName = "Turkish",
            nameSuffix = "",
            url = "https://subtitles.com/titan_ep1_tr.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "tr"
        )
        val subEnDuplicate = SubtitleData(
            originalName = "English (SDH)",
            nameSuffix = "",
            url = "https://subtitles.com/titan_ep1_en.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "en"
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(subEn, subTr, subEnDuplicate),
            syncData = hashMapOf()
        )

        val args = vlc.buildVlcArgs(video, result, 0)
        assertFalse(args.isEmpty(), "Arguments list must not be empty")

        // 1. Executable and target URL
        assertTrue(args[0].contains("vlc"), "First argument must be VLC executable")
        assertEquals("https://stream.example.com/titan_ep1.mp4", args[1], "Second argument must be video URL")

        // 2. Meta title
        assertTrue(
            args.contains("--meta-title=To You, in 2000 Years"),
            "Args must contain --meta-title= matching video name"
        )

        // 3. Resume position (75s from 75000ms)
        assertTrue(
            args.contains("--start-time=75"),
            "Args must contain --start-time=75 matching saved view position"
        )

        // 4. Subtitles (with deduplication)
        assertTrue(
            args.contains("--sub-file=https://subtitles.com/titan_ep1_en.srt"),
            "Args must contain English subtitle URL"
        )
        assertTrue(
            args.contains("--sub-file=https://subtitles.com/titan_ep1_tr.srt"),
            "Args must contain Turkish subtitle URL"
        )
        assertEquals(
            1,
            args.count { it == "--sub-file=https://subtitles.com/titan_ep1_en.srt" },
            "Duplicate subtitle URLs must be deduplicated"
        )

        // 5. HTTP Referrer (:http-referrer= and --http-referrer=)
        assertTrue(
            args.contains(":http-referrer=https://anime-host.com/player"),
            "Args must contain :http-referrer= stream option"
        )
        assertTrue(
            args.contains("--http-referrer=https://anime-host.com/player"),
            "Args must contain --http-referrer= global option"
        )

        // 6. User-Agent (:http-user-agent= and --http-user-agent=)
        assertTrue(
            args.contains(":http-user-agent=CloudStreamDesktop/1.0"),
            "Args must contain :http-user-agent= stream option"
        )
        assertTrue(
            args.contains("--http-user-agent=CloudStreamDesktop/1.0"),
            "Args must contain --http-user-agent= global option"
        )
    }

    // =========================================================================
    // 6. Multi-Link M3U8 Playlist Fallback
    // =========================================================================

    @Test
    fun testCommandLineArgsMultiLinkFallbackM3U8() {
        val vlc = VlcPackage()
        val video = ResultEpisode(
            headerName = "Frieren",
            name = "The End of the Journey",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/frieren1",
            apiName = "MultiProvider",
            id = 332211,
            index = 0,
            tvType = TvType.TvSeries
        )

        val link1 = ExtractorLink(
            source = "FastServer",
            name = "1080p Mirror 1",
            url = "https://mirror1.com/stream.mp4",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val link2 = ExtractorLink(
            source = "BackupServer",
            name = "720p Mirror 2",
            url = "https://mirror2.com/stream.mp4",
            referer = "",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )

        val result = LinkLoadingResult(
            links = listOf(link1, link2),
            subs = emptyList(),
            syncData = hashMapOf()
        )

        // When index == null and multiple links exist, it must construct a temporary M3U8 playlist
        val args = vlc.buildVlcArgs(video, result, null)
        assertFalse(args.isEmpty())

        val targetUrl = args[1]
        assertTrue(targetUrl.endsWith(".m3u8"), "Target URL must be generated .m3u8 playlist")

        val playlistFile = File(targetUrl)
        assertTrue(playlistFile.exists(), "Generated M3U8 playlist file must exist on disk")

        val content = playlistFile.readText()
        assertTrue(content.startsWith("#EXTM3U"), "Playlist must start with #EXTM3U")
        assertTrue(content.contains("https://mirror1.com/stream.mp4"), "Playlist must contain mirror 1")
        assertTrue(content.contains("https://mirror2.com/stream.mp4"), "Playlist must contain mirror 2")
        assertTrue(content.endsWith("#EXT-X-ENDLIST"), "Playlist must terminate with #EXT-X-ENDLIST")
    }
}
