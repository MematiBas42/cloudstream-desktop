package unit

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.MpvExPackage
import com.lagradost.cloudstream3.actions.temp.MpvKtPackage
import com.lagradost.cloudstream3.actions.temp.MpvKtPreviewPackage
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.MpvRxPackage
import com.lagradost.cloudstream3.actions.temp.MpvYTDLPackage
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
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Unit test suite for Domain 22 / Atomic Cluster C08: MpvPackage.
 * Verifies 1:1 upstream parity, CLI argument mapping (--http-header-fields=, --sub-file=, --start=),
 * cross-platform executable resolution, and MpvKtPackage / MpvRxPackage contracts.
 */
class MpvPackageRemediationTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
    }

    // =========================================================================
    // 1. Inheritance & Upstream Class Hierarchy
    // =========================================================================

    @Test
    fun testMpvPackageInheritanceAndContracts() {
        val mpv = MpvPackage()
        assertTrue(mpv is OpenInAppAction, "MpvPackage must inherit from OpenInAppAction")
        assertEquals("is.xyz.mpv", mpv.packageName)
        assertEquals("mpv", mpv.executableName)
        assertTrue(mpv.oneSource, "MpvPackage oneSource must be true matching upstream")
        assertTrue(mpv.isPlayer, "MpvPackage isPlayer must be true")

        val mpvEx = MpvExPackage()
        assertTrue(mpvEx is MpvPackage, "MpvExPackage must inherit from MpvPackage")
        assertEquals("app.marlboroadvance.mpvex", mpvEx.packageName)
        assertEquals("mpv", mpvEx.executableName)
        assertTrue(mpvEx.oneSource, "MpvExPackage oneSource must be true")

        val ytdl = MpvYTDLPackage()
        assertTrue(ytdl is MpvPackage, "MpvYTDLPackage must inherit from MpvPackage")
        assertEquals("is.xyz.mpv.ytdl", ytdl.packageName)
        assertEquals(
            setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.DASH, ExtractorLinkType.M3U8),
            ytdl.sourceTypes,
            "MpvYTDLPackage sourceTypes must match upstream exactly"
        )
    }

    @Test
    fun testMpvKtPackageAndPreviewContract() {
        val mpvKt = MpvKtPackage()
        assertTrue(mpvKt is OpenInAppAction, "MpvKtPackage must inherit from OpenInAppAction")
        assertEquals("live.mehiz.mpvkt", mpvKt.packageName)
        assertTrue(mpvKt.oneSource, "MpvKtPackage oneSource must be true")
        assertEquals(
            setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.DASH, ExtractorLinkType.M3U8),
            mpvKt.sourceTypes,
            "MpvKtPackage sourceTypes must match upstream"
        )
        assertFalse(mpvKt.shouldShow(null, null), "MpvKtPackage must return false for desktop shouldShow")

        val mpvKtAnnotation = MpvKtPackage::class.java.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(mpvKtAnnotation, "MpvKtPackage must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, mpvKtAnnotation.status)

        val preview = MpvKtPreviewPackage()
        assertTrue(preview is MpvKtPackage, "MpvKtPreviewPackage must inherit from MpvKtPackage")
        assertEquals("live.mehiz.mpvkt.preview", preview.packageName)

        val previewAnnotation = MpvKtPreviewPackage::class.java.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(previewAnnotation, "MpvKtPreviewPackage must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, previewAnnotation.status)
    }

    @Test
    fun testMpvRxPackageContract() {
        val mpvRx = MpvRxPackage()
        assertTrue(mpvRx is OpenInAppAction, "MpvRxPackage must inherit from OpenInAppAction")
        assertEquals("app.gyrolet.mpvrx", mpvRx.packageName)
        assertTrue(mpvRx.oneSource, "MpvRxPackage oneSource must be true")
        assertFalse(mpvRx.shouldShow(null, null), "MpvRxPackage must return false for desktop shouldShow")

        val annotation = MpvRxPackage::class.java.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(annotation, "MpvRxPackage must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, annotation.status)
        assertEquals(
            "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/MpvRxPackage.kt:21",
            annotation.upstreamRef
        )
    }

    @Test
    fun testPlatformQuarantineOnResult() {
        val method = MpvPackage::class.java.declaredMethods.firstOrNull { it.name == "onResult" }
        assertNotNull(method, "onResult method must exist on MpvPackage")

        val annotation = method!!.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(annotation, "onResult must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, annotation.status)
        assertEquals(
            "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/MpvPackage.kt:66",
            annotation.upstreamRef
        )
    }

    // =========================================================================
    // 2. Upstream putExtra & onResult Parity
    // =========================================================================

    @Test
    fun testMpvPackagePutExtraUpstreamParity() = runBlocking {
        val mpv = MpvPackage()
        val context = Context()
        val intent = Intent()

        val video = ResultEpisode(
            headerName = "Cyberpunk Edgerunners",
            name = "Let You Down",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/ep1",
            apiName = "TestProvider",
            id = 771122,
            index = 0,
            tvType = TvType.Anime
        )

        DataStoreHelper.setViewPos(video.id, 45000L, 120000L)

        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p Stream",
            url = "https://cdn.example.com/anime.mp4",
            referer = "https://example.com/embed",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )

        val sub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://cdn.example.com/sub_en.srt",
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

        mpv.putExtra(context, intent, video, result, 0)

        assertEquals("Let You Down", intent.getStringExtra("title"))
        assertEquals("https://cdn.example.com/anime.mp4", intent.data?.toString())
        assertEquals(true, intent.getBooleanExtra("secure_uri", false))
        assertEquals(45000, intent.getIntExtra("position", -1))

        val subsExtra = intent.getParcelableArrayExtra<android.net.Uri>("subs")
        assertNotNull(subsExtra)
        assertEquals(1, subsExtra!!.size)
        assertEquals("https://cdn.example.com/sub_en.srt", subsExtra[0].toString())
    }

    @Test
    fun testMpvKtPutExtraParity() = runBlocking {
        val mpvKt = MpvKtPackage()
        val context = Context()
        val intent = Intent()

        val video = ResultEpisode(
            headerName = "Steins;Gate",
            name = "Prologue of the Beginning and the End",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/sg1",
            apiName = "TestProvider",
            id = 882233,
            index = 0,
            tvType = TvType.Anime
        )

        DataStoreHelper.setViewPos(video.id, 90000L, 1500000L)

        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p Stream",
            url = "https://cdn.example.com/sg1.mp4",
            referer = "https://example.com/embed",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(
                SubtitleData("English", "", "https://cdn.example.com/sg1.vtt", SubtitleOrigin.URL, "text/vtt", emptyMap(), "en")
            ),
            syncData = hashMapOf()
        )

        mpvKt.putExtra(context, intent, video, result, 0)

        assertEquals("https://cdn.example.com/sg1.mp4", intent.data?.toString())
        assertEquals(true, intent.getBooleanExtra("secure_uri", false))
        assertEquals(90000, intent.getIntExtra("position", -1))
        val subs = intent.getParcelableArrayExtra<android.net.Uri>("subs")
        assertNotNull(subs)
        assertEquals(1, subs!!.size)
    }

    @Test
    fun testMpvRxPutExtraParity() = runBlocking {
        val mpvRx = MpvRxPackage()
        val context = Context()
        val intent = Intent()

        val video = ResultEpisode(
            headerName = "Frieren",
            name = "The Journey's End",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/frieren1",
            apiName = "TestProvider",
            id = 993344,
            index = 0,
            tvType = TvType.TvSeries
        )

        val link = ExtractorLink(
            source = "TestProvider",
            name = "Frieren Stream",
            url = "https://cdn.example.com/frieren1.m3u8",
            referer = "https://example.com",
            quality = Qualities.P1080.value,
            headers = mapOf("Authorization" to "Bearer token123", "Custom-Key" to "CustomVal"),
            type = ExtractorLinkType.M3U8
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = emptyList(),
            syncData = hashMapOf()
        )

        mpvRx.putExtra(context, intent, video, result, 0)

        assertEquals("The Journey's End", intent.getStringExtra("title"))
        assertEquals("https://cdn.example.com/frieren1.m3u8", intent.data?.toString())
        assertEquals(1, intent.getIntExtra("introdb_season", -1))
        assertEquals(1, intent.getIntExtra("introdb_episode", -1))

        val headers = intent.getStringArrayExtra("headers")
        assertNotNull(headers)
        assertTrue(headers!!.contains("Authorization"))
        assertTrue(headers.contains("Bearer token123"))
    }

    // =========================================================================
    // 3. CLI Argument Generation (buildMpvArgs)
    // =========================================================================

    @Test
    fun testBuildMpvArgsBasicAndTitle() {
        val mpv = MpvPackage()
        val video = ResultEpisode(
            headerName = "Arcane",
            name = "Welcome to the Playground",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/arcane1",
            id = 12345
        )
        val link = ExtractorLink(
            source = "Provider",
            name = "Direct",
            url = "https://video.cdn.com/arcane_01.mp4",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val result = LinkLoadingResult(listOf(link), emptyList(), hashMapOf())

        val args = mpv.buildMpvArgs(
            executable = "mpv",
            video = video,
            result = result,
            index = 0,
            positionMs = 0L
        )

        assertEquals("mpv", args[0])
        assertEquals("https://video.cdn.com/arcane_01.mp4", args[1])
        assertTrue(args.contains("--force-media-title=Welcome to the Playground"))
        assertFalse(args.any { it.startsWith("--start=") }, "Start argument must not be present when position is 0")
    }

    @Test
    fun testBuildMpvArgsStartTimes() {
        val mpv = MpvPackage()
        val video = ResultEpisode(
            headerName = "Arcane",
            name = "Some Rhapsody",
            poster = null,
            episode = 2,
            season = 1,
            data = "https://example.com/arcane2",
            id = 54321
        )
        val link = ExtractorLink("P", "D", "https://video.cdn.com/stream.mp4", "", Qualities.P1080.value, ExtractorLinkType.VIDEO)
        val result = LinkLoadingResult(listOf(link), emptyList(), hashMapOf())

        // 120 seconds resume position (120,000 ms)
        val args120s = mpv.buildMpvArgs(
            executable = "mpv",
            video = video,
            result = result,
            index = 0,
            positionMs = 120000L
        )
        assertTrue(args120s.contains("--start=120"), "Must pass --start=120 for 120,000ms position")

        // 45.8 seconds resume position (45,800 ms)
        val args45s = mpv.buildMpvArgs(
            executable = "mpv",
            video = video,
            result = result,
            index = 0,
            positionMs = 45800L
        )
        assertTrue(args45s.contains("--start=45"), "Must pass --start=45 for 45,800ms position")

        // Sub-second position (< 1000 ms)
        val argsZero = mpv.buildMpvArgs(
            executable = "mpv",
            video = video,
            result = result,
            index = 0,
            positionMs = 800L
        )
        assertFalse(argsZero.any { it.startsWith("--start=") }, "Sub-second position should not add --start")
    }

    @Test
    fun testBuildMpvArgsSubtitleUrls() {
        val mpv = MpvPackage()
        val video = ResultEpisode("Anime", "Ep 1", null, 1, 1, "data", id = 111)
        val link = ExtractorLink("P", "D", "https://cdn.com/video.mp4", "", Qualities.P1080.value, ExtractorLinkType.VIDEO)

        val subs = listOf(
            SubtitleData("English", "", "https://cdn.com/subs/en.vtt", SubtitleOrigin.URL, "text/vtt", emptyMap(), "en"),
            SubtitleData("Spanish", "", "https://cdn.com/subs/es.vtt", SubtitleOrigin.URL, "text/vtt", emptyMap(), "es"),
            SubtitleData("Turkish", "", "https://cdn.com/subs/tr.vtt", SubtitleOrigin.URL, "text/vtt", emptyMap(), "tr"),
            SubtitleData("Blank", "", "   ", SubtitleOrigin.URL, "text/vtt", emptyMap(), "blank")
        )
        val result = LinkLoadingResult(listOf(link), subs, hashMapOf())

        val args = mpv.buildMpvArgs(
            executable = "mpv",
            video = video,
            result = result,
            index = 0,
            positionMs = 0L,
            subsLang = "tr"
        )

        // Verifies all valid subtitle URLs matching upstream extra "subs" are passed as --sub-file=
        assertTrue(args.contains("--sub-file=https://cdn.com/subs/en.vtt"), "Must contain English subtitle")
        assertTrue(args.contains("--sub-file=https://cdn.com/subs/es.vtt"), "Must contain Spanish subtitle")
        assertTrue(args.contains("--sub-file=https://cdn.com/subs/tr.vtt"), "Must contain Turkish subtitle")
        assertFalse(args.any { it == "--sub-file=" || it == "--sub-file=   " }, "Blank subtitle URLs must be filtered out")

        // Verifies preferred language is selected via --slang=
        assertTrue(args.contains("--slang=tr"), "Must set --slang=tr for preferred subtitle language")
    }

    @Test
    fun testBuildMpvArgsHttpHeadersAndReferer() {
        val mpv = MpvPackage()
        val video = ResultEpisode("Show", "Ep 1", null, 1, 1, "data", id = 222)

        val link = ExtractorLink(
            source = "FastStream",
            name = "1080p",
            url = "https://faststream.example.com/playlist.m3u8",
            referer = "https://embed.player.net/v/123",
            quality = Qualities.P1080.value,
            headers = mapOf(
                "User-Agent" to "CloudStreamDesktop/1.0",
                "Cookie" to "session_id=abcdef123456",
                "X-Api-Key" to "secretKeyXYZ"
            ),
            type = ExtractorLinkType.M3U8
        )
        val result = LinkLoadingResult(listOf(link), emptyList(), hashMapOf())

        val args = mpv.buildMpvArgs(
            executable = "mpv",
            video = video,
            result = result,
            index = 0,
            positionMs = 0L
        )

        // Referer & User-Agent direct flags
        assertTrue(args.contains("--referrer=https://embed.player.net/v/123"), "Must pass --referrer=")
        assertTrue(args.contains("--user-agent=CloudStreamDesktop/1.0"), "Must pass --user-agent=")

        // --http-header-fields= formatted string
        val httpHeaderArg = args.firstOrNull { it.startsWith("--http-header-fields=") }
        assertNotNull(httpHeaderArg, "--http-header-fields= argument must be present")
        val headerValue = httpHeaderArg!!.removePrefix("--http-header-fields=")

        assertTrue(headerValue.contains("Referer: https://embed.player.net/v/123"))
        assertTrue(headerValue.contains("User-Agent: CloudStreamDesktop/1.0"))
        assertTrue(headerValue.contains("Cookie: session_id=abcdef123456"))
        assertTrue(headerValue.contains("X-Api-Key: secretKeyXYZ"))
    }

    @Test
    fun testBuildMpvArgsLinkIndexSelection() {
        val mpv = MpvPackage()
        val video = ResultEpisode("Series", "Ep 1", null, 1, 1, "data", id = 333)

        val link1 = ExtractorLink("P1", "720p", "https://cdn.com/720p.mp4", "", Qualities.P720.value, ExtractorLinkType.VIDEO)
        val link2 = ExtractorLink("P2", "1080p", "https://cdn.com/1080p.mp4", "", Qualities.P1080.value, ExtractorLinkType.VIDEO)
        val result = LinkLoadingResult(listOf(link1, link2), emptyList(), hashMapOf())

        // Index 1 -> link2
        val argsIndex1 = mpv.buildMpvArgs("mpv", video, result, index = 1)
        assertEquals("https://cdn.com/1080p.mp4", argsIndex1[1])

        // Index null -> link1
        val argsDefault = mpv.buildMpvArgs("mpv", video, result, index = null)
        assertEquals("https://cdn.com/720p.mp4", argsDefault[1])

        // Empty links -> empty args
        val emptyResult = LinkLoadingResult(emptyList(), emptyList(), hashMapOf())
        val argsEmpty = mpv.buildMpvArgs("mpv", video, emptyResult, index = null)
        assertTrue(argsEmpty.isEmpty(), "Empty links must produce empty argument list")
    }

    // =========================================================================
    // 4. Cross-Platform Executable Resolution
    // =========================================================================

    @Test
    fun testResolveExecutableInPath() {
        assertTrue(MpvPackage.isBinaryInPath("sh"), "POSIX 'sh' must be found in path")
        assertTrue(MpvPackage.isBinaryInPath("ls"), "POSIX 'ls' must be found in path")

        val shFile = MpvPackage.resolveExecutable("sh")
        assertNotNull(shFile)
        assertTrue(shFile!!.exists())

        val shPath = MpvPackage.resolveExecutablePath("sh")
        assertNotNull(shPath)
        assertTrue(shPath!!.isNotBlank())

        assertFalse(MpvPackage.isBinaryInPath("non_existent_binary_xyz_98765"))
        assertNull(MpvPackage.resolveExecutable("non_existent_binary_xyz_98765"))
    }

    @Test
    fun testWindowsExecutableResolutionWithExeExtension(@TempDir tempDir: Path) {
        val programFilesDir = tempDir.toFile()
        val mpvDir = File(programFilesDir, "mpv")
        mpvDir.mkdirs()
        val mpvExe = File(mpvDir, "mpv.exe")
        mpvExe.writeText("fake-mpv-binary")

        val env = mapOf("ProgramFiles" to programFilesDir.absolutePath)

        val resolved = MpvPackage.resolveExecutable(
            binary = "mpv",
            osName = "Windows 11",
            pathEnv = "",
            env = env
        )
        assertNotNull(resolved, "Resolver must find mpv.exe in Windows Program Files\\mpv")
        assertEquals(mpvExe.canonicalPath, resolved!!.canonicalPath)
    }

    // =========================================================================
    // 5. VideoClickActionHolder Registry Parity
    // =========================================================================

    @Test
    fun testVideoClickActionHolderRegistryParity() {
        val actions = VideoClickActionHolder.allVideoClickActions
        val actionNames = actions.map { it::class.java.simpleName }

        assertTrue(actionNames.contains("MpvPackage"), "MpvPackage must be in allVideoClickActions")
        assertTrue(actionNames.contains("MpvExPackage"), "MpvExPackage must be in allVideoClickActions")
        assertTrue(actionNames.contains("MpvYTDLPackage"), "MpvYTDLPackage must be in allVideoClickActions")
        assertTrue(actionNames.contains("MpvKtPackage"), "MpvKtPackage must be in allVideoClickActions")
        assertTrue(actionNames.contains("MpvKtPreviewPackage"), "MpvKtPreviewPackage must be in allVideoClickActions")
        assertTrue(actionNames.contains("MpvRxPackage"), "MpvRxPackage must be in allVideoClickActions")

        val mpvIdx = actionNames.indexOf("MpvPackage")
        val mpvExIdx = actionNames.indexOf("MpvExPackage")
        val ytdlIdx = actionNames.indexOf("MpvYTDLPackage")
        val mpvKtIdx = actionNames.indexOf("MpvKtPackage")
        val mpvKtPreviewIdx = actionNames.indexOf("MpvKtPreviewPackage")
        val mpvRxIdx = actionNames.indexOf("MpvRxPackage")

        assertTrue(mpvIdx < mpvExIdx, "MpvPackage must precede MpvExPackage")
        assertTrue(mpvExIdx < ytdlIdx, "MpvExPackage must precede MpvYTDLPackage")
        assertTrue(ytdlIdx < mpvKtIdx, "MpvYTDLPackage must precede MpvKtPackage")
        assertTrue(mpvKtIdx < mpvKtPreviewIdx, "MpvKtPackage must precede MpvKtPreviewPackage")
        assertTrue(mpvKtPreviewIdx < mpvRxIdx, "MpvKtPreviewPackage must precede MpvRxPackage")
    }
}
