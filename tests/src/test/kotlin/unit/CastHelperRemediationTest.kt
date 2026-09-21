package unit

import androidx.media3.common.MimeTypes
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.MetadataHolder
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker
import com.lagradost.cloudstream3.utils.BiometricAuthenticator
import com.lagradost.cloudstream3.utils.CastHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.MediaInfo
import com.lagradost.cloudstream3.utils.MediaMetadata
import com.lagradost.cloudstream3.utils.MediaTrack
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Industrial JUnit 5 unit test suite for Domain 52 / Atomic Cluster C40: CastHelper
 * Validates:
 * 1. 1:1 upstream parity for CastHelper.getMediaInfo metadata payload population.
 * 2. Movie vs TV series subtitle formatting with Qualities string resolution.
 * 3. MIME type classification across M3U8, DASH, and MP4 containers.
 * 4. Subtitle track mapping to MediaTrack contracts.
 * 5. DLNA / UPnP DIDL-Lite XML metadata payload generation.
 * 6. FCast JSON payload serialization.
 * 7. Verification of @PlatformQuarantine(NOT_APPLICABLE_DESKTOP) on BiometricAuthenticator and BatteryOptimizationChecker.
 * 8. Zero-stub compliance and boundary validation.
 */
class CastHelperRemediationTest {

    private val sampleMovieEpisode = ResultEpisode(
        headerName = "Inception",
        name = null,
        poster = "https://example.com/inception-poster.jpg",
        episode = 1,
        season = null,
        data = "https://example.com/movie/data",
        apiName = "TestMovieProvider",
        id = 101,
        index = 0,
        tvType = TvType.Movie
    )

    private val sampleTvEpisode = ResultEpisode(
        headerName = "Breaking Bad",
        name = "Ozymandias",
        poster = "https://example.com/bb-ep14-poster.jpg",
        episode = 14,
        season = 5,
        data = "https://example.com/tv/data",
        apiName = "TestTvProvider",
        id = 202,
        index = 13,
        tvType = TvType.TvSeries
    )

    private val sampleEpisodeWithoutName = ResultEpisode(
        headerName = "Attack on Titan",
        name = null,
        poster = null,
        episode = 5,
        season = 1,
        data = "https://example.com/aot/ep5",
        apiName = "AnimeProvider",
        id = 303,
        index = 4,
        tvType = TvType.Anime
    )

    private val sampleLinks = listOf(
        ExtractorLink(
            source = "AlphaSource",
            name = "Alpha Server",
            url = "https://cdn.example.com/video_1080p.m3u8",
            referer = "https://example.com",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.M3U8
        ),
        ExtractorLink(
            source = "BetaSource",
            name = "Beta Dash",
            url = "https://cdn.example.com/video_720p.mpd",
            referer = "https://example.com",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.DASH
        ),
        ExtractorLink(
            source = "GammaSource",
            name = "Direct MP4",
            url = "https://cdn.example.com/video_480p.mp4",
            referer = "https://example.com",
            quality = Qualities.P480.value,
            type = ExtractorLinkType.VIDEO
        )
    )

    private val sampleSubtitles = listOf(
        SubtitleData(
            id = "sub_en",
            name = "English [SDH]",
            url = "https://subtitles.example.com/en.srt",
            origin = "OpenSubtitles",
            headers = emptyMap()
        ),
        SubtitleData(
            id = "sub_tr",
            name = "Turkish",
            url = "https://subtitles.example.com/tr.vtt",
            origin = "Subdl",
            headers = emptyMap()
        )
    )

    @Test
    fun `test getMediaInfo correctly populates movie metadata`() {
        val holder = MetadataHolder(
            apiName = "TestMovieProvider",
            isMovie = true,
            title = "Inception (2010)",
            poster = "https://example.com/fallback-poster.jpg",
            currentEpisodeIndex = 0,
            episodes = listOf(sampleMovieEpisode),
            currentLinks = sampleLinks,
            currentSubtitles = sampleSubtitles
        )

        val mediaInfo = CastHelper.getMediaInfo(
            epData = sampleMovieEpisode,
            holder = holder,
            index = 0,
            data = mapOf("provider" to "Alpha"),
            subtitles = sampleSubtitles
        )

        assertNotNull(mediaInfo)
        assertEquals("https://cdn.example.com/video_1080p.m3u8", mediaInfo.url)
        assertEquals(MimeTypes.APPLICATION_M3U8, mediaInfo.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, mediaInfo.streamType)

        val metadata = mediaInfo.metadata
        assertNotNull(metadata)
        assertEquals("Inception (2010)", metadata?.title)
        assertEquals("Alpha Server 1080p", metadata?.subtitle)
        assertEquals("https://example.com/inception-poster.jpg", metadata?.poster)

        // Validate subtitle tracks
        assertEquals(2, mediaInfo.mediaTracks.size)
        val track0 = mediaInfo.mediaTracks[0]
        assertEquals(0L, track0.id)
        assertEquals("English [SDH]", track0.name)
        assertEquals(MediaTrack.TYPE_TEXT, track0.type)
        assertEquals(MediaTrack.SUBTYPE_SUBTITLES, track0.subtype)
        assertEquals("https://subtitles.example.com/en.srt", track0.contentId)

        val track1 = mediaInfo.mediaTracks[1]
        assertEquals(1L, track1.id)
        assertEquals("Turkish", track1.name)
        assertEquals("https://subtitles.example.com/tr.vtt", track1.contentId)
    }

    @Test
    fun `test getMediaInfo correctly formats TV series episode subtitle and name fallback`() {
        val holder = MetadataHolder(
            apiName = "TestTvProvider",
            isMovie = false,
            title = "Breaking Bad",
            poster = "https://example.com/series-poster.jpg",
            currentEpisodeIndex = 0,
            episodes = listOf(sampleTvEpisode),
            currentLinks = sampleLinks,
            currentSubtitles = sampleSubtitles
        )

        // Case 1: Episode has an explicit name
        val mediaInfo1 = CastHelper.getMediaInfo(
            epData = sampleTvEpisode,
            holder = holder,
            index = 1,
            data = null,
            subtitles = sampleSubtitles
        )
        assertNotNull(mediaInfo1)
        assertEquals("https://cdn.example.com/video_720p.mpd", mediaInfo1.url)
        assertEquals(MimeTypes.APPLICATION_MPD, mediaInfo1.contentType)
        assertEquals("Ozymandias - Beta Dash 720p", mediaInfo1.metadata?.subtitle)

        // Case 2: Episode name is null, fallback to "Episode X"
        val mediaInfo2 = CastHelper.getMediaInfo(
            epData = sampleEpisodeWithoutName,
            holder = holder,
            index = 2,
            data = null,
            subtitles = emptyList()
        )
        assertNotNull(mediaInfo2)
        assertEquals("https://cdn.example.com/video_480p.mp4", mediaInfo2.url)
        assertEquals(MimeTypes.VIDEO_MP4, mediaInfo2.contentType)
        assertEquals("Episode 5 - Direct MP4 480p", mediaInfo2.metadata?.subtitle)
        // Poster falls back to holder.poster when epData.poster is null
        assertEquals("https://example.com/series-poster.jpg", mediaInfo2.metadata?.poster)
        assertTrue(mediaInfo2.mediaTracks.isEmpty())
    }

    @Test
    fun `test MediaInfo toDidlLiteXml generates valid DLNA payload`() {
        val holder = MetadataHolder(
            apiName = "TestMovieProvider",
            isMovie = true,
            title = "Interstellar & Beyond",
            poster = "https://example.com/art.jpg?width=500&height=300",
            currentEpisodeIndex = 0,
            episodes = listOf(sampleMovieEpisode),
            currentLinks = sampleLinks,
            currentSubtitles = sampleSubtitles
        )

        val mediaInfo = CastHelper.getMediaInfo(
            epData = sampleMovieEpisode,
            holder = holder,
            index = 0,
            data = null,
            subtitles = sampleSubtitles
        )

        val xml = mediaInfo.toDidlLiteXml()
        assertNotNull(xml)
        assertTrue(xml.startsWith("<DIDL-Lite"))
        assertTrue(xml.endsWith("</DIDL-Lite>"))
        assertTrue(xml.contains("xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""))
        assertTrue(xml.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(xml.contains("<dc:title>Interstellar &amp; Beyond - Alpha Server 1080p</dc:title>"))
        assertTrue(xml.contains("<res protocolInfo=\"http-get:*:application/x-mpegURL:*\">https://cdn.example.com/video_1080p.m3u8</res>"))
        assertTrue(xml.contains("<sec:CaptionInfo sec:type=\"srt\">https://subtitles.example.com/en.srt</sec:CaptionInfo>"))
        assertTrue(xml.contains("<sec:CaptionInfo sec:type=\"srt\">https://subtitles.example.com/tr.vtt</sec:CaptionInfo>"))
    }

    @Test
    fun `test MediaInfo toJsonPayload serializes complete descriptor for FCast`() {
        val holder = MetadataHolder(
            apiName = "TestMovieProvider",
            isMovie = true,
            title = "Movie Title",
            poster = "https://example.com/poster.jpg",
            currentEpisodeIndex = 0,
            episodes = listOf(sampleMovieEpisode),
            currentLinks = sampleLinks,
            currentSubtitles = sampleSubtitles
        )

        val mediaInfo = CastHelper.getMediaInfo(
            epData = sampleMovieEpisode,
            holder = holder,
            index = 0,
            data = "custom_test_payload",
            subtitles = sampleSubtitles
        )

        val json = mediaInfo.toJsonPayload()
        assertNotNull(json)
        assertTrue(json.contains("\"url\""))
        assertTrue(json.contains("https://cdn.example.com/video_1080p.m3u8"))
        assertTrue(json.contains("\"contentType\":\"application/x-mpegURL\""))
        assertTrue(json.contains("\"title\":\"Movie Title\""))
        assertTrue(json.contains("\"subtitle\":\"Alpha Server 1080p\""))
        assertTrue(json.contains("\"subtitles\""))
        assertTrue(json.contains("\"customData\":\"custom_test_payload\""))
    }

    @Test
    fun `test getMediaInfoAny overload handles MetadataHolder and invalid types`() {
        val holder = MetadataHolder(
            apiName = "Test",
            isMovie = true,
            title = "Test",
            poster = null,
            currentEpisodeIndex = 0,
            episodes = listOf(sampleMovieEpisode),
            currentLinks = sampleLinks,
            currentSubtitles = emptyList()
        )

        val validResult = CastHelper.getMediaInfo(
            epData = sampleMovieEpisode,
            holder = holder as Any,
            index = 0,
            data = null,
            subtitles = emptyList()
        )
        assertNotNull(validResult)
        assertEquals("https://cdn.example.com/video_1080p.m3u8", validResult?.url)

        val invalidResult = CastHelper.getMediaInfo(
            epData = sampleMovieEpisode,
            holder = "NotAMetadataHolder",
            index = 0,
            data = null,
            subtitles = emptyList()
        )
        assertNull(invalidResult)
    }

    @Test
    fun `test awaitLinks callback behavior`() {
        var callbackInvoked = false
        var callbackResult = false

        // Case 1: pending == null should not invoke callback
        CastHelper.awaitLinks(null) {
            callbackInvoked = true
        }
        assertFalse(callbackInvoked)

        // Case 2: pending == false indicates failure -> callback(true) to trigger mirror failover
        callbackInvoked = false
        CastHelper.awaitLinks(false) { failed ->
            callbackInvoked = true
            callbackResult = failed
        }
        assertTrue(callbackInvoked)
        assertTrue(callbackResult)

        // Case 3: pending == true indicates success -> callback(false)
        callbackInvoked = false
        CastHelper.awaitLinks(true) { failed ->
            callbackInvoked = true
            callbackResult = failed
        }
        assertTrue(callbackInvoked)
        assertFalse(callbackResult)
    }

    @Test
    fun `test startCast edge cases with invalid parameters`() {
        // Null session without active connection returns false
        val nullResult = CastHelper.run {
            null.startCast(
                apiName = "Test",
                isMovie = true,
                title = "Title",
                poster = null,
                currentEpisodeIndex = 0,
                episodes = listOf(sampleMovieEpisode),
                currentLinks = sampleLinks,
                subtitles = emptyList()
            )
        }
        assertFalse(nullResult)

        // Empty episode list returns false
        val emptyEpisodeResult = CastHelper.run {
            null.startCast(
                apiName = "Test",
                isMovie = true,
                title = "Title",
                poster = null,
                currentEpisodeIndex = 0,
                episodes = emptyList(),
                currentLinks = sampleLinks,
                subtitles = emptyList()
            )
        }
        assertFalse(emptyEpisodeResult)

        // Out of bounds episode index returns false
        val oobIndexResult = CastHelper.run {
            null.startCast(
                apiName = "Test",
                isMovie = true,
                title = "Title",
                poster = null,
                currentEpisodeIndex = 99,
                episodes = listOf(sampleMovieEpisode),
                currentLinks = sampleLinks,
                subtitles = emptyList()
            )
        }
        assertFalse(oobIndexResult)
    }

    @Test
    fun `test BatteryOptimizationChecker carries PlatformQuarantine NOT_APPLICABLE_DESKTOP`() {
        val clazz = BatteryOptimizationChecker::class.java
        val annotation = clazz.getAnnotation(PlatformQuarantine::class.java)

        assertNotNull(annotation, "BatteryOptimizationChecker must be annotated with @PlatformQuarantine")
        assertEquals(
            QuarantineStatus.NOT_APPLICABLE_DESKTOP,
            annotation?.status,
            "BatteryOptimizationChecker must have status NOT_APPLICABLE_DESKTOP"
        )
        assertTrue(
            annotation?.reason?.contains("battery optimization", ignoreCase = true) == true,
            "Quarantine reason must document mobile battery hardware non-applicability"
        )

        // Safe deterministic execution on desktop
        assertFalse(BatteryOptimizationChecker.isAppRestricted())
        assertDoesNotThrow {
            BatteryOptimizationChecker.openBatteryOptimizationSettings()
        }
    }

    @Test
    fun `test BiometricAuthenticator carries PlatformQuarantine NOT_APPLICABLE_DESKTOP`() {
        val clazz = BiometricAuthenticator::class.java
        val annotation = clazz.getAnnotation(PlatformQuarantine::class.java)

        assertNotNull(annotation, "BiometricAuthenticator must be annotated with @PlatformQuarantine")
        assertEquals(
            QuarantineStatus.NOT_APPLICABLE_DESKTOP,
            annotation?.status,
            "BiometricAuthenticator must have status NOT_APPLICABLE_DESKTOP"
        )
        assertTrue(
            annotation?.reason?.contains("biometric", ignoreCase = true) == true,
            "Quarantine reason must document mobile biometric hardware non-applicability"
        )

        // Safe deterministic execution on desktop
        assertFalse(BiometricAuthenticator.deviceHasPasswordPinLock())
        assertFalse(BiometricAuthenticator.isAuthEnabled())
        assertDoesNotThrow {
            BiometricAuthenticator.startBiometricAuthentication()
        }
    }
}
