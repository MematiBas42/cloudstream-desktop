package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AniListApiTest {

    private val api = AniListApi()

    @Test
    fun testUrlToIdAndIdToUrl() {
        val url = "https://anilist.co/anime/16498/"
        val id = api.urlToId(url)
        assertEquals("16498", id)

        val urlWithoutTrailingSlash = "https://anilist.co/anime/21"
        assertEquals("21", api.urlToId(urlWithoutTrailingSlash))
    }

    @Test
    fun testLoginRequest() {
        val loginPage = api.loginRequest()
        assertNotNull(loginPage)
        assertTrue(loginPage!!.url.startsWith("https://anilist.co/api/v2/oauth/authorize"))
        assertTrue(loginPage.url.contains("response_type=token"))
        assertTrue(loginPage.url.contains("client_id="))
    }

    @Test
    fun testLoginParsing() = kotlinx.coroutines.runBlocking {
        val redirectUrl = "cloudstreamapp://anilistlogin/#access_token=test_token_12345&token_type=Bearer&expires_in=31536000"
        val token = api.login(redirectUrl, null)
        assertNotNull(token)
        assertEquals("test_token_12345", token!!.accessToken)
        assertTrue((token.accessTokenLifetime ?: 0) > 0)
    }

    @Test
    fun testStatusConversions() {
        assertEquals(AniListApi.Companion.AniListStatusType.Watching, AniListApi.fromIntToAnimeStatus(0))
        assertEquals(AniListApi.Companion.AniListStatusType.Completed, AniListApi.fromIntToAnimeStatus(1))
        assertEquals(AniListApi.Companion.AniListStatusType.Paused, AniListApi.fromIntToAnimeStatus(2))
        assertEquals(AniListApi.Companion.AniListStatusType.Dropped, AniListApi.fromIntToAnimeStatus(3))
        assertEquals(AniListApi.Companion.AniListStatusType.Planning, AniListApi.fromIntToAnimeStatus(4))
        assertEquals(AniListApi.Companion.AniListStatusType.ReWatching, AniListApi.fromIntToAnimeStatus(5))
        assertEquals(AniListApi.Companion.AniListStatusType.None, AniListApi.fromIntToAnimeStatus(-1))

        assertEquals(AniListApi.Companion.AniListStatusType.Watching, AniListApi.convertAniListStringToStatus("CURRENT"))
        assertEquals(AniListApi.Companion.AniListStatusType.Completed, AniListApi.convertAniListStringToStatus("COMPLETED"))
        assertEquals(AniListApi.Companion.AniListStatusType.Paused, AniListApi.convertAniListStringToStatus("PAUSED"))
        assertEquals(AniListApi.Companion.AniListStatusType.Dropped, AniListApi.convertAniListStringToStatus("DROPPED"))
        assertEquals(AniListApi.Companion.AniListStatusType.Planning, AniListApi.convertAniListStringToStatus("PLANNING"))
        assertEquals(AniListApi.Companion.AniListStatusType.ReWatching, AniListApi.convertAniListStringToStatus("REPEATING"))
    }

    @Test
    fun testEntriesToLibraryItem() {
        val entry = AniListApi.Entries(
            status = "CURRENT",
            completedAt = AniListApi.CompletedAt(0, 0, 0),
            startedAt = AniListApi.StartedAt("2024", "1", "1"),
            updatedAt = 1700000000,
            progress = 12,
            score = 85,
            private = false,
            media = AniListApi.Media(
                id = 16498,
                idMal = 16498,
                season = "FALL",
                seasonYear = 2023,
                format = "TV",
                episodes = 24,
                title = AniListApi.Title(
                    english = "Attack on Titan Final Season",
                    romaji = "Shingeki no Kyojin"
                ),
                description = "Titans attack humanity.",
                coverImage = AniListApi.CoverImage(
                    medium = "https://example.com/m.jpg",
                    large = "https://example.com/l.jpg",
                    extraLarge = "https://example.com/xl.jpg"
                ),
                synonyms = listOf("AOT"),
                nextAiringEpisode = null
            )
        )

        val item = entry.toLibraryItem()
        assertEquals("Attack on Titan Final Season", item.name)
        assertEquals("16498", item.syncId)
        assertEquals("https://anilist.co/anime/16498/", item.url)
        assertEquals(12, item.episodesCompleted)
        assertEquals(24, item.episodesTotal)
        assertEquals(TvType.Anime, item.type)
        assertEquals("https://example.com/xl.jpg", item.posterUrl)
        assertEquals(Score.from100(85), item.personalRating)
        assertEquals("Titans attack humanity.", item.plot)
    }

    @Test
    fun testGetDataRootSerialization() {
        val json = """
            {
                "data": {
                    "Media": {
                        "id": 16498,
                        "episodes": 24,
                        "isFavourite": true,
                        "mediaListEntry": {
                            "progress": 10,
                            "status": "CURRENT",
                            "score": 80
                        },
                        "title": {
                            "english": "Attack on Titan",
                            "romaji": "Shingeki no Kyojin"
                        }
                    }
                }
            }
        """.trimIndent()

        val parsed = parseJson<AniListApi.GetDataRoot>(json)
        assertNotNull(parsed.data?.media)
        val media = parsed.data!!.media!!
        assertEquals(24, media.episodes)
        assertTrue(media.isFavourite == true)
        assertEquals(10, media.mediaListEntry?.progress)
        assertEquals("CURRENT", media.mediaListEntry?.status)
        assertEquals(80, media.mediaListEntry?.score)
    }
}
