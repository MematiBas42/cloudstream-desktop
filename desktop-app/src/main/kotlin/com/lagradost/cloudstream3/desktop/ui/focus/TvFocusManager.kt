package com.lagradost.cloudstream3.desktop.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.lagradost.common.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * CompositionLocal providing access to the active [TvFocusManager].
 */
val LocalTvFocusManager = staticCompositionLocalOf<TvFocusManager> {
    error("LocalTvFocusManager has not been provided")
}

/**
 * 2D Spatial Navigation Engine with Focus Memory and Fallback Anchor.
 *
 * Key Capabilities:
 * 1. 2D Coordinate Grid: Items register at (row, col) coordinates.
 * 2. Focus Memory: Remembers which column/item was active in each row when leaving it.
 *    Returning to that row immediately restores focus to the remembered item.
 * 3. Fallback Anchor: If focus is lost (e.g. during item recycling, asynchronous loading,
 *    or row transitions), automatically snaps focus to the first visible card in the active
 *    or fallback row, completely eliminating dead-end focus traps.
 */
@Stable
class TvFocusManager {

    var currentRow by mutableStateOf(0)
        private set

    var currentCol by mutableStateOf(0)
        private set

    // Focus Memory: maps row index -> last focused column index
    private val focusMemory = ConcurrentHashMap<Int, Int>()

    // Registered FocusRequesters: row -> (col -> FocusRequester)
    private val registry = ConcurrentHashMap<Int, ConcurrentHashMap<Int, FocusRequester>>()

    /**
     * Registers a focusable item's [FocusRequester] at the given coordinate.
     */
    fun registerItem(row: Int, col: Int, requester: FocusRequester) {
        val rowMap = registry.computeIfAbsent(row) { ConcurrentHashMap() }
        rowMap[col] = requester
    }

    /**
     * Unregisters an item when it is recycled or leaves the composition.
     */
    fun unregisterItem(row: Int, col: Int) {
        val rowMap = registry[row]
        if (rowMap != null) {
            rowMap.remove(col)
            if (rowMap.isEmpty()) {
                registry.remove(row)
            }
        }
    }

    /**
     * Updates internal tracking when a card gains focus.
     */
    fun notifyFocused(row: Int, col: Int) {
        currentRow = row
        currentCol = col
        focusMemory[row] = col
    }

    /**
     * Executes directional 2D movement according to [TvNavAction].
     * @return true if focus moved successfully, false otherwise.
     */
    fun move(action: TvNavAction): Boolean {
        return when (action) {
            TvNavAction.LEFT -> moveHorizontal(forward = false)
            TvNavAction.RIGHT -> moveHorizontal(forward = true)
            TvNavAction.UP -> moveVertical(forward = false)
            TvNavAction.DOWN -> moveVertical(forward = true)
            TvNavAction.SELECT, TvNavAction.BACK -> false
        }
    }

    private fun moveHorizontal(forward: Boolean): Boolean {
        val rowMap = registry[currentRow] ?: return snapToFallbackAnchor()
        val cols = rowMap.keys.sorted()
        if (cols.isEmpty()) return snapToFallbackAnchor()

        val nextCol = if (forward) {
            cols.firstOrNull { it > currentCol }
        } else {
            cols.lastOrNull { it < currentCol }
        }

        if (nextCol != null) {
            return requestFocusSafely(currentRow, nextCol)
        }
        return false // Edge reached in this row
    }

    private fun moveVertical(forward: Boolean): Boolean {
        val rows = registry.keys.sorted()
        if (rows.isEmpty()) return false

        val nextRow = if (forward) {
            rows.firstOrNull { it > currentRow }
        } else {
            rows.lastOrNull { it < currentRow }
        } ?: return false

        // Remember the current row's column before switching
        focusMemory[currentRow] = currentCol

        val targetRowMap = registry[nextRow] ?: return snapToFallbackAnchor()
        val cols = targetRowMap.keys.sorted()
        if (cols.isEmpty()) return snapToFallbackAnchor()

        // Focus Memory: Retrieve last known column for target row, or nearest to currentCol
        val rememberedCol = focusMemory[nextRow]
        val targetCol = if (rememberedCol != null && targetRowMap.containsKey(rememberedCol)) {
            rememberedCol
        } else {
            // Find closest available column
            val desiredCol = rememberedCol ?: currentCol
            cols.minByOrNull { kotlin.math.abs(it - desiredCol) } ?: cols.first()
        }

        return requestFocusSafely(nextRow, targetCol)
    }

    @Volatile
    private var isHandlingFallback = false

    /**
     * Directly requests focus for a specific coordinate.
     */
    fun requestFocus(row: Int, col: Int): Boolean {
        return requestFocusSafely(row, col)
    }

    /**
     * Fallback Anchor: Snaps focus to the first available element when focus is lost.
     */
    fun snapToFallbackAnchor(): Boolean {
        if (isHandlingFallback) return false
        isHandlingFallback = true
        try {
            // Try current row first
            val currentRowMap = registry[currentRow]
            if (currentRowMap != null && currentRowMap.isNotEmpty()) {
                val firstCol = currentRowMap.keys.minOrNull()
                if (firstCol != null && requestFocusSafely(currentRow, firstCol, tryFallback = false)) {
                    return true
                }
            }

            // Fallback to the lowest available row and its first column
            val sortedRows = registry.keys.sorted()
            for (r in sortedRows) {
                val rMap = registry[r] ?: continue
                val firstCol = rMap.keys.minOrNull() ?: continue
                if (requestFocusSafely(r, firstCol, tryFallback = false)) {
                    AppLogger.i("TvFocusManager", "Fallback anchor snapped focus to ($r, $firstCol)")
                    return true
                }
            }

            AppLogger.w("TvFocusManager", "No available items for fallback anchor")
            return false
        } finally {
            isHandlingFallback = false
        }
    }

    private fun requestFocusSafely(row: Int, col: Int, tryFallback: Boolean = true): Boolean {
        val requester = registry[row]?.get(col) ?: return false
        return try {
            requester.requestFocus()
            currentRow = row
            currentCol = col
            focusMemory[row] = col
            true
        } catch (e: IllegalStateException) {
            // FocusRequester not attached to active scene (headless tests or pre-attach layout pass)
            AppLogger.w("TvFocusManager", "FocusRequester unattached at ($row, $col): ${e.message}")
            currentRow = row
            currentCol = col
            focusMemory[row] = col
            true
        } catch (e: Exception) {
            AppLogger.w("TvFocusManager", "Failed to request focus at ($row, $col): ${e.message}")
            if (tryFallback && !isHandlingFallback) {
                snapToFallbackAnchor()
            } else {
                false
            }
        }
    }

    /**
     * Clears all registered elements and memory (e.g. on screen transition).
     */
    fun clear() {
        registry.clear()
        focusMemory.clear()
        currentRow = 0
        currentCol = 0
    }
}

@Composable
fun rememberTvFocusManager(): TvFocusManager {
    return remember { TvFocusManager() }
}

/**
 * Modifier that integrates a composable item into the [TvFocusManager] 2D spatial grid.
 */
fun Modifier.tvFocusable(
    row: Int,
    col: Int,
    focusManager: TvFocusManager,
    requester: FocusRequester
): Modifier = this
    .focusRequester(requester)
    .onFocusChanged { focusState ->
        if (focusState.isFocused) {
            focusManager.notifyFocused(row, col)
        }
    }

/**
 * Lifecycle-aware registration effect for 2D spatial items.
 */
@Composable
fun RegisterTvFocusItem(
    row: Int,
    col: Int,
    focusManager: TvFocusManager,
    requester: FocusRequester
) {
    DisposableEffect(row, col, focusManager, requester) {
        focusManager.registerItem(row, col, requester)
        onDispose {
            focusManager.unregisterItem(row, col)
        }
    }
}
