package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Industry-standard desktop mouse drag & fast-swipe modifier for horizontal category rows.
 *
 * Requirements & Ergonomics:
 * 1. Mouse wheel scrolling is 100% UNTOUCHED by this modifier so vertical page scrolling
 *    on HomeScreen always scrolls the main page, even when the cursor is over category rows.
 * 2. Categories can be naturally dragged left/right with mouse click-and-drag.
 * 3. Fast swipe / fling with mouse momentum: when swiped quickly, the velocity tracker
 *    applies natural fling physics (deceleration) matching touch/mobile standards.
 * 4. Micro-movements below touch slop do not consume events, preserving normal card clickability.
 */
@Composable
fun Modifier.desktopMouseSwipeable(
    state: LazyListState,
    coroutineScope: CoroutineScope,
): Modifier {
    val flingBehavior = ScrollableDefaults.flingBehavior()
    var flingJob by remember { mutableStateOf<Job?>(null) }

    return this.pointerInput(state) {
        val velocityTracker = VelocityTracker()

        awaitPointerEventScope {
            while (true) {
                // 1. Wait for mouse press (left button)
                val downEvent = awaitPointerEvent(PointerEventPass.Main)
                val downChange = downEvent.changes.firstOrNull { it.pressed } ?: continue

                // Only handle primary mouse button (left-click drag)
                if (!downEvent.buttons.isPrimaryPressed) continue

                flingJob?.cancel()
                velocityTracker.resetTracking()
                velocityTracker.addPosition(downChange.uptimeMillis, downChange.position)

                var totalDragX = 0f
                var isDragStarted = false
                val touchSlop = viewConfiguration.touchSlop

                // 2. Track drag until pointer release
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull()

                    if (change == null || !change.pressed) {
                        // Mouse released (up)
                        if (isDragStarted) {
                            val velocity = velocityTracker.calculateVelocity().x
                            // Fast swipe fling with natural momentum decay if velocity is significant
                            if (abs(velocity) > 100f) {
                                flingJob = coroutineScope.launch {
                                    state.scroll {
                                        with(flingBehavior) {
                                            performFling(-velocity)
                                        }
                                    }
                                }
                            }
                        }
                        break
                    }

                    val dragDeltaX = change.position.x - change.previousPosition.x
                    totalDragX += dragDeltaX
                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                    if (!isDragStarted) {
                        if (abs(totalDragX) > touchSlop) {
                            isDragStarted = true
                        }
                    }

                    if (isDragStarted && dragDeltaX != 0f) {
                        change.consume()
                        coroutineScope.launch {
                            state.dispatchRawDelta(-dragDeltaX)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Industry-standard desktop mouse drag & swipe modifier for HorizontalPager / PagerState (Hero Carousel).
 *
 * Requirements & Ergonomics:
 * 1. Mouse wheel vertical scrolling is untouched so HomeScreen scrolls freely.
 * 2. Left-click and drag naturally drags the pager pages in real time matching category rows.
 * 3. Releasing with fling velocity or past threshold snaps to previous / next page.
 * 4. Micro-movements below touch slop do not consume events, preserving clicks on
 *    buttons (Watch Now, Details) and card click handlers.
 */
@Composable
fun Modifier.desktopMouseSwipeable(
    state: PagerState,
    coroutineScope: CoroutineScope,
    onDragStateChange: ((Boolean) -> Unit)? = null,
): Modifier {
    var flingJob by remember { mutableStateOf<Job?>(null) }

    return this.pointerInput(state) {
        val velocityTracker = VelocityTracker()

        awaitPointerEventScope {
            while (true) {
                // 1. Wait for mouse press (left button)
                val downEvent = awaitPointerEvent(PointerEventPass.Main)
                val downChange = downEvent.changes.firstOrNull { it.pressed } ?: continue

                // Only handle primary mouse button (left-click drag)
                if (!downEvent.buttons.isPrimaryPressed) continue

                flingJob?.cancel()
                velocityTracker.resetTracking()
                velocityTracker.addPosition(downChange.uptimeMillis, downChange.position)

                var totalDragX = 0f
                var isDragStarted = false
                val touchSlop = viewConfiguration.touchSlop

                // 2. Track drag until pointer release
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull()

                    if (change == null || !change.pressed) {
                        // Mouse released (up)
                        if (isDragStarted) {
                            val velocity = velocityTracker.calculateVelocity().x
                            onDragStateChange?.invoke(true)
                            flingJob = coroutineScope.launch {
                                val targetPage = when {
                                    velocity < -300f || totalDragX < -60f -> {
                                        (state.currentPage + 1).coerceAtMost(state.pageCount - 1)
                                    }
                                    velocity > 300f || totalDragX > 60f -> {
                                        (state.currentPage - 1).coerceAtLeast(0)
                                    }
                                    else -> state.currentPage
                                }
                                state.animateScrollToPage(
                                    page = targetPage,
                                    animationSpec = tween(350),
                                )
                                delay(1500)
                                onDragStateChange?.invoke(false)
                            }
                        }
                        break
                    }

                    val dragDeltaX = change.position.x - change.previousPosition.x
                    totalDragX += dragDeltaX
                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                    if (!isDragStarted) {
                        if (abs(totalDragX) > touchSlop) {
                            isDragStarted = true
                            onDragStateChange?.invoke(true)
                        }
                    }

                    if (isDragStarted && dragDeltaX != 0f) {
                        change.consume()
                        coroutineScope.launch {
                            state.dispatchRawDelta(-dragDeltaX)
                        }
                    }
                }
            }
        }
    }
}
