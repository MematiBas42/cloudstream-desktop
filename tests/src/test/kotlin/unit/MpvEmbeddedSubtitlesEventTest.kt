package unit

import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.player.impl.MpvPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests verifying MpvPlayer EmbeddedSubtitlesFetchedEvent emission,
 * TracksChangedEvent dispatching, deduplication guarantees,
 * and embedded vs external subtitle selection handling.
 */
class MpvEmbeddedSubtitlesEventTest {

    @Test
    fun `test EmbeddedSubtitlesFetchedEvent and TracksChangedEvent emitted on track-list change`() = runBlocking {
        val player = MpvPlayer()
        val receivedEvents = CopyOnWriteArrayList<PlayerEvent>()

        player.initCallbacks(
            eventHandler = { event -> receivedEvents.add(event) },
            requestedListeningPercentages = null
        )

        val trackListJson = """
        {
            "event": "property-change",
            "id": 1,
            "name": "track-list",
            "data": [
                {
                    "id": 1,
                    "type": "video",
                    "selected": true
                },
                {
                    "id": 2,
                    "type": "audio",
                    "title": "English 5.1",
                    "lang": "eng",
                    "selected": true
                },
                {
                    "id": 3,
                    "type": "sub",
                    "title": "SDH",
                    "lang": "eng",
                    "selected": false,
                    "external": false
                },
                {
                    "id": 4,
                    "type": "sub",
                    "title": "Full",
                    "lang": "tur",
                    "selected": false,
                    "external": false
                },
                {
                    "id": 5,
                    "type": "sub",
                    "title": "External VTT",
                    "lang": "spa",
                    "selected": false,
                    "external": true,
                    "external-filename": "https://example.com/sub.vtt"
                }
            ]
        }
        """.trimIndent()

        player.ipcClient.processIpcLine(trackListJson)
        delay(100)

        // 1. Verify EmbeddedSubtitlesFetchedEvent was received
        val embeddedEvents = receivedEvents.filterIsInstance<EmbeddedSubtitlesFetchedEvent>()
        assertEquals(1, embeddedEvents.size, "Exactly one EmbeddedSubtitlesFetchedEvent must be emitted")

        val subTracks = embeddedEvents.first().tracks
        assertEquals(2, subTracks.size, "Only embedded subtitles must be included (excluding external sub ID 5)")

        // Sub 1: English SDH
        val engSub = subTracks[0]
        assertEquals("English", engSub.originalName)
        assertEquals("SDH", engSub.nameSuffix)
        assertEquals("English SDH", engSub.name)
        assertEquals("3", engSub.url)
        assertEquals(SubtitleOrigin.EMBEDDED_IN_VIDEO, engSub.origin)
        assertEquals("eng", engSub.languageCode)

        // Sub 2: Turkish Full
        val turSub = subTracks[1]
        assertEquals("Turkish", turSub.originalName)
        assertEquals("Full", turSub.nameSuffix)
        assertEquals("Turkish Full", turSub.name)
        assertEquals("4", turSub.url)
        assertEquals(SubtitleOrigin.EMBEDDED_IN_VIDEO, turSub.origin)
        assertEquals("tur", turSub.languageCode)

        // 2. Verify SubtitlesUpdatedEvent and TracksChangedEvent were also received
        val subUpdatedEvents = receivedEvents.filterIsInstance<SubtitlesUpdatedEvent>()
        assertTrue(subUpdatedEvents.isNotEmpty(), "SubtitlesUpdatedEvent must be dispatched")

        val tracksChangedEvents = receivedEvents.filterIsInstance<TracksChangedEvent>()
        assertTrue(tracksChangedEvents.isNotEmpty(), "TracksChangedEvent must be dispatched")

        // 3. Verify deduplication: feeding identical track-list does not re-emit EmbeddedSubtitlesFetchedEvent
        val embeddedCountBefore = receivedEvents.filterIsInstance<EmbeddedSubtitlesFetchedEvent>().size
        player.ipcClient.processIpcLine(trackListJson)
        delay(100)
        val embeddedCountAfter = receivedEvents.filterIsInstance<EmbeddedSubtitlesFetchedEvent>().size
        assertEquals(embeddedCountBefore, embeddedCountAfter, "Duplicate track-list must not re-emit EmbeddedSubtitlesFetchedEvent")

        player.releaseCallbacks()
        player.destroy()
    }

    @Test
    fun `test setPreferredSubtitles differentiates embedded vs external subtitles`() = runBlocking {
        val player = MpvPlayer()

        val embeddedSub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "3",
            origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "eng"
        )

        // Selecting embedded subtitle sets sid property, does not fail
        val reloadRequired = player.setPreferredSubtitles(embeddedSub)
        assertFalse(reloadRequired, "setPreferredSubtitles must return false (no reload required in MPV)")
        assertEquals(embeddedSub, player.getCurrentPreferredSubtitle())

        // Clearing subtitles disables subtitle track (sid = 0 / no)
        player.setPreferredSubtitles(null)
        assertNull(player.getCurrentPreferredSubtitle())
        assertEquals(0, player.trackManager.selectedSubtitleTrackId.value)

        player.destroy()
    }

    @Test
    fun `test setActiveSubtitles skips embedded subtitles and only adds external ones`() = runBlocking {
        val player = MpvPlayer()

        val embeddedSub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "3",
            origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "eng"
        )

        val externalSub = SubtitleData(
            originalName = "Spanish",
            nameSuffix = "",
            url = "https://example.com/subs.vtt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "spa"
        )

        // Must execute without throwing exceptions
        player.setActiveSubtitles(setOf(embeddedSub, externalSub))

        player.destroy()
    }
}
