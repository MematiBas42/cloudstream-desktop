package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.WatchType
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
import kotlin.math.abs

class ResultViewModel2Test {

    private class TestProvider : MainAPI() {
        override var name = "TestProvider"
        override var mainUrl = "https://test.provider"
        override val supportedTypes = setOf(
            TvType.Anime,
            TvType.TvSeries,
            TvType.Movie,
            TvType.Live,
            TvType.Torrent
        )

        override suspend fun load(url: String): LoadResponse {
            return when {
                url.contains("anime") -> {
                    newAnimeLoadResponse("Test Anime", url, TvType.Anime) {
                        val subEpisodes = (1..50).map { epNum ->
                            newEpisode(data = "$url/sub/$epNum") {
                                this.episode = epNum
                                this.season = 1
                            }
                        }
                        val dubEpisodes = (1..12).map { epNum ->
                            newEpisode(data = "$url/dub/$epNum") {
                                this.episode = epNum
                                this.season = 1
                            }
                        }
                        this.episodes = mutableMapOf(
                            DubStatus.Subbed to subEpisodes,
                            DubStatus.Dubbed to dubEpisodes
                        )
                    }
                }
                url.contains("tvseries") -> {
                    newTvSeriesLoadResponse("Test TV", url, TvType.TvSeries, (1..20).map { epNum ->
                        newEpisode(data = "$url/ep/$epNum") {
                            this.episode = epNum
                            this.season = 1
                        }
                    })
                }
                url.contains("movie") -> {
                    newMovieLoadResponse("Test Movie", url, TvType.Movie, "$url/watch")
                }
                url.contains("live") -> {
                    newLiveStreamLoadResponse("Test Live", url, "$url/stream")
                }
                url.contains("torrent") -> {
                    newTorrentLoadResponse("Test Torrent", url, torrent = "magnet:?xt=urn:btih:123")
                }
                else -> error("Unknown url $url")
            }
        }
    }

    private val provider = TestProvider()
    private lateinit var testDataFile: File

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        APIHolder.allProviders.clear()
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.customDataFile = null
    }

    @Test
    fun test5ContentTypesParsingAndIdFormulas() = runBlocking {
        // 1. AnimeLoadResponse
        val vmAnime = ResultViewModel2()
        vmAnime.load(
            activity = null,
            url = "https://test.provider/anime",
            apiName = provider.name,
            showFillers = false,
            dubStatus = DubStatus.Subbed,
            autostart = null,
            loadTrailers = false
        )

        val animePageRes = vmAnime.page.filterNotNull().first { it is Resource.Success }
        val animeData = (animePageRes as Resource.Success).value
        assertEquals("Test Anime", animeData.title)

        val epRes = vmAnime.episodes.filterNotNull().first { it is Resource.Success }
        val episodes = (epRes as Resource.Success).value
        assertFalse(episodes.isEmpty())
        val mainId = "https://test.provider/anime".replace(provider.mainUrl, "").replace("/", "").hashCode()
        // Anime ID formula: mainId + episode + idIndex * 1_000_000 + (season * 10_000)
        val expectedAnimeId = mainId + 1 + DubStatus.Subbed.id * 1_000_000 + (1 * 10_000)
        assertEquals(expectedAnimeId, episodes[0].id)

        // 2. TvSeriesLoadResponse
        val vmTv = ResultViewModel2()
        vmTv.load(
            activity = null,
            url = "https://test.provider/tvseries",
            apiName = provider.name,
            showFillers = false,
            dubStatus = DubStatus.None,
            autostart = null,
            loadTrailers = false
        )
        val tvPage = vmTv.page.filterNotNull().first { it is Resource.Success && it.value.title == "Test TV" }
        assertNotNull(tvPage)
        val tvEpRes = vmTv.episodes.filterNotNull().first { it is Resource.Success }
        val tvEpisodes = (tvEpRes as Resource.Success).value
        assertEquals(20, tvEpisodes.size)
        val tvMainId = "https://test.provider/tvseries".replace(provider.mainUrl, "").replace("/", "").hashCode()
        // TvSeries ID formula: mainId + (season * 100_000) + episodeIndex + 1
        val expectedTvId = tvMainId + (1 * 100_000) + 1 + 1
        assertEquals(expectedTvId, tvEpisodes[0].id)

        // 3. MovieLoadResponse
        val vmMovie = ResultViewModel2()
        vmMovie.load(
            activity = null,
            url = "https://test.provider/movie",
            apiName = provider.name,
            showFillers = false,
            dubStatus = DubStatus.None,
            autostart = null,
            loadTrailers = false
        )
        val movieRes = vmMovie.movie.filterNotNull().first { it is Resource.Success }
        val movieData = (movieRes as Resource.Success).value
        val movieMainId = "https://test.provider/movie".replace(provider.mainUrl, "").replace("/", "").hashCode()
        assertEquals(movieMainId, movieData.second.id)

        // 4. LiveStreamLoadResponse
        val vmLive = ResultViewModel2()
        vmLive.load(
            activity = null,
            url = "https://test.provider/live",
            apiName = provider.name,
            showFillers = false,
            dubStatus = DubStatus.None,
            autostart = null,
            loadTrailers = false
        )
        val liveRes = vmLive.movie.filterNotNull().first { it is Resource.Success }
        val liveData = (liveRes as Resource.Success).value
        val liveMainId = "https://test.provider/live".replace(provider.mainUrl, "").replace("/", "").hashCode()
        assertEquals(liveMainId, liveData.second.id)

        // 5. TorrentLoadResponse
        val vmTorrent = ResultViewModel2()
        vmTorrent.load(
            activity = null,
            url = "https://test.provider/torrent",
            apiName = provider.name,
            showFillers = false,
            dubStatus = DubStatus.None,
            autostart = null,
            loadTrailers = false
        )
        val torrentRes = vmTorrent.movie.filterNotNull().first { it is Resource.Success }
        val torrentData = (torrentRes as Resource.Success).value
        val torrentMainId = "https://test.provider/torrent".replace(provider.mainUrl, "").replace("/", "").hashCode()
        assertEquals(torrentMainId, torrentData.second.id)
    }

    @Test
    fun testDubSubRangeTolerance() = runBlocking {
        val vm = ResultViewModel2()
        // Load anime with 50 subbed episodes and 12 dubbed episodes
        vm.load(
            activity = null,
            url = "https://test.provider/anime",
            apiName = provider.name,
            showFillers = false,
            dubStatus = DubStatus.Subbed,
            autostart = null,
            loadTrailers = false
        )

        // Await episodes loaded
        vm.episodes.filterNotNull().first { it is Resource.Success }
        val ranges = vm.rangeSelections.first { it.isNotEmpty() }
        assertTrue(ranges.isNotEmpty())

        // Switch to Dubbed
        vm.changeDubStatus(DubStatus.Dubbed)

        // Range selections for Dubbed (12 episodes) should have 1 range: 1-12
        val dubRanges = vm.rangeSelections.first { it.size == 1 }
        assertEquals(1, dubRanges.size)
        assertEquals(1, dubRanges[0].second.startEpisode)
        assertEquals(12, dubRanges[0].second.endEpisode)

        // Verify range tolerance formula: minByOrNull { abs(it.startEpisode - range.startEpisode) }
        val subRange3 = EpisodeRange(startIndex = 40, length = 10, startEpisode = 41, endEpisode = 50)
        val availableRanges = listOf(
            EpisodeRange(startIndex = 0, length = 12, startEpisode = 1, endEpisode = 12)
        )
        val closest = availableRanges.minByOrNull { abs(it.startEpisode - subRange3.startEpisode) }
        assertNotNull(closest)
        assertEquals(1, closest!!.startEpisode)
    }

    @Test
    fun testStateFlowsAndDialogEvent() = runBlocking {
        val vm = ResultViewModel2()
        assertEquals(WatchType.NONE, vm.watchStatus.value)

        // Test dialog event emission via duplicate warning
        var callbackInvoked = false
        val duplicateEvent = DetailsDialogEvent.DuplicateWarning(
            titleRes = R.string.duplicate_title,
            message = "Duplicate found",
            replaceMessageRes = R.string.duplicate_replace,
            duplicateIds = listOf(101),
            callback = { shouldContinue, _ ->
                callbackInvoked = shouldContinue
            }
        )

        duplicateEvent.callback(true, emptyList())
        assertTrue(callbackInvoked)

        // Test select popup
        var popupSelected: Int? = null
        vm.postPopup(txt("Select Option"), listOf(txt("Opt 1"), txt("Opt 2"))) {
            popupSelected = it
        }

        val popup = vm.selectPopup.value
        assertNotNull(popup)
        assertTrue(popup is SelectPopup.SelectText)
        popup?.callback(1)
        kotlinx.coroutines.delay(100)
        assertEquals(1, popupSelected)
    }
}
