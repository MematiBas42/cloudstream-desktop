package com.lagradost.cloudstream3

import android.app.Activity
import android.view.KeyEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommonActivityTest {

    @Test
    fun testFocusDirectionEnum() {
        assertEquals(4, FocusDirection.values().size)
        assertEquals(FocusDirection.Start, FocusDirection.valueOf("Start"))
        assertEquals(FocusDirection.End, FocusDirection.valueOf("End"))
        assertEquals(FocusDirection.Up, FocusDirection.valueOf("Up"))
        assertEquals(FocusDirection.Down, FocusDirection.valueOf("Down"))
    }

    @Test
    fun testScreenMetrics() {
        assertTrue(CommonActivity.screenWidth > 0)
        assertTrue(CommonActivity.screenHeight > 0)
        assertTrue(CommonActivity.screenWidth >= CommonActivity.screenHeight)
        assertTrue(CommonActivity.screenWidthWithOrientation > 0)
        assertTrue(CommonActivity.screenHeightWithOrientation > 0)
        assertNotNull(CommonActivity.displayMetrics)
    }

    @Test
    fun testGlobalEventBus() {
        var colorResult: Pair<Int, Int>? = null
        val colorObserver: (Pair<Int, Int>) -> Unit = { colorResult = it }
        CommonActivity.onColorSelectedEvent += colorObserver

        CommonActivity.onColorSelectedEvent.invoke(Pair(1, 0xFF00FF))
        assertEquals(Pair(1, 0xFF00FF), colorResult)

        CommonActivity.onColorSelectedEvent -= colorObserver
        CommonActivity.onColorSelectedEvent.invoke(Pair(2, 0x00FF00))
        assertEquals(Pair(1, 0xFF00FF), colorResult)

        var dismissedDialogId: Int? = null
        val dialogObserver: (Int) -> Unit = { dismissedDialogId = it }
        CommonActivity.onDialogDismissedEvent += dialogObserver

        CommonActivity.onDialogDismissedEvent.invoke(42)
        assertEquals(42, dismissedDialogId)

        CommonActivity.onDialogDismissedEvent -= dialogObserver
        CommonActivity.onDialogDismissedEvent.invoke(99)
        assertEquals(42, dismissedDialogId)
    }

    @Test
    fun testKeyEventListenerAndDispatch() {
        var capturedEvent: Pair<KeyEvent?, Boolean>? = null
        CommonActivity.keyEventListener = { pair ->
            capturedEvent = pair
            true
        }

        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
        val act = Activity()
        val handled = CommonActivity.dispatchKeyEvent(act, keyEvent)

        assertTrue(handled == true)
        assertNotNull(capturedEvent)
        assertEquals(KeyEvent.KEYCODE_SPACE, capturedEvent?.first?.keyCode)
        assertFalse(capturedEvent?.second ?: true)

        CommonActivity.keyEventListener = null
    }

    @Test
    fun testToastBusEmission() = runBlocking {
        var toastEventReceived: ToastData? = null
        val observer: (ToastData) -> Unit = { toastEventReceived = it }
        CommonActivity.toastEvent += observer

        val testMessage = "Test Notification Message"
        CommonActivity.showToast(testMessage, 1)

        val flowData = CommonActivity.toastSharedFlow.first()
        assertEquals(testMessage, flowData.message)
        assertEquals(1, flowData.duration)

        assertNotNull(toastEventReceived)
        assertEquals(testMessage, toastEventReceived?.message)
        assertEquals(1, toastEventReceived?.duration)

        CommonActivity.toastEvent -= observer
    }

    @Test
    fun testCastSessionStubQuarantine() {
        val act = Activity()
        with(CommonActivity) {
            val session = act.getCastSession()
            assertNull(session)
        }
    }
}
