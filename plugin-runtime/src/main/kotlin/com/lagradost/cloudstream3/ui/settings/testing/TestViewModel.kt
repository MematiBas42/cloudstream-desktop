// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/testing/TestViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.settings.testing

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.TestingUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TestViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    data class TestProgress(
        val passed: Int,
        val failed: Int,
        val total: Int
    )

    enum class ProviderFilter {
        All,
        Passed,
        Failed
    }

    private val _providerProgress = MutableStateFlow<TestProgress?>(null)
    val providerProgress: StateFlow<TestProgress?> = _providerProgress.asStateFlow()

    private val _providerResults =
        MutableStateFlow<List<Pair<MainAPI, TestingUtils.TestResultProvider>>>(
            emptyList()
        )

    val providerResults: StateFlow<List<Pair<MainAPI, TestingUtils.TestResultProvider>>> =
        _providerResults.asStateFlow()

    private var scope: CoroutineScope? = null
    val isRunningTest: Boolean
        get() = scope != null

    private var filter = ProviderFilter.All
    private val providers = atomicListOf<Pair<MainAPI, TestingUtils.TestResultProvider>>()
    private var passed = 0
    private var failed = 0
    private var total = 0

    private fun updateProgress() {
        _providerProgress.value = TestProgress(passed, failed, total)
        postProviders()
    }

    private fun postProviders() {
        providers.withLock {
            val filtered = when (filter) {
                ProviderFilter.All -> providers.toList()
                ProviderFilter.Passed -> providers.filter { it.second.success }
                ProviderFilter.Failed -> providers.filter { !it.second.success }
            }
            _providerResults.value = filtered
        }
    }

    fun setFilterMethod(filter: ProviderFilter) {
        if (this.filter == filter) return
        this.filter = filter
        postProviders()
    }

    private fun addProvider(api: MainAPI, results: TestingUtils.TestResultProvider) {
        providers.withLock {
            val index = providers.indexOfFirst { it.first == api }
            if (index == -1) {
                providers.add(api to results)
                if (results.success) passed++ else failed++
            } else {
                providers[index] = api to results
            }
            updateProgress()
        }
    }

    fun init() {
        total = APIHolder.allProviders.withLock { APIHolder.allProviders.size }
        updateProgress()
    }

    fun startTest() {
        stopTest()
        val currentScope = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Default)
        scope = currentScope

        val apis = APIHolder.allProviders.withLock { APIHolder.allProviders.toTypedArray() }
        total = apis.size
        failed = 0
        passed = 0
        providers.clear()
        updateProgress()

        TestingUtils.getDeferredProviderTests(currentScope, apis) { api, result ->
            addProvider(api, result)
        }
    }

    fun stopTest() {
        scope?.cancel()
        scope = null
    }

    override fun onCleared() {
        stopTest()
        super.onCleared()
    }
}
