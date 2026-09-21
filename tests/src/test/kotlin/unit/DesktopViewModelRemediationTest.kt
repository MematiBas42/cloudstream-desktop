package unit

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.DesktopViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Architectural Remediation & Lifecycle Parity Test for [DesktopViewModel].
 *
 * Verifies:
 * 1. Safe default dispatcher resolution with Dispatchers.Default fallback in headless/AWT-inactive states.
 * 2. Guarded Dispatchers.Main initialization race protection under concurrent multi-threaded access.
 * 3. Injected custom CoroutineDispatcher support for deterministic unit testing.
 * 4. SupervisorJob fault isolation: child coroutine failure does not cancel sibling jobs or scope.
 * 5. Deterministic onCleared() invocation cancels viewModelScope and all child coroutines to prevent leaks.
 * 6. AutoCloseable contract compliance via close() delegation and Kotlin use { } integration.
 * 7. isCleared state inspection parity before and after onCleared().
 * 8. Rejection and cancellation of post-clear launches.
 * 9. PlatformQuarantine annotation compliance against upstream Lifecycle.kt.
 */
class DesktopViewModelRemediationTest {

    private class ConcreteTestViewModel(
        dispatcher: CoroutineDispatcher? = null
    ) : DesktopViewModel(dispatcher) {
        var onClearedHookCalled: Boolean = false

        override fun onCleared() {
            onClearedHookCalled = true
            super.onCleared()
        }
    }

    @BeforeEach
    fun setUp() {
        DesktopViewModel.resetDefaultDispatcher()
    }

    @AfterEach
    fun tearDown() {
        DesktopViewModel.resetDefaultDispatcher()
    }

    @Test
    fun `initialization with default dispatcher resolves safely without throwing`() {
        val vm = ConcreteTestViewModel()
        assertNotNull(vm.viewModelScope, "viewModelScope must not be null")
        assertTrue(vm.viewModelScope.isActive, "viewModelScope must be active upon creation")
        assertFalse(vm.isCleared, "isCleared must be false initially")
        vm.onCleared()
        assertTrue(vm.isCleared, "isCleared must be true after onCleared()")
    }

    @Test
    fun `initialization with injected test dispatcher executes on injected context`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val vm = ConcreteTestViewModel(testDispatcher)

        val executed = AtomicBoolean(false)
        vm.viewModelScope.launch {
            executed.set(true)
        }

        assertFalse(executed.get(), "Coroutine should not have executed before dispatcher advances")
        testScheduler.advanceUntilIdle()
        assertTrue(executed.get(), "Coroutine must execute after advancing test scheduler")

        vm.onCleared()
        assertTrue(vm.isCleared)
    }

    @Test
    fun `onCleared deterministically cancels active child coroutines and prevents leaks`() = runBlocking {
        val vm = ConcreteTestViewModel(Dispatchers.Default)

        val childStarted = CountDownLatch(1)
        val childFinished = AtomicBoolean(false)
        val childCancelled = AtomicBoolean(false)

        val job = vm.viewModelScope.launch {
            childStarted.countDown()
            try {
                delay(10_000)
                childFinished.set(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                childCancelled.set(true)
                throw e
            }
        }

        assertTrue(childStarted.await(2, TimeUnit.SECONDS), "Child coroutine should start promptly")
        assertTrue(job.isActive, "Child job must be active before onCleared()")
        assertFalse(vm.isCleared)

        vm.onCleared()

        assertTrue(vm.isCleared, "ViewModel must be marked cleared")
        assertFalse(vm.viewModelScope.isActive, "viewModelScope must not be active after onCleared()")
        assertTrue(job.isCancelled, "Child job must be cancelled by onCleared()")
        assertFalse(childFinished.get(), "Child should not finish normally")

        // Allow cancellation to be processed
        delay(50)
        assertTrue(childCancelled.get(), "Child coroutine must experience CancellationException")
        assertTrue(vm.onClearedHookCalled, "onCleared override hook must be called")
    }

    @Test
    fun `SupervisorJob fault isolation ensures child failure does not cancel siblings`() = runBlocking {
        val vm = ConcreteTestViewModel(Dispatchers.Default)

        val siblingRunning = AtomicBoolean(false)
        val siblingCompleted = AtomicBoolean(false)
        val handlerExceptionCaught = AtomicBoolean(false)

        val exceptionHandler = CoroutineExceptionHandler { _, _ ->
            handlerExceptionCaught.set(true)
        }

        // Child 1: Fails with exception
        val failingJob = vm.viewModelScope.launch(exceptionHandler) {
            throw RuntimeException("Simulated provider extraction failure")
        }

        // Child 2: Sibling coroutine that should continue unaffected
        val siblingJob = vm.viewModelScope.launch {
            siblingRunning.set(true)
            delay(100)
            siblingCompleted.set(true)
        }

        siblingJob.join()

        assertTrue(failingJob.isCompleted, "Failing job must complete")
        assertTrue(failingJob.isCancelled, "Failing job must be marked cancelled due to exception")
        assertTrue(siblingCompleted.get(), "Sibling coroutine must successfully complete despite sibling failure")
        assertTrue(vm.viewModelScope.isActive, "viewModelScope must remain active after child failure under SupervisorJob")
        assertFalse(vm.isCleared, "ViewModel must not be marked cleared after child failure")

        vm.onCleared()
        assertTrue(vm.isCleared)
    }

    @Test
    fun `AutoCloseable close contract delegates deterministically to onCleared`() {
        val vm = ConcreteTestViewModel()
        assertFalse(vm.isCleared)

        vm.use {
            assertFalse(it.isCleared)
            assertTrue(it.viewModelScope.isActive)
        }

        assertTrue(vm.isCleared, "ViewModel must be cleared upon exit from use block")
        assertTrue(vm.onClearedHookCalled, "onCleared must be called via AutoCloseable.close()")
        assertFalse(vm.viewModelScope.isActive, "viewModelScope must be cancelled after close()")
    }

    @Test
    fun `onCleared is idempotent and safe against repeated calls`() {
        val vm = ConcreteTestViewModel()
        assertFalse(vm.isCleared)

        vm.onCleared()
        assertTrue(vm.isCleared)
        assertFalse(vm.viewModelScope.isActive)

        // Second and third onCleared calls must not throw
        vm.onCleared()
        vm.onCleared()
        assertTrue(vm.isCleared)
    }

    @Test
    fun `post-clear coroutine launches are immediately cancelled`() = runBlocking {
        val vm = ConcreteTestViewModel(Dispatchers.Default)
        vm.onCleared()
        assertTrue(vm.isCleared)

        val executed = AtomicBoolean(false)
        val postClearJob = vm.viewModelScope.launch {
            executed.set(true)
        }

        assertTrue(postClearJob.isCancelled, "Job launched on cleared scope must be cancelled immediately")
        delay(50)
        assertFalse(executed.get(), "Body of job launched on cleared scope must never run")
    }

    @Test
    fun `concurrent initialization race test prevents deadlocks and resolves cleanly`() {
        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val viewModels = Array<DesktopViewModel?>(threadCount) { null }

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    val vm = ConcreteTestViewModel()
                    viewModels[i] = vm
                    if (vm.viewModelScope.isActive && !vm.isCleared) {
                        successCount.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                } finally {
                    finishLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        val finished = finishLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(finished, "All threads must finish initialization within timeout without deadlocking")
        assertEquals(threadCount, successCount.get(), "All concurrent ViewModel initializations must succeed")

        // Cleanup all instances
        for (vm in viewModels) {
            vm?.onCleared()
            assertTrue(vm?.isCleared == true)
        }
    }

    @Test
    fun `resolveDefaultDispatcher returns valid fallback dispatcher`() {
        val dispatcher = DesktopViewModel.resolveDefaultDispatcher()
        assertNotNull(dispatcher, "resolveDefaultDispatcher must never return null")
    }

    @Test
    fun `PlatformQuarantine annotation parity check against upstream Lifecycle`() {
        val quarantine = DesktopViewModel::class.java.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(quarantine, "DesktopViewModel must be annotated with @PlatformQuarantine")
        assertEquals("upstream/app/src/main/java/com/lagradost/cloudstream3/mvvm/Lifecycle.kt", quarantine.upstreamRef)
        assertEquals(QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE, quarantine.status)
        assertTrue(quarantine.reason.isNotBlank())
    }
}
