// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.deleteAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Official-grade Desktop port of upstream HomeViewModel with 1:1 architectural parity.
 * Exposes pure reactive StateFlow streams for homepage rows, resume watching, bookmarks,
 * preview cards, and account state with zero polling.
 */
class HomeViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    companion object {
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? {
            val resumeWatching = withContext(Dispatchers.IO) {
                getAllResumeStateIds()?.mapNotNull { id ->
                    getLastWatched(id)
                }?.sortedBy { -it.updateTime }
            }
            val resumeWatchingResult = withContext(Dispatchers.IO) {
                resumeWatching?.mapNotNull { resume ->
                    val headerCache = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        resume.parentId.toString()
                    )

                    val data = if (headerCache == null) {
                        // We store resume watching data in download header cache
                        // Because downloads automatically pruned outdated download headers we
                        // removed resume watching data. We should restore the data for affected users.
                        val oldData = getKey<DownloadObjects.DownloadHeaderCached>(
                            DOWNLOAD_HEADER_CACHE_BACKUP,
                            resume.parentId.toString()
                        ) ?: return@mapNotNull null

                        // Restore data
                        setKey(DOWNLOAD_HEADER_CACHE, resume.parentId.toString(), oldData)
                        oldData
                    } else {
                        headerCache
                    }

                    val watchPos = getViewPos(resume.episodeId) ?: run {
                        // Desktop fallback: resolve watch position from DesktopDataStore if resume.episodeId key drifted
                        val hist = com.lagradost.common.storage.DesktopDataStore.getLatestWatchHistoryForShow(data.url)
                            ?: com.lagradost.common.storage.DesktopDataStore.getEpisodeWatched(resume.parentId.toString(), null)
                        if (hist != null && hist.duration > 0L) {
                            DataStoreHelper.PosDur(hist.position, hist.duration)
                        } else null
                    }

                    DataStoreHelper.ResumeWatchingResult(
                        data.name,
                        data.url,
                        data.apiName,
                        data.type,
                        data.poster,
                        watchPos,
                        resume.episodeId,
                        resume.parentId,
                        resume.episode,
                        resume.season,
                        resume.isFromDownload
                    )
                }
            }
            return resumeWatchingResult
        }
    }

    fun deleteResumeWatching() {
        deleteAllResumeStateIds()
        loadResumeWatching()
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
    }

    var repo: APIRepository? = null

    private val _apiName = MutableStateFlow<String?>(null)
    val apiName: StateFlow<String?> = _apiName.asStateFlow()

    private val _currentAccount = MutableStateFlow<DataStoreHelper.Account?>(null)
    val currentAccount: StateFlow<DataStoreHelper.Account?> = _currentAccount.asStateFlow()

    private val _randomItems = MutableStateFlow<List<SearchResponse>?>(null)
    val randomItems: StateFlow<List<SearchResponse>?> = _randomItems.asStateFlow()

    private var currentShuffledList: List<SearchResponse> = listOf()

    private fun autoloadRepo(): APIRepository? {
        val api = apis.withLock { apis.firstOrNull { it.hasMainPage } }
            ?: APIHolder.allProviders.firstOrNull { it.hasMainPage }
        return api?.let { APIRepository(it) }
    }

    private val _availableWatchStatusTypes =
        MutableStateFlow<Pair<Set<WatchType>, Set<WatchType>>>(Pair(emptySet(), emptySet()))
    val availableWatchStatusTypes: StateFlow<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes.asStateFlow()

    private val _bookmarks =
        MutableStateFlow<Pair<Boolean, List<SearchResponse>>>(Pair(false, emptyList()))
    val bookmarks: StateFlow<Pair<Boolean, List<SearchResponse>>> = _bookmarks.asStateFlow()

    private val _resumeWatching =
        MutableStateFlow<List<DataStoreHelper.ResumeWatchingResult>>(emptyList())
    val resumeWatching: StateFlow<List<DataStoreHelper.ResumeWatchingResult>> =
        _resumeWatching.asStateFlow()

    private val _preview =
        MutableStateFlow<Resource<Pair<Boolean, List<LoadResponse>>>>(Resource.Loading())
    val preview: StateFlow<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview.asStateFlow()

    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = mutableSetOf<String>()

    private fun loadResumeWatching() = viewModelScope.launchSafe {
        val resumeWatchingResult = getResumeWatching()
        resumeWatchingResult?.let {
            _resumeWatching.value = it
        }
    }

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.value = setOf<WatchType>() to setOf()
            _bookmarks.value = Pair(false, ArrayList())
            return@launchSafe
        }

        val watchPrefNotNull = preferredWatchStatus?.ifEmpty { null } ?: EnumSet.of(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.value = watchPrefNotNull to currentWatchTypes

        val list = withContext(Dispatchers.IO) {
            watchStatusIds.filter { watchPrefNotNull.contains(it.second) }
                .mapNotNull { getBookmarkedData(it.first) }
                .sortedBy { -it.latestUpdatedTime }
        }
        _bookmarks.value = Pair(true, list)
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null
    private fun loadAndCancel(api: MainAPI) {
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = api.name
        onGoingLoad = load(api)
    }

    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
    )

    private val expandable: MutableMap<String, ExpandableHomepageList> = mutableMapOf()
    private val _page =
        MutableStateFlow<Resource<Map<String, ExpandableHomepageList>>>(Resource.Loading())
    val page: StateFlow<Resource<Map<String, ExpandableHomepageList>>> = _page.asStateFlow()

    val lock: MutableSet<String> = mutableSetOf()

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        if (lock.contains(name)) return null
        lock += name

        repo?.apply {
            waitForHomeDelay()

            expandable[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = getMainPage(nextPage, mainPage.indexOfFirst { it.name == name })
                if (next is Resource.Success) {
                    next.value.filterNotNull().forEach { main ->
                        main.items.forEach { newList ->
                            val key = newList.name
                            expandable[key]?.apply {
                                hasNext = main.hasNext
                                currentPage = nextPage

                                debugWarning({ newList.list.any { outer -> this.list.list.any { it.url == outer.url } } }) {
                                    "Expanded contained an item that was previously already in the list\n${list.name} = ${this.list.list}\n${newList.name} = ${newList.list}"
                                }

                                this.list.list = CopyOnWriteArrayList((this.list.list + newList.list).distinctBy { it.url })
                            } ?: debugWarning {
                                "Expanded an item not in main load named $key, current list is ${expandable.keys}"
                            }
                        }
                    }
                } else {
                    current.hasNext = false
                }
            }
            _page.value = Resource.Success(expandable.toMap())
        }

        lock -= name

        return expandable[name]
    }

    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int
    ): Int {
        var count = 0

        val addItems = arrayListOf<SearchResponse>()
        for (searchResponse in shuffledList) {
            if (!alreadyAdded.contains(searchResponse.url)) {
                addItems.add(searchResponse)
                previewResponsesAdded.add(searchResponse.url)
                if (++count >= size) {
                    break
                }
            }
        }

        val add = addItems.amap { searchResponse ->
            repo?.load(searchResponse.url)
        }.mapNotNull { if (it != null && it is Resource.Success) it.value else null }
        current.addAll(add)
        return add.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        addJob = ioSafe {
            updatePreviewResponses(previewResponses, previewResponsesAdded, currentShuffledList, 1)
            _preview.value = Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses)
        }
    }

    private fun load(api: MainAPI): Job = ioSafe {
        repo = APIRepository(api)

        _apiName.value = repo?.name
        _randomItems.value = listOf()

        if (repo?.hasMainPage != true) {
            _page.value = Resource.Success(emptyMap())
            _preview.value = Resource.Failure(false, "No homepage")
            return@ioSafe
        }

        _page.value = Resource.Loading()
        _preview.value = Resource.Loading()
        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        when (val data = repo?.getMainPage(1, null)) {
            is Resource.Success -> {
                try {
                    expandable.clear()
                    data.value.forEach { home ->
                        home?.items?.forEach { list ->
                            val filteredList =
                                context?.filterHomePageListByFilmQuality(list) ?: list
                            expandable[list.name] =
                                ExpandableHomepageList(
                                    filteredList.copy(
                                        list = CopyOnWriteArrayList(
                                            filteredList.list
                                        )
                                    ), 1, home.hasNext
                                )
                        }
                    }

                    val items = data.value.mapNotNull { it?.items }.flatten()

                    previewResponses.clear()
                    previewResponsesAdded.clear()

                    if (items.isNotEmpty()) {
                        val currentList =
                            items.shuffled().filter { it.list.isNotEmpty() }
                                .flatMap { it.list }
                                .distinctBy { it.url }.toList()

                        if (currentList.isNotEmpty()) {
                            val randomItems =
                                context?.filterSearchResultByFilmQuality(currentList.shuffled())
                                    ?: currentList.shuffled()

                            updatePreviewResponses(
                                previewResponses,
                                previewResponsesAdded,
                                randomItems,
                                3
                            )

                            _randomItems.value = randomItems
                            currentShuffledList = randomItems
                        }
                    }
                    if (previewResponses.isEmpty()) {
                        _preview.value =
                            Resource.Failure(
                                false,
                                "No homepage responses"
                            )
                    } else {
                        _preview.value = Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses)
                    }
                    _page.value = Resource.Success(expandable.toMap())
                } catch (e: Exception) {
                    _randomItems.value = emptyList()
                    logError(e)
                }
            }

            is Resource.Failure -> {
                _page.value = data
                _preview.value = data
            }

            else -> Unit
        }
        isCurrentlyLoadingName = null
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    private val _popup = MutableStateFlow<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: StateFlow<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup.asStateFlow()

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.value = null
        else
            _popup.value = list to deleteCallback
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        loadAndCancel(DataStoreHelper.currentHomePage, forceReload)
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, false)
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.value = getCurrentAccount()
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount
    }

    override fun onCleared() {
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
        super.onCleared()
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(if (list.isEmpty()) null else list)
    }

    fun reloadStored() {
        loadResumeWatching()
        loadStoredData()
    }

    fun click(load: LoadClickCallback) {
        loadResult(load.response.url, load.response.apiName, load.response.name, load.action)
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) = ioSafe {
        val currentPage = page.value

        // if we don't need to reload and we have a valid homepage or currently loading the same thing then return
        val currentLoading = isCurrentlyLoadingName
        if (!forceReload && (currentPage is Resource.Success && currentPage.value.isNotEmpty() || (currentLoading != null && currentLoading == preferredApiName))) {
            return@ioSafe
        }

        val api = getApiFromNameNull(preferredApiName)
        if (preferredApiName == noneApi.name) {
            // just set to random
            if (fromUI) DataStoreHelper.currentHomePage = noneApi.name
            loadAndCancel(noneApi)
        } else if (preferredApiName == randomApi.name) {
            // randomize the api, if none exist like if not loaded or not installed
            // then use nothing
            val validAPIs = context?.filterProviderByPreferredMedia()
            if (validAPIs.isNullOrEmpty()) {
                loadAndCancel(noneApi)
            } else {
                val apiRandom = validAPIs.random()
                loadAndCancel(apiRandom)
                if (fromUI) DataStoreHelper.currentHomePage = apiRandom.name
            }
        } else if (api == null) {
            // API is not found aka not loaded or removed, post the loading
            // progress if waiting for plugins, otherwise nothing
            if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                loadAndCancel(noneApi)
            } else {
                _page.value = Resource.Loading()
                if (preferredApiName != null)
                    _apiName.value = preferredApiName
            }
        } else {
            // if the api is found, then set it to it and save key
            if (fromUI) DataStoreHelper.currentHomePage = api.name
            loadAndCancel(api)
        }
        reloadAccount()
    }
}
