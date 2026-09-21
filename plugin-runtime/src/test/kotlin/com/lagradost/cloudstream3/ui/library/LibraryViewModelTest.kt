package com.lagradost.cloudstream3.ui.library

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
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
import java.util.Date

class LibraryViewModelTest {

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        PlatformPaths.init()
        val testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
    }

    @AfterEach
    fun teardown() {
        DesktopDataStore.customDataFile = null
    }

    private fun createLibraryItem(
        name: String,
        updatedTime: Long = 0L,
        releaseYear: Long = 0L,
        rating: Int = 0,
        syncId: String = name
    ): SyncAPI.LibraryItem {
        return SyncAPI.LibraryItem(
            name = name,
            url = "https://example.com/$name",
            syncId = syncId,
            apiName = "TestAPI",
            lastUpdatedUnixTime = updatedTime,
            releaseDate = if (releaseYear > 0) Date(releaseYear) else null,
            personalRating = Score.from(rating, 10)
        )
    }

    @Test
    fun testPageSortingAlphabetical() {
        val itemA = createLibraryItem("Attack on Titan")
        val itemB = createLibraryItem("Bleach")
        val itemC = createLibraryItem("Cyberpunk")

        val page = SyncAPI.Page(txt("Test"), listOf(itemB, itemA, itemC))

        page.sort(ListSorting.AlphabeticalA)
        assertEquals(listOf("Attack on Titan", "Bleach", "Cyberpunk"), page.items.map { it.name })

        page.sort(ListSorting.AlphabeticalZ)
        assertEquals(listOf("Cyberpunk", "Bleach", "Attack on Titan"), page.items.map { it.name })
    }

    @Test
    fun testPageSortingUpdatedTime() {
        val oldItem = createLibraryItem("Old", updatedTime = 1000L)
        val midItem = createLibraryItem("Mid", updatedTime = 2000L)
        val newItem = createLibraryItem("New", updatedTime = 3000L)

        val page = SyncAPI.Page(txt("Test"), listOf(midItem, oldItem, newItem))

        page.sort(ListSorting.UpdatedNew)
        assertEquals(listOf("New", "Mid", "Old"), page.items.map { it.name })

        page.sort(ListSorting.UpdatedOld)
        assertEquals(listOf("Old", "Mid", "New"), page.items.map { it.name })
    }

    @Test
    fun testPageSortingReleaseDate() {
        val date2020 = createLibraryItem("Item 2020", releaseYear = 1577836800000L)
        val date2022 = createLibraryItem("Item 2022", releaseYear = 1640995200000L)
        val date2024 = createLibraryItem("Item 2024", releaseYear = 1704067200000L)

        val page = SyncAPI.Page(txt("Test"), listOf(date2020, date2024, date2022))

        page.sort(ListSorting.ReleaseDateNew)
        assertEquals(listOf("Item 2024", "Item 2022", "Item 2020"), page.items.map { it.name })

        page.sort(ListSorting.ReleaseDateOld)
        assertEquals(listOf("Item 2020", "Item 2022", "Item 2024"), page.items.map { it.name })
    }

    @Test
    fun testPageSortingRating() {
        val lowRating = createLibraryItem("Low", rating = 3)
        val midRating = createLibraryItem("Mid", rating = 7)
        val highRating = createLibraryItem("High", rating = 10)

        val page = SyncAPI.Page(txt("Test"), listOf(midRating, lowRating, highRating))

        page.sort(ListSorting.RatingHigh)
        assertEquals(listOf("High", "Mid", "Low"), page.items.map { it.name })

        page.sort(ListSorting.RatingLow)
        assertEquals(listOf("Low", "Mid", "High"), page.items.map { it.name })
    }

    @Test
    fun testPageSortingQueryFuzzy() {
        val matchExact = createLibraryItem("Steins;Gate")
        val matchPartial = createLibraryItem("Steins;Gate 0")
        val noMatch = createLibraryItem("Naruto")

        val page = SyncAPI.Page(txt("Test"), listOf(noMatch, matchExact, matchPartial))

        page.sort(ListSorting.Query, query = "Steins;Gate")
        assertEquals("Steins;Gate", page.items[0].name)
        assertEquals("Steins;Gate 0", page.items[1].name)
        assertEquals("Naruto", page.items[2].name)
    }

    @Test
    fun testLibraryViewModelSwitchPage() {
        val vm = LibraryViewModel()
        assertEquals(0, vm.currentPage.value)

        vm.switchPage(3)
        assertEquals(3, vm.currentPage.value)

        vm.onCleared()
    }

    @Test
    fun testLibraryViewModelSortSynchronization() = runBlocking {
        val vm = LibraryViewModel()

        val item1 = createLibraryItem("Zebra", updatedTime = 100L)
        val item2 = createLibraryItem("Apple", updatedTime = 200L)
        val testPage = SyncAPI.Page(txt("All"), listOf(item1, item2))

        // Populate pages
        val pagesField = LibraryViewModel::class.java.getDeclaredField("_pages")
        pagesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = pagesField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<Resource<List<SyncAPI.Page>>?>
        stateFlow.value = Resource.Success(listOf(testPage))

        // Sort by AlphabeticalA
        vm.sort(ListSorting.AlphabeticalA).join()

        assertEquals(ListSorting.AlphabeticalA.ordinal, DataStoreHelper.librarySortingMode)
        assertEquals(ListSorting.AlphabeticalA, vm.currentSortingMethod)

        val sortedPages = (vm.pages.value as Resource.Success).value
        assertEquals(listOf("Apple", "Zebra"), sortedPages[0].items.map { it.name })

        vm.onCleared()
    }

    @Test
    fun testLibraryViewModelLocalListLoading() = runBlocking {
        // Setup bookmarks across watch types
        val watchTypeIds = listOf(
            WatchType.WATCHING to 101,
            WatchType.COMPLETED to 102,
            WatchType.ONHOLD to 103,
            WatchType.DROPPED to 104,
            WatchType.PLANTOWATCH to 105
        )

        for ((watchType, id) in watchTypeIds) {
            DataStoreHelper.setResultWatchState(id, watchType.internalId)
            DataStoreHelper.setBookmarkedData(
                id,
                DataStoreHelper.BookmarkedData(
                    bookmarkedTime = System.currentTimeMillis(),
                    id = id,
                    latestUpdatedTime = System.currentTimeMillis(),
                    name = "Show $id",
                    url = "https://example.com/show/$id",
                    apiName = "TestAPI",
                    type = TvType.TvSeries,
                    posterUrl = "https://example.com/poster/$id.jpg",
                    year = 2024,
                )
            )
        }

        // Add a favorite
        DataStoreHelper.setFavoritesData(
            201,
            DataStoreHelper.FavoritesData(
                favoritesTime = System.currentTimeMillis(),
                id = 201,
                latestUpdatedTime = System.currentTimeMillis(),
                name = "Favorite Show",
                url = "https://example.com/fav/201",
                apiName = "TestAPI",
                type = TvType.Movie,
                posterUrl = "https://example.com/poster/201.jpg",
                year = 2023,
            )
        )

        val vm = LibraryViewModel()
        assertEquals("Local", vm.currentSyncApi?.name)

        AccountManager.localListApi.requireLibraryRefresh = true
        vm.reloadPages(true)

        val pagesResource = vm.pages.filterNotNull().first { it is Resource.Success }
        val pages = (pagesResource as Resource.Success).value

        assertFalse(pages.isEmpty())
        // Should have pages for Watching, Completed, On Hold, Dropped, Plan to Watch, Favorites, Subscriptions
        assertTrue(pages.size >= 6)

        val watchingPage = pages.firstOrNull { it.items.any { item -> item.name == "Show 101" } }
        assertNotNull(watchingPage)

        val completedPage = pages.firstOrNull { it.items.any { item -> item.name == "Show 102" } }
        assertNotNull(completedPage)

        val favoritesPage = pages.firstOrNull { it.items.any { item -> item.name == "Favorite Show" } }
        assertNotNull(favoritesPage)

        vm.onCleared()
    }
}
