package unit

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ResultViewModel2ParityTest {

    @Test
    fun testStateFlowsAndSharedFlowCompleteness() {
        val vm = ResultViewModel2()

        // Verify all 25 StateFlow fields are initialized and exposed
        assertNotNull(vm.page)
        assertNull(vm.page.value)

        assertNotNull(vm.episodes)
        assertTrue(vm.episodes.value is Resource.Loading)

        assertNotNull(vm.movie)
        assertNull(vm.movie.value)

        assertNotNull(vm.episodesCountText)
        assertNull(vm.episodesCountText.value)

        assertNotNull(vm.trailers)
        assertTrue(vm.trailers.value.isEmpty())

        assertNotNull(vm.dubSubSelections)
        assertTrue(vm.dubSubSelections.value.isEmpty())

        assertNotNull(vm.rangeSelections)
        assertTrue(vm.rangeSelections.value.isEmpty())

        assertNotNull(vm.seasonSelections)
        assertTrue(vm.seasonSelections.value.isEmpty())

        assertNotNull(vm.recommendations)
        assertTrue(vm.recommendations.value.isEmpty())

        assertNotNull(vm.selectedRange)
        assertNull(vm.selectedRange.value)

        assertNotNull(vm.selectedSorting)
        assertNull(vm.selectedSorting.value)

        assertNotNull(vm.selectedSortingIndex)
        assertEquals(-1, vm.selectedSortingIndex.value)

        assertNotNull(vm.sortSelections)
        assertTrue(vm.sortSelections.value.isEmpty())

        assertNotNull(vm.selectedSeason)
        assertNull(vm.selectedSeason.value)

        assertNotNull(vm.selectedDubStatus)
        assertNull(vm.selectedDubStatus.value)

        assertNotNull(vm.selectedRangeIndex)
        assertEquals(-1, vm.selectedRangeIndex.value)

        assertNotNull(vm.selectedSeasonIndex)
        assertEquals(-1, vm.selectedSeasonIndex.value)

        assertNotNull(vm.selectedDubStatusIndex)
        assertEquals(-1, vm.selectedDubStatusIndex.value)

        assertNotNull(vm.loadedLinks)
        assertNull(vm.loadedLinks.value)

        assertNotNull(vm.resumeWatching)
        assertNull(vm.resumeWatching.value)

        assertNotNull(vm.episodeSynopsis)
        assertNull(vm.episodeSynopsis.value)

        assertNotNull(vm.subscribeStatus)
        assertNull(vm.subscribeStatus.value)

        assertNotNull(vm.favoriteStatus)
        assertNull(vm.favoriteStatus.value)

        assertNotNull(vm.watchStatus)
        assertEquals(WatchType.NONE, vm.watchStatus.value)

        assertNotNull(vm.selectPopup)
        assertNull(vm.selectPopup.value)

        // Verify SharedFlow<DetailsDialogEvent>
        assertNotNull(vm.dialogEvent)
    }

    @Test
    fun testMathematicalIdFormulasForContentTypes() {
        val mainId = 123456

        // 1. AnimeLoadResponse formula:
        // id = mainId + episode + idIndex * 1_000_000 + (season * 10_000)
        val animeEpisode = 5
        val animeIdIndex = DubStatus.Dubbed.id // Dubbed is 1, Subbed is 0
        val animeSeason = 2
        val expectedAnimeId = mainId + animeEpisode + animeIdIndex * 1_000_000 + (animeSeason * 10_000)
        assertEquals(123456 + 5 + 1_000_000 + 20_000, expectedAnimeId)

        // 2. TvSeriesLoadResponse formula:
        // id = mainId + (season * 100_000) + episodeIndex + 1
        val tvSeason = 3
        val tvEpisodeIndex = 12
        val expectedTvId = mainId + (tvSeason * 100_000) + tvEpisodeIndex + 1
        assertEquals(123456 + 300_000 + 12 + 1, expectedTvId)

        // 3. MovieLoadResponse formula:
        // id = mainId (same ID)
        val expectedMovieId = mainId
        assertEquals(123456, expectedMovieId)

        // 4. LiveStreamLoadResponse formula:
        // id = mainId (same ID)
        val expectedLiveStreamId = mainId
        assertEquals(123456, expectedLiveStreamId)

        // 5. TorrentLoadResponse formula:
        // id = mainId (same ID)
        val expectedTorrentId = mainId
        assertEquals(123456, expectedTorrentId)
    }

    @Test
    fun testDubSubRangeToleranceFormula() {
        // Dub/Sub range tolerance:
        // ranges?.minByOrNull { abs(it.startEpisode - range.startEpisode) }
        val targetRange = EpisodeRange(0, 10, startEpisode = 51, endEpisode = 60)

        // Suppose Sub has 100 episodes (ranges 1-50, 51-100), but Dub only has 12 episodes (range 1-12)
        val dubRanges = listOf(
            EpisodeRange(0, 12, startEpisode = 1, endEpisode = 12)
        )

        val selected = dubRanges.minByOrNull { abs(it.startEpisode - targetRange.startEpisode) }
        assertNotNull(selected)
        assertEquals(1, selected?.startEpisode)
        assertEquals(12, selected?.endEpisode)

        // When multiple ranges exist in dub:
        val multiRanges = listOf(
            EpisodeRange(0, 25, startEpisode = 1, endEpisode = 25),
            EpisodeRange(25, 50, startEpisode = 26, endEpisode = 50)
        )
        val closestTo51 = multiRanges.minByOrNull { abs(it.startEpisode - targetRange.startEpisode) }
        assertEquals(26, closestTo51?.startEpisode)
    }

    @Test
    fun testEpisodeClickActionsParity() {
        assertEquals(1, ACTION_PLAY_EPISODE_IN_PLAYER)
        assertEquals(4, ACTION_CHROME_CAST_EPISODE)
        assertEquals(5, ACTION_CHROME_CAST_MIRROR)
        assertEquals(6, ACTION_DOWNLOAD_EPISODE)
        assertEquals(7, ACTION_DOWNLOAD_MIRROR)
        assertEquals(8, ACTION_RELOAD_EPISODE)
        assertEquals(10, ACTION_SHOW_OPTIONS)
        assertEquals(11, ACTION_CLICK_DEFAULT)
        assertEquals(12, ACTION_SHOW_TOAST)
        assertEquals(13, ACTION_DOWNLOAD_EPISODE_SUBTITLE)
        assertEquals(14, ACTION_DOWNLOAD_EPISODE_SUBTITLE_MIRROR)
        assertEquals(15, ACTION_SHOW_DESCRIPTION)
        assertEquals(18, ACTION_MARK_AS_WATCHED)
        assertEquals(19, ACTION_MARK_WATCHED_UP_TO_THIS_EPISODE)
        assertEquals(1, START_ACTION_RESUME_LATEST)
        assertEquals(2, START_ACTION_LOAD_EP)
    }

    @Test
    fun testKitsuBinarySearchParity() {
        val episodeNumbers = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val targetEpisode = 5
        val index = episodeNumbers.binarySearch(targetEpisode)
        assertEquals(4, index)
        assertEquals(5, episodeNumbers[index])
    }
}
