package unit

import com.lagradost.player.osd.StreamFormatBadges
import com.lagradost.player.osd.StreamMetadata
import com.lagradost.player.osd.calculateProjectedFinishTime
import com.lagradost.player.osd.formatStreamBadge
import com.lagradost.player.skip.AniSkipClient
import com.lagradost.player.skip.SkipInterval
import com.lagradost.player.skip.SkipType
import com.lagradost.player.skip.VideoSkipManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.time.ZoneId

/**
 * Anti-mock unit tests for VideoSkipManager, StreamFormatBadges, and chronometry helpers.
 * Exercises real domain logic, interval boundary conditions, auto-skip triggers, and JSON parsing.
 */
class VideoSkipManagerTest {

    @Test
    fun testIntervalCheckingWithMultipleIntervals() {
        val manager = VideoSkipManager()
        val intervals = listOf(
            SkipInterval(startMs = 10_000L, endMs = 30_000L, type = SkipType.Recap),
            SkipInterval(startMs = 60_000L, endMs = 150_000L, type = SkipType.Intro),
            SkipInterval(startMs = 500_000L, endMs = 560_000L, type = SkipType.Mixed),
            SkipInterval(startMs = 1_400_000L, endMs = 1_490_000L, type = SkipType.Outro)
        )
        manager.loadIntervals(intervals)

        // Before any interval
        assertNull(manager.checkSkip(0L))
        assertNull(manager.checkSkip(9_999L))

        // Inside Recap interval
        val recap = manager.checkSkip(20_000L)
        assertNotNull(recap)
        assertEquals(SkipType.Recap, recap!!.type)
        assertEquals(10_000L, recap.startMs)
        assertEquals(30_000L, recap.endMs)

        // Between Recap and Intro
        assertNull(manager.checkSkip(30_000L))
        assertNull(manager.checkSkip(45_000L))

        // Inside Intro interval
        val intro = manager.checkSkip(100_000L)
        assertNotNull(intro)
        assertEquals(SkipType.Intro, intro!!.type)
        assertEquals(60_000L, intro.startMs)
        assertEquals(150_000L, intro.endMs)

        // Inside Mixed interval
        val mixed = manager.checkSkip(520_000L)
        assertNotNull(mixed)
        assertEquals(SkipType.Mixed, mixed!!.type)

        // Inside Outro interval
        val outro = manager.checkSkip(1_450_000L)
        assertNotNull(outro)
        assertEquals(SkipType.Outro, outro!!.type)

        // After all intervals
        assertNull(manager.checkSkip(1_490_000L))
        assertNull(manager.checkSkip(2_000_000L))
    }

    @Test
    fun testEdgeCaseBoundariesHalfOpenInterval() {
        val manager = VideoSkipManager()
        val startMs = 60_000L
        val endMs = 150_000L
        manager.loadIntervals(listOf(SkipInterval(startMs = startMs, endMs = endMs, type = SkipType.Intro)))

        // Boundary: startMs - 1 (must be outside [startMs, endMs))
        assertNull(manager.checkSkip(startMs - 1L), "Position at startMs - 1 must return null")

        // Boundary: startMs == currentPositionMs (must be inside [startMs, endMs))
        val atStart = manager.checkSkip(startMs)
        assertNotNull(atStart, "Position at startMs must be recognized as active skip interval")
        assertEquals(SkipType.Intro, atStart!!.type)
        assertEquals(startMs, atStart.startMs)
        assertEquals(endMs, atStart.endMs)

        // Boundary: currentPositionMs == endMs - 1 (must be inside [startMs, endMs))
        val atEndMinusOne = manager.checkSkip(endMs - 1L)
        assertNotNull(atEndMinusOne, "Position at endMs - 1 must be recognized as active skip interval")
        assertEquals(SkipType.Intro, atEndMinusOne!!.type)

        // Boundary: currentPositionMs == endMs (must be outside [startMs, endMs))
        assertNull(manager.checkSkip(endMs), "Position at endMs must return null (half-open interval)")

        // Boundary: endMs + 1 (must be outside [startMs, endMs))
        assertNull(manager.checkSkip(endMs + 1L), "Position at endMs + 1 must return null")
    }

    @Test
    fun testAutoSkipTriggerBehavior() {
        val manager = VideoSkipManager()
        val interval = SkipInterval(startMs = 50_000L, endMs = 140_000L, type = SkipType.Intro)
        manager.loadIntervals(listOf(interval))

        var autoSkipCallbackCount = 0
        var callbackReceivedInterval: SkipInterval? = null
        manager.onAutoSkip = {
            autoSkipCallbackCount++
            callbackReceivedInterval = it
        }

        // Case 1: autoSkipEnabled = false (manual mode)
        manager.autoSkipEnabled = false
        val manualCheck = manager.checkSkip(70_000L)
        assertNotNull(manualCheck)
        assertEquals(0, autoSkipCallbackCount, "Callback must not trigger when autoSkipEnabled is false")
        assertNull(manager.lastSkippedInterval)

        // Case 2: autoSkipEnabled = true (auto-skip mode)
        manager.autoSkipEnabled = true
        val autoCheck = manager.checkSkip(70_000L)
        assertNotNull(autoCheck)
        assertEquals(1, autoSkipCallbackCount, "Callback must trigger when autoSkipEnabled is true")
        assertEquals(interval, callbackReceivedInterval)
        assertEquals(interval, manager.lastSkippedInterval)

        // Execution helper
        val seekTarget = manager.executeSkip(70_000L)
        assertEquals(140_000L, seekTarget, "executeSkip should return endMs")

        // Outside interval execution returns null
        val seekOutside = manager.executeSkip(10_000L)
        assertNull(seekOutside)
    }

    @Test
    fun testAniSkipResponseJsonParsing() {
        val client = AniSkipClient()

        val sampleJson = """
        {
            "found": true,
            "results": [
                {
                    "interval": {
                        "startTime": 310.571,
                        "endTime": 400.571
                    },
                    "skipType": "op",
                    "skipId": "d4f34b6d-0547-4438-ac93-b25b577eddd5",
                    "episodeLength": 1443.984
                },
                {
                    "interval": {
                        "startTime": 1396.006,
                        "endTime": 1434.0
                    },
                    "skipType": "ed",
                    "skipId": "5751c57a-2bcd-4dd9-be93-58e6af0f8c81",
                    "episodeLength": 1434.985
                },
                {
                    "interval": {
                        "startTime": 0.0,
                        "endTime": 45.5
                    },
                    "skipType": "recap",
                    "skipId": "recap-uuid",
                    "episodeLength": 1434.985
                },
                {
                    "interval": {
                        "startTime": 100.0,
                        "endTime": 190.0
                    },
                    "skipType": "mixed-op",
                    "skipId": "mixed-uuid",
                    "episodeLength": 1434.985
                }
            ],
            "message": "Successfully found skip times",
            "statusCode": 200
        }
        """.trimIndent()

        val parsed = client.parseResponse(sampleJson)
        assertEquals(4, parsed.size)

        // Opening (op) -> Intro
        assertEquals(SkipType.Intro, parsed[0].type)
        assertEquals(310_571L, parsed[0].startMs)
        assertEquals(400_571L, parsed[0].endMs)

        // Ending (ed) -> Outro
        assertEquals(SkipType.Outro, parsed[1].type)
        assertEquals(1_396_006L, parsed[1].startMs)
        assertEquals(1_434_000L, parsed[1].endMs)

        // Recap -> Recap
        assertEquals(SkipType.Recap, parsed[2].type)
        assertEquals(0L, parsed[2].startMs)
        assertEquals(45_500L, parsed[2].endMs)

        // Mixed-op -> Mixed
        assertEquals(SkipType.Mixed, parsed[3].type)
        assertEquals(100_000L, parsed[3].startMs)
        assertEquals(190_000L, parsed[3].endMs)

        // Empty results when found is false
        val notFoundJson = """{"found":false,"results":[],"message":"No skip times found","statusCode":404}"""
        val emptyList = client.parseResponse(notFoundJson)
        assertTrue(emptyList.isEmpty())

        // Corrupt / empty json handling
        assertTrue(client.parseResponse("").isEmpty())
        assertTrue(client.parseResponse("   ").isEmpty())
        assertTrue(client.parseResponse("INVALID_JSON_PAYLOAD").isEmpty())
    }

    @Test
    fun testStreamFormatBadges() {
        // Complete 4-badge metadata: videoCodec • resolution • audioChannels • dynamicRange
        val full = StreamMetadata(
            videoCodec = "HEVC",
            audioChannels = "5.1",
            resolution = "1080p",
            dynamicRange = "HDR10"
        )
        assertEquals("HEVC • 1080p • 5.1 • HDR10", StreamFormatBadges.formatBadge(full))
        assertEquals("HEVC • 1080p • 5.1 • HDR10", full.formatBadge())
        assertEquals("HEVC • 1080p • 5.1 • HDR10", formatStreamBadge(full))

        // 4K Dolby Atmos badge
        val uhd = StreamMetadata(
            videoCodec = "AV1",
            audioChannels = "7.1",
            resolution = "4K",
            dynamicRange = "Dolby Vision"
        )
        assertEquals("AV1 • 4K • 7.1 • Dolby Vision", StreamFormatBadges.formatBadge(uhd))

        // Partial metadata with missing / blank fields
        val partial1 = StreamMetadata(
            videoCodec = "AVC",
            audioChannels = null,
            resolution = "720p",
            dynamicRange = null
        )
        assertEquals("AVC • 720p", StreamFormatBadges.formatBadge(partial1))

        val partial2 = StreamMetadata(
            videoCodec = "VP9",
            audioChannels = "Stereo",
            resolution = null,
            dynamicRange = ""
        )
        assertEquals("VP9 • Stereo", StreamFormatBadges.formatBadge(partial2))

        val singleBadge = StreamMetadata(
            videoCodec = null,
            audioChannels = null,
            resolution = "1080p",
            dynamicRange = null
        )
        assertEquals("1080p", StreamFormatBadges.formatBadge(singleBadge))

        // All null or whitespace metadata
        val empty = StreamMetadata(null, null, null, null)
        assertEquals("", StreamFormatBadges.formatBadge(empty))

        val blanks = StreamMetadata("  ", "   ", "", null)
        assertEquals("", StreamFormatBadges.formatBadge(blanks))
    }

    @Test
    fun testProjectedFinishTimeCalculation() {
        // Test case 1: Deterministic LocalTime finish time
        // 14:00 start, 60 min duration (3,600,000ms), 15 min elapsed (900,000ms)
        // Remaining: 45 min -> finish time: 14:45
        val currentTime = LocalTime.of(14, 0)
        val durationMs = 3_600_000L
        val positionMs = 900_000L
        val finishTime = StreamFormatBadges.calculateProjectedFinishTime(currentTime, durationMs, positionMs)
        assertEquals("14:45", finishTime)

        // Test case 2: Midnight wrap-around
        // 23:45 start, 30 min duration (1,800,000ms), 0 min elapsed (0ms)
        // Remaining: 30 min -> finish time: 00:15
        val nearMidnight = LocalTime.of(23, 45)
        val midnightFinish = StreamFormatBadges.calculateProjectedFinishTime(nearMidnight, 1_800_000L, 0L)
        assertEquals("00:15", midnightFinish)

        // Test case 3: Position reached or exceeded duration (remaining = 0)
        val completedFinish = StreamFormatBadges.calculateProjectedFinishTime(currentTime, 1_800_000L, 1_800_000L)
        assertEquals("14:00", completedFinish)

        val pastEndFinish = StreamFormatBadges.calculateProjectedFinishTime(currentTime, 1_800_000L, 2_000_000L)
        assertEquals("14:00", pastEndFinish)

        // Test case 4: Variable playback speed (2.0x speed)
        // 14:00 start, 60 min duration, 20 min elapsed -> 40 min remaining at 2.0x speed = 20 wall-clock min -> 14:20
        val fastPlaybackFinish = StreamFormatBadges.calculateProjectedFinishTime(
            currentTime = currentTime,
            durationMs = 3_600_000L,
            positionMs = 1_200_000L,
            speed = 2.0
        )
        assertEquals("14:20", fastPlaybackFinish)

        // Test case 5: Fixed epoch millis timestamp with UTC zone
        // 1700000000000L = 2023-11-14T22:13:20Z -> 22:13 UTC
        // remaining = 1,000,000ms (16 min 40 sec) -> 22:30 UTC
        val fixedMillis = 1700000000000L
        val calculated = calculateProjectedFinishTime(
            currentTimeMs = fixedMillis,
            durationMs = 2_000_000L,
            positionMs = 1_000_000L,
            zoneId = ZoneId.of("UTC")
        )
        assertEquals("22:30", calculated)

        // Test case 6: Digital clock format HH:mm
        assertEquals("09:05", StreamFormatBadges.formatDigitalClock(LocalTime.of(9, 5)))
        assertEquals("21:30", StreamFormatBadges.formatDigitalClock(LocalTime.of(21, 30)))
    }
}
