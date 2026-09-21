package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SearchViewModelTest {

    private class TestSearchProvider(
        override var name: String,
        private val results: List<SearchResponse>,
        private val searchDelayMs: Long = 0L,
    ) : MainAPI() {
        override var mainUrl = "https://test.$name"
        override val supportedTypes = setOf(TvType.Movie)

        override suspend fun search(query: String, page: Int): SearchResponseList {
            if (searchDelayMs > 0) {
                delay(searchDelayMs)
            }
            val filtered = results.filter { it.name.contains(query, ignoreCase = true) }
            return newSearchResponseList(filtered)
        }
    }

    private val dummyProvider = object : MainAPI() {
        override var name = "Dummy"
        override var mainUrl = "https://test.dummy"
        override val supportedTypes = setOf(TvType.Movie)
    }

    private fun createSearchResponse(title: String, provider: String): SearchResponse {
        return dummyProvider.newMovieSearchResponse(title, "https://test.$provider/$title", TvType.Movie) {
            this.posterUrl = "https://test.$provider/$title.jpg"
        }
    }

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        PlatformPaths.init()
        val testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        APIHolder.allProviders.clear()
        APIHolder.initAll()
    }

    @AfterEach
    fun teardown() {
        DesktopDataStore.customDataFile = null
        APIHolder.allProviders.clear()
        APIHolder.initAll()
    }

    @Test
    fun testBundleSearchInterleaving() {
        val itemA1 = createSearchResponse("Alpha 1", "A")
        val itemA2 = createSearchResponse("Alpha 2", "A")
        val itemA3 = createSearchResponse("Alpha 3", "A")

        val itemB1 = createSearchResponse("Beta 1", "B")
        val itemB2 = createSearchResponse("Beta 2", "B")

        val itemC1 = createSearchResponse("Gamma 1", "C")
        val itemC2 = createSearchResponse("Gamma 2", "C")
        val itemC3 = createSearchResponse("Gamma 3", "C")
        val itemC4 = createSearchResponse("Gamma 4", "C")

        val map = linkedMapOf(
            "ProviderA" to ExpandableSearchList(listOf(itemA1, itemA2, itemA3), 1, true),
            "ProviderB" to ExpandableSearchList(listOf(itemB1, itemB2), 1, false),
            "ProviderC" to ExpandableSearchList(listOf(itemC1, itemC2, itemC3, itemC4), 1, true),
        )

        val bundled = SearchViewModel.bundleSearch(map)
        val expected = listOf(
            itemA1, itemB1, itemC1,
            itemA2, itemB2, itemC2,
            itemA3, itemC3,
            itemC4
        )

        assertEquals(expected.size, bundled.list.size)
        assertEquals(expected.map { it.name }, bundled.list.map { it.name })
    }

    @Test
    fun testBundleSearchSingleAndEmpty() {
        val item = createSearchResponse("Solo", "A")
        val singleMap = mapOf("A" to ExpandableSearchList(listOf(item), 1, false))
        val singleResult = SearchViewModel.bundleSearch(singleMap)
        assertEquals(1, singleResult.list.size)
        assertEquals("Solo", singleResult.list[0].name)

        val emptyMap = emptyMap<String, ExpandableSearchList>()
        val emptyResult = SearchViewModel.bundleSearch(emptyMap)
        assertTrue(emptyResult.list.isEmpty())
    }

    @Test
    fun testSearchHistoryOperations() = runBlocking {
        val viewModel = SearchViewModel(Dispatchers.Unconfined)

        val item1 = SearchHistoryItem(
            searchedAt = 1000L,
            searchText = "Batman",
            type = listOf(TvType.Movie),
            key = "key1",
        )
        val item2 = SearchHistoryItem(
            searchedAt = 2000L,
            searchText = "Superman",
            type = listOf(TvType.Movie),
            key = "key2",
        )

        CloudStreamApp.setKey("${DataStoreHelper.currentAccount}/$SEARCH_HISTORY_KEY", "key1", item1)
        CloudStreamApp.setKey("${DataStoreHelper.currentAccount}/$SEARCH_HISTORY_KEY", "key2", item2)

        viewModel.updateHistory().join()
        val history = viewModel.currentHistory.value
        assertEquals(2, history.size)
        // Descending order by searchedAt
        assertEquals("Superman", history[0].searchText)
        assertEquals("Batman", history[1].searchText)

        // Remove item1
        viewModel.removeHistoryItem(item1).join()
        val historyAfterRemove = viewModel.currentHistory.value
        assertEquals(1, historyAfterRemove.size)
        assertEquals("Superman", historyAfterRemove[0].searchText)

        // Clear history
        viewModel.clearHistory().join()
        val historyAfterClear = viewModel.currentHistory.value
        assertTrue(historyAfterClear.isEmpty())

        viewModel.onCleared()
    }

    @Test
    fun testSearchRaceConditionPrevention() = runBlocking {
        val slowItem = createSearchResponse("Slow Result", "Slow")
        val fastItem = createSearchResponse("Fast Result", "Fast")

        val slowProvider = TestSearchProvider("SlowProvider", listOf(slowItem), searchDelayMs = 250L)
        val fastProvider = TestSearchProvider("FastProvider", listOf(fastItem), searchDelayMs = 10L)

        APIHolder.allProviders.add(slowProvider)
        APIHolder.allProviders.add(fastProvider)
        APIHolder.addPluginMapping(slowProvider)
        APIHolder.addPluginMapping(fastProvider)

        val viewModel = SearchViewModel()
        viewModel.reloadRepos()

        // Trigger first search (slow)
        viewModel.searchAndCancel("Slow")

        // Immediately trigger second search (cancels first, fast completes first)
        delay(20)
        viewModel.searchAndCancel("Fast")

        // Wait for all to finish
        delay(450)

        val searchResponse = viewModel.searchResponse.filterNotNull().first { it is Resource.Success }
        val results = (searchResponse as Resource.Success).value.list

        // Only the fast search query results should be present
        assertTrue(results.any { it.name == "Fast Result" })
        assertFalse(results.any { it.name == "Slow Result" })

        viewModel.onCleared()
    }

    @Test
    fun testSuggestionsHandling() {
        val viewModel = SearchViewModel()

        // Less than 2 chars returns empty list immediately
        viewModel.fetchSuggestions("a")
        assertTrue(viewModel.searchSuggestions.value.isEmpty())

        viewModel.clearSuggestions()
        assertTrue(viewModel.searchSuggestions.value.isEmpty())

        viewModel.onCleared()
    }
}
