package unit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.player.NetflixScrimMath
import com.lagradost.cloudstream3.desktop.ui.player.TvLogoResolutionState
import com.lagradost.cloudstream3.desktop.ui.player.TvPlayerHudController
import com.lagradost.cloudstream3.desktop.ui.player.resolveLogoFallback
import com.lagradost.player.api.PlayerState
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import com.lagradost.player.osd.MpvOsdRenderer
import com.lagradost.player.osd.PlayerHudMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Industrial Unit & Integration Test Suite for Domain 16:
 * MPV TV OSD Architecture & Netflix-Style Metadata Scrim.
 *
 * CRITICAL QUALITY MANDATE:
 * - Tests exact 680dp gradient stop mathematics, color values, and linear interpolation.
 * - Tests deterministic 4-second auto-hide countdown lifecycle, D-Pad reset tokens, and pause persistence.
 * - Tests logo watermark fallback state machine (bindLogo parity).
 * - Tests comprehensive metadata badge text formatting permutations.
 * - Tests SubStation Alpha (ASS) OSD script generation, text sanitization, and word wrapping.
 * - Tests real AF_UNIX domain socket IPC communication without synthetic mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpvTvOsdMetadataTest {

    private lateinit var socketPath: Path
    private var serverChannel: ServerSocketChannel? = null
    private var acceptedClientChannel: SocketChannel? = null
    private var ipcClient: MpvIpcClient? = null
    private var ioScope: CoroutineScope? = null

    @BeforeEach
    fun setUp() {
        val randomSuffix = UUID.randomUUID().toString().take(8)
        socketPath = Path.of("/tmp/cs_test_osd_${randomSuffix}.sock")
        Files.deleteIfExists(socketPath)
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            ipcClient?.close()
            runCatching { acceptedClientChannel?.close() }
            runCatching { serverChannel?.close() }
            Files.deleteIfExists(socketPath)
            ioScope?.cancel()
        }
    }

    // =========================================================================
    // 1. Exact 680dp Netflix Scrim Gradient Stop Mathematics
    // =========================================================================

    @Test
    fun `test scrim width and gradient stop ratios conform strictly to spec`() {
        // Upstream: player_custom_layout_tv.xml hardcoded 680dp
        assertEquals(680.dp, NetflixScrimMath.ScrimWidth)
        assertEquals(680f, NetflixScrimMath.SCRIM_WIDTH_DP)

        // 3 stops: 0dp (0.0), 340dp (0.5), 680dp (1.0)
        assertEquals(0.0f, NetflixScrimMath.STOP_0_RATIO)
        assertEquals(0.5f, NetflixScrimMath.STOP_1_RATIO)
        assertEquals(1.0f, NetflixScrimMath.STOP_2_RATIO)

        assertEquals(0f, NetflixScrimMath.STOP_0_POS_DP)
        assertEquals(340f, NetflixScrimMath.STOP_1_POS_DP)
        assertEquals(680f, NetflixScrimMath.STOP_2_POS_DP)
    }

    @Test
    fun `test colorimetry values and exact ARGB channels`() {
        // Start: #E6000000 (alpha 0xE6 = 230 -> 230/255 ≈ 0.90196)
        assertEquals(Color(0xE6000000), NetflixScrimMath.ColorStart)
        assertEquals(230f / 255f, NetflixScrimMath.ColorStart.alpha, 0.001f)
        assertEquals(0f, NetflixScrimMath.ColorStart.red)
        assertEquals(0f, NetflixScrimMath.ColorStart.green)
        assertEquals(0f, NetflixScrimMath.ColorStart.blue)

        // Center: #99000000 (alpha 0x99 = 153 -> 153/255 = 0.60000)
        assertEquals(Color(0x99000000), NetflixScrimMath.ColorCenter)
        assertEquals(153f / 255f, NetflixScrimMath.ColorCenter.alpha, 0.001f)
        assertEquals(0.6f, NetflixScrimMath.ColorCenter.alpha, 0.001f)

        // End: #00000000 (alpha 0x00 = 0 -> 0.0)
        assertEquals(Color(0x00000000), NetflixScrimMath.ColorEnd)
        assertEquals(0.0f, NetflixScrimMath.ColorEnd.alpha, 0.001f)

        // ColorStops array structure
        assertEquals(3, NetflixScrimMath.ColorStops.size)
        assertEquals(0.0f, NetflixScrimMath.ColorStops[0].first)
        assertEquals(NetflixScrimMath.ColorStart, NetflixScrimMath.ColorStops[0].second)
        assertEquals(0.5f, NetflixScrimMath.ColorStops[1].first)
        assertEquals(NetflixScrimMath.ColorCenter, NetflixScrimMath.ColorStops[1].second)
        assertEquals(1.0f, NetflixScrimMath.ColorStops[2].first)
        assertEquals(NetflixScrimMath.ColorEnd, NetflixScrimMath.ColorStops[2].second)
    }

    @ParameterizedTest(name = "Scrim alpha at xDp={0} evaluates to alpha={1}")
    @CsvSource(
        "0.0,    0.90196",  // Stop 0 boundary
        "170.0,  0.75098",  // Halfway between stop 0 and stop 1 (230 + 153)/2 = 191.5 -> 191.5/255 ≈ 0.75098
        "340.0,  0.60000",  // Stop 1 center
        "510.0,  0.30000",  // Halfway between stop 1 and stop 2 153/2 = 76.5 -> 76.5/255 = 0.30000
        "680.0,  0.00000",  // Stop 2 end (fully transparent)
        "-10.0,  0.90196",  // Overscan bleed clamped to left edge
        "1000.0, 0.00000",  // Beyond scrim clamped to right edge (transparent)
    )
    fun `test scrim linear alpha gradient mathematics at coordinate stops`(
        xDp: Float,
        expectedAlpha: Float
    ) {
        val calculatedAlpha = NetflixScrimMath.calculateAlphaAt(xDp)
        assertEquals(expectedAlpha, calculatedAlpha, 0.001f, "Alpha at xDp=$xDp must match linear interpolation")
    }

    @ParameterizedTest(name = "Horizontal coverage on {0}dp screen is {1}")
    @CsvSource(
        "1920.0, 0.35416667", // 1080p standard: exactly 35.4166%
        "1280.0, 0.53125",    // 720p HD: 53.125%
        "3840.0, 0.17708333", // 4K UHD: 17.7083%
        "2560.0, 0.265625",   // 1440p QHD: 26.5625%
    )
    fun `test horizontal screen estate ratio calculations`(
        viewportWidthDp: Float,
        expectedRatio: Float
    ) {
        val ratio = NetflixScrimMath.horizontalCoverageRatio(viewportWidthDp)
        assertEquals(expectedRatio, ratio, 0.0001f, "Viewport coverage must equal 680 / viewportWidthDp")
    }

    // =========================================================================
    // 2. Logo Fallback Resolution State Machine (bindLogo Parity)
    // =========================================================================

    @Test
    fun `test logo resolution displays high-res logo when valid URL is provided`() {
        val state = resolveLogoFallback(
            url = "https://image.tmdb.org/t/p/original/logo123.png",
            title = "Inception",
            isImageError = false
        )
        assertTrue(state is TvLogoResolutionState.ShowLogo)
        assertEquals("https://image.tmdb.org/t/p/original/logo123.png", (state as TvLogoResolutionState.ShowLogo).url)
    }

    @Test
    fun `test logo resolution trims surrounding whitespace from URL`() {
        val state = resolveLogoFallback(
            url = "  https://image.tmdb.org/t/p/original/trimmed_logo.png  \n",
            title = "Interstellar",
            isImageError = false
        )
        assertTrue(state is TvLogoResolutionState.ShowLogo)
        assertEquals("https://image.tmdb.org/t/p/original/trimmed_logo.png", (state as TvLogoResolutionState.ShowLogo).url)
    }

    @Test
    fun `test logo resolution falls back to 30sp typography when URL is null`() {
        val state = resolveLogoFallback(
            url = null,
            title = "Breaking Bad",
            isImageError = false
        )
        assertTrue(state is TvLogoResolutionState.ShowTitleFallback)
        assertEquals("Breaking Bad", (state as TvLogoResolutionState.ShowTitleFallback).title)
    }

    @Test
    fun `test logo resolution falls back to typography when URL is empty or blank`() {
        val emptyState = resolveLogoFallback(url = "", title = "Stranger Things")
        assertTrue(emptyState is TvLogoResolutionState.ShowTitleFallback)
        assertEquals("Stranger Things", (emptyState as TvLogoResolutionState.ShowTitleFallback).title)

        val blankState = resolveLogoFallback(url = "   \t\n  ", title = "The Matrix")
        assertTrue(blankState is TvLogoResolutionState.ShowTitleFallback)
        assertEquals("The Matrix", (blankState as TvLogoResolutionState.ShowTitleFallback).title)
    }

    @Test
    fun `test logo resolution falls back to typography on network load error`() {
        val state = resolveLogoFallback(
            url = "https://cdn.invalid.domain/broken_logo.png",
            title = "Game of Thrones",
            isImageError = true
        )
        assertTrue(state is TvLogoResolutionState.ShowTitleFallback)
        assertEquals("Game of Thrones", (state as TvLogoResolutionState.ShowTitleFallback).title)
    }

    // =========================================================================
    // 3. Deterministic 4-Second Auto-Hide Countdown Lifecycle
    // =========================================================================

    @Test
    fun `test initial state is hidden with zero alpha`() {
        val dispatcher = StandardTestDispatcher()
        val testScope = TestScope(dispatcher)
        val controller = TvPlayerHudController(scope = testScope, autoHideDelayMs = 4000L)

        assertFalse(controller.uiState.value.isVisible)
        assertFalse(controller.uiState.value.isPaused)
        assertEquals(0f, controller.uiState.value.alpha)
        assertNull(controller.uiState.value.metadata)

        controller.dispose()
    }

    @Test
    fun `test user interaction reveals HUD and auto-hides after exactly 4000ms`() = runTest {
        val controller = TvPlayerHudController(scope = this, autoHideDelayMs = 4000L)

        // 1. Initial wake-up consumes event
        val consumed = controller.onUserInteraction(isDpadNavigation = true)
        assertTrue(consumed, "Initial wake-up on hidden HUD must consume D-Pad event")
        assertTrue(controller.uiState.value.isVisible)
        assertEquals(1f, controller.uiState.value.alpha)

        // 2. Advance to 3999ms: Still visible
        advanceTimeBy(3999L)
        assertTrue(controller.uiState.value.isVisible, "HUD must remain visible before 4000ms timeout")

        // 3. Advance to 4001ms: Auto-hides
        advanceTimeBy(2L)
        assertFalse(controller.uiState.value.isVisible, "HUD must auto-hide after 4000ms of inactivity")
        assertEquals(0f, controller.uiState.value.alpha)

        controller.dispose()
    }

    @Test
    fun `test dpad navigation resets 4-second countdown and does not consume event when visible`() = runTest {
        val controller = TvPlayerHudController(scope = this, autoHideDelayMs = 4000L)

        // 1. Wake up HUD
        controller.onUserInteraction(isDpadNavigation = true)
        assertTrue(controller.uiState.value.isVisible)

        // 2. User interacts 2500ms into the countdown
        advanceTimeBy(2500L)
        assertTrue(controller.uiState.value.isVisible)

        val consumed = controller.onUserInteraction(isDpadNavigation = true)
        assertFalse(consumed, "Subsequent D-Pad navigation when visible must not consume event so controls receive focus")
        assertTrue(controller.uiState.value.isVisible)

        // 3. Advance past original 4000ms mark (to 5000ms total from start)
        advanceTimeBy(2500L)
        assertTrue(controller.uiState.value.isVisible, "HUD must still be visible because timer was reset at 2500ms")

        // 4. Advance to 6501ms total (4000ms after the reset at 2500ms)
        advanceTimeBy(1501L)
        assertFalse(controller.uiState.value.isVisible, "HUD must auto-hide 4000ms after the last user interaction")

        controller.dispose()
    }

    @Test
    fun `test pause persistence prevents auto-hide until playback resumes`() = runTest {
        val controller = TvPlayerHudController(scope = this, autoHideDelayMs = 4000L)

        // 1. Player pauses: HUD must appear and stay visible indefinitely
        controller.onPlayerStateChanged(PlayerState(isPaused = true, isPlaying = false))
        assertTrue(controller.uiState.value.isVisible)
        assertTrue(controller.uiState.value.isPaused)

        // Advance past 4000ms, 8000ms, 12000ms
        advanceTimeBy(12000L)
        assertTrue(controller.uiState.value.isVisible, "HUD must remain persistently visible while video is paused")

        // 2. Resume playback: Re-arms 4000ms auto-hide
        controller.onPlayerStateChanged(PlayerState(isPaused = false, isPlaying = true))
        assertFalse(controller.uiState.value.isPaused)
        assertTrue(controller.uiState.value.isVisible, "HUD should still be visible immediately after unpausing")

        // Advance to 3999ms after resume
        advanceTimeBy(3999L)
        assertTrue(controller.uiState.value.isVisible, "HUD must remain visible during 4s post-resume window")

        // Advance past 4000ms after resume
        advanceTimeBy(2L)
        assertFalse(controller.uiState.value.isVisible, "HUD must auto-hide 4000ms after playback resumes")

        controller.dispose()
    }

    @Test
    fun `test manual hideHud immediately dismisses HUD and cancels timer`() = runTest {
        val controller = TvPlayerHudController(scope = this, autoHideDelayMs = 4000L)

        controller.showHud()
        assertTrue(controller.uiState.value.isVisible)
        assertEquals(1f, controller.uiState.value.alpha)

        controller.hideHud()
        assertFalse(controller.uiState.value.isVisible)
        assertEquals(0f, controller.uiState.value.alpha)

        advanceTimeBy(5000L)
        assertFalse(controller.uiState.value.isVisible)

        controller.dispose()
    }

    @Test
    fun `test rapid concurrent navigation maintains monotonic token consistency`() = runTest {
        val controller = TvPlayerHudController(scope = this, autoHideDelayMs = 200L)

        controller.onUserInteraction(isDpadNavigation = true)

        // Dispatch 50 rapid interactions
        for (i in 1..50) {
            advanceTimeBy(10L)
            controller.onUserInteraction(isDpadNavigation = true)
            assertTrue(controller.uiState.value.isVisible, "HUD must remain visible during rapid navigation")
        }

        // Advance 190ms after last interaction: Still visible
        advanceTimeBy(190L)
        assertTrue(controller.uiState.value.isVisible)

        // Advance past 200ms from last interaction: Hides cleanly
        advanceTimeBy(20L)
        assertFalse(controller.uiState.value.isVisible)

        controller.dispose()
    }

    // =========================================================================
    // 4. Metadata Badge Text Formatting
    // =========================================================================

    @Test
    fun `test movie badge formatting with all fields present`() {
        val meta = PlayerHudMetadata(
            title = "Dune: Part Two",
            tags = listOf("Sci-Fi", "Adventure", "Action", "Drama", "Fantasy"), // > 3 tags
            year = 2024,
            durationMinutes = 166,
            imdbScore = 8.6,
            contentRating = "PG-13",
            isMovie = true
        )
        val formatted = meta.formatBadgeText()
        // Top 3 genres: "Sci-Fi, Adventure, Action"
        // Year: "2024"
        // Rating: "⭐ 8.6"
        // Duration: "2h 46m"
        // Content Rating: "PG-13"
        assertEquals("Sci-Fi, Adventure, Action • 2024 • ⭐ 8.6 • 2h 46m • PG-13", formatted)
    }

    @Test
    fun `test series badge formatting with season and episode numbers`() {
        val meta = PlayerHudMetadata(
            title = "Arcane",
            tags = listOf("Animation", "Action"),
            year = 2024,
            seasonNumber = 2,
            episodeNumber = 6,
            imdbScore = 9.0,
            contentRating = "TV-14",
            isMovie = false
        )
        val formatted = meta.formatBadgeText()
        assertEquals("Animation, Action • 2024 • S2:E6 • ⭐ 9.0 • TV-14", formatted)
    }

    @Test
    fun `test series badge formatting with season only or episode only`() {
        val seasonOnly = PlayerHudMetadata(
            title = "Fallout",
            seasonNumber = 1,
            episodeNumber = null,
            year = 2024,
            isMovie = false
        )
        assertEquals("2024 • S1", seasonOnly.formatBadgeText())

        val episodeOnly = PlayerHudMetadata(
            title = "Anime OVA",
            seasonNumber = null,
            episodeNumber = 4,
            isMovie = false
        )
        assertEquals("E4", episodeOnly.formatBadgeText())
    }

    @Test
    fun `test duration formatting for under 60 minutes and exact hour marks`() {
        val shortDoc = PlayerHudMetadata(
            title = "Short Film",
            durationMinutes = 45,
            isMovie = true
        )
        assertEquals("45m", shortDoc.formatBadgeText())

        val twoHours = PlayerHudMetadata(
            title = "Epic Movie",
            durationMinutes = 120,
            isMovie = true
        )
        assertEquals("2h 0m", twoHours.formatBadgeText())
    }

    @Test
    fun `test missing or zero-score metadata handles gracefully without empty bullets`() {
        val emptyMeta = PlayerHudMetadata(title = "Untitled")
        assertEquals("", emptyMeta.formatBadgeText())

        val zeroScoreMeta = PlayerHudMetadata(
            title = "Unrated Indie",
            year = 2023,
            imdbScore = 0.0,
            durationMinutes = 0,
            contentRating = ""
        )
        assertEquals("2023", zeroScoreMeta.formatBadgeText())
    }

    // =========================================================================
    // 5. SubStation Alpha (ASS) Script Generation & Sanitization
    // =========================================================================

    @Test
    fun `test buildAssScript generates valid ASS v4 script with 1080p resolution and styles`() {
        val client = MpvIpcClient(scope = ioScope!!)
        val renderer = MpvOsdRenderer(client)

        val meta = PlayerHudMetadata(
            title = "Cyberpunk: Edgerunners",
            tags = listOf("Anime", "Sci-Fi"),
            year = 2022,
            seasonNumber = 1,
            episodeNumber = 10,
            imdbScore = 8.3,
            contentRating = "TV-MA",
            plotOverview = "A street kid trying to survive in a technology and body modification-obsessed city of the future.",
            isMovie = false
        )

        val ass = renderer.buildAssScript(meta)

        // Script Info header
        assertTrue(ass.contains("PlayResX: 1920"))
        assertTrue(ass.contains("PlayResY: 1080"))
        assertTrue(ass.contains("ScaledBorderAndShadow: yes"))

        // Styles
        assertTrue(ass.contains("Style: ScrimBox"))
        assertTrue(ass.contains("Style: Title,Sans,44"))
        assertTrue(ass.contains("Style: Meta,Sans,22"))
        assertTrue(ass.contains("Style: Synopsis,Sans,24"))

        // Gradient bands (3 bands approximating the 680dp scrim)
        assertTrue(ass.contains("1a&H19&"), "Band 1 must use 90% black alpha (&H19&)")
        assertTrue(ass.contains("1a&H66&"), "Band 2 must use 60% black alpha (&H66&)")
        assertTrue(ass.contains("1a&HB3&"), "Band 3 must use 30% black alpha (&HB3&)")
        assertTrue(ass.contains("m 0 0 l 220 0 l 220 1080 l 0 1080"))

        // Title and badge placement
        assertTrue(ass.contains("{\\pos(64,380)}Cyberpunk: Edgerunners"))
        assertTrue(ass.contains("Anime, Sci-Fi • 2022 • S1:E10 • ⭐ 8.3 • TV-MA"))
        assertTrue(ass.contains("{\\pos(64,500)}"))
    }

    @Test
    fun `test sanitizeAssText escapes backslashes, braces, and line breaks`() {
        val client = MpvIpcClient(scope = ioScope!!)
        val renderer = MpvOsdRenderer(client)

        val maliciousText = "Hello {override} \\n Second Line \\"
        val sanitized = renderer.sanitizeAssText(maliciousText)

        assertEquals("Hello \\{override\\} \\\\n Second Line \\\\", sanitized)
    }

    @Test
    fun `test wrapAndSanitizeText wraps long synopsis and caps at max lines`() {
        val client = MpvIpcClient(scope = ioScope!!)
        val renderer = MpvOsdRenderer(client)

        val longText = "Word1 Word2 Word3 Word4 Word5 Word6 Word7 Word8 Word9 Word10 Word11 Word12 Word13 Word14 Word15"
        val wrapped = renderer.wrapAndSanitizeText(longText, lineLength = 20, maxLines = 3)

        val lineCount = wrapped.split("\\N").size
        assertTrue(lineCount in 1..3, "Wrapped synopsis must not exceed max lines (3), got $lineCount")
    }

    // =========================================================================
    // 6. Real AF_UNIX Domain Socket IPC Integration (Anti-Mock Standard)
    // =========================================================================

    @Test
    fun `test real Unix domain socket receives osd-overlay JSON-RPC commands`() = runBlocking {
        // 1. Bind real AF_UNIX domain socket on Linux filesystem
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
            bind(UnixDomainSocketAddress.of(socketPath))
            configureBlocking(true)
        }
        assertTrue(Files.exists(socketPath), "Unix domain socket file must exist on filesystem")

        // 2. Connect real MpvIpcClient
        val client = MpvIpcClient(scope = ioScope!!)
        ipcClient = client

        val acceptJob = ioScope!!.launch {
            val accepted = serverChannel!!.accept()
            acceptedClientChannel = accepted
            accepted.configureBlocking(true)

            // Read initial subscription commands
            val initBuf = ByteBuffer.allocate(4096)
            accepted.read(initBuf)
        }

        val connected = client.connect(socketPath)
        assertTrue(connected, "MpvIpcClient must connect successfully to Unix domain socket")
        acceptJob.join()

        // 3. Render metadata via MpvOsdRenderer
        val renderer = MpvOsdRenderer(client, overlayId = 42)
        val metadata = PlayerHudMetadata(
            title = "Oppenheimer",
            tags = listOf("Biography", "Drama", "History"),
            year = 2023,
            durationMinutes = 180,
            imdbScore = 8.9,
            contentRating = "R",
            plotOverview = "The story of American scientist J. Robert Oppenheimer."
        )

        renderer.render(metadata, isVisible = true)

        // 4. Read real socket buffer and verify JSON-RPC payload
        val readBuf = ByteBuffer.allocate(16384)
        val bytesRead = acceptedClientChannel!!.read(readBuf)
        assertTrue(bytesRead > 0, "Server must receive bytes from MpvIpcClient")

        val rawJson = String(readBuf.array(), 0, bytesRead, StandardCharsets.UTF_8)
        assertTrue(rawJson.contains("osd-overlay"), "Command must be osd-overlay")
        assertTrue(rawJson.contains("ass-events"), "Overlay format must be ass-events")
        assertTrue(rawJson.contains("42"), "Overlay ID must match 42")
        assertTrue(rawJson.contains("Oppenheimer"), "Payload must contain title")
        assertTrue(rawJson.contains("Biography, Drama, History"), "Payload must contain genres")
        assertTrue(rawJson.contains("⭐ 8.9"), "Payload must contain IMDb score")

        // 5. Clear overlay
        readBuf.clear()
        renderer.clearOverlay()

        val clearBytes = acceptedClientChannel!!.read(readBuf)
        assertTrue(clearBytes > 0, "Server must receive clear overlay command")
        val clearJson = String(readBuf.array(), 0, clearBytes, StandardCharsets.UTF_8)
        assertTrue(clearJson.contains("osd-overlay"))
        assertTrue(clearJson.contains("none"))
    }
}
