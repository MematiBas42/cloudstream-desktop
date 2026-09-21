package com.lagradost.cloudstream3.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.lagradost.cloudstream3.ui.DesktopViewModel

/**
 * Deterministic lifecycle-safe ViewModel rememberer for Compose Desktop.
 * Calls onCleared() when the Composable leaves the composition tree to prevent memory leaks.
 */
@Composable
fun <VM : DesktopViewModel> rememberCloseableViewModel(
    key: Any = Unit,
    factory: () -> VM
): VM {
    val vm = remember(key) { factory() }
    DisposableEffect(key) {
        onDispose { vm.onCleared() }
    }
    return vm
}
