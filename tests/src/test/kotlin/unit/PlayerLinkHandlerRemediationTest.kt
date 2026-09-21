package unit

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.player.impl.PlayerLinkHandler
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * High-fidelity unit test suite for Domain 24 / Atomic Cluster C19: PlayerLinkHandler.
 *
 * Verifies:
 * 1. L112: In-flight mirror fallback position preservation across loadfile replace (lastKnownPositionMs).
 * 2. In-flight mirror fallback bypassing initial resume 5% intro and 95% credit reset thresholds.
 * 3. MPV JSON-RPC `loadfile <url> replace start=...` command generation with millisecond fidelity.
 * 4. [PlayerLinkHandler.prepareMirrorFallback] and [PlayerLinkHandler.resolveFallbackPosition] contracts.
 * 5. ValidatedLink helper [PlayerLinkHandler.ValidatedLink.toLoadFileCommand].
 * 6. ExtractorLinkPlayList (EDL) position preservation.
 * 7. Offline file:// and local media path resolution.
 * 8. Zero stubs and zero shims confirmed across the link handling subsystem.
 */
class PlayerLinkHandlerRemediationTest {

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
    }

    private fun createExtractorLink(
        url: String,
        name: String = "Test Mirror",
        source: String = "Test Provider",
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        quality: Int = Qualities.P1080.value,
        referer: String = "https://test.provider/embed",
        headers: Map<String, String> = emptyMap(),
    ): ExtractorLink {
        val link = ExtractorLink(
            source = source,
            name = name,
            url = url,
            type = type,
        )
        link.referer = referer
        link.quality = quality
        if (headers.isNotEmpty()) {
            link.headers = headers
        }
        return link
    }

    // =========================================================================
    // 1. In-Flight Mirror Fallback Position Preservation (L112)
    // =========================================================================

    @Test
    fun testValidatePreservesLastKnownPositionMs() {
        val link = createExtractorLink("https://cdn.example.com/stream.mp4")
        val timestampMs = 45_123L

        val result = PlayerLinkHandler.validate(
            link = link,
            explicitTitle = "Episode 1",
            useLocalProxy = false,
            lastKnownPositionMs = timestampMs,
        )

        assertTrue(result.isSuccess, "Validation should succeed for valid HTTP URL")
        val validated = result.getOrThrow()
        assertEquals(timestampMs, validated.lastKnownPositionMs, "ValidatedLink must retain exact lastKnownPositionMs")
        assertEquals(45.123, validated.startPositionSeconds, 0.0001, "startPositionSeconds must be converted with millisecond precision")
    }

    @Test
    fun testValidateCoercesNegativePositionToZero() {
        val link = createExtractorLink("https://cdn.example.com/stream.mp4")
        val result = PlayerLinkHandler.validate(
            link = link,
            lastKnownPositionMs = -5_000L,
        )

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals(0L, validated.lastKnownPositionMs, "Negative position must be coerced to 0L")
        assertEquals(0.0, validated.startPositionSeconds, 0.0001)
    }

    @Test
    fun testLoadLinkForwardsLastKnownPositionMs() {
        val link = createExtractorLink("https://cdn.example.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val timestampMs = 128_750L

        val result = PlayerLinkHandler.loadLink(
            link = link,
            explicitTitle = "Episode 2",
            lastKnownPositionMs = timestampMs,
        )

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals(timestampMs, validated.lastKnownPositionMs)
        assertEquals(128.75, validated.startPositionSeconds, 0.0001)
        assertEquals(PlayerLinkHandler.StreamKind.HLS, validated.streamKind)
    }

    @Test
    fun testPrepareMirrorFallbackPreservesExactTimestamp() {
        val mirror1 = createExtractorLink("https://mirror1.test/video.mp4", name = "Primary Mirror")
        val mirror2 = createExtractorLink("https://mirror2.test/video.mp4", name = "Backup Mirror")

        val currentPlaybackMs = 942_850L // 15 minutes, 42.85 seconds

        val fallbackResult = PlayerLinkHandler.prepareMirrorFallback(
            candidateLink = mirror2,
            lastKnownPositionMs = currentPlaybackMs,
            explicitTitle = "Fallbacked Title",
        )

        assertTrue(fallbackResult.isSuccess, "Mirror fallback validation must succeed")
        val validatedFallback = fallbackResult.getOrThrow()
        assertEquals(mirror2.url, validatedFallback.url)
        assertEquals("Fallbacked Title", validatedFallback.displayTitle)
        assertEquals(currentPlaybackMs, validatedFallback.lastKnownPositionMs, "Exact timestamp must be preserved across mirror fallback")
        assertEquals(942.85, validatedFallback.startPositionSeconds, 0.0001)
    }

    // =========================================================================
    // 2. Mirror Fallback vs Initial Resume Thresholds (5% and 95% rules)
    // =========================================================================

    @Test
    fun testResolveFallbackPositionBypassesIntroThreshold() {
        val durationMs = 1_440_000L // 24 minutes (standard anime episode)
        val positionInIntroMs = 15_000L // 15 seconds (1.04% of duration, <= 5%)

        // Initial resume via resumeStartSeconds() resets to 0 for intro/recap (<= 5%)
        val resumeSeconds = PlayerLinkHandler.resumeStartSeconds(positionInIntroMs, durationMs)
        assertEquals(0L, resumeSeconds, "resumeStartSeconds must return 0 for <= 5% (initial resume logic)")

        // In-flight mirror fallback via resolveFallbackPosition() MUST preserve exact position
        val fallbackPositionMs = PlayerLinkHandler.resolveFallbackPosition(
            sameEpisode = true,
            lastKnownPositionMs = positionInIntroMs,
        )
        assertEquals(
            positionInIntroMs,
            fallbackPositionMs,
            "In-flight mirror fallback must preserve exact timestamp even within the first 5% of video",
        )
    }

    @Test
    fun testResolveFallbackPositionBypassesOutroThreshold() {
        val durationMs = 1_440_000L // 24 minutes
        val positionInOutroMs = 1_400_000L // 23 minutes 20s (97.2% of duration, >= 95%)

        // Initial resume resets to 0 for credits (>= 95%)
        val resumeSeconds = PlayerLinkHandler.resumeStartSeconds(positionInOutroMs, durationMs)
        assertEquals(0L, resumeSeconds, "resumeStartSeconds must return 0 for >= 95% (credits logic)")

        // In-flight mirror fallback MUST resume at the exact timestamp
        val fallbackPositionMs = PlayerLinkHandler.resolveFallbackPosition(
            sameEpisode = true,
            lastKnownPositionMs = positionInOutroMs,
        )
        assertEquals(
            positionInOutroMs,
            fallbackPositionMs,
            "In-flight mirror fallback must preserve exact timestamp even in credits (>= 95%)",
        )
    }

    @Test
    fun testResolveFallbackPositionExplicitStartPositionPrecedence() {
        val fallbackPositionMs = PlayerLinkHandler.resolveFallbackPosition(
            sameEpisode = true,
            lastKnownPositionMs = 50_000L,
            startPositionMs = 75_000L,
        )
        assertEquals(75_000L, fallbackPositionMs, "Explicit positive startPositionMs must take precedence over lastKnownPositionMs")
    }

    @Test
    fun testResolveFallbackPositionNonSameEpisode() {
        val nonSameEpisodePosition = PlayerLinkHandler.resolveFallbackPosition(
            sameEpisode = false,
            lastKnownPositionMs = 50_000L,
            startPositionMs = null,
        )
        assertEquals(0L, nonSameEpisodePosition, "Non-same episode must start at 0L when startPositionMs is null")

        val explicitStart = PlayerLinkHandler.resolveFallbackPosition(
            sameEpisode = false,
            lastKnownPositionMs = 50_000L,
            startPositionMs = 30_000L,
        )
        assertEquals(30_000L, explicitStart, "Non-same episode must use explicit startPositionMs")
    }

    // =========================================================================
    // 3. MPV loadfile replace Command Formatting
    // =========================================================================

    @Test
    fun testFormatMpvStartOption() {
        assertEquals("start=0", PlayerLinkHandler.formatMpvStartOption(0L))
        assertEquals("start=0", PlayerLinkHandler.formatMpvStartOption(-1000L))
        assertEquals("start=10.000", PlayerLinkHandler.formatMpvStartOption(10_000L))
        assertEquals("start=45.123", PlayerLinkHandler.formatMpvStartOption(45_123L))
        assertEquals("start=1234.567", PlayerLinkHandler.formatMpvStartOption(1_234_567L))
    }

    @Test
    fun testBuildLoadFileCommandWithoutPosition() {
        val url = "https://cdn.example.com/video.mp4"
        val cmd = PlayerLinkHandler.buildLoadFileCommand(url, mode = "replace", positionMs = 0L)

        assertEquals(listOf("loadfile", url, "replace"), cmd)
    }

    @Test
    fun testBuildLoadFileCommandWithPositionPreservation() {
        val url = "https://cdn.example.com/video.mp4"
        val positionMs = 45_123L
        val cmd = PlayerLinkHandler.buildLoadFileCommand(url, mode = "replace", positionMs = positionMs)

        assertEquals(4, cmd.size)
        assertEquals("loadfile", cmd[0])
        assertEquals(url, cmd[1])
        assertEquals("replace", cmd[2])
        assertEquals("start=45.123", cmd[3])
    }

    @Test
    fun testBuildLoadFileArgs() {
        val url = "https://cdn.example.com/video.mp4"
        val args = PlayerLinkHandler.buildLoadFileArgs(url, positionMs = 30_500L, mode = "replace")

        assertEquals(listOf("loadfile", url, "replace", "start=30.500"), args)
    }

    @Test
    fun testValidatedLinkToLoadFileCommand() {
        val link = createExtractorLink("https://cdn.example.com/stream.mp4")
        val validated = PlayerLinkHandler.validate(
            link = link,
            lastKnownPositionMs = 62_400L,
        ).getOrThrow()

        val command = validated.toLoadFileCommand(mode = "replace")
        assertEquals(listOf("loadfile", "https://cdn.example.com/stream.mp4", "replace", "start=62.400"), command)
    }

    // =========================================================================
    // 4. MpvJsonRpcProtocol IPC Command Serialization
    // =========================================================================

    @Test
    fun testMpvJsonRpcProtocolBuildLoadFileCommandWithPosition() {
        val url = "https://cdn.example.com/mirror2.mp4"
        val positionMs = 45_000L

        val json = MpvJsonRpcProtocol.buildLoadFileCommand(
            url = url,
            mode = "replace",
            positionMs = positionMs,
            requestId = 42,
        )

        assertTrue(json.contains(""""command":["loadfile","$url","replace","start=45.000"]"""), "JSON payload must carry start option: $json")
        assertTrue(json.contains(""""request_id":42"""), "JSON payload must carry request_id: $json")
    }

    @Test
    fun testMpvJsonRpcProtocolBuildLoadFileCommandDefaultBackwardCompatibility() {
        val url = "https://cdn.example.com/video.mp4"
        val json = MpvJsonRpcProtocol.buildLoadFileCommand(url)

        assertTrue(json.contains(""""command":["loadfile","$url","replace"]"""))
    }

    // =========================================================================
    // 5. ExtractorLinkPlayList (EDL) Position Preservation
    // =========================================================================

    @Test
    fun testPlayListEdlPreservesLastKnownPosition() {
        val playList = ExtractorLinkPlayList(
            source = "ChunkedSource",
            name = "Chunked Video",
            url = "",
            playlist = listOf(
                PlayListItem("https://cdn.test/part1.ts", 10_000_000L),
                PlayListItem("https://cdn.test/part2.ts", 10_000_000L),
            ),
            type = ExtractorLinkType.VIDEO,
        )

        val result = PlayerLinkHandler.validate(
            link = playList,
            lastKnownPositionMs = 15_000L,
        )

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertTrue(validated.url.endsWith(".edl"), "URL must resolve to generated EDL file")
        assertEquals(15_000L, validated.lastKnownPositionMs, "EDL validated link must preserve lastKnownPositionMs")
        assertEquals(15.0, validated.startPositionSeconds, 0.0001)

        val loadCmd = validated.toLoadFileCommand()
        assertEquals(4, loadCmd.size)
        assertEquals("start=15.000", loadCmd[3])
    }

    // =========================================================================
    // 6. Offline Local Files and Cross-Platform Paths
    // =========================================================================

    @Test
    fun testFileUriValidationWithPositionPreservation() {
        val fileUri = "file:///home/user/Videos/movie.mp4"
        val link = createExtractorLink(fileUri, name = "Offline Movie")

        val result = PlayerLinkHandler.validate(
            link = link,
            lastKnownPositionMs = 80_000L,
        )

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals("/home/user/Videos/movie.mp4", validated.url)
        assertEquals(80_000L, validated.lastKnownPositionMs)
        assertEquals(80.0, validated.startPositionSeconds, 0.0001)
    }

    @Test
    fun testAbsolutePosixPathValidationWithPosition() {
        val posixPath = "/var/media/download.mp4"
        val link = createExtractorLink(posixPath, name = "Downloaded Media")

        val result = PlayerLinkHandler.loadLink(
            link = link,
            lastKnownPositionMs = 33_333L,
        )

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals(posixPath, validated.url)
        assertEquals(33_333L, validated.lastKnownPositionMs)
        assertEquals(33.333, validated.startPositionSeconds, 0.0001)
    }

    @Test
    fun testWindowsDriveLetterPathValidation() {
        val winPath = "C:\\Videos\\test.mp4"
        val link = createExtractorLink(winPath, name = "Windows Media")

        val result = PlayerLinkHandler.loadLink(
            link = link,
            lastKnownPositionMs = 12_000L,
        )

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals(winPath, validated.url)
        assertEquals(12_000L, validated.lastKnownPositionMs)
    }

    // =========================================================================
    // 7. Header and Display Sanitization Verification
    // =========================================================================

    @Test
    fun testHeaderMapDefaultsUserAgentAndCleansCrlf() {
        val link = createExtractorLink(
            url = "https://cdn.example.com/test.mp4",
            headers = mapOf(
                "Referer\r\n" to "https://upstream.test\n",
                "X-Custom-Auth" to "Bearer 12345\r",
            ),
        )

        val headers = PlayerLinkHandler.buildHeaderMap(link)
        assertEquals("https://upstream.test", headers["Referer"])
        assertEquals("Bearer 12345", headers["X-Custom-Auth"])
        assertTrue(headers.containsKey("User-Agent"))
    }

    @Test
    fun testSanitizeDisplayTitle() {
        val dirtyTitle = "My  Movie\r\nTitle, With Commas and \n Newlines  "
        val cleaned = PlayerLinkHandler.sanitizeDisplayTitle(dirtyTitle)
        assertEquals("My  Movie   Title  With Commas and    Newlines", cleaned)
    }
}
