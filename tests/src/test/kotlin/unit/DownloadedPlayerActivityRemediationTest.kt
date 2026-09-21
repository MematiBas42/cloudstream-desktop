package unit

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.OfflinePlaybackHelper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper
import com.lagradost.cloudstream3.utils.NavigationRequest
import com.lagradost.cloudstream3.utils.UIHelper
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enterprise JUnit 5 test suite verifying 1:1 upstream parity, anti-stub integrity,
 * and desktop CLI file argument launching for [DownloadedPlayerActivity] (Cluster C23).
 */
class DownloadedPlayerActivityRemediationTest {

    private lateinit var tempDir: Path
    private val recordedNavRequests = mutableListOf<NavigationRequest>()
    private var lastNavCallback: ((NavigationRequest) -> Unit)? = null

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        DesktopDataStore.init()
        tempDir = Files.createTempDirectory("cs_downloaded_player_test")
        recordedNavRequests.clear()

        val callback: (NavigationRequest) -> Unit = { req ->
            recordedNavRequests.add(req)
        }
        lastNavCallback = callback
        UIHelper.navigateEvent += callback
    }

    @AfterEach
    fun tearDown() {
        lastNavCallback?.let { UIHelper.navigateEvent -= it }
        recordedNavRequests.clear()
        tempDir.toFile().deleteRecursively()
        CommonActivity.keyEventListener = null
    }

    // =========================================================================
    // 1. Activity Lifecycle & Event Dispatching Tests
    // =========================================================================

    @Test
    @DisplayName("DownloadedPlayerActivity instantiates with correct TAG and Activity contract")
    fun testActivityInstantiationAndTag() {
        assertEquals("DownloadedPlayerActivity", DownloadedPlayerActivity.TAG)
        val activity = DownloadedPlayerActivity()
        assertNotNull(activity)
        assertFalse(activity.isFinishing())
        assertFalse(activity.isDestroyed())
    }

    @Test
    @DisplayName("onCreate executes lifecycle setup and attaches back pressed callback")
    fun testOnCreateLifecycle() {
        val activity = DownloadedPlayerActivity()
        val videoFile = tempDir.resolve("sample.mp4").toFile().apply { writeText("dummy content") }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.fromFile(videoFile)
        }
        activity.intent = intent

        // Invoke onCreate
        assertDoesNotThrow {
            activity.onCreate(Bundle())
        }

        // Verify that back callback triggers moveTaskToBack
        var backTriggered = false
        val customActivity = object : DownloadedPlayerActivity() {
            override fun moveTaskToBack(nonRoot: Boolean): Boolean {
                backTriggered = true
                return true
            }
        }
        customActivity.onCreate(Bundle())
        BackPressedCallbackHelper.triggerBackPressed(customActivity, "DownloadedPlayerActivity")
        assertTrue(backTriggered, "Back callback must invoke moveTaskToBack")
    }

    @Test
    @DisplayName("onResume sets CommonActivity activity instance")
    fun testOnResumeSetsActivityInstance() {
        val activity = DownloadedPlayerActivity()
        activity.onResume()
        assertEquals(activity, CommonActivity.activity)
    }

    @Test
    @DisplayName("dispatchKeyEvent and onKeyDown delegate to CommonActivity listener")
    fun testKeyEventDelegation() {
        val activity = DownloadedPlayerActivity()
        var interceptedEvent: KeyEvent? = null

        CommonActivity.keyEventListener = { (event, _) ->
            interceptedEvent = event
            true
        }

        val testEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val handledDispatch = activity.dispatchKeyEvent(testEvent)
        assertTrue(handledDispatch)
        assertEquals(testEvent, interceptedEvent)

        val handledKeyDown = activity.onKeyDown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, testEvent)
        assertTrue(handledKeyDown)
    }

    // =========================================================================
    // 2. Intent Comparison & Duplicate Suppression Tests (isSameIntent)
    // =========================================================================

    @Test
    @DisplayName("isSameIntent returns false when current activity intent is null")
    fun testIsSameIntentWithNullCurrentIntent() {
        val activity = DownloadedPlayerActivity()
        activity.intent = null

        val newIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("file:///path/to/video.mp4")
        }
        assertFalse(activity.isSameIntent(newIntent))
    }

    @Test
    @DisplayName("isSameIntent detects identical data URIs")
    fun testIsSameIntentWithMatchingDataUri() {
        val activity = DownloadedPlayerActivity()
        val uri1 = Uri.parse("file:///home/user/movie.mkv")
        val uri2 = Uri.parse("file:///home/user/movie.mkv")
        val uriDifferent = Uri.parse("file:///home/user/other.mkv")

        activity.intent = Intent(Intent.ACTION_VIEW).apply { data = uri1 }
        val matchingIntent = Intent(Intent.ACTION_VIEW).apply { data = uri2 }
        val differentIntent = Intent(Intent.ACTION_VIEW).apply { data = uriDifferent }

        assertTrue(activity.isSameIntent(matchingIntent), "Identical URIs must match")
        assertFalse(activity.isSameIntent(differentIntent), "Different URIs must not match")
    }

    @Test
    @DisplayName("isSameIntent detects identical ClipData URIs")
    fun testIsSameIntentWithMatchingClipDataUri() {
        val activity = DownloadedPlayerActivity()
        val uri = Uri.parse("content://media/external/video/1")

        val oldIntent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newRawUri("Video", uri)
        }
        activity.intent = oldIntent

        val newMatchingIntent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newRawUri("Video", uri)
        }
        val newDifferentIntent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newRawUri("Video", Uri.parse("content://media/external/video/2"))
        }

        assertTrue(activity.isSameIntent(newMatchingIntent), "Matching ClipData URIs must match")
        assertFalse(activity.isSameIntent(newDifferentIntent), "Different ClipData URIs must not match")
    }

    @Test
    @DisplayName("isSameIntent detects matching EXTRA_TEXT URLs")
    fun testIsSameIntentWithMatchingExtraText() {
        val activity = DownloadedPlayerActivity()
        val url = "https://example.com/stream.m3u8"

        activity.intent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(Intent.EXTRA_TEXT, url)
        }

        val matchingIntent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(Intent.EXTRA_TEXT, url)
        }
        val differentIntent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(Intent.EXTRA_TEXT, "https://example.com/other.m3u8")
        }

        assertTrue(activity.isSameIntent(matchingIntent), "Matching EXTRA_TEXT must match")
        assertFalse(activity.isSameIntent(differentIntent), "Different EXTRA_TEXT must not match")
    }

    @Test
    @DisplayName("onNewIntent suppresses duplicate reload if intent matches")
    fun testOnNewIntentDuplicateSuppression() {
        val activity = DownloadedPlayerActivity()
        val uri = Uri.parse("file:///tmp/sample.mp4")
        activity.intent = Intent(Intent.ACTION_VIEW).apply { data = uri }

        recordedNavRequests.clear()
        // Same intent passed to onNewIntent
        activity.onNewIntent(Intent(Intent.ACTION_VIEW).apply { data = uri })

        // Should NOT trigger any new navigation request because it was suppressed
        assertTrue(recordedNavRequests.isEmpty(), "Duplicate onNewIntent must be suppressed")
    }

    // =========================================================================
    // 3. Intent Routing & Protocol Parsing Tests (handleIntent)
    // =========================================================================

    @Test
    @DisplayName("handleIntent routes ACTION_VIEW with data Uri to playUri")
    fun testHandleIntentActionViewDataUri() {
        val activity = DownloadedPlayerActivity()
        val videoFile = tempDir.resolve("sample.mkv").toFile().apply { writeText("mkv data") }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.fromFile(videoFile)
        }

        activity.handleIntent(intent)

        assertEquals(1, recordedNavRequests.size)
        val req = recordedNavRequests[0]
        assertEquals(R.id.global_to_navigation_player, req.navigationId)
        assertTrue(req.args is GeneratorPlayer)
    }

    @Test
    @DisplayName("handleIntent routes ACTION_VIEW with ClipData uri to playUri")
    fun testHandleIntentClipDataUri() {
        val activity = DownloadedPlayerActivity()
        val uri = Uri.parse("file:///var/tmp/clip_video.mp4")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newRawUri("Clip", uri)
        }

        activity.handleIntent(intent)

        assertEquals(1, recordedNavRequests.size)
        assertEquals(R.id.global_to_navigation_player, recordedNavRequests[0].navigationId)
    }

    @Test
    @DisplayName("handleIntent routes ACTION_VIEW with ClipData text url to playLink")
    fun testHandleIntentClipDataTextUrl() {
        val activity = DownloadedPlayerActivity()
        val url = "https://cdn.example.org/stream.mp4"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newPlainText("URL", url)
        }

        activity.handleIntent(intent)

        assertEquals(1, recordedNavRequests.size)
        assertEquals(R.id.global_to_navigation_player, recordedNavRequests[0].navigationId)
    }

    @Test
    @DisplayName("handleIntent routes ACTION_VIEW with EXTRA_TEXT to playLink")
    fun testHandleIntentExtraText() {
        val activity = DownloadedPlayerActivity()
        val url = "https://cdn.example.org/movie.mp4"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(Intent.EXTRA_TEXT, url)
        }

        activity.handleIntent(intent)

        assertEquals(1, recordedNavRequests.size)
        assertEquals(R.id.global_to_navigation_player, recordedNavRequests[0].navigationId)
    }

    @Test
    @DisplayName("handleIntent routes content and file scheme without explicit action")
    fun testHandleIntentContentAndFileScheme() {
        val activity = DownloadedPlayerActivity()
        val contentIntent = Intent().apply {
            data = Uri.parse("content://media/external/video/media/42")
        }
        activity.handleIntent(contentIntent)
        assertEquals(1, recordedNavRequests.size)

        val fileIntent = Intent().apply {
            data = Uri.parse("file:///home/user/Downloads/film.mp4")
        }
        activity.handleIntent(fileIntent)
        assertEquals(2, recordedNavRequests.size)
    }

    @Test
    @DisplayName("handleIntent processes CloudStreamPackage offline links intent")
    fun testHandleIntentCloudStreamPackage() {
        val activity = DownloadedPlayerActivity()
        val linkJson = toJson(CloudStreamPackage.MinimalVideoLink("https://example.com/video.mp4", "1080p"))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            putExtra(CloudStreamPackage.LINKS_EXTRA, arrayOf(linkJson))
            putExtra(CloudStreamPackage.ID_EXTRA, 4200)
            putExtra(CloudStreamPackage.POSITION_EXTRA, 15000L)
            putExtra(CloudStreamPackage.DURATION_EXTRA, 60000L)
        }

        activity.handleIntent(intent)

        assertEquals(1, recordedNavRequests.size)
        assertEquals(R.id.global_to_navigation_player, recordedNavRequests[0].navigationId)
    }

    @Test
    @DisplayName("handleIntent calls finishAndRemoveTask on empty unhandled intent")
    fun testHandleIntentEmptyCallsFinish() {
        var finished = false
        val activity = object : DownloadedPlayerActivity() {
            override fun finishAndRemoveTask() {
                finished = true
            }
        }
        val emptyIntent = Intent()
        activity.handleIntent(emptyIntent)

        assertTrue(finished, "Empty unhandled intent must invoke finishAndRemoveTask")
        assertTrue(recordedNavRequests.isEmpty())
    }

    // =========================================================================
    // 4. Desktop CLI File Argument Launcher Tests (handleFileArgument)
    // =========================================================================

    @Test
    @DisplayName("handleFileArgument launches magnet URI via playUri")
    fun testHandleFileArgumentMagnet() {
        val magnet = "magnet:?xt=urn:btih:d1234567890abcdef&dn=Test+Movie"
        val handled = DownloadedPlayerActivity.handleFileArgument(magnet)

        assertTrue(handled)
        assertEquals(1, recordedNavRequests.size)
        assertEquals(R.id.global_to_navigation_player, recordedNavRequests[0].navigationId)
    }

    @Test
    @DisplayName("handleFileArgument launches file:// and content:// URIs")
    fun testHandleFileArgumentUris() {
        val fileUri = "file:///home/user/videos/series_s01e01.mkv"
        val handledFile = DownloadedPlayerActivity.handleFileArgument(fileUri)
        assertTrue(handledFile)
        assertEquals(1, recordedNavRequests.size)

        val contentUri = "content://com.android.providers.media.documents/document/video%3A123"
        val handledContent = DownloadedPlayerActivity.handleFileArgument(contentUri)
        assertTrue(handledContent)
        assertEquals(2, recordedNavRequests.size)
    }

    @Test
    @DisplayName("handleFileArgument launches HTTP and HTTPS direct media links")
    fun testHandleFileArgumentHttpStream() {
        val httpUrl = "https://storage.cloudstream.org/downloads/movie.mp4"
        val handled = DownloadedPlayerActivity.handleFileArgument(httpUrl)

        assertTrue(handled)
        assertEquals(1, recordedNavRequests.size)
    }

    @Test
    @DisplayName("handleFileArgument launches existing local filesystem file")
    fun testHandleFileArgumentExistingFile() {
        val sampleVideo = tempDir.resolve("local_movie.mp4").toFile().apply {
            writeText("video stream binary payload")
        }

        val handled = DownloadedPlayerActivity.handleFileArgument(sampleVideo.absolutePath)
        assertTrue(handled)
        assertEquals(1, recordedNavRequests.size)
    }

    @Test
    @DisplayName("handleFileArgument handles recognized media extensions even with paths to resolve")
    fun testHandleFileArgumentKnownExtensions() {
        val mediaExtensions = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".wmv", ".m4v", ".ts", ".m3u8")

        for ((idx, ext) in mediaExtensions.withIndex()) {
            val path = "/media/storage/films/movie_$idx$ext"
            val handled = DownloadedPlayerActivity.handleFileArgument(path)
            assertTrue(handled, "Media extension $ext must be recognized as playable target")
        }
        assertEquals(mediaExtensions.size, recordedNavRequests.size)
    }

    @Test
    @DisplayName("handleFileArgument returns false for blank or non-media argument")
    fun testHandleFileArgumentInvalid() {
        assertFalse(DownloadedPlayerActivity.handleFileArgument(null))
        assertFalse(DownloadedPlayerActivity.handleFileArgument(""))
        assertFalse(DownloadedPlayerActivity.handleFileArgument("   "))
        assertFalse(DownloadedPlayerActivity.handleFileArgument("not_a_media_file.txt"))
        assertFalse(DownloadedPlayerActivity.handleFileArgument("/tmp/document.pdf"))
        assertTrue(recordedNavRequests.isEmpty())
    }

    // =========================================================================
    // 5. Cross-Platform & Windows Path Format Tests
    // =========================================================================

    @Test
    @DisplayName("handleFileArgument supports Windows backslash paths and case-insensitive extensions")
    fun testWindowsPathsAndCaseInsensitiveExtensions() {
        val winPath = """C:\Users\Nitro\Videos\Action_Movie.MKV"""
        val handled = DownloadedPlayerActivity.handleFileArgument(winPath)
        assertTrue(handled)
        assertEquals(1, recordedNavRequests.size)

        val winPathUpper = """D:\Downloads\anime_episode.MP4"""
        val handledUpper = DownloadedPlayerActivity.handleFileArgument(winPathUpper)
        assertTrue(handledUpper)
        assertEquals(2, recordedNavRequests.size)
    }

    @Test
    @DisplayName("handleFileArgument strips enclosing quotes from CLI arguments")
    fun testQuotedCliArguments() {
        val quotedPath = "\"/home/user/My Videos/Holiday Trip.mp4\""
        val handled = DownloadedPlayerActivity.handleFileArgument(quotedPath)
        assertTrue(handled)
        assertEquals(1, recordedNavRequests.size)

        val singleQuotedPath = "'/home/user/Movies/SciFi.mkv'"
        val handledSingle = DownloadedPlayerActivity.handleFileArgument(singleQuotedPath)
        assertTrue(handledSingle)
        assertEquals(2, recordedNavRequests.size)
    }

    @Test
    @DisplayName("createViewIntent creates valid ACTION_VIEW Intent with proper Uri")
    fun testCreateViewIntent() {
        val filePath = "/var/media/demo.mp4"
        val intent = DownloadedPlayerActivity.createViewIntent(filePath)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertNotNull(intent.data)
        assertEquals("file", intent.data?.scheme)
        assertTrue(intent.data.toString().contains("demo.mp4"))
    }
}
