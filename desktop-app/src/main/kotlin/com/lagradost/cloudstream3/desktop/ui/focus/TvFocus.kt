package com.lagradost.cloudstream3.desktop.ui.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * High-precision immutable representation of an active focus rectangle in window coordinates.
 * Traced 1:1 to upstream FocusTarget in MainActivity.kt:910-928.
 */
@Immutable
data class FocusTarget(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val isVisible: Boolean = false,
    val cornerRadius: Float = 10f
) {
    fun toRect(): Rect = Rect(x, y, x + width, y + height)

    companion object {
        val Zero = FocusTarget()

        /**
         * Linear interpolation between start and end focus bounding boxes over [fraction] (0.0 .. 1.0).
         */
        fun lerp(start: FocusTarget, end: FocusTarget, fraction: Float): FocusTarget {
            val clampedFraction = fraction.coerceIn(0f, 1f)
            val inv = 1f - clampedFraction
            return FocusTarget(
                x = start.x * inv + end.x * clampedFraction,
                y = start.y * inv + end.y * clampedFraction,
                width = start.width * inv + end.width * clampedFraction,
                height = start.height * inv + end.height * clampedFraction,
                isVisible = end.isVisible,
                cornerRadius = start.cornerRadius * inv + end.cornerRadius * clampedFraction
            )
        }
    }
}

/**
 * Mathematical calculations and snap threshold evaluation for TV traveling focus rings.
 * Upstream Reference: MainActivity.kt:1110-1118 (deltaMinX, deltaMinY).
 */
object TvFocusMath {
    const val DEFAULT_MAX_THRESHOLD = 90f // 60.toPx on 1.5x TV density scale

    /**
     * Delta minimum threshold: min(dimension / 2, maxThreshold).
     */
    fun calculateDeltaMin(dimension: Float, maxThreshold: Float = DEFAULT_MAX_THRESHOLD): Float {
        return min(dimension / 2f, maxThreshold)
    }

    /**
     * Determines whether a transition between [start] and [end] focus targets should snap immediately
     * without running the 200ms lerp animation (e.g. for small scroll adjustments or micro-movements).
     */
    fun shouldSnap(
        start: FocusTarget,
        end: FocusTarget,
        maxThreshold: Float = DEFAULT_MAX_THRESHOLD,
        dimensionTolerance: Float = 1f
    ): Boolean {
        val deltaMinX = calculateDeltaMin(end.width, maxThreshold)
        val deltaMinY = calculateDeltaMin(end.height, maxThreshold)
        val sameDimensions = abs(start.width - end.width) < dimensionTolerance &&
            abs(start.height - end.height) < dimensionTolerance
        val withinDistance = abs(start.x - end.x) < deltaMinX &&
            abs(start.y - end.y) < deltaMinY
        return sameDimensions && withinDistance
    }
}

/**
 * State coordinator managing spatial focus targets, 200ms lerp animation, and snap threshold logic.
 */
@Stable
class TvFocusState(
    private val scope: CoroutineScope? = null
) {
    var currentTarget by mutableStateOf(FocusTarget.Zero)
        private set

    var previousTarget by mutableStateOf(FocusTarget.Zero)
        private set

    var isOutlineVisible by mutableStateOf(false)
        private set

    // Animation progress (0.0 -> 1.0) driving the lerp interpolation
    val animationProgress = Animatable(1f)
    private var animationJob: Job? = null
    private var focusLossJob: Job? = null

    /**
     * Updates focus target with 200ms lerp animation or immediate snap if within deltaMin threshold.
     */
    suspend fun updateFocus(
        newTarget: FocusTarget,
        snapImmediately: Boolean = false
    ) {
        focusLossJob?.cancel()
        if (!newTarget.isVisible || newTarget.width <= 0f || newTarget.height <= 0f) {
            isOutlineVisible = false
            return
        }

        val wasHidden = !isOutlineVisible
        previousTarget = if (wasHidden) newTarget else currentTarget
        currentTarget = newTarget
        isOutlineVisible = true

        if (wasHidden || snapImmediately) {
            animationProgress.snapTo(1f)
            return
        }

        // Upstream Snap Threshold check (MainActivity.kt:1110-1118)
        if (TvFocusMath.shouldSnap(previousTarget, newTarget)) {
            animationProgress.snapTo(1f)
            return
        }

        // 200ms Lerp Animation (MainActivity.kt:1141)
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 200,
                easing = FastOutSlowInEasing
            )
        )
    }

    /**
     * Non-suspending dispatch helper to report focus target changes from composable callbacks.
     */
    fun reportFocus(
        newTarget: FocusTarget,
        snapImmediately: Boolean = false,
        callerScope: CoroutineScope? = null
    ) {
        focusLossJob?.cancel()
        val targetScope = callerScope ?: scope
        if (targetScope != null) {
            animationJob?.cancel()
            animationJob = targetScope.launch {
                updateFocus(newTarget, snapImmediately)
            }
        } else {
            if (!newTarget.isVisible || newTarget.width <= 0f || newTarget.height <= 0f) {
                isOutlineVisible = false
                return
            }
            previousTarget = if (!isOutlineVisible) newTarget else currentTarget
            currentTarget = newTarget
            isOutlineVisible = true
        }
    }

    /**
     * Invoked when focus leaves a component. Uses a 50ms debounce window
     * to avoid flicker when focus immediately transfers to another item.
     */
    fun onFocusLost(callerScope: CoroutineScope? = null) {
        val targetScope = callerScope ?: scope
        if (targetScope != null) {
            focusLossJob?.cancel()
            focusLossJob = targetScope.launch {
                kotlinx.coroutines.delay(50)
                isOutlineVisible = false
            }
        } else {
            isOutlineVisible = false
        }
    }

    fun clearFocus() {
        focusLossJob?.cancel()
        animationJob?.cancel()
        isOutlineVisible = false
        currentTarget = FocusTarget.Zero
        previousTarget = FocusTarget.Zero
    }
}

val LocalTvFocusState = staticCompositionLocalOf<TvFocusState> {
    TvFocusState()
}

val LocalTvFocusEngine = LocalTvFocusState
typealias TvFocusEngine = TvFocusState

@Composable
fun rememberTvFocusState(): TvFocusState {
    val scope = rememberCoroutineScope()
    return remember { TvFocusState(scope) }
}

/**
 * Modifier to report exact window coordinates of focusable components to the traveling focus engine.
 */
fun Modifier.trackTvFocusBounds(
    isFocused: Boolean,
    focusState: TvFocusState,
    cornerRadius: Float = 10f,
    coroutineScope: CoroutineScope? = null
): Modifier = this.composed {
    var lastCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    LaunchedEffect(isFocused, lastCoordinates) {
        val coords = lastCoordinates
        if (isFocused && coords != null && coords.isAttached) {
            val bounds = coords.boundsInWindow()
            if (bounds.width > 0f && bounds.height > 0f && bounds.bottom > 0f && bounds.right > 0f) {
                val target = FocusTarget(
                    x = bounds.left,
                    y = bounds.top,
                    width = bounds.width,
                    height = bounds.height,
                    isVisible = true,
                    cornerRadius = cornerRadius
                )
                focusState.reportFocus(target, callerScope = coroutineScope)
            } else {
                focusState.onFocusLost(coroutineScope)
            }
        } else if (!isFocused) {
            focusState.onFocusLost(coroutineScope)
        }
    }

    this.onGloballyPositioned { coordinates: LayoutCoordinates ->
        lastCoordinates = coordinates
    }
}

/**
 * Animated floating traveling focus ring overlay (outline.xml 2dp white stroke, 10dp rounded corners).
 * Interpolates smoothly across the 2D window space over 200ms using the deltaMin snap threshold.
 */
@Composable
fun TvFocusOverlay(
    modifier: Modifier = Modifier,
    focusState: TvFocusState = LocalTvFocusState.current,
    strokeColor: Color = Color.White,
    strokeWidth: Dp = 2.dp,
    defaultCornerRadius: Dp = 10.dp
) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focusState.isOutlineVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "TvFocusOverlayAlpha"
    )

    if (alpha <= 0.01f) return

    val progress = focusState.animationProgress.value
    val interpolated = FocusTarget.lerp(
        start = focusState.previousTarget,
        end = focusState.currentTarget,
        fraction = progress
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (interpolated.width > 0f && interpolated.height > 0f) {
            val strokePx = strokeWidth.toPx()
            val radiusPx = if (interpolated.cornerRadius > 0f) {
                interpolated.cornerRadius
            } else {
                defaultCornerRadius.toPx()
            }

            drawRoundRect(
                color = strokeColor.copy(alpha = strokeColor.alpha * alpha),
                topLeft = Offset(interpolated.x, interpolated.y),
                size = Size(interpolated.width, interpolated.height),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(width = strokePx)
            )
        }
    }
}

/**
 * Alias for spec compatibility.
 */
@Composable
fun TvTravelingFocusOverlay(
    focusEngine: TvFocusState = LocalTvFocusState.current,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color.White,
    strokeWidth: Float = 2.5f
) {
    TvFocusOverlay(
        modifier = modifier,
        focusState = focusEngine,
        strokeColor = strokeColor,
        strokeWidth = (strokeWidth / 1.5f).dp
    )
}

/**
 * Canonical exception target identifiers matching upstream MainActivity.kt:1253-1267.
 * Components tagged with these IDs will NOT trigger vertical viewport auto-centering.
 */
object TvCenteringExceptions {
    const val HERO_INFO_BUTTON = "hero_preview_info_button"
    const val HERO_PLAY_BUTTON = "hero_preview_play_button"
    const val DETAILS_PLAY_MOVIE = "details_play_movie_button"
    const val DETAILS_PLAY_SERIES = "details_play_series_button"
    const val DETAILS_RESUME_SERIES = "details_resume_series_button"
    const val DETAILS_TRAILER = "details_play_trailer_button"
    const val DETAILS_BOOKMARK = "details_bookmark_button"
    const val DETAILS_FAVORITE = "details_favorite_button"
    const val DETAILS_SUBSCRIBE = "details_subscribe_button"
    const val DETAILS_SEARCH = "details_search_button"
    const val DETAILS_EPISODES_DRAWER = "details_episodes_show_button"

    private val exceptions = setOf(
        HERO_INFO_BUTTON,
        HERO_PLAY_BUTTON,
        DETAILS_PLAY_MOVIE,
        DETAILS_PLAY_SERIES,
        DETAILS_RESUME_SERIES,
        DETAILS_TRAILER,
        DETAILS_BOOKMARK,
        DETAILS_FAVORITE,
        DETAILS_SUBSCRIBE,
        DETAILS_SEARCH,
        DETAILS_EPISODES_DRAWER
    )

    fun isException(tag: String?): Boolean = tag != null && exceptions.contains(tag)
}

/**
 * Mathematical calculations for viewport auto-centering.
 * Traced to upstream centerView() in MainActivity.kt:425-441.
 */
object TvViewportCenteringMath {
    /**
     * Calculates the scroll delta required to center an item on screen.
     * itemCenterY = itemTop + itemHeight / 2
     * screenCenterY = screenHeight / 2
     * scrollDelta = itemCenterY - screenCenterY
     */
    fun calculateCenterScrollDelta(itemTop: Float, itemHeight: Float, screenHeight: Float): Float {
        val itemCenterY = itemTop + (itemHeight / 2f)
        val screenCenterY = screenHeight / 2f
        return itemCenterY - screenCenterY
    }
}
