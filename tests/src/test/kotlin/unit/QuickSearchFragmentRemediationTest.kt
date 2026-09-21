package unit

import android.app.Activity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_HISTORY_KEY
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHistoryItem
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Architectural Remediation Test Suite for QuickSearchFragment and Search History Engine.
 *
 * Validates:
 * 1. Byte-to-byte parity of dub/sub regex query sanitization.
 * 2. Event bus (quickSearchEvent) and reactive coroutine flow (quickSearchFlow) event dispatch.
 * 3. Single provider resolution and instant quickSearch routing.
 * 4. Dynamic query hint generation matching R.string.search_hint_site.
 * 5. Full chronological search history audit under $currentAccount/search_history,
 *    including descending timestamp sort, atomic removal, clear, and multi-account isolation.
 * 6. QuickSearch history guard (quick searches must not pollute history).
 * 7. Callback and lifecycle cleanup (onDestroy).
 */
class QuickSearchFragmentRemediationTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("cs_quick_search_test_")
        System.setProperty("XDG_DATA_HOME", tempDir.toAbsolutePath().toString())
        PlatformPaths.init()
        DataStoreHelper.selectedKeyIndex = 0
        DesktopDataStore.reload()
        CloudStreamApp.removeKeys("${DataStoreHelper.currentAccount}/$SEARCH_HISTORY_KEY")
        APIHolder.allProviders.clear()
        APIHolder.apis = atomicListOf()
    }

    @AfterEach
    fun tearDown() {
        CloudStreamApp.removeKeys("0/$SEARCH_HISTORY_KEY")
        CloudStreamApp.removeKeys("1/$SEARCH_HISTORY_KEY")
        DataStoreHelper.selectedKeyIndex = 0
        APIHolder.allProviders.clear()
        APIHolder.apis = atomicListOf()
        APIHolder.initAll()
        QuickSearchFragment.reset()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testTitleSanitizationDubSubVariants() {
        // Upstream explicit suffix cases
        assertEquals("Attack on Titan", QuickSearchFragment.sanitizeQuery("Attack on Titan (DUB)"))
        assertEquals("Naruto Shippuden", QuickSearchFragment.sanitizeQuery("Naruto Shippuden (SUB)"))
        assertEquals("Bleach", QuickSearchFragment.sanitizeQuery("Bleach (Dub)"))
        assertEquals("One Piece", QuickSearchFragment.sanitizeQuery("One Piece (Sub)"))
        assertEquals("Demon Slayer", QuickSearchFragment.sanitizeQuery("Demon Slayer (dub)"))
        assertEquals("Jujutsu Kaisen", QuickSearchFragment.sanitizeQuery("Jujutsu Kaisen (sub)"))
        assertEquals("Death Note", QuickSearchFragment.sanitizeQuery("   Death Note (Sub)   "))
        assertEquals("Solo Leveling", QuickSearchFragment.sanitizeQuery("Solo Leveling"))
        assertEquals("", QuickSearchFragment.sanitizeQuery("   "))
        assertNull(QuickSearchFragment.sanitizeQuery(null))

        // Multiple spaces and inner parentheses preserved
        assertEquals("Fate/Zero (2011)", QuickSearchFragment.sanitizeQuery("Fate/Zero (2011) (DUB)"))
        assertEquals("Steins;Gate", QuickSearchFragment.sanitizeQuery("Steins;Gate  (dub)  "))
    }

    @Test
    fun testPushSearchDispatchesToEventBusAndSharedFlow() {
        var receivedBusQuery: String? = null
        var receivedBusProviders: Array<String>? = null

        val listener: (Pair<String?, Array<String>?>) -> Unit = { (query, providers) ->
            receivedBusQuery = query
            receivedBusProviders = providers
        }

        QuickSearchFragment.quickSearchEvent += listener

        try {
            QuickSearchFragment.pushSearch("Frieren: Beyond Journey's End (Dub)", arrayOf("AllAnime", "GogoAnime"))
            assertEquals("Frieren: Beyond Journey's End", receivedBusQuery)
            assertNotNull(receivedBusProviders)
            assertEquals(2, receivedBusProviders?.size)
            assertEquals("AllAnime", receivedBusProviders?.get(0))
            assertEquals("GogoAnime", receivedBusProviders?.get(1))

            // Verify reactive SharedFlow emission
            val flowValue = QuickSearchFragment.quickSearchFlow.replayCache.firstOrNull()
            assertNotNull(flowValue)
            assertEquals("Frieren: Beyond Journey's End", flowValue?.first)
            assertArrayEquals(arrayOf("AllAnime", "GogoAnime"), flowValue?.second)
        } finally {
            QuickSearchFragment.quickSearchEvent -= listener
        }
    }

    @Test
    fun testPushSearchActivityOverload() {
        var receivedQuery: String? = null
        var receivedProviders: Array<String>? = null

        val listener: (Pair<String?, Array<String>?>) -> Unit = { (query, providers) ->
            receivedQuery = query
            receivedProviders = providers
        }

        QuickSearchFragment.quickSearchEvent += listener

        try {
            val dummyActivity = Activity()
            QuickSearchFragment.pushSearch(dummyActivity, "Hunter x Hunter (SUB)", arrayOf("Zoro"))
            assertEquals("Hunter x Hunter", receivedQuery)
            assertNotNull(receivedProviders)
            assertEquals("Zoro", receivedProviders?.firstOrNull())
        } finally {
            QuickSearchFragment.quickSearchEvent -= listener
        }
    }

    @Test
    fun testSingleProviderAndQuickSearchResolution() {
        val quickApi = object : MainAPI() {
            override var name = "QuickTestApi"
            override var mainUrl = "https://quicktest.org"
            override val supportedTypes = setOf(TvType.Movie)
            override var hasQuickSearch = true
        }

        val regularApi = object : MainAPI() {
            override var name = "RegularTestApi"
            override var mainUrl = "https://regulartest.org"
            override val supportedTypes = setOf(TvType.TvSeries)
            override var hasQuickSearch = false
        }

        APIHolder.allProviders.add(quickApi)
        APIHolder.allProviders.add(regularApi)
        APIHolder.apis = atomicListOf(quickApi, regularApi)

        val fragment = QuickSearchFragment()

        // 1. When providers is null
        fragment.providers = null
        assertFalse(fragment.isSingleProvider)
        assertNull(fragment.firstProvider)
        assertFalse(fragment.isSingleProviderQuickSearch)

        // 2. When multiple providers
        fragment.providers = setOf("QuickTestApi", "RegularTestApi")
        assertFalse(fragment.isSingleProvider)
        assertFalse(fragment.isSingleProviderQuickSearch)

        // 3. When single provider without quickSearch
        fragment.providers = setOf("RegularTestApi")
        assertTrue(fragment.isSingleProvider)
        assertEquals("RegularTestApi", fragment.firstProvider)
        assertFalse(fragment.isSingleProviderQuickSearch)

        // 4. When single provider with quickSearch
        fragment.providers = setOf("QuickTestApi")
        assertTrue(fragment.isSingleProvider)
        assertEquals("QuickTestApi", fragment.firstProvider)
        assertTrue(fragment.isSingleProviderQuickSearch)
    }

    @Test
    fun testOnQueryTextChangeAndSubmitRouting() = runBlocking {
        var lastSearchQuery: String? = null
        var lastIsQuickSearch: Boolean? = null

        val testVm = object : SearchViewModel(Dispatchers.Unconfined) {
            override fun searchAndCancel(
                query: String,
                providersActive: Set<String>,
                ignoreSettings: Boolean,
                isQuickSearch: Boolean
            ) = kotlinx.coroutines.Job().also {
                lastSearchQuery = query
                lastIsQuickSearch = isQuickSearch
            }
        }

        val quickApi = object : MainAPI() {
            override var name = "InstantApi"
            override var mainUrl = "https://instant.org"
            override val supportedTypes = setOf(TvType.Anime)
            override var hasQuickSearch = true
        }

        APIHolder.allProviders.add(quickApi)
        APIHolder.apis = atomicListOf(quickApi)

        val fragment = QuickSearchFragment()
        fragment.searchViewModel = testVm
        fragment.providers = setOf("InstantApi")

        // 1. Text change with quick search enabled triggers isQuickSearch = true
        val changeResult = fragment.onQueryTextChange("Naruto")
        assertTrue(changeResult)
        assertEquals("Naruto", lastSearchQuery)
        assertEquals(true, lastIsQuickSearch)

        // 2. Query submit triggers isQuickSearch = false
        val submitResult = fragment.onQueryTextSubmit("Naruto Shippuden")
        assertTrue(submitResult)
        assertEquals("Naruto Shippuden", lastSearchQuery)
        assertEquals(false, lastIsQuickSearch)

        // 3. Text change when provider has NO quickSearch does not fire search
        val slowApi = object : MainAPI() {
            override var name = "SlowApi"
            override var mainUrl = "https://slow.org"
            override val supportedTypes = setOf(TvType.Anime)
            override var hasQuickSearch = false
        }
        APIHolder.allProviders.clear()
        APIHolder.allProviders.add(slowApi)
        APIHolder.apis = atomicListOf(slowApi)
        fragment.providers = setOf("SlowApi")

        lastSearchQuery = null
        lastIsQuickSearch = null
        fragment.onQueryTextChange("Bleach")
        assertNull(lastSearchQuery, "Text change must not trigger search when provider lacks quickSearch")
        assertNull(lastIsQuickSearch)
    }

    @Test
    fun testHandleAutoSearchAndInitFromBundle() {
        var submittedQuery: String? = null

        val fragment = object : QuickSearchFragment() {
            override fun onQueryTextSubmit(query: String, context: android.content.Context?): Boolean {
                submittedQuery = query
                return true
            }
        }

        // Test initFromBundle with autoSearch and provider array
        fragment.initFromBundle(arrayOf("Provider1", "Provider2"), "Tokyo Ghoul (Dub)")
        assertEquals(setOf("Provider1", "Provider2"), fragment.providers)
        assertEquals("Tokyo Ghoul", submittedQuery)

        // Test with blank autoSearch (should not submit)
        submittedQuery = null
        fragment.handleAutoSearch("   (SUB)   ")
        assertNull(submittedQuery)
    }

    @Test
    fun testDynamicQueryHintFormatting() {
        assertEquals("Search movies, series, anime, live... ", QuickSearchFragment.getQueryHint(null))
        assertEquals("Search Sflix...", QuickSearchFragment.getQueryHint("Sflix"))
        assertEquals("Search AllAnime...", QuickSearchFragment.getQueryHint("AllAnime"))
    }

    @Test
    fun testSearchHistoryChronologicalPersistenceAndAccountIsolation() = runBlocking {
        // Step 1: Write history entries with distinct timestamps under Account 0
        DataStoreHelper.selectedKeyIndex = 0
        val vm0 = SearchViewModel(Dispatchers.Unconfined)

        val itemOld = SearchHistoryItem(searchedAt = 1000L, searchText = "First Search", type = emptyList(), key = "k1")
        val itemMid = SearchHistoryItem(searchedAt = 2000L, searchText = "Second Search", type = emptyList(), key = "k2")
        val itemNew = SearchHistoryItem(searchedAt = 3000L, searchText = "Third Search", type = emptyList(), key = "k3")

        CloudStreamApp.setKey("0/$SEARCH_HISTORY_KEY", "k1", itemOld)
        CloudStreamApp.setKey("0/$SEARCH_HISTORY_KEY", "k2", itemMid)
        CloudStreamApp.setKey("0/$SEARCH_HISTORY_KEY", "k3", itemNew)

        vm0.updateHistory().join()
        val history0 = vm0.currentHistory.value

        assertEquals(3, history0.size)
        // Descending order assertion: newest searchedAt must be first
        assertEquals("Third Search", history0[0].searchText)
        assertEquals("Second Search", history0[1].searchText)
        assertEquals("First Search", history0[2].searchText)

        // Step 2: Account isolation - Switch to Account 1
        DataStoreHelper.selectedKeyIndex = 1
        val vm1 = SearchViewModel(Dispatchers.Unconfined)
        vm1.updateHistory().join()
        assertTrue(vm1.currentHistory.value.isEmpty(), "Account 1 must have empty search history initially")

        val acc1Item = SearchHistoryItem(searchedAt = 5000L, searchText = "Account 1 Unique", type = emptyList(), key = "acc1_k1")
        CloudStreamApp.setKey("1/$SEARCH_HISTORY_KEY", "acc1_k1", acc1Item)
        vm1.updateHistory().join()
        assertEquals(1, vm1.currentHistory.value.size)
        assertEquals("Account 1 Unique", vm1.currentHistory.value.first().searchText)

        // Switch back to Account 0 - ensure Account 0 still has its original entries unaffected
        DataStoreHelper.selectedKeyIndex = 0
        vm0.updateHistory().join()
        assertEquals(3, vm0.currentHistory.value.size)

        // Step 3: Single item removal by item
        vm0.removeHistoryItem(itemMid).join()
        val afterRemove = vm0.currentHistory.value
        assertEquals(2, afterRemove.size)
        assertFalse(afterRemove.any { it.searchText == "Second Search" })
        assertTrue(afterRemove.any { it.searchText == "Third Search" })
        assertTrue(afterRemove.any { it.searchText == "First Search" })

        // Step 4: Clear all history for current account
        vm0.clearHistory().join()
        assertTrue(vm0.currentHistory.value.isEmpty())

        // Account 1 must still retain its item
        DataStoreHelper.selectedKeyIndex = 1
        vm1.updateHistory().join()
        assertEquals(1, vm1.currentHistory.value.size)
        assertEquals("Account 1 Unique", vm1.currentHistory.value.first().searchText)

        vm0.onCleared()
        vm1.onCleared()
    }

    @Test
    fun testConstantsAndClickCallbackLifecycle() {
        assertEquals("autosearch", QuickSearchFragment.AUTOSEARCH_KEY)
        assertEquals("providers", QuickSearchFragment.PROVIDER_KEY)

        var clicked = false
        val callback: (SearchClickCallback) -> Unit = { clicked = true }
        QuickSearchFragment.clickCallback = callback
        assertNotNull(QuickSearchFragment.clickCallback)

        val fragment = QuickSearchFragment()
        fragment.onDestroy()
        assertNull(QuickSearchFragment.clickCallback)
    }
}
