package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SyncSearchViewModelTest {

    @Test
    fun testViewModelLifecycleAndClear() {
        val vm = SyncSearchViewModel(Dispatchers.Default)
        assertTrue(vm.viewModelScope.isActive)
        vm.onCleared()
        assertFalse(vm.viewModelScope.isActive)
    }

    @Test
    fun testSyncSearchResultSearchResponseContract() {
        val score = Score.from10(8.5)
        val response: SearchResponse = SyncSearchViewModel.SyncSearchResultSearchResponse(
            name = "Test Anime",
            url = "https://example.com/anime/1",
            apiName = "AniList",
            type = TvType.Anime,
            posterUrl = "https://example.com/poster.jpg",
            id = 12345,
            quality = SearchQuality.HD,
            posterHeaders = mapOf("User-Agent" to "Test"),
            score = score
        )

        assertEquals("Test Anime", response.name)
        assertEquals("https://example.com/anime/1", response.url)
        assertEquals("AniList", response.apiName)
        assertEquals(TvType.Anime, response.type)
        assertEquals("https://example.com/poster.jpg", response.posterUrl)
        assertEquals(12345, response.id)
        assertEquals(SearchQuality.HD, response.quality)
        assertEquals("Test", response.posterHeaders?.get("User-Agent"))
        assertEquals(score, response.score)
    }

    @Test
    fun testConversionBetweenSyncSearchResultAndSearchResponse() {
        val score = Score.from10(9.1)
        val syncResult = SyncAPI.SyncSearchResult(
            name = "Steins;Gate",
            apiName = "MyAnimeList",
            syncId = "mal-9253",
            url = "https://myanimelist.net/anime/9253",
            posterUrl = "https://cdn.myanimelist.net/images/anime/1935/127974.jpg",
            type = TvType.Anime,
            quality = SearchQuality.BlueRay,
            posterHeaders = mapOf("Referer" to "https://myanimelist.net"),
            id = 9253,
            score = score
        )

        // Test fromSyncSearchResult
        val converted = SyncSearchViewModel.SyncSearchResultSearchResponse.fromSyncSearchResult(syncResult)
        assertEquals("Steins;Gate", converted.name)
        assertEquals("MyAnimeList", converted.apiName)
        assertEquals("https://myanimelist.net/anime/9253", converted.url)
        assertEquals("https://cdn.myanimelist.net/images/anime/1935/127974.jpg", converted.posterUrl)
        assertEquals(TvType.Anime, converted.type)
        assertEquals(SearchQuality.BlueRay, converted.quality)
        assertEquals(9253, converted.id)
        assertEquals(score, converted.score)

        // Test extension function toSearchResponse
        val extensionConverted = syncResult.toSearchResponse()
        assertEquals(converted, extensionConverted)

        // Test converting back to SyncSearchResult
        val backToSync = converted.toSyncSearchResult(syncId = "mal-9253")
        assertEquals(syncResult.name, backToSync.name)
        assertEquals(syncResult.apiName, backToSync.apiName)
        assertEquals(syncResult.syncId, backToSync.syncId)
        assertEquals(syncResult.url, backToSync.url)
        assertEquals(syncResult.posterUrl, backToSync.posterUrl)
        assertEquals(syncResult.type, backToSync.type)
        assertEquals(syncResult.quality, backToSync.quality)
        assertEquals(syncResult.id, backToSync.id)
        assertEquals(syncResult.score, backToSync.score)
    }
}
