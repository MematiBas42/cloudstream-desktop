package com.lagradost.cloudstream3.ui.settings.testing

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.TestingUtils
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TestViewModelAndTestingUtilsTest {

    private class MockTestingProvider(
        override var name: String = "MockProvider",
        override var mainUrl: String = "https://mock.example.com",
        override val supportedTypes: Set<TvType> = setOf(TvType.Movie),
        override var hasMainPage: Boolean = true,
        private val shouldFailSearch: Boolean = false,
        private val shouldFailLoad: Boolean = false,
        private val shouldFailLinks: Boolean = false,
    ) : MainAPI() {
        override val mainPage: List<MainPageData> = listOf(
            MainPageData("Featured", "featured_data", true)
        )

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val item = newMovieSearchResponse("iron guy", "$mainUrl/movie/1", TvType.Movie)
            return newHomePageResponse(listOf(HomePageList("Featured", listOf(item))))
        }

        override suspend fun search(query: String, page: Int): SearchResponseList? {
            if (shouldFailSearch) return null
            val item = newMovieSearchResponse("iron guy", "$mainUrl/movie/1", TvType.Movie)
            return newSearchResponseList(listOf(item))
        }

        override suspend fun load(url: String): LoadResponse? {
            if (shouldFailLoad) return null
            return newMovieLoadResponse("iron guy", url, TvType.Movie, "data_url_1")
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            if (shouldFailLinks) return false
            callback(
                newExtractorLink(
                    source = name,
                    name = "MockStream",
                    url = "https://stream.example.com/video.mp4",
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
    }

    @BeforeEach
    fun setup() {
        APIHolder.allProviders.clear()
    }

    @AfterEach
    fun tearDown() {
        APIHolder.allProviders.clear()
    }

    @Test
    fun `TestingUtils Logger records messages and levels correctly`() {
        val logger = TestingUtils.Logger()
        logger.log("Info message")
        logger.warn("Warning message")
        logger.error("Error message")

        val raw = logger.getRawLog()
        assertEquals(3, raw.size)
        assertEquals(TestingUtils.Logger.LogLevel.Normal, raw[0].level)
        assertEquals("Info message", raw[0].message)
        assertEquals("Info message", raw[0].toString())

        assertEquals(TestingUtils.Logger.LogLevel.Warning, raw[1].level)
        assertEquals("Warning message", raw[1].message)
        assertEquals("Warning: Warning message", raw[1].toString())

        assertEquals(TestingUtils.Logger.LogLevel.Error, raw[2].level)
        assertEquals("Error message", raw[2].message)
        assertEquals("Error: Error message", raw[2].toString())
    }

    @Test
    fun `TestingUtils testHomepage with no main page returns Pass`() = runBlocking {
        val provider = MockTestingProvider(hasMainPage = false)
        val logger = TestingUtils.Logger()
        val result = TestingUtils.testHomepage(provider, logger)
        assertTrue(result.success)
        assertEquals(TestingUtils.TestResult.Pass, result)
    }

    @Test
    fun `TestingUtils testHomepage with valid main page returns TestResultList`() = runBlocking {
        val provider = MockTestingProvider(hasMainPage = true)
        val logger = TestingUtils.Logger()
        val result = TestingUtils.testHomepage(provider, logger)
        assertTrue(result.success)
        assertTrue(result is TestingUtils.TestResultList)
        val list = (result as TestingUtils.TestResultList).results
        assertEquals(1, list.size)
        assertEquals("iron guy", list[0].name)
    }

    @Test
    fun `TestingUtils getDeferredProviderTests executes test pipeline successfully`() {
        val provider = MockTestingProvider()
        val scope = CoroutineScope(Dispatchers.Default)
        var receivedApi: MainAPI? = null
        var receivedResult: TestingUtils.TestResultProvider? = null
        val latch = CountDownLatch(1)

        TestingUtils.getDeferredProviderTests(scope, arrayOf(provider)) { api, result ->
            receivedApi = api
            receivedResult = result
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedApi)
        assertEquals("MockProvider", receivedApi?.name)
        assertNotNull(receivedResult)
        assertTrue(receivedResult?.success == true)
        assertNull(receivedResult?.exception)
    }

    @Test
    fun `TestingUtils getDeferredProviderTests captures failure when search fails`() {
        val provider = MockTestingProvider(shouldFailSearch = true)
        val scope = CoroutineScope(Dispatchers.Default)
        var receivedResult: TestingUtils.TestResultProvider? = null
        val latch = CountDownLatch(1)

        TestingUtils.getDeferredProviderTests(scope, arrayOf(provider)) { _, result ->
            receivedResult = result
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedResult)
        assertFalse(receivedResult?.success == true)
        assertNotNull(receivedResult?.exception)
    }

    @Test
    fun `TestViewModel lifecycle and progress tracking`() = runBlocking {
        val vm = TestViewModel(Dispatchers.Default)

        assertEquals(false, vm.isRunningTest)
        assertNull(vm.providerProgress.value)
        assertTrue(vm.providerResults.value.isEmpty())

        val provider = MockTestingProvider()
        APIHolder.allProviders.add(provider)
        vm.init()

        assertNotNull(vm.providerProgress.value)
        assertEquals(1, vm.providerProgress.value?.total)
        assertEquals(0, vm.providerProgress.value?.passed)
        assertEquals(0, vm.providerProgress.value?.failed)

        vm.startTest()
        assertTrue(vm.isRunningTest)

        // Wait for test to complete or run
        var attempts = 0
        while (attempts < 50 && (vm.providerProgress.value?.passed ?: 0) == 0) {
            delay(50)
            attempts++
        }

        assertEquals(1, vm.providerProgress.value?.passed)
        assertEquals(0, vm.providerProgress.value?.failed)
        assertEquals(1, vm.providerResults.value.size)

        vm.stopTest()
        assertFalse(vm.isRunningTest)

        vm.onCleared()
    }

    @Test
    fun `TestViewModel filtering works across Passed Failed All`() = runBlocking {
        val vm = TestViewModel(Dispatchers.Default)
        val passingProvider = MockTestingProvider(name = "Passing")
        val failingProvider = MockTestingProvider(name = "Failing", shouldFailSearch = true)

        APIHolder.allProviders.add(passingProvider)
        APIHolder.allProviders.add(failingProvider)
        vm.init()

        vm.startTest()
        var attempts = 0
        while (attempts < 50 && ((vm.providerProgress.value?.passed ?: 0) + (vm.providerProgress.value?.failed ?: 0)) < 2) {
            delay(50)
            attempts++
        }

        vm.setFilterMethod(TestViewModel.ProviderFilter.All)
        assertEquals(2, vm.providerResults.value.size)

        vm.setFilterMethod(TestViewModel.ProviderFilter.Passed)
        assertEquals(1, vm.providerResults.value.size)
        assertEquals("Passing", vm.providerResults.value[0].first.name)

        vm.setFilterMethod(TestViewModel.ProviderFilter.Failed)
        assertEquals(1, vm.providerResults.value.size)
        assertEquals("Failing", vm.providerResults.value[0].first.name)

        vm.stopTest()
        vm.onCleared()
    }
}
