package com.lagradost.cloudstream3.desktop.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Standardized High-Level 10-Foot TV Navigation Actions.
 */
enum class TvNavAction {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    SELECT,
    BACK
}

/**
 * Hardware key event interceptor for 10-Foot TV / Leanback navigation.
 *
 * Intercepts:
 * - D-Pad (Up, Down, Left, Right, Center)
 * - Keyboard Arrow keys (Up, Down, Left, Right)
 * - Enter / Spacebar / NumPad Enter (Select / Confirm)
 * - Escape / Backspace / Android Back (Back / Dismiss)
 * - Gamepad D-pad & standard Action buttons (A/Cross for Select, B/Circle for Back)
 *
 * Implements a Virtual Repeat Debounce:
 * - 250ms initial delay before continuous repeat begins
 * - 80ms repeat interval thereafter
 * This prevents runaway list and grid scrolling caused by aggressive OS/hardware auto-repeat.
 */
class KeyNavigationDebouncer(
    val initialDelayMs: Long = 250L,
    val repeatIntervalMs: Long = 80L
) {
    private var lastKey: Long? = null
    private var pressStartTime: Long = 0L
    private var lastFireTime: Long = 0L
    private var isRepeating: Boolean = false

    /**
     * Determines whether an incoming key event should be executed or debounced.
     * @return true if the event should be processed; false if it should be swallowed.
     */
    fun shouldProcessEvent(event: KeyEvent): Boolean {
        val keyId = event.key.keyCode

        when (event.type) {
            KeyEventType.KeyUp -> {
                if (lastKey == keyId) {
                    reset()
                }
                return false
            }

            KeyEventType.KeyDown -> {
                val now = System.currentTimeMillis()

                if (lastKey != keyId) {
                    // New key press
                    lastKey = keyId
                    pressStartTime = now
                    lastFireTime = now
                    isRepeating = false
                    return true
                }

                // Existing key being held down
                if (!isRepeating) {
                    if (now - pressStartTime >= initialDelayMs) {
                        isRepeating = true
                        lastFireTime = now
                        return true
                    }
                    return false
                } else {
                    if (now - lastFireTime >= repeatIntervalMs) {
                        lastFireTime = now
                        return true
                    }
                    return false
                }
            }

            else -> return false
        }
    }

    fun reset() {
        lastKey = null
        pressStartTime = 0L
        lastFireTime = 0L
        isRepeating = false
    }
}

object KeyNavigationInterceptor {

    val globalDebouncer = KeyNavigationDebouncer(
        initialDelayMs = 250L,
        repeatIntervalMs = 80L
    )

    /**
     * Maps Compose and AWT KeyEvents into TvNavAction.
     */
    fun mapKeyEventToAction(event: KeyEvent): TvNavAction? {
        val key = event.key
        val awtCode = (event.nativeKeyEvent as? AwtKeyEvent)?.keyCode ?: 0

        return when {
            // Direction Up
            key == Key.DirectionUp || awtCode == AwtKeyEvent.VK_UP -> TvNavAction.UP

            // Direction Down
            key == Key.DirectionDown || awtCode == AwtKeyEvent.VK_DOWN -> TvNavAction.DOWN

            // Direction Left
            key == Key.DirectionLeft || awtCode == AwtKeyEvent.VK_LEFT -> TvNavAction.LEFT

            // Direction Right
            key == Key.DirectionRight || awtCode == AwtKeyEvent.VK_RIGHT -> TvNavAction.RIGHT

            // Select / Activate: Enter, Space, Numpad Enter, D-Pad Center, Gamepad Button A (VK_ENTER or standard mapping)
            key == Key.Enter || key == Key.NumPadEnter || key == Key.Spacebar ||
                key == Key.DirectionCenter || awtCode == AwtKeyEvent.VK_ENTER ||
                awtCode == AwtKeyEvent.VK_SPACE -> TvNavAction.SELECT

            // Back / Dismiss: Escape, Android Back, Gamepad Button B (VK_ESCAPE or standard mapping)
            key == Key.Escape || key == Key.Back ||
                awtCode == AwtKeyEvent.VK_ESCAPE -> TvNavAction.BACK

            // Gamepad specific keyCode heuristics if forwarded as function/joystick buttons
            isGamepadSelect(awtCode) -> TvNavAction.SELECT
            isGamepadBack(awtCode) -> TvNavAction.BACK

            else -> null
        }
    }

    private fun isGamepadSelect(awtCode: Int): Boolean {
        // Typical Linux / XInput / evdev button A / Cross keycodes
        return awtCode == 0x60 || awtCode == 0x130 || awtCode == AwtKeyEvent.VK_ACCEPT
    }

    private fun isGamepadBack(awtCode: Int): Boolean {
        // Typical Linux / XInput / evdev button B / Circle keycodes
        return awtCode == 0x61 || awtCode == 0x131 || awtCode == AwtKeyEvent.VK_CANCEL
    }
}

/**
 * Modifier that intercepts TV key navigation with the 250ms/80ms debounce filter
 * and routes the resolved [TvNavAction] to [onAction].
 */
fun Modifier.interceptTvKeyNavigation(
    debouncer: KeyNavigationDebouncer = KeyNavigationInterceptor.globalDebouncer,
    onAction: (TvNavAction) -> Boolean
): Modifier = this.onPreviewKeyEvent { event ->
    val action = KeyNavigationInterceptor.mapKeyEventToAction(event)
    if (action != null) {
        if (event.type == KeyEventType.KeyUp) {
            debouncer.reset()
            return@onPreviewKeyEvent false
        }

        if (event.type == KeyEventType.KeyDown) {
            val shouldExecute = debouncer.shouldProcessEvent(event)
            if (shouldExecute) {
                val handled = onAction(action)
                if (handled) return@onPreviewKeyEvent true
            } else {
                // Event was debounced/throttled: consume it so it doesn't leak into OS runaway scroll
                return@onPreviewKeyEvent true
            }
        }
    }
    false
}
