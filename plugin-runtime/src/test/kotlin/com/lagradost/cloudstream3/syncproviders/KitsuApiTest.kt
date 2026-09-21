package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.Kitsu
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi.Companion.KitsuStatusType
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KitsuApiTest {

    private val api = KitsuApi()

    @Test
    fun testProperties() {
        assertEquals("Kitsu", api.name)
        assertEquals("kitsu", api.idPrefix)
        assertTrue(api.hasInApp)
        assertEquals("https://kitsu.app", api.mainUrl)
        assertEquals(SyncIdName.Kitsu, api.syncIdName)
        assertEquals("https://kitsu.app", api.createAccountUrl)

        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.WATCHING))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.COMPLETED))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.PLANTOWATCH))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.DROPPED))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.ONHOLD))
        assertTrue(api.supportedWatchTypes.contains(SyncWatchType.NONE))

        assertNotNull(api.inAppLoginRequirement)
        assertTrue(api.inAppLoginRequirement!!.password)
        assertTrue(api.inAppLoginRequirement!!.email)
    }

    @Test
    fun testUrlToId() {
        val url = "https://kitsu.app/anime/12345"
        val id = api.urlToId(url)
        assertEquals("/anime/12345", id)

        val urlWithSlug = "https://kitsu.app/anime/12345/frieren"
        assertEquals("/anime/12345/", api.urlToId(urlWithSlug))
    }

    @Test
    fun testStatusTypes() {
        assertEquals(0, KitsuStatusType.Watching.value)
        assertEquals(1, KitsuStatusType.Completed.value)
        assertEquals(2, KitsuStatusType.OnHold.value)
        assertEquals(3, KitsuStatusType.Dropped.value)
        assertEquals(4, KitsuStatusType.PlanToWatch.value)
        assertEquals(-1, KitsuStatusType.None.value)
        assertTrue(KitsuStatusType.entries.isNotEmpty())
    }

    @Test
    fun testKitsuNodeToLibraryItem() {
        val node = KitsuApi.KitsuNode(
            id = "entry_100",
            attributes = KitsuApi.KitsuNodeAttributes(
                titles = null,
                canonicalTitle = null,
                posterImage = null,
                synopsis = null,
                startDate = null,
                endDate = null,
                episodeCount = null,
                episodeLength = null,
                name = "Test User",
                location = "Earth",
                createdAt = "2023-01-01T00:00:00.000Z",
                avatar = null,
                progress = 12,
                ratingTwenty = 18,
                updatedAt = "2024-01-01T12:00:00.000Z",
                status = "current"
            ),
            relationships = null,
            anime = KitsuApi.KitsuAnimeData(
                id = "54321",
                attributes = KitsuApi.KitsuAnimeAttributes(
                    titles = KitsuApi.KitsuTitles(enJp = "Sousou no Frieren", jaJp = "葬送のフリーレン"),
                    canonicalTitle = "Frieren: Beyond Journey's End",
                    posterImage = KitsuApi.KitsuPosterImage(
                        large = "https://example.com/large.jpg",
                        medium = "https://example.com/med.jpg"
                    ),
                    synopsis = "Elf wizard journeys after defeating demon king.",
                    startDate = "2023-09-29",
                    endDate = "2024-03-22",
                    episodeCount = 28,
                    episodeLength = 24
                )
            )
        )

        val item = node.toLibraryItem()
        assertEquals("Frieren: Beyond Journey's End", item.name)
        assertEquals("https://kitsu.app/anime/54321/", item.url)
        assertEquals("entry_100", item.syncId)
        assertEquals(12, item.episodesCompleted)
        assertEquals(28, item.episodesTotal)
        assertEquals(Score.from(18, 20), item.personalRating)
        assertEquals("Kitsu", item.apiName)
        assertEquals(TvType.Anime, item.type)
        assertEquals("https://example.com/large.jpg", item.posterUrl)
        assertEquals("Elf wizard journeys after defeating demon king.", item.plot)
        assertNotNull(item.releaseDate)
    }

    @Test
    fun testResponseTokenSerialization() {
        val json = """
            {
                "token_type": "Bearer",
                "expires_in": 2592000,
                "access_token": "kitsu_test_access_token",
                "refresh_token": "kitsu_test_refresh_token"
            }
        """.trimIndent()

        val token = parseJson<KitsuApi.ResponseToken>(json)
        assertEquals("Bearer", token.tokenType)
        assertEquals(2592000, token.expiresIn)
        assertEquals("kitsu_test_access_token", token.accessToken)
        assertEquals("kitsu_test_refresh_token", token.refreshToken)
    }

    @Test
    fun testObjectKitsuDataModel() {
        assertTrue(Kitsu.isEnabled)

        val json = """
            {
                "data": {
                    "lookupMapping": {
                        "id": "anime_999",
                        "episodes": {
                            "nodes": [
                                {
                                    "number": 1,
                                    "titles": { "canonical": "The Journey's End" },
                                    "description": { "en": "Frieren continues her journey." },
                                    "thumbnail": { "original": { "url": "https://img.kitsu.io/ep1.jpg" } }
                                }
                            ]
                        }
                    }
                }
            }
        """.trimIndent()

        val response = parseJson<Kitsu.KitsuResponse>(json)
        assertNotNull(response.data)
        assertNotNull(response.data?.lookupMapping)
        assertEquals("anime_999", response.data?.lookupMapping?.id)
        val episodes = response.data?.lookupMapping?.episodes?.nodes
        assertNotNull(episodes)
        assertEquals(1, episodes?.size)
        assertEquals(1, episodes?.get(0)?.num)
        assertEquals("The Journey's End", episodes?.get(0)?.titles?.canonical)
        assertEquals("Frieren continues her journey.", episodes?.get(0)?.description?.en)
        assertEquals("https://img.kitsu.io/ep1.jpg", episodes?.get(0)?.thumbnail?.original?.url)
    }
}
