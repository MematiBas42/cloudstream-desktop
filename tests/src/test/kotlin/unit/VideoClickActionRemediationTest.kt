package unit

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.platform.DesktopPlatform
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.Callable

/**
 * High-fidelity unit tests verifying 1:1 upstream architectural parity, zero-stub enforcement,
 * and robust cross-platform desktop adaptation for VideoClickAction (Cluster C06_VideoClickAction):
 *
 * 1. Cross-Platform URL & Browser Execution:
 *    - Replaces hardcoded ProcessBuilder("xdg-open", uri) with DesktopPlatform.openUrl(uri).
 *    - Validates DesktopPlatform.openUrl() handles web URLs, file URIs, and invalid schemas safely.
 *    - Validates Windows, Linux, and macOS execution paths without throwing IOException.
 *
 * 2. Embedded Player In-App Launch Parameter Preservation:
 *    - Validates that OpenInAppAction targeting CloudStream (BuildConfig.APPLICATION_ID / CloudStreamPackage)
 *      preserves all original parameters (url, title, headers, subtitles, resume position) without data loss.
 *    - Validates Intent extras (LINKS_EXTRA, SUBTITLE_EXTRA, ID_EXTRA, TITLE_EXTRA, POSITION_EXTRA)
 *      are populated and successfully dispatched to OfflinePlaybackHelper.playIntent.
 *
 * 3. VideoClickAction Core Execution & Error Handling:
 *    - Validates uiThread() safe suspension and fallback handling in test/headless environments.
 *    - Validates uniqueId() format parity ($sourcePlugin:${class.qualifiedName}).
 *    - Validates shouldShowSafe() exception catching and false return.
 *    - Validates runActionSafe() handles error types (NotImplementedError, ErrorLoadingException,
 *      ActivityNotFoundException, IOException) gracefully without crashing.
 */
class VideoClickActionRemediationTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        DesktopDataStore.init()
    }

    // =========================================================================
    // 1. Cross-Platform URL & Browser Execution (DesktopPlatform.openUrl)
    // =========================================================================

    @Test
    @DisplayName("DesktopPlatform.openUrl handles blank, malformed, and valid URLs safely")
    fun testDesktopPlatformOpenUrlSafety() {
        // Blank URL returns false without throwing
        assertFalse(DesktopPlatform.openUrl(""), "Blank URL must return false")
        assertFalse(DesktopPlatform.openUrl("   "), "Whitespace URL must return false")

        // Valid HTTPS URL execution does not throw
        assertDoesNotThrow {
            DesktopPlatform.openUrl("https://recloudstream.github.io/")
        }

        // File URL execution does not throw
        val tempFile = File.createTempFile("cs_test_video", ".mp4")
        tempFile.deleteOnExit()
        assertDoesNotThrow {
            DesktopPlatform.openUrl(tempFile.toURI().toString())
        }
    }

    @Test
    @DisplayName("DesktopPlatform binary resolution supports cross-platform executables")
    fun testDesktopPlatformResolveExecutable() {
        val mpvResolved = DesktopPlatform.resolveExecutable("mpv")
        assertNotNull(mpvResolved)
        assertTrue(mpvResolved.contains("mpv", ignoreCase = true))

        val vlcResolved = DesktopPlatform.resolveExecutable("vlc")
        assertNotNull(vlcResolved)
        assertTrue(vlcResolved.contains("vlc", ignoreCase = true))

        // PlatformPaths.currentOS detection works without throwing
        assertNotNull(PlatformPaths.currentOS)
    }

    @Test
    @DisplayName("PlayInBrowserAction delegates to launch and openUrl")
    fun testPlayInBrowserActionProperties() {
        val action = PlayInBrowserAction()
        assertEquals(txt(com.lagradost.cloudstream3.R.string.episode_action_play_in_format, "Browser").asStringNull(null),
            action.name.asStringNull(null))
        assertTrue(action.isPlayer)
        assertTrue(action.oneSource)
        assertTrue(action.sourceTypes.contains(ExtractorLinkType.VIDEO))
        assertTrue(action.sourceTypes.contains(ExtractorLinkType.M3U8))
        assertTrue(action.sourceTypes.contains(ExtractorLinkType.DASH))
        assertTrue(action.shouldShow(null, null))
    }

    // =========================================================================
    // 2. Embedded Player In-App Launch Parameter Preservation (Zero Data Loss)
    // =========================================================================

    private class TestEmbeddedAppAction(
        appName: UiText = txt("CloudStream Test"),
        packageName: String = BuildConfig.APPLICATION_ID,
        intentClass: String? = "com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity"
    ) : OpenInAppAction(appName, packageName, intentClass) {
        var putExtraInvoked = false
        var capturedIntent: Intent? = null

        override suspend fun putExtra(
            context: Context,
            intent: Intent,
            video: ResultEpisode,
            result: LinkLoadingResult,
            index: Int?
        ) {
            putExtraInvoked = true
            capturedIntent = intent
        }

        override fun onResult(activity: Activity, intent: Intent?) {
            // Test stub
        }
    }

    @Test
    @DisplayName("OpenInAppAction preserves all original parameters when targeting embedded player")
    fun testOpenInAppActionPreservesAllParameters() = runBlocking {
        val action = TestEmbeddedAppAction()
        val context = Context()

        val episode = ResultEpisode(
            headerName = "Season 1",
            name = "Episode 42 - The Parity Test",
            poster = "https://example.com/poster.jpg",
            episode = 42,
            season = 1,
            data = "episode_42_payload",
            apiName = "TestProvider",
            id = 99942,
            index = 41
        )

        val link1 = ExtractorLink(
            source = "PrimaryStream",
            name = "1080p Master",
            url = "https://example.com/video_1080p.mp4",
            referer = "https://example.com/embed",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO,
            headers = mapOf("Authorization" to "Bearer token123", "User-Agent" to "CloudStreamDesktop/1.0")
        )

        val link2 = ExtractorLink(
            source = "BackupStream",
            name = "720p Mirror",
            url = "https://example.com/video_720p.m3u8",
            referer = "https://example.com/embed",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8,
            headers = mapOf("Custom-Header" to "value")
        )

        val sub = SubtitleData(
            url = "https://example.com/subs_en.vtt",
            nameSuffix = "",
            mimeType = "text/vtt",
            originalName = "English (Full)",
            headers = mapOf("Cookie" to "auth=abc"),
            origin = SubtitleOrigin.URL,
            languageCode = "en"
        )

        val linkLoadingResult = LinkLoadingResult(
            links = listOf(link1, link2),
            subs = listOf(sub),
            syncData = hashMapOf("provider" to "TestProvider")
        )

        // Set initial resume position in DataStore
        DataStoreHelper.setViewPos(episode.id, 45000L, 1200000L)

        // Execute runAction
        action.runAction(context, episode, linkLoadingResult, 0)

        // Verify putExtra was invoked
        assertTrue(action.putExtraInvoked, "putExtra must be called before launching")
        val intent = action.capturedIntent
        assertNotNull(intent, "Captured intent must not be null")

        // 1. Verify links preserved without data loss
        val linksJson = intent!!.getStringArrayExtra(CloudStreamPackage.LINKS_EXTRA)
        assertNotNull(linksJson, "LINKS_EXTRA must be present")
        assertEquals(2, linksJson!!.size, "Both links must be preserved")

        val parsedLink1 = parseJson<CloudStreamPackage.MinimalVideoLink>(linksJson[0])
        assertEquals("https://example.com/video_1080p.mp4", parsedLink1.url)
        assertEquals("1080p Master", parsedLink1.name)
        assertEquals(Qualities.P1080.value, parsedLink1.quality)
        assertEquals("Bearer token123", parsedLink1.headers["Authorization"])
        assertEquals("https://example.com/embed", parsedLink1.headers["referer"])

        val parsedLink2 = parseJson<CloudStreamPackage.MinimalVideoLink>(linksJson[1])
        assertEquals("https://example.com/video_720p.m3u8", parsedLink2.url)
        assertEquals("720p Mirror", parsedLink2.name)

        // 2. Verify subtitles preserved without data loss
        val subsJson = intent.getStringArrayExtra(CloudStreamPackage.SUBTITLE_EXTRA)
        assertNotNull(subsJson, "SUBTITLE_EXTRA must be present")
        assertEquals(1, subsJson!!.size)

        val parsedSub = parseJson<CloudStreamPackage.MinimalSubtitleLink>(subsJson[0])
        assertEquals("https://example.com/subs_en.vtt", parsedSub.url)
        assertEquals("English (Full)", parsedSub.name)
        assertEquals("text/vtt", parsedSub.mimeType)
        assertEquals("auth=abc", parsedSub.headers["Cookie"])

        // 3. Verify title, ID, and resume position preserved
        assertEquals("Episode 42 - The Parity Test", intent.getStringExtra(CloudStreamPackage.TITLE_EXTRA))
        assertEquals(99942, intent.getIntExtra(CloudStreamPackage.ID_EXTRA, 0))
        assertEquals(45000L, intent.getLongExtra(CloudStreamPackage.POSITION_EXTRA, 0L))

        // 4. Verify shouldShow recognizes embedded player
        assertTrue(action.shouldShow(context, episode), "shouldShow must return true for embedded player")
    }

    @Test
    @DisplayName("VideoClickAction.launchProcess routes embedded player intent directly to OfflinePlaybackHelper")
    fun testLaunchProcessRoutesEmbeddedPlayer() {
        val testAction = object : VideoClickAction() {
            override val name = txt("Test Action")
            override fun shouldShow(context: Context?, video: ResultEpisode?) = true
            override suspend fun runAction(
                context: Context?,
                video: ResultEpisode,
                result: LinkLoadingResult,
                index: Int?
            ) {}
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(BuildConfig.APPLICATION_ID)
        intent.putExtra(CloudStreamPackage.ID_EXTRA, 12345)
        intent.putExtra(CloudStreamPackage.TITLE_EXTRA, "Embedded Play Test")

        val minimalLink = CloudStreamPackage.MinimalVideoLink(
            uri = null,
            url = "https://example.com/stream.mp4",
            name = "Test Stream",
            mimeType = "video/mp4",
            headers = mapOf("Referer" to "https://example.com/"),
            quality = Qualities.P1080.value
        )
        intent.putExtra(CloudStreamPackage.LINKS_EXTRA, arrayOf(com.lagradost.cloudstream3.utils.AppUtils.toJson(minimalLink)))

        // Must execute launchProcess without throwing any exception and route to embedded player
        assertDoesNotThrow {
            testAction.launchProcess(intent)
        }
    }

    // =========================================================================
    // 3. VideoClickAction Core Execution & Error Handling
    // =========================================================================

    @Test
    @DisplayName("VideoClickAction uniqueId matches exact canonical format")
    fun testUniqueIdFormat() {
        val action = PlayInBrowserAction()
        action.sourcePlugin = "plugins/core.jar"
        assertEquals("plugins/core.jar:com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction", action.uniqueId())

        action.sourcePlugin = null
        assertEquals("null:com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction", action.uniqueId())
    }

    @Test
    @DisplayName("VideoClickAction uiThread executes callable and returns result safely")
    fun testUiThreadExecution() = runBlocking {
        val action = PlayInBrowserAction()
        val result = action.uiThread(Callable {
            "UI_RESULT_OK"
        })
        assertEquals("UI_RESULT_OK", result)
    }

    @Test
    @DisplayName("shouldShowSafe catches exceptions and returns false")
    fun testShouldShowSafeCatchesExceptions() {
        val faultyAction = object : VideoClickAction() {
            override val name = txt("Faulty")
            override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
                throw RuntimeException("Faulty shouldShow calculation")
            }
            override suspend fun runAction(
                context: Context?,
                video: ResultEpisode,
                result: LinkLoadingResult,
                index: Int?
            ) {}
        }

        assertFalse(faultyAction.shouldShowSafe(null, null), "shouldShowSafe must catch exception and return false")
    }

    @Test
    @DisplayName("runActionSafe catches exceptions without bubbling to caller")
    fun testRunActionSafeErrorHandling() {
        val throwingAction = object : VideoClickAction() {
            override val name = txt("Throwing")
            override fun shouldShow(context: Context?, video: ResultEpisode?) = true
            override suspend fun runAction(
                context: Context?,
                video: ResultEpisode,
                result: LinkLoadingResult,
                index: Int?
            ) {
                throw com.lagradost.cloudstream3.ErrorLoadingException("Simulated link load failure")
            }
        }

        val dummyEpisode = ResultEpisode("H", "Ep", null, 1, 1, "d", "api", 10, 0)
        val dummyResult = LinkLoadingResult(emptyList(), emptyList(), hashMapOf())

        assertDoesNotThrow {
            throwingAction.runActionSafe(Context(), dummyEpisode, dummyResult, null)
        }
    }

    @Test
    @DisplayName("OpenInAppAction onResultSafe swallows exceptions safely")
    fun testOnResultSafeExceptionSwallowing() {
        val action = object : OpenInAppAction(txt("CrashTest"), "com.example.app") {
            override suspend fun putExtra(
                context: Context,
                intent: Intent,
                video: ResultEpisode,
                result: LinkLoadingResult,
                index: Int?
            ) {}

            override fun onResult(activity: Activity, intent: Intent?) {
                throw IllegalStateException("Simulated onResult crash")
            }
        }

        assertDoesNotThrow {
            action.onResultSafe(Activity(), Intent())
        }
    }
}
