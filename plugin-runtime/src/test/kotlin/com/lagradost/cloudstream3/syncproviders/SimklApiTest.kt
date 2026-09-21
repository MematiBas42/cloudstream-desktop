package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.SimklSyncServices
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.AllItemsResponse
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.EpisodeMetadata
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.MediaObject
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.SimklListStatusType
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.getDateTime
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.getPosterUrl
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi.Companion.getUnixTime
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SimklApiTest {

    private val api = SimklApi()

    @Test
    fun testUrlToId() {
        val tvUrl = "https://simkl.com/tv/12345/breaking-bad"
        assertEquals("12345", api.urlToId(tvUrl))

        val animeUrl = "https://simkl.com/anime/67890/steins-gate"
        assertEquals("67890", api.urlToId(animeUrl))

        val movieUrl = "https://simkl.com/movies/54321/interstellar"
        assertEquals("54321", api.urlToId(movieUrl))

        val invalidUrl = "https://example.com/other"
        assertEquals("", api.urlToId(invalidUrl))
    }

    @Test
    fun testPosterUrl() {
        val poster = "123456"
        val expected = "https://wsrv.nl/?url=https://simkl.in/posters/123456_m.webp"
        assertEquals(expected, getPosterUrl(poster))
    }

    @Test
    fun testUnixTimeAndDateTime() {
        val dateString = "2014-09-01T09:10:11Z"
        val unixTime = getUnixTime(dateString)
        assertEquals(1409562611L, unixTime)

        val formattedDate = getDateTime(unixTime)
        assertEquals(dateString, formattedDate)
    }

    @Test
    fun testSimklListStatusType() {
        assertEquals(SimklListStatusType.Watching, SimklListStatusType.fromString("watching"))
        assertEquals(SimklListStatusType.Completed, SimklListStatusType.fromString("completed"))
        assertEquals(SimklListStatusType.Paused, SimklListStatusType.fromString("hold"))
        assertEquals(SimklListStatusType.Dropped, SimklListStatusType.fromString("dropped"))
        assertEquals(SimklListStatusType.Planning, SimklListStatusType.fromString("plantowatch"))
        assertNull(SimklListStatusType.fromString("unknown_status"))
    }

    @Test
    fun testMediaObjectIdsAndMatching() {
        val serviceMap = mapOf(
            SimklSyncServices.Simkl to "1001",
            SimklSyncServices.Imdb to "tt1234567",
            SimklSyncServices.Tmdb to "999",
            SimklSyncServices.Mal to "888",
            SimklSyncServices.AniList to "777",
        )
        val ids = MediaObject.Ids.fromMap(serviceMap)
        assertEquals(1001, ids.simkl)
        assertEquals("tt1234567", ids.imdb)
        assertEquals("999", ids.tmdb)
        assertEquals("888", ids.mal)
        assertEquals("777", ids.anilist)

        val showIds = AllItemsResponse.ShowMetadata.Show.Ids(
            simkl = 1001,
            slug = "test-slug",
            imdb = "tt1234567",
            zap2it = null,
            tmdb = "999",
            offen = null,
            tvdb = null,
            mal = "888",
            anidb = null,
            anilist = "777",
            traktslug = null,
        )
        assertTrue(showIds.matchesId(SimklSyncServices.Simkl, "1001"))
        assertTrue(showIds.matchesId(SimklSyncServices.Imdb, "tt1234567"))
        assertTrue(showIds.matchesId(SimklSyncServices.Tmdb, "999"))
        assertTrue(showIds.matchesId(SimklSyncServices.Mal, "888"))
        assertTrue(showIds.matchesId(SimklSyncServices.AniList, "777"))
        assertFalse(showIds.matchesId(SimklSyncServices.Simkl, "9999"))
    }

    @Test
    fun testMediaObjectToSyncSearchResult() {
        val mediaObject = MediaObject(
            title = "Attack on Titan",
            year = 2013,
            ids = MediaObject.Ids(simkl = 42200),
            totalEpisodes = 25,
            status = "ended",
            poster = "42200",
            type = "anime",
        )
        assertTrue(mediaObject.hasEnded())
        val searchResult = mediaObject.toSyncSearchResult()
        assertNotNull(searchResult)
        assertEquals("Attack on Titan", searchResult!!.name)
        assertEquals("Simkl", searchResult.apiName)
        assertEquals("42200", searchResult.syncId)
        assertEquals("https://simkl.com/shows/42200", searchResult.url)
        assertEquals(TvType.TvSeries, searchResult.type)
    }

    @Test
    fun testEpisodeMetadataConversions() {
        val episodes = listOf(
            EpisodeMetadata(title = "Ep 1", description = "Desc 1", season = 1, episode = 1, img = null),
            EpisodeMetadata(title = "Ep 2", description = "Desc 2", season = 1, episode = 2, img = null),
            EpisodeMetadata(title = "Ep 1", description = "Desc 3", season = 2, episode = 1, img = null),
        )

        val convertedEpisodes = EpisodeMetadata.convertToEpisodes(episodes)
        assertNotNull(convertedEpisodes)
        assertEquals(3, convertedEpisodes!!.size)
        assertEquals(1, convertedEpisodes[0].number)
        assertEquals(2, convertedEpisodes[1].number)
        assertEquals(1, convertedEpisodes[2].number)

        val seasons = EpisodeMetadata.convertToSeasons(episodes)
        assertNotNull(seasons)
        assertEquals(2, seasons!!.size)
        assertEquals(1, seasons[0].number)
        assertEquals(2, seasons[0].episodes.size)
        assertEquals(2, seasons[1].number)
        assertEquals(1, seasons[1].episodes.size)
    }

    @Test
    fun testAllItemsResponseMerge() {
        val first = AllItemsResponse(
            shows = listOf(
                AllItemsResponse.ShowMetadata(
                    lastWatchedAt = "2024-01-01T00:00:00Z",
                    status = "watching",
                    userRating = 8,
                    lastWatched = "s1e5",
                    watchedEpisodesCount = 5,
                    totalEpisodesCount = 10,
                    show = AllItemsResponse.ShowMetadata.Show(
                        title = "Show 1",
                        poster = "p1",
                        year = 2020,
                        ids = AllItemsResponse.ShowMetadata.Show.Ids(1, null, null, null, null, null, null, null, null, null, null)
                    )
                )
            )
        )

        val second = AllItemsResponse(
            shows = listOf(
                AllItemsResponse.ShowMetadata(
                    lastWatchedAt = "2024-01-02T00:00:00Z",
                    status = "completed",
                    userRating = 9,
                    lastWatched = "s1e10",
                    watchedEpisodesCount = 10,
                    totalEpisodesCount = 10,
                    show = AllItemsResponse.ShowMetadata.Show(
                        title = "Show 1",
                        poster = "p1",
                        year = 2020,
                        ids = AllItemsResponse.ShowMetadata.Show.Ids(1, null, null, null, null, null, null, null, null, null, null)
                    )
                ),
                AllItemsResponse.ShowMetadata(
                    lastWatchedAt = "2024-01-02T00:00:00Z",
                    status = "watching",
                    userRating = null,
                    lastWatched = "s1e1",
                    watchedEpisodesCount = 1,
                    totalEpisodesCount = 12,
                    show = AllItemsResponse.ShowMetadata.Show(
                        title = "Show 2",
                        poster = "p2",
                        year = 2021,
                        ids = AllItemsResponse.ShowMetadata.Show.Ids(2, null, null, null, null, null, null, null, null, null, null)
                    )
                )
            )
        )

        val merged = AllItemsResponse.merge(first, second)
        assertEquals(2, merged.shows.size)
        // First item was replaced with updated status
        assertEquals("completed", merged.shows[0].status)
        assertEquals(10, merged.shows[0].watchedEpisodesCount)
        // Second item was added
        assertEquals("Show 2", merged.shows[1].show.title)
    }

    @Test
    fun testLoginRequest() {
        val loginPage = api.loginRequest()
        assertNotNull(loginPage)
        assertTrue(loginPage!!.url.startsWith("https://simkl.com/oauth/authorize"))
        assertTrue(loginPage.url.contains("response_type=code"))
        assertTrue(loginPage.url.contains("redirect_uri=cloudstreamapp://simkl"))
        assertNotNull(loginPage.payload)
        assertTrue(loginPage.url.contains("state=${loginPage.payload}"))
    }
}
