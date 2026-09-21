package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.setLastWatched
import com.lagradost.cloudstream3.utils.DataStoreHelper.setResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos
import com.lagradost.cloudstream3.utils.DataStoreHelper.setBookmarkedData
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class HomeViewModelParityTest {

    private class TestMainPageAPI(
        override var name: String = "TestProvider",
        override var mainUrl: String = "https://test.home",
        override val supportedTypes: Set<TvType> = setOf(TvType.Movie),
        override val hasMainPage: Boolean = true,
        override var sequentialMainPage: Boolean = false,
        override var sequentialMainPageDelay: Long = 0L,
    ) : MainAPI() {
        var mainPageCalls = 0
        var requestedPages = mutableListOf<Pair<Int, String?>>()

        override val mainPage: List<MainPageData> = listOf(
            MainPageData("Popular Movies", "popular", true),
            MainPageData("Latest Releases", "latest", false)
        )

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            mainPageCalls++
            requestedPages.add(page to request.name)
            val items = if (page == 1) {
                listOf(
                    newMovieSearchResponse("Movie 1", "$mainUrl/m1", TvType.Movie) { this.posterUrl = "$mainUrl/p1.jpg"; this.year = 1 },
                    newMovieSearchResponse("Movie 2", "$mainUrl/m2", TvType.Movie) { this.posterUrl = "$mainUrl/p2.jpg"; this.year = 2 }
                )
            } else {
                listOf(
                    newMovieSearchResponse("Movie 2", "$mainUrl/m2", TvType.Movie) { this.posterUrl = "$mainUrl/p2.jpg"; this.year = 2 }, // Duplicate to test distinctBy
                    newMovieSearchResponse("Movie 3", "$mainUrl/m3", TvType.Movie) { this.posterUrl = "$mainUrl/p3.jpg"; this.year = 3 }
                )
            }
            return newHomePageResponse(
                list = listOf(HomePageList(request.name, items, request.horizontalImages)),
                hasNext = page < 3
            )
        }

        override suspend fun load(url: String): LoadResponse {
            return newMovieLoadResponse(
                name = "Loaded $url",
                url = url,
                type = TvType.Movie,
                dataUrl = url
            )
        }
    }

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        PlatformPaths.init()
        val testDataFile = tempDir.resolve("datastore_home_test.json").toFile()
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
    fun testStateFlowInitialParity() {
        val viewModel = HomeViewModel(Dispatchers.Default)

        assertTrue(viewModel.page.value is Resource.Loading)
        assertEquals(emptyList<DataStoreHelper.ResumeWatchingResult>(), viewModel.resumeWatching.value)
        assertEquals(Pair(false, emptyList<SearchResponse>()), viewModel.bookmarks.value)
        assertEquals(Pair(emptySet<WatchType>(), emptySet<WatchType>()), viewModel.availableWatchStatusTypes.value)
        assertTrue(viewModel.preview.value is Resource.Loading)
        assertNull(viewModel.randomItems.value)
        assertNull(viewModel.apiName.value)

        viewModel.onCleared()
    }

    @Test
    fun testLoadAndCancelWithMainPage() = runBlocking {
        val provider = TestMainPageAPI("TestHomePageProvider")
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)

        val viewModel = HomeViewModel(Dispatchers.Default)
        viewModel.loadAndCancel(provider.name, forceReload = true)

        // Wait for page state to succeed
        val pageResult = viewModel.page.filter { it is Resource.Success }.first()
        val pageMap = (pageResult as Resource.Success).value

        assertEquals(provider.name, viewModel.apiName.value)
        assertTrue(pageMap.containsKey("Popular Movies"))
        assertTrue(pageMap.containsKey("Latest Releases"))

        val popular = pageMap["Popular Movies"]!!
        assertEquals(1, popular.currentPage)
        assertTrue(popular.hasNext)
        assertEquals(2, popular.list.list.size)
        assertEquals("Movie 1", popular.list.list[0].name)
        assertEquals("Movie 2", popular.list.list[1].name)

        viewModel.onCleared()
    }

    @Test
    fun testNoHomepageProvider() = runBlocking {
        val noHomeProvider = object : MainAPI() {
            override var name: String = "NoHomeProvider"
            override var mainUrl: String = "https://no.home"
            override val supportedTypes: Set<TvType> = setOf(TvType.Movie)
            override val hasMainPage: Boolean = false
        }
        APIHolder.allProviders.add(noHomeProvider)
        APIHolder.addPluginMapping(noHomeProvider)

        val viewModel = HomeViewModel(Dispatchers.Default)
        viewModel.loadAndCancel(noHomeProvider.name, forceReload = true)

        val pageResult = viewModel.page.filter { it is Resource.Success }.first()
        val pageMap = (pageResult as Resource.Success).value
        assertTrue(pageMap.isEmpty())

        val previewResult = viewModel.preview.filter { it is Resource.Failure }.first()
        assertEquals("No homepage", (previewResult as Resource.Failure).errorString)

        viewModel.onCleared()
    }

    @Test
    fun testExpandAndReturnWithDeduplication() = runBlocking {
        val provider = TestMainPageAPI("ExpandProvider")
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)

        val viewModel = HomeViewModel(Dispatchers.Default)
        viewModel.loadAndCancel(provider.name, forceReload = true)

        // Wait for first page to load
        viewModel.page.filter { it is Resource.Success }.first()

        // Expand "Popular Movies"
        val expanded = viewModel.expandAndReturn("Popular Movies")
        assertNotNull(expanded)
        assertEquals(2, expanded!!.currentPage)
        assertTrue(expanded.hasNext)

        // List originally had Movie 1 and Movie 2.
        // Page 2 returns Movie 2 (duplicate) and Movie 3.
        // distinctBy { it.url } must produce exactly 3 items: Movie 1, Movie 2, Movie 3!
        assertEquals(3, expanded.list.list.size)
        val urls = expanded.list.list.map { it.url }
        assertEquals(listOf("${provider.mainUrl}/m1", "${provider.mainUrl}/m2", "${provider.mainUrl}/m3"), urls)

        viewModel.onCleared()
    }

    @Test
    fun testGetResumeWatchingWithCacheAndBackupFallback() = runBlocking {
        val parentId = 1001
        val episodeId = 2001

        setLastWatched(
            parentId = parentId,
            episodeId = episodeId,
            episode = 1,
            season = 1,
            isFromDownload = false,
            updateTime = 5000L
        )
        setViewPos(episodeId, 30_000L, 120_000L)

        val cachedHeader = DownloadObjects.DownloadHeaderCached(
            apiName = "TestProvider",
            url = "https://test.home/item1",
            type = TvType.TvSeries,
            name = "Awesome Series",
            poster = "https://test.home/poster.jpg",
            id = parentId,
            cacheTime = 5000L
        )

        // Case 1: Header is in primary DOWNLOAD_HEADER_CACHE
        setKey(DOWNLOAD_HEADER_CACHE, parentId.toString(), cachedHeader)

        val results1 = HomeViewModel.getResumeWatching()
        assertNotNull(results1)
        assertEquals(1, results1!!.size)
        val item1 = results1[0]
        assertEquals("Awesome Series", item1.name)
        assertEquals("https://test.home/item1", item1.url)
        assertEquals("TestProvider", item1.apiName)
        assertEquals(TvType.TvSeries, item1.type)
        assertEquals(30_000L, item1.watchPos?.position)
        assertEquals(120_000L, item1.watchPos?.duration)
        assertEquals(episodeId, item1.id)
        assertEquals(parentId, item1.parentId)

        // Case 2: Primary cache deleted, but backup exists -> should restore to primary!
        DesktopDataStore.removeKey(DOWNLOAD_HEADER_CACHE, parentId.toString())
        assertNull(getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, parentId.toString()))

        // Put into backup
        setKey(DOWNLOAD_HEADER_CACHE_BACKUP, parentId.toString(), cachedHeader)

        val results2 = HomeViewModel.getResumeWatching()
        assertNotNull(results2)
        assertEquals(1, results2!!.size)
        assertEquals("Awesome Series", results2[0].name)

        // Verify restoration in DOWNLOAD_HEADER_CACHE!
        val restored = getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, parentId.toString())
        assertNotNull(restored)
        assertEquals("Awesome Series", restored!!.name)

        // Test deleteResumeWatching()
        val viewModel = HomeViewModel(Dispatchers.Default)
        viewModel.deleteResumeWatching()
        delay(100)
        assertEquals(emptyList<DataStoreHelper.ResumeWatchingResult>(), viewModel.resumeWatching.value)

        viewModel.onCleared()
    }

    @Test
    fun testBookmarksAndWatchStatus() = runBlocking {
        val id1 = 501
        val id2 = 502

        setResultWatchState(id1, WatchType.WATCHING.internalId)
        setResultWatchState(id2, WatchType.COMPLETED.internalId)

        val bookmark1 = DataStoreHelper.BookmarkedData(
            bookmarkedTime = 1000L,
            id = id1,
            latestUpdatedTime = 1000L,
            name = "Watching Show",
            url = "https://test.home/w1",
            apiName = "TestProvider",
            type = TvType.TvSeries,
            posterUrl = null,
            year = 2024
        )
        val bookmark2 = DataStoreHelper.BookmarkedData(
            bookmarkedTime = 2000L,
            id = id2,
            latestUpdatedTime = 2000L,
            name = "Completed Show",
            url = "https://test.home/c2",
            apiName = "TestProvider",
            type = TvType.Movie,
            posterUrl = null,
            year = 2023
        )
        setBookmarkedData(id1, bookmark1)
        setBookmarkedData(id2, bookmark2)

        val viewModel = HomeViewModel(Dispatchers.Default)
        viewModel.loadStoredData(setOf(WatchType.WATCHING))

        val bookmarkResult = viewModel.bookmarks.filter { it.first }.first()
        assertTrue(bookmarkResult.first)
        assertEquals(1, bookmarkResult.second.size)
        assertEquals("Watching Show", bookmarkResult.second[0].name)

        val (preferredTypes, currentTypes) = viewModel.availableWatchStatusTypes.value
        assertEquals(setOf(WatchType.WATCHING), preferredTypes)
        assertTrue(currentTypes.contains(WatchType.WATCHING))
        assertTrue(currentTypes.contains(WatchType.COMPLETED))

        // Test deleteBookmarks
        viewModel.deleteBookmarks(listOf(bookmark1))
        delay(100)
        val updatedBookmarks = viewModel.bookmarks.value
        // After id1 deleted, only WATCHING was selected so list becomes empty
        assertEquals(0, updatedBookmarks.second.size)

        viewModel.onCleared()
    }

    @Test
    fun testCompanionEventsRegistrationAndCleanup() = runBlocking {
        val provider = TestMainPageAPI("EventTestProvider")
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)
        DataStoreHelper.currentHomePage = provider.name

        val viewModel = HomeViewModel(Dispatchers.Default)

        // Trigger reloadHomeEvent
        MainActivity.reloadHomeEvent.invoke(true)

        val pageResult = viewModel.page.filter { it is Resource.Success }.first()
        assertTrue((pageResult as Resource.Success).value.isNotEmpty())

        // Clear view model
        viewModel.onCleared()

        // After clearing, verify no crash on invoking companion events
        MainActivity.reloadHomeEvent.invoke(true)
        MainActivity.bookmarksUpdatedEvent.invoke(true)
        MainActivity.afterPluginsLoadedEvent.invoke(true)
        MainActivity.mainPluginsLoadedEvent.invoke(true)
        MainActivity.reloadAccountEvent.invoke(true)
    }

    @Test
    fun testQuickSearchSubmit() {
        var queryReceived: String? = null
        var providersReceived: Array<String>? = null

        val listener: (Pair<String?, Array<String>?>) -> Unit = { (query, providers) ->
            queryReceived = query
            providersReceived = providers
        }

        QuickSearchFragment.quickSearchEvent += listener

        val viewModel = HomeViewModel(Dispatchers.Default)
        val testProvider = TestMainPageAPI("SearchSubmitProvider")
        viewModel.repo = com.lagradost.cloudstream3.ui.APIRepository(testProvider)

        viewModel.queryTextSubmit("Inception")

        assertEquals("Inception", queryReceived)
        assertNotNull(providersReceived)
        assertEquals("SearchSubmitProvider", providersReceived!![0])

        QuickSearchFragment.quickSearchEvent -= listener
        viewModel.onCleared()
    }

    @Test
    fun testPopupState() {
        val viewModel = HomeViewModel(Dispatchers.Default)
        assertNull(viewModel.popup.value)

        val list = HomeViewModel.ExpandableHomepageList(
            HomePageList("Test", emptyList(), false),
            1,
            false
        )

        var deleted = false
        viewModel.popup(list) { deleted = true }

        assertNotNull(viewModel.popup.value)
        assertEquals("Test", viewModel.popup.value!!.first.list.name)
        viewModel.popup.value!!.second?.invoke()
        assertTrue(deleted)

        viewModel.popup(null)
        assertNull(viewModel.popup.value)

        viewModel.onCleared()
    }
}
