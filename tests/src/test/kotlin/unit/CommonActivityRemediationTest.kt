package unit

import android.app.Activity
import android.content.Context
import android.content.DesktopContextProvider
import android.view.KeyEvent
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.FocusDirection
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ToastData
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * JUnit 5 Unit Test suite for Cluster C04_CommonActivity:
 *
 * Verifies:
 * 1. Locale Resource Key Parity: R.string.locale_key resolution to "app_locale", Context.updateLocale() loading preferences correctly.
 * 2. Startup Torrent Pruning: Torrent.deleteAllFiles() purges leftover chunks recursively, and CommonActivity.init() triggers it safely.
 * 3. Media Key Event Dispatch: dispatchKeyEvent handles KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS, etc.,
 *    dispatching to keyEventListener both with an Activity and in headless/desktop mode (act == null).
 * 4. Theme Preference Keys: R.string.app_theme_key and R.string.primary_color_key parity.
 * 5. Toast Bus & Screen Metrics: Reactive toast bus emission and robust screen dimensions.
 */
class CommonActivityRemediationTest {

    private lateinit var testTempDir: Path
    private val originalLocale = Locale.getDefault()

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        testTempDir = Files.createTempDirectory("cs_test_common_activity")
        CommonActivity.keyEventListener = null
    }

    @AfterEach
    fun tearDown() {
        CommonActivity.keyEventListener = null
        Locale.setDefault(originalLocale)
        testTempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. Locale Resource Key Parity & Preference Loading
    // =========================================================================

    @Test
    fun testLocaleKeyResourceParityAndPreferenceLoading() {
        val act = Activity()
        CommonActivity.setActivityInstance(act)

        // Verify R.string.locale_key resolves to the canonical "app_locale" key
        val resolvedKey = act.getString(R.string.locale_key)
        assertEquals("app_locale", resolvedKey, "R.string.locale_key must resolve to 'app_locale' for upstream parity")

        // Configure SharedPreferences with a test locale
        val prefs = PreferenceManager.getDefaultSharedPreferences(act)
        prefs.edit().putString(resolvedKey, "tr").commit()

        // Execute updateLocale
        with(CommonActivity) {
            act.updateLocale()
        }

        assertEquals("tr", Locale.getDefault().language, "Context.updateLocale() should update Locale.getDefault() to 'tr'")

        // Update to another language
        prefs.edit().putString(resolvedKey, "de").commit()
        with(CommonActivity) {
            act.updateLocale()
        }

        assertEquals("de", Locale.getDefault().language, "Context.updateLocale() should update Locale.getDefault() to 'de'")
    }

    @Test
    fun testLocaleFallbackToAppLanguage() {
        val act = Activity()
        CommonActivity.setActivityInstance(act)

        val prefs = PreferenceManager.getDefaultSharedPreferences(act)
        // Remove app_locale and set legacy app_language
        prefs.edit().remove("app_locale").putString("app_language", "es").commit()

        with(CommonActivity) {
            act.updateLocale()
        }

        assertEquals("es", Locale.getDefault().language, "Context.updateLocale() should fallback to 'app_language' if 'app_locale' is absent")
    }

    @Test
    fun testSetLocaleDirect() {
        val act = Activity()
        CommonActivity.setLocale(act, "fr-FR")
        assertEquals("fr", Locale.getDefault().language)
        assertEquals("FR", Locale.getDefault().country)

        // Null checks
        CommonActivity.setLocale(null, "de")
        // Should not crash and should not alter locale
        assertEquals("fr", Locale.getDefault().language)

        CommonActivity.setLocale(act, null)
        assertEquals("fr", Locale.getDefault().language)
    }

    // =========================================================================
    // 2. Startup Torrent Temp File Pruning
    // =========================================================================

    @Test
    fun testTorrentDeleteAllFilesPruning() {
        val act = Activity()
        CommonActivity.setActivityInstance(act)

        val cache = act.cacheDir ?: CloudStreamApp.context?.cacheDir ?: PlatformPaths.cacheDir.toFile()
        val torrentDir = File(cache, "torrent_tmp")
        torrentDir.mkdirs()

        val chunk1 = File(torrentDir, "piece_001.part")
        val chunk2 = File(torrentDir, "subfolder/piece_002.part")
        chunk2.parentFile?.mkdirs()
        chunk1.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        chunk2.writeBytes(byteArrayOf(6, 7, 8, 9, 10))

        assertTrue(chunk1.exists(), "Test chunk 1 should exist prior to cleanup")
        assertTrue(chunk2.exists(), "Test chunk 2 should exist prior to cleanup")

        // Perform pruning
        val deleted = Torrent.deleteAllFiles()
        assertTrue(deleted, "Torrent.deleteAllFiles() should return true when directory is pruned")
        assertFalse(torrentDir.exists(), "torrent_tmp directory must be deleted recursively")
        assertFalse(chunk1.exists(), "chunk 1 must be deleted")
        assertFalse(chunk2.exists(), "chunk 2 must be deleted")

        // Second deletion when directory does not exist should not throw
        assertDoesNotThrow {
            Torrent.deleteAllFiles()
        }
    }

    @Test
    fun testCommonActivityInitPerformsPruningAndLocale() {
        val act = Activity()
        val prefs = PreferenceManager.getDefaultSharedPreferences(act)
        prefs.edit().putString("app_locale", "it").commit()

        val cache = act.cacheDir ?: CloudStreamApp.context?.cacheDir ?: PlatformPaths.cacheDir.toFile()
        val torrentDir = File(cache, "torrent_tmp")
        torrentDir.mkdirs()
        val testChunk = File(torrentDir, "init_test.part")
        testChunk.writeText("torrent stream data")

        assertTrue(testChunk.exists(), "Test chunk must exist before CommonActivity.init")

        // Call CommonActivity.init
        CommonActivity.init(act)

        // Verify activity is set
        assertEquals(act, CommonActivity.activity)

        // Verify locale was updated
        assertEquals("it", Locale.getDefault().language, "CommonActivity.init should call act.updateLocale()")

        // Give background ioSafe coroutine a brief window or check directly
        // Note: Torrent.deleteAllFiles() was scheduled in ioSafe
        Thread.sleep(100)
        assertFalse(testChunk.exists(), "torrent_tmp files should be pruned after CommonActivity.init()")
    }

    // =========================================================================
    // 3. Media Key Event Dispatching
    // =========================================================================

    @Test
    fun testMediaKeyDispatchToKeyEventListener() {
        val act = Activity()
        CommonActivity.setActivityInstance(act)

        var lastReceivedEvent: Pair<KeyEvent?, Boolean>? = null
        var shouldConsume = true

        CommonActivity.keyEventListener = { pair ->
            lastReceivedEvent = pair
            shouldConsume
        }

        val mediaKeys = listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
            KeyEvent.KEYCODE_SPACE
        )

        for (keyCode in mediaKeys) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val handled = CommonActivity.dispatchKeyEvent(act, event)

            assertTrue(handled == true, "dispatchKeyEvent should return true when keyEventListener consumes $keyCode")
            assertNotNull(lastReceivedEvent, "keyEventListener should receive the event")
            assertEquals(keyCode, lastReceivedEvent?.first?.keyCode)
            assertFalse(lastReceivedEvent?.second ?: true, "hasNavigated should be false for media keys")
        }

        // Test unconsumed event returns null
        shouldConsume = false
        val pauseEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
        val notHandled = CommonActivity.dispatchKeyEvent(act, pauseEvent)
        assertNull(notHandled, "dispatchKeyEvent should return null when keyEventListener does not consume the event")
    }

    @Test
    fun testMediaKeyDispatchWithoutActivity() {
        // In desktop mode, key events may be dispatched without an explicit Android Activity instance
        CommonActivity.setActivityInstance(null)

        var capturedEvent: Pair<KeyEvent?, Boolean>? = null
        CommonActivity.keyEventListener = { pair ->
            capturedEvent = pair
            true
        }

        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        val handled = CommonActivity.dispatchKeyEvent(null, event)

        assertTrue(handled == true, "dispatchKeyEvent(null, event) must still dispatch to keyEventListener")
        assertNotNull(capturedEvent)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, capturedEvent?.first?.keyCode)
    }

    @Test
    fun testKeyEventIsMediaKeyHelper() {
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_STOP))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD))
        assertTrue(KeyEvent.isMediaKey(KeyEvent.KEYCODE_VOLUME_MUTE))

        assertFalse(KeyEvent.isMediaKey(KeyEvent.KEYCODE_DPAD_UP))
        assertFalse(KeyEvent.isMediaKey(KeyEvent.KEYCODE_ENTER))
        assertFalse(KeyEvent.isMediaKey(KeyEvent.KEYCODE_SPACE))
    }

    // =========================================================================
    // 4. Theme Resource Keys Parity
    // =========================================================================

    @Test
    fun testThemeResourceKeysParity() {
        val act = Activity()
        assertEquals("app_theme_key", act.getString(R.string.app_theme_key))
        assertEquals("primary_color_key", act.getString(R.string.primary_color_key))

        val prefs = PreferenceManager.getDefaultSharedPreferences(act)
        prefs.edit()
            .putString("app_theme_key", "Dracula")
            .putString("primary_color_key", "Green")
            .commit()

        CommonActivity.loadThemes(act)

        assertEquals(6, CommonActivity.appliedTheme, "Dracula theme should map to id 6")
        assertEquals(113, CommonActivity.appliedColor, "Green primary color should map to id 113")
    }

    // =========================================================================
    // 5. Toast Bus Emission & Screen Metrics
    // =========================================================================

    @Test
    fun testToastBusAndEvent() = runBlocking {
        var eventReceived: ToastData? = null
        val observer: (ToastData) -> Unit = { eventReceived = it }
        CommonActivity.toastEvent += observer

        val msg = "Testing CommonActivity Toast System"
        CommonActivity.showToast(msg, Toast.LENGTH_LONG)

        val flowItem = CommonActivity.toastSharedFlow.first()
        assertEquals(msg, flowItem.message)
        assertEquals(Toast.LENGTH_LONG, flowItem.duration)

        assertNotNull(eventReceived)
        assertEquals(msg, eventReceived?.message)
        assertEquals(Toast.LENGTH_LONG, eventReceived?.duration)

        CommonActivity.toastEvent -= observer
    }

    @Test
    fun testFocusDirectionAndScreenDimensions() {
        assertEquals(4, FocusDirection.values().size)
        assertEquals(FocusDirection.Start, FocusDirection.valueOf("Start"))
        assertEquals(FocusDirection.End, FocusDirection.valueOf("End"))
        assertEquals(FocusDirection.Up, FocusDirection.valueOf("Up"))
        assertEquals(FocusDirection.Down, FocusDirection.valueOf("Down"))

        assertTrue(CommonActivity.screenWidth > 0)
        assertTrue(CommonActivity.screenHeight > 0)
        assertTrue(CommonActivity.screenWidth >= CommonActivity.screenHeight)
        assertTrue(CommonActivity.screenWidthWithOrientation > 0)
        assertTrue(CommonActivity.screenHeightWithOrientation > 0)
        assertNotNull(CommonActivity.displayMetrics)
    }
}
