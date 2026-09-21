// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/mvvm/Lifecycle.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import java.awt.GraphicsEnvironment
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Desktop ViewModel Base Architecture
 *
 * Replaces Android [androidx.lifecycle.ViewModel] and Android LifecycleOwner observation
 * patterns from upstream [com.lagradost.cloudstream3.mvvm.Lifecycle].
 *
 * Provides structured coroutine concurrency via [viewModelScope] (backed by [SupervisorJob]),
 * thread-safe main dispatcher resolution with graceful [Dispatchers.Default] fallback for
 * headless/CLI environments, and deterministic cleanup via [onCleared].
 */
@PlatformQuarantine(
    reason = "Desktop replacement for Android ViewModel & LifecycleOwner/LiveData observation via CoroutineScope and StateFlow",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/mvvm/Lifecycle.kt",
    status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
)
abstract class DesktopViewModel(
    dispatcher: CoroutineDispatcher? = null
) : AutoCloseable {
    private val supervisorJob: CompletableJob = SupervisorJob()

    val viewModelScope: CoroutineScope = CoroutineScope(
        supervisorJob + (dispatcher ?: resolveDefaultDispatcher())
    )

    /**
     * Whether this ViewModel has been cleared and its [viewModelScope] cancelled.
     */
    val isCleared: Boolean
        get() = !viewModelScope.isActive

    /**
     * Deterministically cancels the ViewModel's [viewModelScope] and underlying [SupervisorJob].
     * Cancels all running child coroutines and prevents future launches to eliminate memory leaks.
     */
    open fun onCleared() {
        supervisorJob.cancel()
        viewModelScope.cancel()
    }

    /**
     * Standard [AutoCloseable] interface implementation delegating to [onCleared].
     */
    override fun close() {
        onCleared()
    }

    companion object {
        private val dispatcherLock = Any()

        @Volatile
        private var defaultDispatcher: CoroutineDispatcher? = null

        /**
         * Resolves the primary UI dispatcher with fallback to [Dispatchers.Default].
         *
         * Guards against initialization race conditions before the AWT desktop event loop
         * (EventQueue) is active, and provides a graceful fallback to [Dispatchers.Default]
         * in headless CLI, unit tests, or server environments where [Dispatchers.Main] is missing.
         */
        fun resolveDefaultDispatcher(): CoroutineDispatcher {
            defaultDispatcher?.let { return it }

            return synchronized(dispatcherLock) {
                defaultDispatcher ?: run {
                    val resolved = try {
                        val isHeadless = try {
                            GraphicsEnvironment.isHeadless()
                        } catch (_: Throwable) {
                            true
                        }

                        if (isHeadless) {
                            Dispatchers.Default
                        } else {
                            val main = Dispatchers.Main
                            main.isDispatchNeeded(EmptyCoroutineContext)
                            main
                        }
                    } catch (_: Throwable) {
                        Dispatchers.Default
                    }
                    defaultDispatcher = resolved
                    resolved
                }
            }
        }

        /**
         * Resets the cached default dispatcher.
         * Useful in test suites when switching dispatchers dynamically via kotlinx.coroutines.test.
         */
        internal fun resetDefaultDispatcher() {
            synchronized(dispatcherLock) {
                defaultDispatcher = null
            }
        }
    }
}
