package com.lagradost.cloudstream3.ui

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class APIRepositoryTest {

    private class TestStreamProvider : MainAPI() {
        override var name = "TestStreamProvider"
        override var mainUrl = "https://teststream.local"
        override val supportedTypes = setOf(TvType.Movie)
        override val hasMainPage = true

        override val mainPage = listOf(
            MainPageData("Popular Movies", "popular", false),
            MainPageData("Trending Movies", "trending", false),
        )

        var loadCallCount = AtomicInteger(0)

        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val item = newMovieSearchResponse("Home $page - ${request.name}", "$mainUrl/movie/${request.data}", TvType.Movie)
            return newHomePageResponse(listOf(HomePageList(request.name, listOf(item))))
        }

        override suspend fun search(query: String): List<SearchResponse> {
            return listOf(
                newMovieSearchResponse("Result for $query", "$mainUrl/movie/search-1", TvType.Movie)
            )
        }

        override suspend fun load(url: String): LoadResponse {
            loadCallCount.incrementAndGet()
            return newMovieLoadResponse("Test Title", url, TvType.Movie, "$url/watch")
        }

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            subtitleCallback(newSubtitleFile("en", "https://teststream.local/subtitles/en.vtt"))
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Stream",
                    url = "https://teststream.local/stream/master.m3u8",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
    }

    @Test
    fun `test search to load to loadLinks full flow generates ExtractorLink`() = runBlocking {
        val provider = TestStreamProvider()
        val repo = APIRepository(provider)

        // 1. Search
        val searchResult = repo.search("Inception", 1)
        assertTrue(searchResult is Resource.Success)
        val searchList = (searchResult as Resource.Success).value
        assertFalse(searchList.items.isEmpty())
        val firstItem = searchList.items.first()
        assertEquals("Result for Inception", firstItem.name)

        // 2. Load
        val loadResult = repo.load(firstItem.url)
        assertTrue(loadResult is Resource.Success)
        val loadResponse = (loadResult as Resource.Success).value as MovieLoadResponse
        assertEquals("Test Title", loadResponse.name)
        assertEquals("https://teststream.local/movie/search-1/watch", loadResponse.dataUrl)

        // 3. LoadLinks -> ExtractorLink & SubtitleFile
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val success = repo.loadLinks(
            data = loadResponse.dataUrl,
            isCasting = false,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )

        assertTrue(success)
        assertEquals(1, subtitles.size)
        assertEquals("en", subtitles[0].lang)
        assertEquals("https://teststream.local/subtitles/en.vtt", subtitles[0].url)

        assertEquals(1, links.size)
        val link = links[0]
        assertEquals("TestStreamProvider Stream", link.name)
        assertEquals("https://teststream.local/stream/master.m3u8", link.url)
        assertTrue(link.isM3u8)
        assertEquals(ExtractorLinkType.M3U8, link.type)
        assertEquals(Qualities.P1080.value, link.quality)
    }

    @Test
    fun `test LRU cache with 20 items limit and TTL invalidation`() = runBlocking {
        val provider = TestStreamProvider()
        val repo = APIRepository(provider)

        // Initial load
        val url = "https://teststream.local/movie/cache-test"
        val res1 = repo.load(url)
        assertTrue(res1 is Resource.Success)
        assertEquals(1, provider.loadCallCount.get())

        // Cached load should not invoke provider.load
        val res2 = repo.load(url)
        assertTrue(res2 is Resource.Success)
        assertEquals(1, provider.loadCallCount.get())

        // Fill cache up to CACHE_SIZE (20 items)
        for (i in 1..20) {
            val itemUrl = "https://teststream.local/movie/cache-item-$i"
            val itemRes = repo.load(itemUrl)
            assertTrue(itemRes is Resource.Success)
        }
        assertEquals(21, provider.loadCallCount.get())

        // 21st item pushes rolling cache index
        val itemUrl21 = "https://teststream.local/movie/cache-item-21"
        val itemRes21 = repo.load(itemUrl21)
        assertTrue(itemRes21 is Resource.Success)
        assertEquals(22, provider.loadCallCount.get())

        // Force reload via event clears cache
        MainActivity.afterPluginsLoadedEvent.invoke(true)

        // Loading the same item again should trigger provider.load
        val resAfterClear = repo.load(itemUrl21)
        assertTrue(resAfterClear is Resource.Success)
        assertEquals(23, provider.loadCallCount.get())
    }

    @Test
    fun `test timeouts clamp within MIN_TIMEOUT and MAX_TIMEOUT`() {
        assertEquals(120_000L, APIRepository.getTimeout(null))
        assertEquals(5_000L, APIRepository.getTimeout(1_000L)) // Clamped to MIN_TIMEOUT
        assertEquals(50_000L, APIRepository.getTimeout(50_000L))
        assertEquals(480_000L, APIRepository.getTimeout(999_999L)) // Clamped to MAX_TIMEOUT
    }

    @Test
    fun `test anti-scraping sequentialMainPageDelay and waitForHomeDelay`() = runBlocking {
        val provider = TestStreamProvider().apply {
            sequentialMainPage = true
            sequentialMainPageDelay = 50L
            sequentialMainPageScrollDelay = 20L
        }
        val repo = APIRepository(provider)

        val startTime = System.currentTimeMillis()
        val result = repo.getMainPage(1)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(result is Resource.Success)
        val pages = (result as Resource.Success).value
        assertEquals(2, pages.size)
        // Since there are 2 pages and sequentialMainPageDelay is 50ms, elapsed must be at least ~40ms
        assertTrue(elapsed >= 30L, "Elapsed $elapsed ms should be >= 30ms due to sequential delay")

        // Test waitForHomeDelay
        repo.waitForHomeDelay()
        assertTrue(provider.lastHomepageRequest > 0)
    }

    @Test
    fun `test isInvalidData and error handling`() = runBlocking {
        val provider = TestStreamProvider()
        val repo = APIRepository(provider)

        assertTrue(APIRepository.isInvalidData(""))
        assertTrue(APIRepository.isInvalidData("[]"))
        assertTrue(APIRepository.isInvalidData("about:blank"))
        assertFalse(APIRepository.isInvalidData("https://valid.url"))

        val loadInvalid = repo.load("about:blank")
        assertTrue(loadInvalid is Resource.Failure)

        val loadLinksInvalid = repo.loadLinks(
            data = "[]",
            isCasting = false,
            subtitleCallback = {},
            callback = {}
        )
        assertFalse(loadLinksInvalid)
    }
}
