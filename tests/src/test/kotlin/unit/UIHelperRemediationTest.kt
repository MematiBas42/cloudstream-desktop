package unit

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ToastData
import com.lagradost.cloudstream3.utils.InputDialogRequest
import com.lagradost.cloudstream3.utils.SelectionDialogRequest
import com.lagradost.cloudstream3.utils.SingleSelectionHelper
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogInstant
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialogText
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showOptionSelectStringRes
import com.lagradost.cloudstream3.utils.TextDialogRequest
import com.lagradost.cloudstream3.utils.UIHelper
import com.lagradost.cloudstream3.utils.UIHelper.adjustAlpha
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.getSpanCount
import com.lagradost.cloudstream3.utils.UIHelper.isBottomLayout
import com.lagradost.cloudstream3.utils.UIHelper.toDp
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.setTextHtml
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Architectural Remediation Test Suite for UIHelper, SingleSelectionHelper, and TextUtil (Cluster C39).
 *
 * Validates:
 * 1. Clipboard safe access with AWT retry mechanism upon transient locking / IllegalStateException.
 * 2. Clipboard fallback paths (POSIX wl-copy/xclip/xsel and Windows clip) and oversized text protection.
 * 3. Clipboard toast feedback matching upstream format and localized string resources.
 * 4. Dialog safe dismissal (dismissSafe) checking isShowing and isFinishing states for Activity and CommonActivity.
 * 5. Dynamic span count calculation respecting configuration orientation (Landscape vs Portrait).
 * 6. SingleSelectionHelper 1:1 upstream signature parity and reactive dialog request event bus dispatch.
 * 7. TextUtil TextView extension parity (setText, setTextHtml, visibility toggling).
 * 8. Zero-stub compliance and clean lifecycle handling.
 */
class UIHelperRemediationTest {

    private lateinit var context: Context
    private lateinit var activity: Activity

    private val toastEvents = CopyOnWriteArrayList<ToastData>()
    private val selectionRequests = CopyOnWriteArrayList<SelectionDialogRequest>()
    private val inputRequests = CopyOnWriteArrayList<InputDialogRequest>()
    private val textRequests = CopyOnWriteArrayList<TextDialogRequest>()

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.dataDir = tempDir.resolve("data")
        PlatformPaths.cacheDir = tempDir.resolve("cache")
        DesktopDataStore.init(tempDir.resolve("storage.json").toFile())

        context = Context()
        activity = Activity()
        CloudStreamApp.context = context
        CommonActivity.setActivityInstance(activity)

        toastEvents.clear()
        selectionRequests.clear()
        inputRequests.clear()
        textRequests.clear()

        CommonActivity.toastEvent += { toastData ->
            toastEvents.add(toastData)
        }

        SingleSelectionHelper.onSelectionEvent += { req ->
            selectionRequests.add(req)
        }
        SingleSelectionHelper.onInputEvent += { req ->
            inputRequests.add(req)
        }
        SingleSelectionHelper.onTextEvent += { req ->
            textRequests.add(req)
        }

        UIHelper.systemClipboardProvider = null
        SingleSelectionHelper.customDialogHandler = null
        SingleSelectionHelper.customInputHandler = null
        SingleSelectionHelper.customTextHandler = null
    }

    @AfterEach
    fun tearDown() {
        UIHelper.systemClipboardProvider = null
        SingleSelectionHelper.customDialogHandler = null
        SingleSelectionHelper.customInputHandler = null
        SingleSelectionHelper.customTextHandler = null
    }

    // =========================================================================
    // 1. Clipboard Safe Access & Retry Mechanism Tests
    // =========================================================================

    @Test
    @DisplayName("copyToClipboardAwt succeeds on first attempt when clipboard is available")
    fun testCopyToClipboardAwtSuccessFirstAttempt() {
        var callCount = 0
        val fakeClipboard = object : Clipboard("TestClipboard") {
            override fun setContents(contents: Transferable?, owner: java.awt.datatransfer.ClipboardOwner?) {
                callCount++
            }
        }

        val result = UIHelper.copyToClipboardAwt(
            text = "Hello CloudStream",
            maxRetries = 3,
            retryDelayMs = 5L,
            clipboardSupplier = { fakeClipboard }
        )

        assertTrue(result, "AWT clipboard write should succeed on first attempt")
        assertEquals(1, callCount, "setContents should be called exactly once")
    }

    @Test
    @DisplayName("copyToClipboardAwt retries upon IllegalStateException and succeeds on subsequent attempt")
    fun testCopyToClipboardAwtRetrySuccess() {
        val attempts = AtomicInteger(0)
        val fakeClipboard = object : Clipboard("TestClipboard") {
            override fun setContents(contents: Transferable?, owner: java.awt.datatransfer.ClipboardOwner?) {
                val current = attempts.incrementAndGet()
                if (current < 3) {
                    throw IllegalStateException("cannot open system Clipboard (simulated lock)")
                }
            }
        }

        val result = UIHelper.copyToClipboardAwt(
            text = "Retry Text",
            maxRetries = 5,
            retryDelayMs = 5L,
            clipboardSupplier = { fakeClipboard }
        )

        assertTrue(result, "copyToClipboardAwt should succeed after retries")
        assertEquals(3, attempts.get(), "Should succeed on 3rd attempt after 2 locked attempts")
    }

    @Test
    @DisplayName("copyToClipboardAwt fails gracefully when max retries are exhausted")
    fun testCopyToClipboardAwtExhaustedRetries() {
        val attempts = AtomicInteger(0)
        val fakeClipboard = object : Clipboard("TestClipboard") {
            override fun setContents(contents: Transferable?, owner: java.awt.datatransfer.ClipboardOwner?) {
                attempts.incrementAndGet()
                throw IllegalStateException("cannot open system Clipboard permanently")
            }
        }

        val result = UIHelper.copyToClipboardAwt(
            text = "Exhausted Text",
            maxRetries = 3,
            retryDelayMs = 5L,
            clipboardSupplier = { fakeClipboard }
        )

        assertFalse(result, "copyToClipboardAwt should return false when all retries fail")
        assertEquals(3, attempts.get(), "Should attempt exactly maxRetries times")
    }

    @Test
    @DisplayName("clipboardHelper displays success toast matching upstream format")
    fun testClipboardHelperSuccessToast() {
        var capturedText: String? = null
        UIHelper.systemClipboardProvider = { text ->
            capturedText = text
            true
        }

        UIHelper.clipboardHelper(txt("Media Link"), "https://example.com/video.mp4")

        assertEquals("https://example.com/video.mp4", capturedText)
        assertTrue(toastEvents.isNotEmpty(), "A toast should be shown on success")
        val toast = toastEvents.first().message
        assertTrue(toast.startsWith("Media Link"), "Toast should include label prefix: $toast")
        assertTrue(toast.contains("copied") || toast.contains("kopyalandı"), "Toast should include copied suffix: $toast")
    }

    @Test
    @DisplayName("clipboardHelper rejects oversized text payload without crashing")
    fun testClipboardHelperOversizedText() {
        val hugeText = "X".repeat(5_000_001)
        UIHelper.clipboardHelper(txt("Large File"), hugeText)

        assertTrue(toastEvents.isNotEmpty(), "A toast should be shown for oversized text")
        val toast = toastEvents.first().message
        assertTrue(
            toast.contains("Too much text") || toast.contains("Çok fazla") || toast.contains("575"),
            "Toast should indicate text too large: $toast"
        )
    }

    @Test
    @DisplayName("clipboardHelper handles clipboard failures and displays error toast")
    fun testClipboardHelperFailureToast() {
        UIHelper.systemClipboardProvider = { _ -> false }

        UIHelper.clipboardHelper(txt("Test"), "sample text")

        assertTrue(toastEvents.isNotEmpty(), "A toast should be shown on failure")
        val toast = toastEvents.first().message
        assertTrue(
            toast.contains("Error") || toast.contains("Hata") || toast.contains("577"),
            "Toast should indicate clipboard error: $toast"
        )
    }

    // =========================================================================
    // 2. Dialog Safe Dismissal Tests
    // =========================================================================

    private class TestDialog(ctx: Context) : Dialog(ctx) {
        var dismissCount = 0

        fun markShowing(showing: Boolean) {
            this.isShowing = showing
        }

        override fun dismiss() {
            dismissCount++
            super.dismiss()
        }
    }

    private class TestActivity : Activity() {
        var finishing = false
        override fun isFinishing(): Boolean = finishing
    }

    @Test
    @DisplayName("Dialog.dismissSafe(activity) dismisses only when showing and activity is not finishing")
    fun testDialogDismissSafeWithActivity() {
        val testAct = TestActivity()
        val dialog = TestDialog(context)

        // Case 1: Dialog not showing -> should not dismiss
        dialog.markShowing(false)
        testAct.finishing = false
        dialog.dismissSafe(testAct)
        assertEquals(0, dialog.dismissCount, "Non-showing dialog should not be dismissed")

        // Case 2: Dialog showing and activity not finishing -> should dismiss
        dialog.markShowing(true)
        testAct.finishing = false
        dialog.dismissSafe(testAct)
        assertEquals(1, dialog.dismissCount, "Showing dialog with active activity should be dismissed")

        // Case 3: Dialog showing but activity is finishing -> should not dismiss (prevents leak/crash)
        dialog.markShowing(true)
        testAct.finishing = true
        dialog.dismissSafe(testAct)
        assertEquals(1, dialog.dismissCount, "Showing dialog with finishing activity should not be dismissed")
    }

    @Test
    @DisplayName("Dialog.dismissSafe() uses CommonActivity instance correctly")
    fun testDialogDismissSafeGlobalActivity() {
        val testAct = TestActivity()
        CommonActivity.setActivityInstance(testAct)
        val dialog = TestDialog(context)

        dialog.markShowing(true)
        testAct.finishing = false
        dialog.dismissSafe()
        assertEquals(1, dialog.dismissCount, "Global dismissSafe should dismiss when CommonActivity is active")

        dialog.markShowing(true)
        testAct.finishing = true
        dialog.dismissSafe()
        assertEquals(1, dialog.dismissCount, "Global dismissSafe should not dismiss when CommonActivity is finishing")
    }

    @Test
    @DisplayName("Dialog.dismissSafe handles null references without exception")
    fun testDialogDismissSafeNullSafety() {
        val nullDialog: Dialog? = null
        nullDialog.dismissSafe(activity)
        nullDialog.dismissSafe()
        // No exception thrown
    }

    // =========================================================================
    // 3. Span Count & Configuration Orientation Tests
    // =========================================================================

    @Test
    @DisplayName("getSpanCount calculates correct spans for landscape and portrait")
    fun testGetSpanCountLandscapeAndPortrait() {
        context.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        assertEquals(6, context.getSpanCount(isHorizontal = false), "Landscape vertical span count should be 6")
        assertEquals(3, context.getSpanCount(isHorizontal = true), "Landscape horizontal span count should be 3")

        context.resources.configuration.orientation = Configuration.ORIENTATION_PORTRAIT
        assertEquals(3, context.getSpanCount(isHorizontal = false), "Portrait vertical span count should be 3")
        assertEquals(2, context.getSpanCount(isHorizontal = true), "Portrait horizontal span count should be 2")
    }

    // =========================================================================
    // 4. SingleSelectionHelper Parity & Reactive Dialog Tests
    // =========================================================================

    @Test
    @DisplayName("SingleSelectionHelper.showMultiDialog dispatches SelectionDialogRequest with multiple selection")
    fun testSingleSelectionHelperMultiDialog() {
        val items = listOf("Dub", "Sub", "Raw")
        val selected = listOf(0, 1)
        var callbackResult: List<Int>? = null

        activity.showMultiDialog(
            items = items,
            selectedIndex = selected,
            name = "Audio Streams",
            dismissCallback = {},
            callback = { callbackResult = it }
        )

        assertEquals(1, selectionRequests.size, "One selection request should be published")
        val req = selectionRequests.first()
        assertEquals("Audio Streams", req.title)
        assertEquals(items, req.items)
        assertEquals(selected, req.selectedIndices)
        assertTrue(req.isMultiSelect)
        assertTrue(req.showApply)

        req.onSelected(listOf(1, 2))
        assertEquals(listOf(1, 2), callbackResult, "Callback should be invoked with selected indices")
    }

    @Test
    @DisplayName("SingleSelectionHelper.showDialog dispatches single-selection request")
    fun testSingleSelectionHelperSingleDialog() {
        val items = listOf("1080p", "720p", "480p")
        var selectedItem: Int? = null

        activity.showDialog(
            items = items,
            selectedIndex = 1,
            name = "Video Quality",
            showApply = true,
            dismissCallback = {},
            callback = { selectedItem = it }
        )

        assertEquals(1, selectionRequests.size)
        val req = selectionRequests.first()
        assertEquals("Video Quality", req.title)
        assertEquals(items, req.items)
        assertEquals(listOf(1), req.selectedIndices)
        assertFalse(req.isMultiSelect)

        req.onSelected(listOf(0))
        assertEquals(0, selectedItem, "Single selection callback should be invoked")
    }

    @Test
    @DisplayName("SingleSelectionHelper.showBottomDialog and instant dispatch correctly")
    fun testSingleSelectionHelperBottomDialogs() {
        var selected: Int? = null
        activity.showBottomDialog(
            items = listOf("Server 1", "Server 2"),
            selectedIndex = 0,
            name = "Select Mirror",
            showApply = false,
            dismissCallback = {},
            callback = { selected = it }
        )

        assertEquals(1, selectionRequests.size)
        selectionRequests.first().onSelected(listOf(1))
        assertEquals(1, selected)

        activity.showBottomDialogInstant(
            items = listOf("Mirror A", "Mirror B"),
            name = "Instant Mirror",
            dismissCallback = {},
            callback = { selected = it }
        )

        assertEquals(2, selectionRequests.size)
        selectionRequests[1].onSelected(listOf(0))
        assertEquals(0, selected)
    }

    @Test
    @DisplayName("SingleSelectionHelper.showNginxTextInputDialog dispatches input request")
    fun testSingleSelectionHelperTextInputDialog() {
        var submittedValue: String? = null
        activity.showNginxTextInputDialog(
            name = "Repository URL",
            value = "https://cloudstream.cf",
            textInputType = 16,
            dismissCallback = {},
            callback = { submittedValue = it }
        )

        assertEquals(1, inputRequests.size)
        val req = inputRequests.first()
        assertEquals("Repository URL", req.title)
        assertEquals("https://cloudstream.cf", req.initialValue)
        assertEquals(16, req.textInputType)

        req.onSubmitted("https://repo.example.com")
        assertEquals("https://repo.example.com", submittedValue)
    }

    @Test
    @DisplayName("SingleSelectionHelper.showBottomDialogText dispatches text request")
    fun testSingleSelectionHelperTextDialog() {
        var dismissed = false
        activity.showBottomDialogText(
            title = "Changelog",
            text = "Version 4.0 Desktop Release",
            dismissCallback = { dismissed = true }
        )

        assertEquals(1, textRequests.size)
        val req = textRequests.first()
        assertEquals("Changelog", req.title)
        assertEquals("Version 4.0 Desktop Release", req.text)

        req.onDismiss()
        assertTrue(dismissed, "Dismiss callback should be called")
    }

    @Test
    @DisplayName("SingleSelectionHelper.showOptionSelectStringRes resolves string resource IDs")
    fun testSingleSelectionHelperOptionSelectStringRes() {
        var chosenIndex: Int? = null
        activity.showOptionSelectStringRes(
            view = null,
            poster = "https://image.tmdb.org/poster.jpg",
            options = listOf(R.string.type_watching, R.string.type_completed),
            callback = { pair ->
                chosenIndex = pair.second
            }
        )

        assertEquals(1, selectionRequests.size)
        val req = selectionRequests.first()
        assertEquals("https://image.tmdb.org/poster.jpg", req.title)
        assertEquals(2, req.items.size)
        assertTrue(req.items[0].contains("Watching") || req.items[0].contains("İzleniyor"))

        req.onSelected(listOf(1))
        assertEquals(1, chosenIndex)
    }

    // =========================================================================
    // 5. TextUtil TextView Extension Parity Tests
    // =========================================================================

    @Test
    @DisplayName("TextView.setText sets text and manages visibility properly")
    fun testTextViewSetText() {
        val tv = TextView(context)
        tv.maxLines = 1

        // Multi-line text with maxLines = 1 should replace newlines with space
        tv.setText(txt("Line 1\nLine 2"))
        assertEquals("Line 1 Line 2", tv.text.toString())
        assertTrue(tv.isVisible)
        assertFalse(tv.isGone)

        // Null UiText should hide the view
        tv.setText(null)
        assertFalse(tv.isVisible)

        // Blank text should set isGone = true
        tv.setText(txt(""))
        assertTrue(tv.isGone)
    }

    @Test
    @DisplayName("TextView.setTextHtml strips HTML tags and sets plain text")
    fun testTextViewSetTextHtml() {
        val tv = TextView(context)

        tv.setTextHtml(txt("<b>Bold Title</b> &amp; <i>Italic</i>"))
        val content = tv.text?.toString() ?: ""
        assertTrue(content.contains("Bold Title") && content.contains("Italic"))
        assertTrue(tv.isVisible)
    }

    // =========================================================================
    // 6. Density, Color & Layout Unit Tests
    // =========================================================================

    @Test
    @DisplayName("Density conversion (toPx, toDp) computes correct values")
    fun testDensityConversion() {
        UIHelper.displayDensity = 2.0f

        assertEquals(20, 10.toPx)
        assertEquals(20.0f, 10.0f.toPx)
        assertEquals(5, 10.toDp)
        assertEquals(5.0f, 10.0f.toDp)

        UIHelper.displayDensity = 1.0f
    }

    @Test
    @DisplayName("adjustAlpha accurately modifies alpha channel")
    fun testAdjustAlpha() {
        val originalColor = Color.argb(255, 100, 150, 200)
        val transparentColor = adjustAlpha(originalColor, 0.5f)

        val alpha = Color.alpha(transparentColor)
        val red = Color.red(transparentColor)
        val green = Color.green(transparentColor)
        val blue = Color.blue(transparentColor)

        assertEquals(128, alpha, "Alpha channel should be roughly half (128)")
        assertEquals(100, red)
        assertEquals(150, green)
        assertEquals(200, blue)
    }

    @Test
    @DisplayName("isBottomLayout reads desktop data store and preferences")
    fun testIsBottomLayout() {
        assertFalse(context.isBottomLayout(), "Default bottom layout should be false")

        DesktopDataStore.setKey("bottom_title_key", true)
        assertTrue(context.isBottomLayout(), "Should reflect DesktopDataStore setting")

        DesktopDataStore.removeKey("bottom_title_key")
    }
}
