package unit

import android.content.DesktopContextProvider
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CustomSite
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.BasicLink
import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

open class TestMainApiParent : MainAPI() {
    override var name = "TestParentProvider"
    override var mainUrl = "https://parent.example.com"
}

/**
 * Enterprise JUnit 5 verification suite for Atomic Cluster C05 (MainActivity).
 *
 * Verifies:
 * 1. Plugin Provider Synchronization:
 *    - MainActivity.onAllPluginsLoaded delegates to APIRepository.syncProviders().
 *    - CustomSite clone provider instantiation and distinct provider registration.
 *    - Invalidation of APIHolder.apiMap and update of APIHolder.apis.
 * 2. Deep-Link Intent Routing (handleAppIntentUrl):
 *    - Repository import URLs (https://cs.repo, cs.repo, cloudstreamrepo://).
 *    - Magnet URI parsing (magnet:?xt=...&dn=...) and player event dispatch.
 *    - Torrent URL parsing (.torrent) and player event dispatch.
 *    - Search URI parsing (cloudstreamsearch://, cloudstreamapp://search?query=...).
 *    - Player URI parsing (cloudstreamplayer://, cloudstreamapp://player?...).
 *    - Resume watching URI parsing (cloudstreamcontinuewatching://, cloudstreamapp://continuewatching?id=...).
 *    - Share URI parsing (csshare:<b64>?<b64>).
 *    - Download navigation URI parsing (cloudstream://download, cloudstreamapp://downloads).
 * 3. BackPressedDispatcher & Back-Stack History:
 *    - LIFO callback dispatch order.
 *    - Disabled callback bypass.
 *    - attachBackPressedCallback and detachBackPressedCallback lifecycle.
 *    - MainActivity.navigateBack() integration with active dispatcher and event bus.
 * 4. Exit File Cleanup & Diagnostics:
 *    - deleteFileOnExit registration in DataStore.
 *    - cleanUpFilesToDelete disk file deletion and key reset.
 *    - setLastError reading crash diagnostic file.
 */
class MainActivityRemediationTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        DesktopDataStore.init()
        tempDir = Files.createTempDirectory("cs_main_activity_test")
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
        MainActivity.backStackHistory.clear()
        MainActivity.nextSearchQuery = null
    }

    // =========================================================================
    // 1. Plugin Provider Synchronization Tests
    // =========================================================================

    @Test
    fun testOnAllPluginsLoadedPopulatesProvidersAndClones() = runBlocking {
        // Register test parent provider
        val parentApi = TestMainApiParent()
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.clear()
            APIHolder.allProviders.add(parentApi)
        }

        // Configure custom clone site in DataStore
        val customClone = CustomSite(
            parentClassName = "TestMainApiParent",
            name = "ClonedTestProvider",
            url = "https://clone.example.com",
            lang = "en"
        )
        CloudStreamApp.setKey(USER_PROVIDER_API, arrayOf(customClone))

        // Trigger onAllPluginsLoaded
        MainActivity.onAllPluginsLoaded(false)

        // Allow ioSafe coroutine to complete
        Thread.sleep(250)

        // Verify providers were synchronized into APIHolder.apis
        APIHolder.apis.withLock {
            val names = APIHolder.apis.map { it.name }
            assertTrue(names.contains("TestParentProvider"), "Expected parent provider in APIHolder.apis")
            assertTrue(names.contains("ClonedTestProvider"), "Expected cloned provider in APIHolder.apis")
        }
        assertNull(APIHolder.apiMap, "apiMap cache should be invalidated after provider sync")

        // Cleanup
        CloudStreamApp.setKey(USER_PROVIDER_API, emptyArray<CustomSite>())
    }

    @Test
    fun testAfterPluginsLoadedEventTriggersSync() = runBlocking {
        var triggered = false
        val listener: (Boolean) -> Unit = { triggered = true }
        MainActivity.afterPluginsLoadedEvent += listener

        try {
            MainActivity.afterPluginsLoadedEvent.invoke(true)
            assertTrue(triggered, "afterPluginsLoadedEvent should invoke attached listeners")
        } finally {
            MainActivity.afterPluginsLoadedEvent -= listener
        }
    }

    // =========================================================================
    // 2. Deep-Link Intent Routing (handleAppIntentUrl) Tests
    // =========================================================================

    @Test
    fun testHandleCsRepoDeepLink() {
        var repoEventFired = false
        val listener: (Boolean) -> Unit = { repoEventFired = true }
        MainActivity.afterRepositoryLoadedEvent += listener

        try {
            // cs.repo format
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "https://cs.repo?https://raw.githubusercontent.com/test/repo/master/repo.json",
                isWebview = false
            )
            assertTrue(handled, "https://cs.repo link should be handled")
        } finally {
            MainActivity.afterRepositoryLoadedEvent -= listener
        }
    }

    @Test
    fun testHandleCloudstreamRepoScheme() {
        val handled = MainActivity.handleAppIntentUrl(
            activity = null,
            str = "cloudstreamrepo://raw.githubusercontent.com/test/repo/master/repo.json",
            isWebview = false
        )
        assertTrue(handled, "cloudstreamrepo:// link should be handled")
    }

    @Test
    fun testHandleMagnetLinkIntent() {
        var receivedMagnetLink: String? = null
        var receivedPlayerLink: BasicLink? = null

        val magnetListener: (String) -> Unit = { receivedMagnetLink = it }
        val playerListener: (BasicLink) -> Unit = { receivedPlayerLink = it }

        MainActivity.magnetIntentEvent += magnetListener
        MainActivity.playerIntentEvent += playerListener

        try {
            val magnetUrl = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74fedb&dn=Big+Buck+Bunny"
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = magnetUrl,
                isWebview = false
            )

            assertTrue(handled, "magnet: URL should be handled")
            assertEquals(magnetUrl, receivedMagnetLink)
            assertNotNull(receivedPlayerLink)
            assertEquals(magnetUrl, receivedPlayerLink?.url)
            assertEquals("Big Buck Bunny", receivedPlayerLink?.name)
        } finally {
            MainActivity.magnetIntentEvent -= magnetListener
            MainActivity.playerIntentEvent -= playerListener
        }
    }

    @Test
    fun testHandleTorrentUrlIntent() {
        var receivedPlayerLink: BasicLink? = null
        val playerListener: (BasicLink) -> Unit = { receivedPlayerLink = it }
        MainActivity.playerIntentEvent += playerListener

        try {
            val torrentUrl = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso.torrent"
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = torrentUrl,
                isWebview = false
            )

            assertTrue(handled, ".torrent link should be handled")
            assertNotNull(receivedPlayerLink)
            assertEquals(torrentUrl, receivedPlayerLink?.url)
            assertEquals("ubuntu-24.04-desktop-amd64.iso.torrent", receivedPlayerLink?.name)
        } finally {
            MainActivity.playerIntentEvent -= playerListener
        }
    }

    @Test
    fun testHandleSearchDeepLinks() {
        var searchEventQuery: String? = null
        val searchListener: (String?) -> Unit = { searchEventQuery = it }
        MainActivity.searchIntentEvent += searchListener

        try {
            // cloudstreamsearch:// scheme
            val handled1 = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstreamsearch://cyberpunk%20edgerunners",
                isWebview = false
            )
            assertTrue(handled1, "cloudstreamsearch:// should be handled")
            assertEquals("cyberpunk edgerunners", searchEventQuery)
            assertEquals("cyberpunk edgerunners", MainActivity.nextSearchQuery)

            // cloudstreamapp://search?query= format
            val handled2 = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstreamapp://search?query=interstellar",
                isWebview = false
            )
            assertTrue(handled2, "cloudstreamapp://search?query= should be handled")
            assertEquals("interstellar", searchEventQuery)
            assertEquals("interstellar", MainActivity.nextSearchQuery)
        } finally {
            MainActivity.searchIntentEvent -= searchListener
        }
    }

    @Test
    fun testHandlePlayerDeepLink() {
        var playerLink: BasicLink? = null
        val playerListener: (BasicLink) -> Unit = { playerLink = it }
        MainActivity.playerIntentEvent += playerListener

        try {
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstreamplayer://https%3A%2F%2Fcdn.example.com%2Fvideo.m3u8?name=Big+Buck+Bunny",
                isWebview = false
            )
            assertTrue(handled, "cloudstreamplayer:// should be handled")
            assertNotNull(playerLink)
            assertEquals("https://cdn.example.com/video.m3u8", playerLink?.url)
            assertEquals("Big Buck Bunny", playerLink?.name)
        } finally {
            MainActivity.playerIntentEvent -= playerListener
        }
    }

    @Test
    fun testHandleResumeWatchingDeepLink() {
        var receivedId: Int? = null
        val listener: (Int) -> Unit = { receivedId = it }
        MainActivity.resumeWatchingIntentEvent += listener

        try {
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstreamcontinuewatching://10492",
                isWebview = false
            )
            assertTrue(handled, "cloudstreamcontinuewatching:// should be handled")
            assertEquals(10492, receivedId)

            val handledApp = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstreamapp://continuewatching?id=2048",
                isWebview = false
            )
            assertTrue(handledApp, "cloudstreamapp://continuewatching?id= should be handled")
            assertEquals(2048, receivedId)
        } finally {
            MainActivity.resumeWatchingIntentEvent -= listener
        }
    }

    @Test
    fun testHandleShareDeepLink() {
        var receivedResult: Triple<String, String, String>? = null
        val listener: (Triple<String, String, String>) -> Unit = { receivedResult = it }
        MainActivity.loadResultIntentEvent += listener

        try {
            val apiName = "TestApi"
            val targetUrl = "https://example.com/show/1"
            val b64Api = Base64.getEncoder().encodeToString(apiName.toByteArray(Charsets.UTF_8))
            val b64Url = Base64.getEncoder().encodeToString(targetUrl.toByteArray(Charsets.UTF_8))

            val shareUri = "csshare:$b64Api?$b64Url"
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = shareUri,
                isWebview = false
            )
            assertTrue(handled, "csshare: link should be handled")
            assertNotNull(receivedResult)
            assertEquals(targetUrl, receivedResult?.first)
            assertEquals(apiName, receivedResult?.second)
        } finally {
            MainActivity.loadResultIntentEvent -= listener
        }
    }

    @Test
    fun testHandleDownloadsNavigationDeepLink() {
        var downloadsNavigated = false
        val listener: (Boolean) -> Unit = { downloadsNavigated = true }
        MainActivity.navigateDownloadsEvent += listener

        try {
            val handled = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstream://download",
                isWebview = false
            )
            assertTrue(handled, "cloudstream://download should be handled")
            assertTrue(downloadsNavigated)

            downloadsNavigated = false
            val handledApp = MainActivity.handleAppIntentUrl(
                activity = null,
                str = "cloudstreamapp://downloads",
                isWebview = false
            )
            assertTrue(handledApp, "cloudstreamapp://downloads should be handled")
            assertTrue(downloadsNavigated)
        } finally {
            MainActivity.navigateDownloadsEvent -= listener
        }
    }

    // =========================================================================
    // 3. BackPressedDispatcher & Back-Stack History Tests
    // =========================================================================

    @Test
    fun testBackPressedDispatcherLifoOrderAndEnablement() {
        val activity = MainActivity()
        val dispatcher = activity.onBackPressedDispatcher

        val executionOrder = mutableListOf<String>()

        val callback1 = object : MainActivity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                executionOrder.add("CB1")
            }
        }
        val callback2 = object : MainActivity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                executionOrder.add("CB2")
            }
        }

        dispatcher.addCallback(callback1)
        dispatcher.addCallback(callback2)

        // Should invoke callback2 first (LIFO)
        val handled = dispatcher.onBackPressed()
        assertTrue(handled)
        assertEquals(listOf("CB2"), executionOrder)

        // Disable callback2, next press should invoke callback1
        callback2.isEnabled = false
        val handledSecond = dispatcher.onBackPressed()
        assertTrue(handledSecond)
        assertEquals(listOf("CB2", "CB1"), executionOrder)

        // Disable callback1, no callbacks left enabled
        callback1.isEnabled = false
        val handledThird = dispatcher.onBackPressed()
        assertFalse(handledThird, "Expected false when no callbacks are enabled")
    }

    @Test
    fun testAttachAndDetachBackPressedCallback() {
        val activity = MainActivity()
        var runCount = 0

        activity.attachBackPressedCallback("TestCallback") {
            runCount++
        }
        assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())

        activity.onBackPressed()
        assertEquals(1, runCount)

        activity.detachBackPressedCallback("TestCallback")
        assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun testNavigateBackWithHistory() {
        var backEventFired = false
        val listener: (Unit) -> Unit = { backEventFired = true }
        MainActivity.backPressedEvent += listener

        try {
            MainActivity.backStackHistory.add("Screen.Home")
            MainActivity.backStackHistory.add("Screen.Details")

            val navigated = MainActivity.navigateBack()
            assertTrue(navigated)
            assertEquals(1, MainActivity.backStackHistory.size)
            assertEquals("Screen.Home", MainActivity.backStackHistory.first())
            assertTrue(backEventFired)
        } finally {
            MainActivity.backPressedEvent -= listener
        }
    }

    // =========================================================================
    // 4. Temporary Exit File Cleanup & Diagnostics Tests
    // =========================================================================

    @Test
    fun testDeleteFileOnExitAndCleanUpFiles() {
        val testFile = tempDir.resolve("exit_cleanup_test.txt").toFile()
        testFile.writeText("sample data to be cleaned on exit")
        assertTrue(testFile.exists())

        MainActivity.deleteFileOnExit(testFile)

        // Execute cleanup
        MainActivity.cleanUpFilesToDelete()

        assertFalse(testFile.exists(), "Temporary file should have been deleted by cleanUpFilesToDelete()")
    }

    @Test
    fun testSetLastErrorProcessesAndRemovesDiagnosticFile() {
        val context = DesktopContextProvider.context
        val errorFile = context.filesDir.resolve("last_error")
        errorFile.writeText("Fatal JVM Crash: NullPointerException in VideoDecoder")
        assertTrue(errorFile.exists())

        try {
            MainActivity.lastError = null
            MainActivity.setLastError(context)

            assertNotNull(MainActivity.lastError)
            assertTrue(MainActivity.lastError!!.contains("Fatal JVM Crash"))
            assertFalse(errorFile.exists(), "last_error file should be deleted after processing")
        } finally {
            MainActivity.lastError = null
            errorFile.delete()
        }
    }
}
