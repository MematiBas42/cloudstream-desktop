package unit

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.player.impl.MpvProcessLauncher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.UUID

/**
 * Unit test suite for Domain 23 / Atomic Cluster C28:
 * MpvProcessLauncher TLS Verification Bypass Flag.
 *
 * Verifies:
 * 1. MPV command arguments contain `--tls-verify=no` and `--demuxer-lavf-o-append=tls_verify=0`
 *    to prevent silent stream crashes on rogue CDN certificates (self-signed, expired, domain mismatch).
 * 2. Parity with upstream Android CS3IPlayer (`ignoreSSL = true` + `SSLTrustManager`).
 * 3. Network reconnection arguments and resilience flags order.
 * 4. Custom headers, DRM options, subtitles, window ID (wid) embedding, and extraArgs coexistence.
 * 5. Socket cleanup and mediaUrl positioned as the final positional argument.
 */
class MpvProcessLauncherTLSTest {

    @Test
    fun testTlsVerifyBypassFlagsPresentInCommandLine() {
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/mpv_test.sock",
            mediaUrl = "https://rogue-cdn.piratestream.cc/hls/master.m3u8"
        )

        assertTrue(
            args.contains("--tls-verify=no"),
            "MPV arguments must include --tls-verify=no to bypass strict TLS validation on rogue CDNs"
        )
        assertTrue(
            args.contains("--demuxer-lavf-o-append=tls_verify=0"),
            "MPV arguments must include --demuxer-lavf-o-append=tls_verify=0 for libavformat demuxer TLS bypass"
        )
    }

    @Test
    fun testTlsVerifyWithReconnectionResilienceFlags() {
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/mpv_test.sock",
            mediaUrl = "https://mirror1.cdn-edge.xyz/stream.mp4"
        )

        // Verify lavf reconnection settings
        assertTrue(args.contains("--demuxer-lavf-o-append=reconnect=1"))
        assertTrue(args.contains("--demuxer-lavf-o-append=reconnect_streamed=1"))
        assertTrue(args.contains("--demuxer-lavf-o-append=reconnect_delay_max=5"))
        assertTrue(args.contains("--demuxer-lavf-o-append=reconnect_on_http_error=403,404,429,500,503"))

        // Verify TLS bypass flags
        val tlsIdx = args.indexOf("--tls-verify=no")
        val lavfTlsIdx = args.indexOf("--demuxer-lavf-o-append=tls_verify=0")
        val urlIdx = args.indexOf("https://mirror1.cdn-edge.xyz/stream.mp4")

        assertTrue(tlsIdx >= 0, "--tls-verify=no must be present")
        assertTrue(lavfTlsIdx >= 0, "--demuxer-lavf-o-append=tls_verify=0 must be present")
        assertTrue(tlsIdx < urlIdx, "TLS flags must precede the media URL")
        assertTrue(lavfTlsIdx < urlIdx, "lavf TLS flags must precede the media URL")
    }

    @Test
    fun testCustomHeadersCoexistWithTlsBypass() {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) CloudStream/Desktop",
            "Referer" to "https://upstream.streamprovider.to/",
            "Authorization" to "Bearer rogue_token_xyz",
            "X-Requested-With" to "com.lagradost.cloudstream3"
        )

        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/test.sock",
            mediaUrl = "https://untrusted-ssl-edge.org/video.mp4",
            userAgent = headers["User-Agent"],
            referrer = headers["Referer"],
            headers = headers
        )

        assertTrue(args.contains("--tls-verify=no"))
        assertTrue(args.contains("--demuxer-lavf-o-append=tls_verify=0"))
        assertTrue(args.contains("--user-agent=Mozilla/5.0 (X11; Linux x86_64) CloudStream/Desktop"))
        assertTrue(args.contains("--referrer=https://upstream.streamprovider.to/"))

        val httpHeaderArg = args.firstOrNull { it.startsWith("--http-header-fields=") }
        assertNotNull(httpHeaderArg, "--http-header-fields argument must be generated for extra headers")
        assertTrue(httpHeaderArg!!.contains("Authorization: Bearer rogue_token_xyz"))
        assertTrue(httpHeaderArg.contains("X-Requested-With: com.lagradost.cloudstream3"))
    }

    @Test
    fun testClearKeyDrmWithTlsVerificationBypass() = runBlocking {
        val drmLink = newDrmExtractorLink(
            source = "ProviderX",
            name = "Stream with ClearKey and Untrusted SSL",
            url = "https://rogue-drm-cdn.cc/stream.mpd",
            type = ExtractorLinkType.DASH,
            uuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030e4d41e12")
        ) {
            kid = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
            key = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="
        }

        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/drm_test.sock",
            mediaUrl = drmLink.url,
            link = drmLink
        )

        // TLS bypass should be preserved even when DRM arguments are injected
        assertTrue(args.contains("--tls-verify=no"))
        assertTrue(args.contains("--demuxer-lavf-o-append=tls_verify=0"))

        // ClearKey DRM args for lavf demuxer should be present
        val drmArg = args.firstOrNull { it.startsWith("--demuxer-lavf-o-append=decryption_key=") }
        assertNotNull(drmArg, "Decryption key must be appended for ClearKey DRM stream")
    }

    @Test
    fun testEmbeddedWindowPreservesTlsFlags() {
        val testWid = 987654321L
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/wid_test.sock",
            mediaUrl = "https://cdn.example.org/stream.mkv",
            wid = testWid
        )

        assertTrue(args.contains("--wid=$testWid"))
        assertTrue(args.contains("--tls-verify=no"))
        assertTrue(args.contains("--demuxer-lavf-o-append=tls_verify=0"))
        assertTrue(args.contains("--input-vo-keyboard=no"))
        assertTrue(args.contains("--input-default-bindings=no"))
    }

    @Test
    fun testExtraArgsAppendAfterTlsFlags() {
        val customArgs = listOf("--cache-secs=120", "--force-seekable=yes")
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/extra_test.sock",
            mediaUrl = "https://cdn.example.org/stream.mp4",
            extraArgs = customArgs
        )

        assertTrue(args.contains("--tls-verify=no"))
        assertTrue(args.contains("--demuxer-lavf-o-append=tls_verify=0"))
        assertTrue(args.contains("--cache-secs=120"))
        assertTrue(args.contains("--force-seekable=yes"))

        // Media URL must remain the very last positional argument
        assertEquals("https://cdn.example.org/stream.mp4", args.last())
    }

    @Test
    fun testSubtitlesCoexistWithTlsFlags() {
        val subtitles = listOf(
            "https://subs.provider.cc/english.srt",
            "https://subs.provider.cc/turkish.vtt",
            "https://subs.provider.cc/english.srt", // Duplicate should be filtered out
            "   ", // Blank entry should be ignored
        )
        val args = MpvProcessLauncher.buildCommandLine(
            executable = "mpv",
            socketPath = "/tmp/sub_test.sock",
            mediaUrl = "https://cdn.example.org/stream.mp4",
            subtitles = subtitles
        )

        assertTrue(args.contains("--tls-verify=no"))
        assertTrue(args.contains("--sub-file=https://subs.provider.cc/english.srt"))
        assertTrue(args.contains("--sub-file=https://subs.provider.cc/turkish.vtt"))
        assertEquals(1, args.count { it == "--sub-file=https://subs.provider.cc/english.srt" })
        assertEquals(2, args.count { it.startsWith("--sub-file=") })
        assertEquals("https://cdn.example.org/stream.mp4", args.last())
    }

    @Test
    fun testSocketFileCleanupBeforeLaunch(@TempDir tempDir: Path) {
        val socketFile = tempDir.resolve("test_mpv.sock").toFile()
        socketFile.writeText("stale_socket_data")
        assertTrue(socketFile.exists(), "Precondition: stale socket file must exist")

        // Passing a dummy executable (e.g. echo or true) allows launch to run without failing executable check
        val dummyExec = if (File("/bin/true").canExecute()) "/bin/true" else "/usr/bin/true"
        if (File(dummyExec).canExecute()) {
            val process = MpvProcessLauncher.launch(
                socketPath = socketFile.absolutePath,
                mediaUrl = "https://test.cdn/video.mp4",
                executable = dummyExec
            )
            process.destroyForcibly()

            // The stale file should have been deleted by launch before starting the process
            // (even if dummyExec exits immediately, socketFile was cleaned)
            assertFalse(
                socketFile.exists() && socketFile.readText() == "stale_socket_data",
                "Stale socket file must be deleted prior to launching MPV"
            )
        }
    }

    @Test
    fun testFindMpvExecutableDoesNotThrow() {
        // Must return either a non-empty executable path or null, but never throw
        val execPath = org.junit.jupiter.api.function.ThrowingSupplier {
            MpvProcessLauncher.findMpvExecutable()
        }.let { assertDoesNotThrow(it) }
        if (execPath != null) {
            assertTrue(File(execPath).isFile, "Resolved MPV binary must be an existing file")
            assertTrue(File(execPath).canExecute(), "Resolved MPV binary must be executable")
        }
    }
}
