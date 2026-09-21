package com.lagradost.cloudstream3.utils

import android.content.Context
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.settings.DesktopAppSettings
import com.lagradost.cloudstream3.ui.settings.DesktopPreferenceStore
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.getShortSeasonText
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.isTv
import com.lagradost.cloudstream3.utils.AppContextUtils.isTvSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.AppContextUtils.splitQuery
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.nio.file.Path
import java.util.Locale

class AppContextUtilsTest {

    private lateinit var testDataFile: File
    private lateinit var context: Context
    private val originalLocale = Locale.getDefault()

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        context = Context()
    }

    @AfterEach
    fun tearDown() {
        Locale.setDefault(originalLocale)
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
    }

    @Test
    fun testSortSubs() {
        val sub1 = SubtitleData("English", " [HI]", "https://sub1", SubtitleOrigin.URL, "text/vtt", emptyMap(), "en")
        val sub2 = SubtitleData("English", "", "https://sub2", SubtitleOrigin.URL, "text/vtt", emptyMap(), "en")
        val sub3 = SubtitleData("Turkish", "", "https://sub3", SubtitleOrigin.URL, "text/vtt", emptyMap(), "tr")
        val sub4 = SubtitleData("Arabic", "", "https://sub4", SubtitleOrigin.URL, "text/vtt", emptyMap(), "ar")

        val sorted = sortSubs(setOf(sub1, sub2, sub3, sub4))
        assertEquals(4, sorted.size)
        assertEquals("Arabic", sorted[0].originalName)
        assertEquals("English", sorted[1].originalName)
        assertEquals("", sorted[1].nameSuffix) // sub2 has empty suffix -> comes before sub1
        assertEquals("English", sorted[2].originalName)
        assertEquals(" [HI]", sorted[2].nameSuffix)
        assertEquals("Turkish", sorted[3].originalName)
    }

    @Test
    fun testHtmlStripping() {
        val htmlText = "<p>This is a <b>bold</b> and <i>italic</i> test.</p>"
        val plain = htmlText.html().toString()
        assertEquals("This is a bold and italic test.", plain)

        val nullText: String? = null
        assertEquals("", nullText.html().toString())
    }

    @Test
    fun testGetNameFullEnglish() {
        Locale.setDefault(Locale.US)

        // Season and episode with name
        assertEquals("S1:E2 Episode Title", context.getNameFull("Episode Title", 2, 1))

        // Episode only with name
        assertEquals("Episode 5. Episode Title", context.getNameFull("Episode Title", 5, null))

        // Name only
        assertEquals("Movie Title", context.getNameFull("Movie Title", null, null))

        // Season and episode without name
        assertEquals("Season 2 - Episode 4", context.getNameFull(null, 4, 2))

        // Episode only without name
        assertEquals("Episode 3", context.getNameFull(null, 3, null))
    }

    @Test
    fun testGetNameFullTurkish() {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        // Season and episode with name in Turkish
        assertEquals("S1:B2 Bolum Basligi", context.getNameFull("Bolum Basligi", 2, 1))

        // Episode only with name in Turkish
        assertEquals("Bölüm 5. Bolum Basligi", context.getNameFull("Bolum Basligi", 5, null))

        // Season and episode without name in Turkish
        assertEquals("Sezon 2 - Bölüm 4", context.getNameFull(null, 4, 2))

        // Episode only without name in Turkish
        assertEquals("Bölüm 3", context.getNameFull(null, 3, null))
    }

    @Test
    fun testGetShortSeasonText() {
        Locale.setDefault(Locale.US)
        assertEquals("S1:E2", context.getShortSeasonText(2, 1))
        assertEquals("E5", context.getShortSeasonText(5, null))
        assertNull(context.getShortSeasonText(null, null))

        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        assertEquals("S1:B2", context.getShortSeasonText(2, 1))
        assertEquals("B5", context.getShortSeasonText(5, null))
    }

    @Test
    fun testSplitQuery() {
        val url = URL("https://example.com/search?q=test&lang=en&page=2")
        val params = splitQuery(url)
        assertEquals("test", params["q"])
        assertEquals("en", params["lang"])
        assertEquals("2", params["page"])
    }

    @Test
    fun testGetApiDubstatusSettings() {
        val dubs = context.getApiDubstatusSettings()
        assertTrue(dubs.contains(DubStatus.Subbed))
        assertTrue(dubs.contains(DubStatus.Dubbed))
    }

    @Test
    fun testFilterQuality() {
        val dummyApi = object : MainAPI() {
            override var name = "TestAPI"
            override var mainUrl = "https://test.com"
        }

        val res1 = dummyApi.newMovieSearchResponse("Movie 4K", "https://movie1", TvType.Movie) {
            this.quality = SearchQuality.FourK
        }
        val res2 = dummyApi.newMovieSearchResponse("Movie Cam", "https://movie2", TvType.Movie) {
            this.quality = SearchQuality.Cam
        }

        val list = listOf<SearchResponse>(res1, res2)

        // Without filter quality: both pass
        val unfiltered = context.filterSearchResultByFilmQuality(list)
        assertEquals(2, unfiltered.size)

        // Now set filter quality to exclude Cam
        val appSettings = DesktopAppSettings(DesktopPreferenceStore())
        appSettings.ui.filterQuality.set(setOf(SearchQuality.Cam))

        val filtered = context.filterSearchResultByFilmQuality(list)
        assertEquals(1, filtered.size)
        assertEquals("Movie 4K", filtered[0].name)

        // Test HomePageList filter
        val homeList = HomePageList(
            name = "Trending",
            list = list,
            isHorizontalImages = false
        )
        val filteredHome = context.filterHomePageListByFilmQuality(homeList)
        assertEquals(1, filteredHome.list.size)
        assertEquals("Movie 4K", filteredHome.list[0].name)
    }

    @Test
    fun testIsTvAndIsTvSettings() {
        // By default desktop layout is not TV
        assertFalse(context.isTv)
        assertFalse(context.isTvSettings())
    }
}
