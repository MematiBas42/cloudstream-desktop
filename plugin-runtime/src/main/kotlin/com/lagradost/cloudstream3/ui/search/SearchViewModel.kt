// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchViewModel.kt", upstreamCommit = "9feeaedee64b3efc150a3f05df18f955abe170d0")
package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class ExpandableSearchList(
    var list: List<SearchResponse>,
    var currentPage: Int,
    var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    private val _searchResponse: MutableStateFlow<Resource<ExpandableSearchList>?> =
        MutableStateFlow(null)
    val searchResponse: StateFlow<Resource<ExpandableSearchList>?> = _searchResponse.asStateFlow()

    private val _currentSearch: MutableStateFlow<Map<String, ExpandableSearchList>> =
        MutableStateFlow(emptyMap())
    val currentSearch: StateFlow<Map<String, ExpandableSearchList>> = _currentSearch.asStateFlow()

    private val _currentHistory: MutableStateFlow<List<SearchHistoryItem>> =
        MutableStateFlow(emptyList())
    val currentHistory: StateFlow<List<SearchHistoryItem>> = _currentHistory.asStateFlow()

    private val _searchSuggestions: MutableStateFlow<List<String>> =
        MutableStateFlow(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    fun clearSearch() {
        _searchResponse.value = Resource.Success(ExpandableSearchList(emptyList(), 0, false))
        _currentSearch.value = emptyMap()
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = mutableMapOf()

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        onGoingSearch = search(query, providersActive, ignoreSettings, isQuickSearch)
    }

    private suspend fun updateHistoryInternal() {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.value = items
    }

    fun updateHistory(): Job = ioSafe {
        updateHistoryInternal()
    }

    fun removeHistoryItem(item: SearchHistoryItem): Job = ioSafe {
        removeKey("$currentAccount/$SEARCH_HISTORY_KEY", item.key)
        updateHistoryInternal()
    }

    fun removeHistoryItem(key: String): Job = ioSafe {
        removeKey("$currentAccount/$SEARCH_HISTORY_KEY", key)
        updateHistoryInternal()
    }

    fun clearHistory(): Job = ioSafe {
        removeKeys("$currentAccount/$SEARCH_HISTORY_KEY")
        updateHistoryInternal()
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     *
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()

        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.value = emptyList()
            return
        }

        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.value = suggestions
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.value = emptyList()
    }

    private val lock: MutableSet<String> = mutableSetOf()

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        if (lock.contains(name)) return null
        val query = lastQuery ?: return null
        val repo = repos.find { it.name == name } ?: return null

        lock += name

        expandableSearches[name]?.let { current ->
            debugAssert({ !current.hasNext }) {
                "Expand called when not needed"
            }

            val nextPage = current.currentPage + 1
            val next = repo.search(query, nextPage)
            if (next is Resource.Success) {
                val nextValue = next.value
                expandableSearches[name]?.apply {
                    this.hasNext = nextValue.hasNext
                    this.currentPage = nextPage

                    debugWarning({ nextValue.items.any { outer -> this.list.any { it.url == outer.url } } }) {
                        "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextValue.items} = ${this.list}"
                    }

                    // just to be sure we are not adding the same shit for some reason
                    // Avoids weird behavior in the recyclerview by recreating the list
                    this.list = (this.list + nextValue.items).distinctBy { it.url }
                } ?: debugWarning {
                    "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                }
            } else {
                current.hasNext = false
            }

            _searchResponse.value = Resource.Success(bundleSearch(expandableSearches))
            _currentSearch.value = expandableSearches.toMap()
        }

        lock -= name

        val item = expandableSearches[name] ?: return null
        return HomeViewModel.ExpandableHomepageList(
            HomePageList(name, item.list),
            item.currentPage,
            item.hasNext
        )
    }

    companion object {
        fun bundleSearch(lists: Map<String, ExpandableSearchList>): ExpandableSearchList {
            if (lists.size == 1) {
                return lists.values.first()
            }

            val list = ArrayList<SearchResponse>()
            val nestedList = lists.map { it.value.list }

            // Round-robin interleaving to balance relevant search results across providers
            var index = 0
            while (true) {
                var added = 0
                for (sublist in nestedList) {
                    if (sublist.size > index) {
                        list.add(sublist[index])
                        added++
                    }
                }
                if (added == 0) break
                index++
            }

            return ExpandableSearchList(list, 1, false)
        }
    }

    fun bundleSearch(lists: MutableMap<String, ExpandableSearchList>): ExpandableSearchList =
        Companion.bundleSearch(lists)

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(),
                        key = key,
                    )
                )
            }

            _searchResponse.value = Resource.Loading()
            _currentSearch.value = emptyMap()
            expandableSearches.clear()

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                val filteredRepos = repos.filter { a ->
                    (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                }
                filteredRepos.amap { a -> // Parallel
                    val search = if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                    if (currentSearchIndex != currentIndex) return@amap
                    if (search is Resource.Success) {
                        val searchValue = search.value
                        synchronized(expandableSearches) {
                            expandableSearches[a.name] =
                                ExpandableSearchList(searchValue.items, 1, searchValue.hasNext)
                        }
                    }

                    val currentSnapshot = synchronized(expandableSearches) {
                        expandableSearches.toMap()
                    }
                    _currentSearch.value = currentSnapshot
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                val orderedMap = synchronized(expandableSearches) {
                    val repoOrder = repos.map { it.name }
                    expandableSearches.entries
                        .sortedBy { entry ->
                            val idx = repoOrder.indexOf(entry.key)
                            if (idx >= 0) idx else Int.MAX_VALUE
                        }
                        .associate { it.key to it.value }
                }

                _currentSearch.value = orderedMap
                val list = bundleSearch(orderedMap)

                _searchResponse.value = Resource.Success(list)
            }
        }

    override fun onCleared() {
        onGoingSearch?.cancel()
        suggestionJob?.cancel()
        super.onCleared()
    }
}
