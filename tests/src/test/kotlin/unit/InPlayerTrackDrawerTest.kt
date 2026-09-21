package unit

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.desktop.ui.player.InPlayerDrawerState
import com.lagradost.cloudstream3.desktop.ui.player.InPlayerEpisodeItem
import com.lagradost.cloudstream3.desktop.ui.player.PlayerDrawerTab
import com.lagradost.cloudstream3.desktop.ui.player.PlayerDrawerViewModel
import com.lagradost.player.tracks.AudioTrack
import com.lagradost.player.tracks.MpvTrackManager
import com.lagradost.player.tracks.SubtitleOrigin
import com.lagradost.player.tracks.SubtitleTrack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Industrial-grade unit test suite for Domain 18:
 * In-Player Episode Switcher & Audio/Subtitle Track Picker.
 *
 * Adheres to the Anti-Mock mandate: tests real JSON-RPC payload parsing,
 * mathematical boundary clamping, ISO 639 linguistic resolution,
 * and playlist indexing guarantees.
 */
class InPlayerTrackDrawerTest {

    @Nested
    @DisplayName("1. MPV track-list JSON Parsing & Model Mapping")
    inner class MpvTrackListParsingTests {

        @Test
        fun `testParseMpvTrackListPropertyChangeEvent`() {
            val trackListJson = """
            {
                "event": "property-change",
                "id": 5,
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
                        "title": "English Surround",
                        "lang": "eng",
                        "codec": "eac3",
                        "demux-channels": "5.1",
                        "demux-channel-count": 6,
                        "selected": true
                    },
                    {
                        "id": 3,
                        "type": "audio",
                        "title": "Turkish Commentary",
                        "lang": "tur",
                        "codec": "aac",
                        "demux-channels": "stereo",
                        "audio-channels": 2,
                        "selected": false
                    },
                    {
                        "id": 4,
                        "type": "audio",
                        "title": "Japanese DTS Master",
                        "lang": "jpn",
                        "codec": "dts-hd",
                        "demux-channels": "7.1",
                        "audio-channels": 8,
                        "selected": false
                    },
                    {
                        "id": 5,
                        "type": "sub",
                        "title": "English SDH",
                        "lang": "eng",
                        "codec": "ass",
                        "selected": true,
                        "external": false
                    },
                    {
                        "id": 6,
                        "type": "sub",
                        "title": "Turkish Fansub",
                        "lang": "tur",
                        "codec": "webvtt",
                        "selected": false,
                        "external": true,
                        "external-filename": "https://subtitles.cloudstream.test/sub_tr.vtt"
                    },
                    {
                        "id": 7,
                        "type": "sub",
                        "title": "Local French Subs",
                        "lang": "fra",
                        "codec": "subrip",
                        "selected": false,
                        "external": true,
                        "external-filename": "/home/user/.local/share/subtitles/fr.srt"
                    }
                ]
            }
            """.trimIndent()

            val (audioTracks, subtitleTracks) = MpvTrackManager.parseTrackListJson(trackListJson)

            // Validate Audio Tracks
            assertEquals(3, audioTracks.size, "Must parse exactly 3 audio tracks, excluding video and subs")

            // Track 1: English 5.1 EAC3
            val audio1 = audioTracks[0]
            assertEquals(2, audio1.id)
            assertEquals("English Surround", audio1.title)
            assertEquals("eng", audio1.lang)
            assertEquals("5.1 Surround", audio1.channels)
            assertEquals("E-AC-3 (Dolby Digital Plus)", audio1.codec)
            assertTrue(audio1.isSelected)

            // Track 2: Turkish Stereo AAC
            val audio2 = audioTracks[1]
            assertEquals(3, audio2.id)
            assertEquals("Turkish Commentary", audio2.title)
            assertEquals("tur", audio2.lang)
            assertEquals("Stereo", audio2.channels)
            assertEquals("AAC", audio2.codec)
            assertFalse(audio2.isSelected)

            // Track 3: Japanese 7.1 DTS-HD MA
            val audio3 = audioTracks[2]
            assertEquals(4, audio3.id)
            assertEquals("Japanese DTS Master", audio3.title)
            assertEquals("jpn", audio3.lang)
            assertEquals("7.1 Surround", audio3.channels)
            assertEquals("DTS-HD MA", audio3.codec)
            assertFalse(audio3.isSelected)

            // Validate Subtitle Tracks
            assertEquals(3, subtitleTracks.size, "Must parse exactly 3 subtitle tracks")

            // Sub 1: Embedded ASS
            val sub1 = subtitleTracks[0]
            assertEquals(5, sub1.id)
            assertEquals("English SDH", sub1.title)
            assertEquals("eng", sub1.lang)
            assertTrue(sub1.isSelected)
            assertEquals(SubtitleOrigin.EMBEDDED_IN_VIDEO, sub1.origin)

            // Sub 2: External Web URL VTT
            val sub2 = subtitleTracks[1]
            assertEquals(6, sub2.id)
            assertEquals("Turkish Fansub", sub2.title)
            assertEquals("tur", sub2.lang)
            assertFalse(sub2.isSelected)
            assertEquals(SubtitleOrigin.URL, sub2.origin)

            // Sub 3: Downloaded local file SRT
            val sub3 = subtitleTracks[2]
            assertEquals(7, sub3.id)
            assertEquals("Local French Subs", sub3.title)
            assertEquals("fra", sub3.lang)
            assertFalse(sub3.isSelected)
            assertEquals(SubtitleOrigin.DOWNLOADED_FILE, sub3.origin)
        }

        @Test
        fun `testParseMpvTrackListRawArrayAndEdgeCases`() {
            // Direct JSON array without property-change envelope
            val rawArrayJson = """
            [
                {"id": 10, "type": "audio", "title": "Stereo FLAC", "lang": "ger", "codec": "flac", "audio-channels": 2, "selected": "yes"},
                {"id": 11, "type": "audio", "title": "Mono Voice", "lang": "ita", "codec": "opus", "audio-channels": 1, "selected": 0},
                {"id": 12, "type": "sub", "title": "Greek", "lang": "ell", "selected": 1}
            ]
            """.trimIndent()

            val (audios, subs) = MpvTrackManager.parseTrackListJson(rawArrayJson)
            assertEquals(2, audios.size)
            assertEquals(1, subs.size)

            assertEquals(10, audios[0].id)
            assertEquals("Stereo", audios[0].channels)
            assertEquals("FLAC", audios[0].codec)
            assertTrue(audios[0].isSelected, "selected: 'yes' must evaluate to true")

            assertEquals(11, audios[1].id)
            assertEquals("Mono", audios[1].channels)
            assertEquals("Opus", audios[1].codec)
            assertFalse(audios[1].isSelected, "selected: 0 must evaluate to false")

            assertEquals(12, subs[0].id)
            assertTrue(subs[0].isSelected, "selected: 1 must evaluate to true")
            assertEquals(SubtitleOrigin.EMBEDDED_IN_VIDEO, subs[0].origin)

            // Malformed and empty inputs must degrade safely to empty lists without crashing
            assertEquals(Pair(emptyList<AudioTrack>(), emptyList<SubtitleTrack>()), MpvTrackManager.parseTrackListJson(""))
            assertEquals(Pair(emptyList<AudioTrack>(), emptyList<SubtitleTrack>()), MpvTrackManager.parseTrackListJson("   "))
            assertEquals(Pair(emptyList<AudioTrack>(), emptyList<SubtitleTrack>()), MpvTrackManager.parseTrackListJson("{not a json}"))
            assertEquals(Pair(emptyList<AudioTrack>(), emptyList<SubtitleTrack>()), MpvTrackManager.parseTrackList(null))
        }
    }

    @Nested
    @DisplayName("2. ISO 639 Language Code Resolution")
    inner class LanguageResolutionTests {

        @Test
        fun `testIso639TwoLetterCodesResolution`() {
            assertEquals("English", MpvTrackManager.resolveLanguageName("en"))
            assertEquals("Turkish", MpvTrackManager.resolveLanguageName("tr"))
            assertEquals("Spanish", MpvTrackManager.resolveLanguageName("es"))
            assertEquals("Japanese", MpvTrackManager.resolveLanguageName("ja"))
            assertEquals("German", MpvTrackManager.resolveLanguageName("de"))
            assertEquals("French", MpvTrackManager.resolveLanguageName("fr"))
            assertEquals("Italian", MpvTrackManager.resolveLanguageName("it"))
            assertEquals("Korean", MpvTrackManager.resolveLanguageName("ko"))
            assertEquals("Chinese", MpvTrackManager.resolveLanguageName("zh"))
            assertEquals("Russian", MpvTrackManager.resolveLanguageName("ru"))
            assertEquals("Portuguese", MpvTrackManager.resolveLanguageName("pt"))
            assertEquals("Arabic", MpvTrackManager.resolveLanguageName("ar"))
            assertEquals("Hindi", MpvTrackManager.resolveLanguageName("hi"))
        }

        @Test
        fun `testIso639ThreeLetterCodesResolution`() {
            assertEquals("English", MpvTrackManager.resolveLanguageName("eng"))
            assertEquals("Turkish", MpvTrackManager.resolveLanguageName("tur"))
            assertEquals("Spanish", MpvTrackManager.resolveLanguageName("spa"))
            assertEquals("Japanese", MpvTrackManager.resolveLanguageName("jpn"))
            assertEquals("French", MpvTrackManager.resolveLanguageName("fra"))
            assertEquals("French", MpvTrackManager.resolveLanguageName("fre"))
            assertEquals("German", MpvTrackManager.resolveLanguageName("deu"))
            assertEquals("German", MpvTrackManager.resolveLanguageName("ger"))
            assertEquals("Italian", MpvTrackManager.resolveLanguageName("ita"))
            assertEquals("Korean", MpvTrackManager.resolveLanguageName("kor"))
            assertEquals("Chinese", MpvTrackManager.resolveLanguageName("zho"))
            assertEquals("Chinese", MpvTrackManager.resolveLanguageName("chi"))
            assertEquals("Russian", MpvTrackManager.resolveLanguageName("rus"))
            assertEquals("Portuguese", MpvTrackManager.resolveLanguageName("por"))
        }

        @Test
        fun `testRegionTagsAndCaseInsensitivity`() {
            assertEquals("English", MpvTrackManager.resolveLanguageName("en-US"))
            assertEquals("Portuguese", MpvTrackManager.resolveLanguageName("pt-BR"))
            assertEquals("Spanish", MpvTrackManager.resolveLanguageName("es_419"))
            assertEquals("Chinese", MpvTrackManager.resolveLanguageName("zh-CN"))

            // Case Insensitivity
            assertEquals("English", MpvTrackManager.resolveLanguageName("ENG"))
            assertEquals("Turkish", MpvTrackManager.resolveLanguageName("TuR"))
            assertEquals("Spanish", MpvTrackManager.resolveLanguageName("Spa"))
        }

        @Test
        fun `testUnknownAndBlankLanguageCodesFallback`() {
            assertEquals("Unknown", MpvTrackManager.resolveLanguageName(null))
            assertEquals("Unknown", MpvTrackManager.resolveLanguageName(""))
            assertEquals("Unknown", MpvTrackManager.resolveLanguageName("   "))
            assertEquals("Klingon", MpvTrackManager.resolveLanguageName("klingon"))
        }
    }

    @Nested
    @DisplayName("3. Subtitle Delay Clamping (-5.0s to +5.0s)")
    inner class SubtitleDelayClampingTests {

        @Test
        fun `testDelayWithinBounds`() {
            assertEquals(0.0, MpvTrackManager.clampSubtitleDelay(0.0), 0.001)
            assertEquals(1.25, MpvTrackManager.clampSubtitleDelay(1.25), 0.001)
            assertEquals(-2.40, MpvTrackManager.clampSubtitleDelay(-2.40), 0.001)
            assertEquals(5.0, MpvTrackManager.clampSubtitleDelay(5.0), 0.001)
            assertEquals(-5.0, MpvTrackManager.clampSubtitleDelay(-5.0), 0.001)
        }

        @Test
        fun `testDelayOutsideBoundsStrictlyClamped`() {
            assertEquals(5.0, MpvTrackManager.clampSubtitleDelay(5.1), 0.001)
            assertEquals(5.0, MpvTrackManager.clampSubtitleDelay(12.5), 0.001)
            assertEquals(5.0, MpvTrackManager.clampSubtitleDelay(100.0), 0.001)

            assertEquals(-5.0, MpvTrackManager.clampSubtitleDelay(-5.05), 0.001)
            assertEquals(-5.0, MpvTrackManager.clampSubtitleDelay(-15.0), 0.001)
            assertEquals(-5.0, MpvTrackManager.clampSubtitleDelay(-1000.0), 0.001)
        }

        @Test
        fun `testDelaySteppingIn100msIncrements`() {
            // Step +100ms
            assertEquals(0.1, MpvTrackManager.stepSubtitleDelay(0.0, 1), 0.001)
            // Step -100ms
            assertEquals(-0.1, MpvTrackManager.stepSubtitleDelay(0.0, -1), 0.001)
            // Step +1000ms (+1.0s)
            assertEquals(1.0, MpvTrackManager.stepSubtitleDelay(0.0, 10), 0.001)
            // Step -1000ms (-1.0s)
            assertEquals(-1.0, MpvTrackManager.stepSubtitleDelay(0.0, -10), 0.001)

            // Step at upper boundary cannot exceed +5.0s
            assertEquals(5.0, MpvTrackManager.stepSubtitleDelay(4.95, 2), 0.001)
            // Step at lower boundary cannot exceed -5.0s
            assertEquals(-5.0, MpvTrackManager.stepSubtitleDelay(-4.95, -2), 0.001)

            // Reset returns exactly 0.0s
            assertEquals(0.0, MpvTrackManager.resetSubtitleDelay(), 0.001)
        }
    }

    @Nested
    @DisplayName("4. Episode Playlist Index Bounds & Navigation")
    inner class EpisodePlaylistBoundsTests {

        @Test
        fun `testClampPlaylistIndex`() {
            val size = 12

            assertEquals(0, MpvTrackManager.clampPlaylistIndex(0, size))
            assertEquals(5, MpvTrackManager.clampPlaylistIndex(5, size))
            assertEquals(11, MpvTrackManager.clampPlaylistIndex(11, size))

            // Negative index clamped to 0
            assertEquals(0, MpvTrackManager.clampPlaylistIndex(-1, size))
            assertEquals(0, MpvTrackManager.clampPlaylistIndex(-99, size))

            // Overflow index clamped to size - 1
            assertEquals(11, MpvTrackManager.clampPlaylistIndex(12, size))
            assertEquals(11, MpvTrackManager.clampPlaylistIndex(50, size))

            // Empty playlist always safely clamps to 0
            assertEquals(0, MpvTrackManager.clampPlaylistIndex(5, 0))
            assertEquals(0, MpvTrackManager.clampPlaylistIndex(-1, 0))
        }

        @Test
        fun `testNextAndPreviousEpisodeIndexNavigation`() {
            val size = 3

            // Forward navigation
            assertEquals(1, MpvTrackManager.getNextEpisodeIndex(0, size))
            assertEquals(2, MpvTrackManager.getNextEpisodeIndex(1, size))
            assertNull(MpvTrackManager.getNextEpisodeIndex(2, size), "Last episode has no next episode")

            // Backward navigation
            assertEquals(1, MpvTrackManager.getPreviousEpisodeIndex(2, size))
            assertEquals(0, MpvTrackManager.getPreviousEpisodeIndex(1, size))
            assertNull(MpvTrackManager.getPreviousEpisodeIndex(0, size), "First episode has no previous episode")

            // Out-of-bounds start
            assertNull(MpvTrackManager.getNextEpisodeIndex(5, size))
            assertNull(MpvTrackManager.getPreviousEpisodeIndex(-1, size))
            assertNull(MpvTrackManager.getNextEpisodeIndex(0, 0))
            assertNull(MpvTrackManager.getPreviousEpisodeIndex(0, 0))
        }

        @Test
        fun `testIsIndexValid`() {
            val size = 5

            assertTrue(MpvTrackManager.isIndexValid(0, size))
            assertTrue(MpvTrackManager.isIndexValid(2, size))
            assertTrue(MpvTrackManager.isIndexValid(4, size))

            assertFalse(MpvTrackManager.isIndexValid(-1, size))
            assertFalse(MpvTrackManager.isIndexValid(5, size))
            assertFalse(MpvTrackManager.isIndexValid(10, size))
            assertFalse(MpvTrackManager.isIndexValid(0, 0))
        }
    }

    @Nested
    @DisplayName("5. Audio Channel Label & Codec Formatting")
    inner class AudioChannelFormattingTests {

        @Test
        fun `testChannelCountToLabelFormatting`() {
            assertEquals("Mono", MpvTrackManager.formatAudioChannels(audioChannels = 1))
            assertEquals("Stereo", MpvTrackManager.formatAudioChannels(audioChannels = 2))
            assertEquals("3ch", MpvTrackManager.formatAudioChannels(audioChannels = 3))
            assertEquals("4ch", MpvTrackManager.formatAudioChannels(audioChannels = 4))
            assertEquals("5ch", MpvTrackManager.formatAudioChannels(audioChannels = 5))
            assertEquals("5.1 Surround", MpvTrackManager.formatAudioChannels(audioChannels = 6))
            assertEquals("7ch", MpvTrackManager.formatAudioChannels(audioChannels = 7))
            assertEquals("7.1 Surround", MpvTrackManager.formatAudioChannels(audioChannels = 8))

            assertEquals("", MpvTrackManager.formatAudioChannels(audioChannels = 0))
            assertEquals("", MpvTrackManager.formatAudioChannels(audioChannels = null))
        }

        @Test
        fun `testDemuxChannelsStringResolution`() {
            assertEquals("Mono", MpvTrackManager.formatAudioChannels(demuxChannels = "mono"))
            assertEquals("Stereo", MpvTrackManager.formatAudioChannels(demuxChannels = "stereo"))
            assertEquals("5.1 Surround", MpvTrackManager.formatAudioChannels(demuxChannels = "5.1"))
            assertEquals("5.1 Surround", MpvTrackManager.formatAudioChannels(demuxChannels = "5.1(side)"))
            assertEquals("7.1 Surround", MpvTrackManager.formatAudioChannels(demuxChannels = "7.1"))
            assertEquals("4ch", MpvTrackManager.formatAudioChannels(demuxChannels = "quad"))
            assertEquals("4ch", MpvTrackManager.formatAudioChannels(demuxChannels = "4.0"))
            assertEquals("Custom", MpvTrackManager.formatAudioChannels(demuxChannels = "custom"))
        }

        @Test
        fun `testAudioCodecNormalization`() {
            assertEquals("E-AC-3 (Dolby Digital Plus)", MpvTrackManager.formatAudioCodec("eac3"))
            assertEquals("AC-3 (Dolby Digital)", MpvTrackManager.formatAudioCodec("ac3"))
            assertEquals("DTS", MpvTrackManager.formatAudioCodec("dts"))
            assertEquals("DTS", MpvTrackManager.formatAudioCodec("dca"))
            assertEquals("DTS-HD MA", MpvTrackManager.formatAudioCodec("dts-hd"))
            assertEquals("Dolby TrueHD", MpvTrackManager.formatAudioCodec("truehd"))
            assertEquals("AAC", MpvTrackManager.formatAudioCodec("aac"))
            assertEquals("Opus", MpvTrackManager.formatAudioCodec("opus"))
            assertEquals("FLAC", MpvTrackManager.formatAudioCodec("flac"))
            assertEquals("Vorbis", MpvTrackManager.formatAudioCodec("vorbis"))
            assertEquals("MP3", MpvTrackManager.formatAudioCodec("mp3"))
            assertEquals("", MpvTrackManager.formatAudioCodec(null))
            assertEquals("", MpvTrackManager.formatAudioCodec(""))
        }
    }

    @Nested
    @DisplayName("6. MpvTrackManager State & Track Selection IPC")
    inner class TrackManagerFunctionalTests {

        @Test
        fun `testTrackManagerReactiveStateUpdatesAndSelection`() {
            val manager = MpvTrackManager()

            val json = """
            [
                {"id": 1, "type": "audio", "title": "Audio 1", "lang": "eng", "selected": true},
                {"id": 2, "type": "audio", "title": "Audio 2", "lang": "tur", "selected": false},
                {"id": 10, "type": "sub", "title": "Sub 1", "lang": "eng", "selected": true},
                {"id": 20, "type": "sub", "title": "Sub 2", "lang": "tur", "selected": false}
            ]
            """.trimIndent()

            manager.updateTracksFromJson(json)

            assertEquals(2, manager.audioTracks.value.size)
            assertEquals(2, manager.subtitleTracks.value.size)
            assertEquals(1, manager.selectedAudioTrackId.value)
            assertEquals(10, manager.selectedSubtitleTrackId.value)

            // Select Audio 2
            manager.selectAudioTrack(2)
            assertEquals(2, manager.selectedAudioTrackId.value)
            assertTrue(manager.audioTracks.value.first { it.id == 2 }.isSelected)
            assertFalse(manager.audioTracks.value.first { it.id == 1 }.isSelected)

            // Select Subtitle 20
            manager.selectSubtitleTrack(20)
            assertEquals(20, manager.selectedSubtitleTrackId.value)
            assertTrue(manager.subtitleTracks.value.first { it.id == 20 }.isSelected)
            assertFalse(manager.subtitleTracks.value.first { it.id == 10 }.isSelected)

            // Turn Subtitles OFF (id = 0)
            manager.selectSubtitleTrack(0)
            assertEquals(0, manager.selectedSubtitleTrackId.value)
            assertFalse(manager.subtitleTracks.value.any { it.isSelected }, "No subtitle track should be selected when turned off")

            // Delay adjustment and reset
            assertEquals(1.5, manager.setSubtitleDelay(1.5), 0.001)
            assertEquals(1.5, manager.subtitleDelaySec.value, 0.001)

            assertEquals(2.0, manager.adjustSubtitleDelay(0.5), 0.001)
            assertEquals(2.0, manager.subtitleDelaySec.value, 0.001)

            assertEquals(0.0, manager.resetSubtitleDelay(), 0.001)
            assertEquals(0.0, manager.subtitleDelaySec.value, 0.001)
        }
    }
}
