package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.MALApi.Companion.MalStatusType
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MALApiTest {

    private val api = MALApi()

    @Test
    fun testProperties() {
        assertEquals("MAL", api.name)
        assertEquals("mal", api.idPrefix)
        assertTrue(api.hasOAuth2)
        assertEquals("mallogin", api.redirectUrlIdentifier)
        assertEquals("https://myanimelist.net", api.mainUrl)
        assertEquals(SyncIdName.MyAnimeList, api.syncIdName)
        assertEquals("https://myanimelist.net/register.php", api.createAccountUrl)

        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.WATCHING))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.COMPLETED))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.PLANTOWATCH))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.DROPPED))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.ONHOLD))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.NONE))
    }

    @Test
    fun testLoginRequest() {
        val loginPage = api.loginRequest()
        assertNotNull(loginPage)
        assertTrue(loginPage!!.url.startsWith("https://myanimelist.net/v1/oauth2/authorize"))
        assertTrue(loginPage.url.contains("response_type=code"))
        assertTrue(loginPage.url.contains("client_id="))
        assertTrue(loginPage.url.contains("code_challenge="))
        assertTrue(loginPage.url.contains("state=RequestID"))

        assertNotNull(loginPage.payload)
        val payload = parseJson<MALApi.Payload>(loginPage.payload!!)
        assertTrue(payload.requestId > 0)
        assertTrue(payload.codeVerifier.isNotBlank())
    }

    @Test
    fun testUrlToId() {
        val url = "https://myanimelist.net/anime/52991/Sousou_no_Frieren"
        val id = api.urlToId(url)
        assertEquals("/anime/52991/", id)
    }

    @Test
    fun testStatusConversions() {
        assertEquals(MalStatusType.Watching, MALApi.convertToStatus("watching"))
        assertEquals(MalStatusType.Completed, MALApi.convertToStatus("completed"))
        assertEquals(MalStatusType.OnHold, MALApi.convertToStatus("on_hold"))
        assertEquals(MalStatusType.Dropped, MALApi.convertToStatus("dropped"))
        assertEquals(MalStatusType.PlanToWatch, MALApi.convertToStatus("plan_to_watch"))
        assertEquals(MalStatusType.None, MALApi.convertToStatus("unknown"))
    }

    @Test
    fun testDataToLibraryItem() {
        val data = MALApi.Data(
            node = MALApi.Node(
                id = 52991,
                title = "Sousou no Frieren",
                mainPicture = MALApi.MainPicture(
                    medium = "https://example.com/m.jpg",
                    large = "https://example.com/l.jpg"
                ),
                alternativeTitles = null,
                mediaType = "tv",
                numEpisodes = 28,
                status = "finished_airing",
                startDate = "2023-09-29",
                endDate = "2024-03-22",
                averageEpisodeDuration = 1440,
                synopsis = "The adventure is over, but life goes on for an elf mage.",
                mean = 9.38,
                genres = listOf(MALApi.Genres(id = 1, name = "Adventure"), MALApi.Genres(id = 10, name = "Fantasy")),
                rank = 1,
                popularity = 50,
                numListUsers = 500000,
                numFavorites = 30000,
                numScoringUsers = 350000,
                startSeason = MALApi.StartSeason(year = 2023, season = "fall"),
                broadcast = null,
                nsfw = "white",
                createdAt = "2023-01-01T00:00:00+0000",
                updatedAt = "2024-03-22T00:00:00+0000"
            ),
            listStatus = MALApi.ListStatus(
                status = "watching",
                score = 10,
                numEpisodesWatched = 20,
                isRewatching = false,
                updatedAt = "2024-01-01T12:00:00+0000"
            )
        )

        val item = data.toLibraryItem()
        assertEquals("Sousou no Frieren", item.name)
        assertEquals("https://myanimelist.net/anime/52991/", item.url)
        assertEquals("52991", item.syncId)
        assertEquals(20, item.episodesCompleted)
        assertEquals(28, item.episodesTotal)
        assertEquals(Score.from10(10), item.personalRating)
        assertEquals("MAL", item.apiName)
        assertEquals(TvType.Anime, item.type)
        assertEquals("https://example.com/l.jpg", item.posterUrl)
        assertEquals("The adventure is over, but life goes on for an elf mage.", item.plot)
        // Upstream MALApi catches DateTimeException when converting date-only string via Instant.from()
        assertNull(item.releaseDate)
    }

    @Test
    fun testResponseTokenSerialization() {
        val json = """
            {
                "token_type": "Bearer",
                "expires_in": 2678400,
                "access_token": "mal_test_access_token",
                "refresh_token": "mal_test_refresh_token"
            }
        """.trimIndent()

        val token = parseJson<MALApi.ResponseToken>(json)
        assertEquals("Bearer", token.tokenType)
        assertEquals(2678400, token.expiresIn)
        assertEquals("mal_test_access_token", token.accessToken)
        assertEquals("mal_test_refresh_token", token.refreshToken)
    }

    @Test
    fun testMalUserSerialization() {
        val json = """
            {
                "id": 123456,
                "name": "MalUserTest",
                "location": "Tokyo",
                "joined_at": "2020-01-01T00:00:00+00:00",
                "picture": "https://example.com/pic.jpg"
            }
        """.trimIndent()

        val user = parseJson<MALApi.MalUser>(json)
        assertEquals(123456, user.id)
        assertEquals("MalUserTest", user.name)
        assertEquals("Tokyo", user.location)
        assertEquals("https://example.com/pic.jpg", user.picture)
    }
}
