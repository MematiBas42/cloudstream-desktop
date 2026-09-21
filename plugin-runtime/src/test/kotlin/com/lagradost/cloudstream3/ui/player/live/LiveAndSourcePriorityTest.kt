package com.lagradost.cloudstream3.ui.player.live

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.lagradost.cloudstream3.ui.player.source_priority.LinkSource
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.QualityProfileType
import com.lagradost.cloudstream3.ui.player.source_priority.QualityProfileDialog
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

class LiveAndSourcePriorityTest {

    // --- Mock Player Implementation for testing ---
    private class TestMockPlayer(
        override var isCurrentMediaItemDynamic: Boolean = false,
        override var duration: Long = C.TIME_UNSET,
        override var currentLiveOffset: Long = C.TIME_UNSET,
        override var currentPosition: Long = 0L,
        override var currentMediaItemIndex: Int = 0
    ) : Player {
        val listeners = mutableListOf<Player.Listener>()
        val seekOperations = mutableListOf<Long>()

        override fun seekTo(positionMs: Long) {
            seekOperations.add(positionMs)
            currentPosition = positionMs
        }

        override fun addListener(listener: Player.Listener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners.remove(listener)
        }
    }

    // =========================================================================
    // 1. LivestreamChunk & LiveManager Math Tests
    // =========================================================================

    @Test
    fun `test LivestreamChunk targetPosition calculation`() {
        // duration 20s (20000ms):
        // durationMs - PREFERRED_LIVE_OFFSET = 20000 - 5000 = 15000
        // durationMs / 2 - CHUNK_VARIANCE = 10000 - 3000 = 7000
        // minOf(15000, 7000) = 7000
        val chunkLong = LivestreamChunk(20_000L)
        assertEquals(7_000L, chunkLong.targetPosition)

        // duration 4s (4000ms):
        // durationMs - PREFERRED_LIVE_OFFSET = 4000 - 5000 = -1000
        // durationMs / 2 - CHUNK_VARIANCE = 2000 - 3000 = -1000
        // maxOf(0, -1000) = 0
        val chunkShort = LivestreamChunk(4_000L)
        assertEquals(0L, chunkShort.targetPosition)
    }

    @Test
    fun `test LivestreamChunk isPositionLive and getTimeAheadOfLive`() {
        val receiveTime = System.currentTimeMillis()
        val chunk = LivestreamChunk(20_000L, receiveTime) // targetPosition = 7000

        // At exact live position: position = 7000
        assertTrue(chunk.isPositionLive(7_000L))
        assertEquals(0L, chunk.getTimeAheadOfLive(7_000L))

        // Position 2000ms ahead of live
        assertEquals(2_000L, chunk.getTimeAheadOfLive(9_000L))

        // Position 20 seconds behind live: should not be considered live
        assertFalse(chunk.isPositionLive(7_000L - 20_000L))
    }

    @Test
    fun `test LiveManager with dynamic and non-dynamic streams`() {
        val player = TestMockPlayer()
        val liveManager = LiveManager(player)

        // Non-dynamic stream: should always return 0 and false
        player.isCurrentMediaItemDynamic = false
        player.duration = 60_000L
        assertEquals(0L, liveManager.getTimeAheadOfLive(10_000L))
        assertFalse(liveManager.isAtLiveEdge())

        // Dynamic stream with known currentLiveOffset
        player.isCurrentMediaItemDynamic = true
        player.duration = 100_000L
        player.currentLiveOffset = 4_000L // within LIVE_MARGIN + PREFERRED_LIVE_OFFSET (11000)
        assertTrue(liveManager.isAtLiveEdge())

        player.currentLiveOffset = 15_000L // > 11000
        assertFalse(liveManager.isAtLiveEdge())

        // Dynamic stream with sliding chunk fallback
        player.currentLiveOffset = C.TIME_UNSET
        val chunk = LivestreamChunk(20_000L)
        liveManager.submitLivestreamChunk(chunk)
        player.currentPosition = chunk.targetPosition
        assertTrue(liveManager.isAtLiveEdge())
    }

    // =========================================================================
    // 2. LiveHelper Event & StateFlow Tests
    // =========================================================================

    @Test
    fun `test LiveHelper registers player and drives StateFlow`() {
        val player = TestMockPlayer(
            isCurrentMediaItemDynamic = true,
            duration = 100_000L,
            currentLiveOffset = C.TIME_UNSET
        )

        LiveHelper.registerPlayer(player)
        val manager = LiveHelper.getLiveManager(player)
        assertNotNull(manager)
        assertEquals(1, player.listeners.size)

        val listener = player.listeners[0]

        // Submit a timeline update with dynamic livestream window (duration 20s)
        val timeline = object : Timeline() {
            override fun getWindow(mediaItemIndex: Int, window: Window): Window {
                window.isDynamic = true
                window.durationMs = 20_000L
                return window
            }
        }
        listener.onTimelineChanged(timeline, 0)

        // Position discontinuity ahead of live: targetPosition is 7000ms, jump to 25000ms
        val oldPos = Player.PositionInfo(positionMs = 7_000L)
        val newPos = Player.PositionInfo(positionMs = 25_000L)

        listener.onPositionDiscontinuity(oldPos, newPos, 0)

        // Check seek back occurred
        assertTrue(player.seekOperations.isNotEmpty(), "Must seek back when ahead of live by > 100ms")

        // Verify StateFlow values
        assertTrue(LiveHelper.timeAheadOfLive.value > 100L)

        // Check unregister
        LiveHelper.unregisterPlayer(player)
        assertEquals(0, player.listeners.size)
    }

    // =========================================================================
    // 3. QualityDataHelper insertType & Download Profile Restoration Tests
    // =========================================================================

    @Test
    fun `test QualityDataHelper getProfiles restores unique profiles on fresh state`() {
        val profiles = QualityDataHelper.getProfiles()

        // 1. Must return PROFILE_COUNT (7) profiles
        assertEquals(7, profiles.size)

        // 2. Every unique type (WiFi, Data, Download) must be present in the profiles
        val allTypes = profiles.flatMap { it.types }.toSet()
        assertTrue(allTypes.contains(QualityProfileType.WiFi), "WiFi profile must exist")
        assertTrue(allTypes.contains(QualityProfileType.Data), "Data profile must exist")
        assertTrue(allTypes.contains(QualityProfileType.Download), "Download profile must exist")

        // 3. Verify DownloadManager's exact lookup does NOT throw NoSuchElementException!
        val downloadProfile = profiles.first {
            it.types.contains(QualityProfileType.Download)
        }
        assertNotNull(downloadProfile)
        assertEquals(1, downloadProfile.id) // Inserted on earliest profile (id=1)
    }

    @Test
    fun `test QualityDataHelper getLinkPriority calculation`() = runBlocking {
        val profileId = 1
        val link: ExtractorLink = newExtractorLink(
            source = "TestMirror",
            name = "Test 1080p",
            url = "https://example.com/stream.m3u8",
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.P1080.value
        }

        val priority = QualityDataHelper.getLinkPriority(profileId, link)
        // Default quality priority for 1080p is Qualities.P1080.defaultPriority
        // Default source priority is 1
        assertEquals(Qualities.P1080.defaultPriority + 1, priority)
    }

    @Test
    fun `test LinkSource data model and QualityProfileDialog companion`() = runBlocking {
        val link: ExtractorLink = newExtractorLink(
            source = "SuperHost",
            name = "720p",
            url = "https://example.com/720.mp4",
            type = ExtractorLinkType.VIDEO
        )

        val linkSource = LinkSource(link)
        assertEquals("SuperHost", linkSource.source)

        // getAllDefaultSources should run without throwing
        val sources = QualityProfileDialog.getAllDefaultSources()
        assertNotNull(sources)
    }
}
