// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/library/LibraryViewModel.kt", upstreamCommit = "9feeaedee64b3efc150a3f05df18f955abe170d0")
package com.lagradost.cloudstream3.ui.library

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.throwAbleToResource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val LAST_SYNC_API_KEY = "last_sync_api"

class LibraryViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    fun switchPage(page: Int) {
        _currentPage.value = page
    }

    private val _currentPage: MutableStateFlow<Int> = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _pages: MutableStateFlow<Resource<List<SyncAPI.Page>>?> = MutableStateFlow(null)
    val pages: StateFlow<Resource<List<SyncAPI.Page>>?> = _pages.asStateFlow()

    private val _currentApiName: MutableStateFlow<String> = MutableStateFlow("")
    val currentApiName: StateFlow<String> = _currentApiName.asStateFlow()

    private val availableSyncApis
        get() = AccountManager.syncApis.filter { it.isAvailable }

    var currentSyncApi = availableSyncApis.let { allApis ->
        val lastSelection = getKey<String>("$currentAccount/$LAST_SYNC_API_KEY")
        availableSyncApis.firstOrNull { it.name == lastSelection } ?: allApis.firstOrNull()
    }
        private set(value) {
            field = value
            setKey("$currentAccount/$LAST_SYNC_API_KEY", field?.name)
        }

    val availableApiNames: List<String>
        get() = availableSyncApis.map { it.name }

    var sortingMethods = emptyList<ListSorting>()
        private set

    var currentSortingMethod: ListSorting? = sortingMethods.firstOrNull()
        private set

    fun switchList(name: String) {
        val index = availableApiNames.indexOf(name)
        if (index in availableSyncApis.indices) {
            currentSyncApi = availableSyncApis[index]
            _currentApiName.value = currentSyncApi?.name ?: ""
            reloadPages(true)
        }
    }

    fun sort(method: ListSorting, query: String? = null) = ioSafe {
        val value = _pages.value ?: return@ioSafe
        if (value is Resource.Success) {
            sort(method, query, value.value)
        }
    }

    private fun sort(method: ListSorting, query: String? = null, items: List<SyncAPI.Page>) {
        currentSortingMethod = method
        DataStoreHelper.librarySortingMode = method.ordinal

        items.forEach { page ->
            page.sort(method, query)
        }
        _pages.value = Resource.Success(items.toList())
    }

    fun reloadPages(forceReload: Boolean) {
        // Only skip loading if its not forced and pages is not empty
        if (!forceReload && (pages.value as? Resource.Success)?.value?.isNotEmpty() == true &&
            currentSyncApi?.requireLibraryRefresh != true
        ) return

        ioSafe {
            currentSyncApi?.let { repo ->
                _currentApiName.value = repo.name
                _pages.value = Resource.Loading()
                val libraryResource = repo.library()
                val err = libraryResource.exceptionOrNull()
                if (err != null) {
                    _pages.value = throwAbleToResource(err)
                    return@let
                }
                val library = libraryResource.getOrNull()
                if (library == null) {
                    _pages.value = Resource.Failure(false, "Unable to fetch library")
                    return@let
                }

                sortingMethods = library.supportedListSorting.toList()
                repo.requireLibraryRefresh = false

                val pages = library.allLibraryLists.map {
                    SyncAPI.Page(
                        it.name,
                        it.items
                    )
                }

                val desiredSortingMethod =
                    ListSorting.entries.getOrNull(DataStoreHelper.librarySortingMode)
                if (desiredSortingMethod != null && library.supportedListSorting.contains(
                        desiredSortingMethod
                    )
                ) {
                    sort(desiredSortingMethod, null, pages)
                } else {
                    // null query = no sorting
                    sort(ListSorting.Query, null, pages)
                }
            }
        }
    }

    init {
        MainActivity.reloadLibraryEvent += ::reloadPages
    }

    override fun onCleared() {
        MainActivity.reloadLibraryEvent -= ::reloadPages
        super.onCleared()
    }
}
