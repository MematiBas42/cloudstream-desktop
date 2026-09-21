package com.lagradost.cloudstream3.subtitles

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI
import com.lagradost.cloudstream3.syncproviders.SubtitleRepo
import com.lagradost.cloudstream3.syncproviders.providers.Addic7ed
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubSourceApi
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.utils.SubtitleUtils
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SubtitleEngineTest {

    @Test
    fun `test SubtitleEntities defaults and properties`() {
        val entity = SubtitleEntity(
            idPrefix = "test_prefix",
            name = "Test Movie",
            data = "https://example.com/sub.srt",
            source = "Test Source"
        )

        assertEquals("test_prefix", entity.idPrefix)
        assertEquals("Test Movie", entity.name)
        assertEquals("en", entity.lang)
        assertEquals("https://example.com/sub.srt", entity.data)
        assertEquals(TvType.Movie, entity.type)
        assertEquals("Test Source", entity.source)
        assertEquals(null, entity.epNumber)
        assertEquals(null, entity.seasonNumber)
        assertEquals(null, entity.year)
        assertFalse(entity.isHearingImpaired)
        assertTrue(entity.headers.isEmpty())

        val search = SubtitleSearch(
            query = "Interstellar",
            lang = "en",
            imdbId = "tt0816692",
            year = 2014
        )
        assertEquals("Interstellar", search.query)
        assertEquals("en", search.lang)
        assertEquals("tt0816692", search.imdbId)
        assertEquals(2014, search.year)
    }

    @Test
    fun `test SubtitleResource addUrl and addFile`() {
        val resource = SubtitleResource()
        resource.addUrl("https://example.com/en.srt", "English")
        resource.addUrl(null, "Null url should be ignored")

        val tempFile = File.createTempFile("sub_test", ".srt")
        tempFile.writeText("1\n00:00:01,000 --> 00:00:04,000\nHello World\n")
        resource.addFile(tempFile, "Local English")

        val subs = resource.getSubtitles()
        assertEquals(2, subs.size)

        assertEquals("English", subs[0].name)
        assertEquals("https://example.com/en.srt", subs[0].url)
        assertEquals(SubtitleOrigin.URL, subs[0].origin)

        assertEquals("Local English", subs[1].name)
        assertTrue(subs[1].url.startsWith("file://"))
        assertEquals(SubtitleOrigin.DOWNLOADED_FILE, subs[1].origin)

        tempFile.delete()
    }

    @Test
    fun `test SubtitleUtils matching and clean display name`() {
        assertEquals("Movie.2024.1080p", SubtitleUtils.cleanDisplayName("Movie.2024.1080p.mkv"))
        assertEquals("Movie.2024.1080p", SubtitleUtils.cleanDisplayName("Movie.2024.1080p.srt"))

        val display = "Inception.2010.1080p.mkv"
        val cleanDisplay = SubtitleUtils.cleanDisplayName(display)

        // Matching subtitles
        assertTrue(SubtitleUtils.isMatchingSubtitle("Inception.2010.1080p.en.srt", display, cleanDisplay))
        assertTrue(SubtitleUtils.isMatchingSubtitle("Inception.2010.1080p.tr.vtt", display, cleanDisplay))
        assertTrue(SubtitleUtils.isMatchingSubtitle("Inception.2010.1080p.ass", display, cleanDisplay))

        // Same file is not subtitle
        assertFalse(SubtitleUtils.isMatchingSubtitle("Inception.2010.1080p.mkv", display, cleanDisplay))

        // Unrelated file
        assertFalse(SubtitleUtils.isMatchingSubtitle("Interstellar.2014.1080p.srt", display, cleanDisplay))

        // Invalid extension
        assertFalse(SubtitleUtils.isMatchingSubtitle("Inception.2010.1080p.mp4", display, cleanDisplay))
    }

    @Test
    fun `test SubtitleRepo caching mechanisms`() = runBlocking {
        var searchCount = 0
        var resourceCount = 0

        val dummyApi = object : SubtitleAPI() {
            override val name = "DummySub"
            override val idPrefix = "dummy"

            override suspend fun search(auth: AuthData?, query: SubtitleSearch): List<SubtitleEntity> {
                searchCount++
                return listOf(
                    SubtitleEntity(
                        idPrefix = idPrefix,
                        name = "Dummy Subtitle for ${query.query}",
                        data = "https://example.com/dummy.srt",
                        source = name
                    )
                )
            }

            override suspend fun load(auth: AuthData?, subtitle: SubtitleEntity): String {
                resourceCount++
                return "https://example.com/loaded/${subtitle.name}.srt"
            }
        }

        val repo = SubtitleRepo(dummyApi)
        val query = SubtitleSearch(query = "Matrix", lang = "en")

        // First search calls API
        val results1 = repo.search(query).getOrThrow()
        assertEquals(1, results1.size)
        assertEquals(1, searchCount)

        // Second search hits cache
        val results2 = repo.search(query).getOrThrow()
        assertEquals(1, results2.size)
        assertEquals(1, searchCount)

        // First resource call
        val entity = results1.first()
        val res1 = repo.resource(entity).getOrThrow()
        assertEquals(1, res1.getSubtitles().size)
        assertEquals(1, resourceCount)

        // Second resource call hits cache
        val res2 = repo.resource(entity).getOrThrow()
        assertEquals(1, res2.getSubtitles().size)
        assertEquals(1, resourceCount)
    }

    @Test
    fun `test Subtitle Providers configuration in AccountManager`() {
        assertEquals(4, AccountManager.subtitleProviders.size)

        val providers = AccountManager.subtitleProviders.map { it.api }
        assertTrue(providers.any { it is OpenSubtitlesApi })
        assertTrue(providers.any { it is Addic7ed })
        assertTrue(providers.any { it is SubDlApi })
        assertTrue(providers.any { it is SubSourceApi })

        // Check OpenSubtitlesApi contracts
        val openSubs = AccountManager.openSubtitlesApi
        assertEquals("OpenSubtitles", openSubs.name)
        assertEquals("opensubtitles", openSubs.idPrefix)
        assertTrue(openSubs.hasInApp)
        assertEquals(true, openSubs.inAppLoginRequirement?.username)
        assertEquals(true, openSubs.inAppLoginRequirement?.password)
        assertEquals("https://www.opensubtitles.com/en/users/sign_up", openSubs.createAccountUrl)

        // Check SubDlApi contracts
        val subDl = AccountManager.subDlApi
        assertEquals("SubDL", subDl.name)
        assertEquals("subdl", subDl.idPrefix)
        assertTrue(subDl.hasInApp)
        assertTrue(subDl.requiresLogin)
        assertEquals(true, subDl.inAppLoginRequirement?.password)
        assertEquals(true, subDl.inAppLoginRequirement?.email)
        assertEquals("https://subdl.com/panel/register", subDl.createAccountUrl)

        // Check SubSourceApi contracts
        val subSource = AccountManager.subSourceApi
        assertEquals("SubSource", subSource.name)
        assertEquals("subsource", subSource.idPrefix)
        assertFalse(subSource.requiresLogin)

        // Check Addic7ed contracts
        val addic7ed = AccountManager.addic7ed
        assertEquals("Addic7ed", addic7ed.name)
        assertEquals("addic7ed", addic7ed.idPrefix)
        assertFalse(addic7ed.requiresLogin)
    }

    @Test
    fun `test AuthAPI toRepo conversion for SubtitleAPI`() {
        val openSubs = OpenSubtitlesApi()
        @Suppress("DEPRECATION_ERROR")
        val repo = openSubs.toRepo()
        assertTrue(repo is SubtitleRepo)
        assertEquals("opensubtitles", (repo as SubtitleRepo).idPrefix)
    }
}
