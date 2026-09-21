package unit

import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.sync.*
import com.lagradost.common.sync.providers.AniListApi
import com.lagradost.common.sync.providers.LocalListSyncProvider
import com.lagradost.common.sync.providers.MALApi
import com.lagradost.common.sync.providers.SimklApi
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concrete anti-mock test suite for Domain 13 & 12:
 * Media Scrobblers, TV Device Code Auth & LocalList Sync.
 *
 * Verifies:
 * 1. 85% playback scrobble threshold calculation, duration floors, and session idempotency.
 * 2. High-concurrency single-flight Mutex race protection against duplicate token refreshes.
 * 3. Real HTTP socket RFC 8628 TV Device Code polling state transitions.
 * 4. LocalList offline bookmark persistence, library shelves, and outbox spooling.
 */
class MediaScrobblerSyncTest {

    private lateinit var tempDir: Path
    private lateinit var testDataFile: File
    private var testHttpServer: HttpServer? = null
    private var serverPort: Int = 0

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        tempDir = Files.createTempDirectory("cs_sync_test_${UUID.randomUUID()}")
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        SyncManager.init()
        SyncManager.clearAccounts()
        SyncManager.resetScrobbledSessions()
        SyncOutboxQueue.clear()
    }

    @AfterEach
    fun tearDown() {
        testHttpServer?.stop(0)
        testHttpServer = null

        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        tempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // Test 1: 85% Scrobble Threshold & Mathematical Rules
    // =========================================================================

    @Test
    fun test85PercentScrobbleThresholdCalculation() {
        // Rule 1: Duration floor - video < 120s must NEVER auto-scrobble (e.g. trailer/preview)
        val clipProgress = ScrobbleProgress(
            mediaId = "trailer_123",
            episodeNumber = 1,
            positionSec = 90.0,
            durationSec = 100.0, // 90% progress, but duration < 120s
            isCompleted = false
        )
        assertEquals(0.90, clipProgress.calculateRatio(), 0.001)
        assertFalse(clipProgress.isScrobbleThresholdReached(), "Playback duration under 120s must not reach threshold")
        assertFalse(clipProgress.shouldScrobble())

        // Rule 2: Normal episode under 85% threshold (e.g. 84.9%)
        val inProgress = ScrobbleProgress(
            mediaId = "anime_show_1",
            episodeNumber = 5,
            positionSec = 1018.0,
            durationSec = 1200.0, // 84.83%
            isCompleted = false
        )
        assertTrue(inProgress.calculateRatio() < 0.85)
        assertFalse(inProgress.isScrobbleThresholdReached(), "Progress under 85% must not trigger scrobble")

        // Rule 3: Exact 85% threshold (1020s / 1200s = 0.85)
        val thresholdProgress = ScrobbleProgress(
            mediaId = "anime_show_1",
            episodeNumber = 5,
            positionSec = 1020.0,
            durationSec = 1200.0,
            isCompleted = false
        )
        assertEquals(0.85, thresholdProgress.calculateRatio(), 0.001)
        assertTrue(thresholdProgress.isScrobbleThresholdReached(), "Exact 85% ratio must reach threshold")
        assertTrue(thresholdProgress.shouldScrobble())

        // Rule 4: Past 85% threshold (95%)
        val pastThreshold = ScrobbleProgress(
            mediaId = "anime_show_1",
            episodeNumber = 5,
            positionSec = 1140.0,
            durationSec = 1200.0,
            isCompleted = false
        )
        assertTrue(pastThreshold.isScrobbleThresholdReached())

        // Rule 5: Completion flag overrules percentage (e.g. truncated file)
        val earlyCompleted = ScrobbleProgress(
            mediaId = "anime_show_1",
            episodeNumber = 5,
            positionSec = 70.0,
            durationSec = 200.0, // 35%, but marked completed
            isCompleted = true
        )
        assertTrue(earlyCompleted.isScrobbleThresholdReached(), "isCompleted=true must trigger scrobble")
    }

    @Test
    fun testScrobblePipelineSessionDeduplicationAndIdempotency() = runBlocking(Dispatchers.IO) {
        val showId = "show_frieren_${UUID.randomUUID().toString().take(6)}"

        // Pre-create bookmark in LocalList
        val initialEntry = SyncEntry(
            id = showId,
            name = "Frieren: Beyond Journey's End",
            status = SyncWatchType.WATCHING,
            watchedEpisodes = 3,
            totalEpisodes = 28
        )
        SyncManager.localListProvider.saveBookmark(initialEntry)

        // Event 1: Playback at 50% (600s / 1200s) -> Should NOT scrobble
        val progress50 = ScrobbleProgress(
            mediaId = showId,
            episodeNumber = 4,
            seasonNumber = 1,
            positionSec = 600.0,
            durationSec = 1200.0
        )
        val result50 = SyncManager.processPlaybackProgress(progress50)
        assertFalse(result50, "50% progress must not scrobble")
        assertEquals(3, SyncManager.localListProvider.getBookmark(showId)?.watchedEpisodes)

        // Event 2: Playback crosses 85% (1050s / 1200s = 87.5%) -> MUST scrobble
        val progress85 = ScrobbleProgress(
            mediaId = showId,
            episodeNumber = 4,
            seasonNumber = 1,
            positionSec = 1050.0,
            durationSec = 1200.0
        )
        val result85 = SyncManager.processPlaybackProgress(progress85)
        assertTrue(result85, "Crossing 85% must trigger scrobble successfully")

        // Assert local bookmark updated to episode 4
        val updatedBookmark = SyncManager.localListProvider.getBookmark(showId)
        assertNotNull(updatedBookmark)
        assertEquals(4, updatedBookmark?.watchedEpisodes)
        assertEquals(SyncWatchType.WATCHING, updatedBookmark?.status)

        // Event 3: User continues watching to 95% in the same session -> Must be DEDUPLICATED
        val progress95 = ScrobbleProgress(
            mediaId = showId,
            episodeNumber = 4,
            seasonNumber = 1,
            positionSec = 1140.0,
            durationSec = 1200.0
        )
        val result95 = SyncManager.processPlaybackProgress(progress95)
        assertFalse(result95, "Repeated progress in same session must be deduplicated via idempotency guard")

        // Event 4: User rewinds back to 100s -> Must be DEDUPLICATED
        val progressRewind = ScrobbleProgress(
            mediaId = showId,
            episodeNumber = 4,
            seasonNumber = 1,
            positionSec = 100.0,
            durationSec = 1200.0
        )
        val resultRewind = SyncManager.processPlaybackProgress(progressRewind)
        assertFalse(resultRewind, "Rewind in same session must not re-trigger scrobble")

        // Event 5: Final episode (28 of 28) reached -> Status must transition to COMPLETED
        val progressFinal = ScrobbleProgress(
            mediaId = showId,
            episodeNumber = 28,
            seasonNumber = 1,
            positionSec = 1100.0,
            durationSec = 1200.0
        )
        val resultFinal = SyncManager.processPlaybackProgress(progressFinal)
        assertTrue(resultFinal, "Final episode scrobble must succeed")

        val completedBookmark = SyncManager.localListProvider.getBookmark(showId)
        assertEquals(28, completedBookmark?.watchedEpisodes)
        assertEquals(SyncWatchType.COMPLETED, completedBookmark?.status)
    }

    // =========================================================================
    // Test 2: Token Refresh Single-Flight Mutex Race Protection
    // =========================================================================

    @Test
    fun testSingleFlightTokenRefreshRaceProtection() = runBlocking(Dispatchers.IO) {
        // Setup: Save an account with an expired access token
        val initialAccount = SyncAccount(
            id = "mal_user_42",
            username = "OtakuTester",
            providerPrefix = "mal",
            accessToken = "expired_old_token_111",
            refreshToken = "refresh_token_valid_xyz",
            expiresAtSec = (System.currentTimeMillis() / 1000L) - 300 // expired 5 mins ago
        )
        SyncManager.saveAccount(initialAccount)

        assertTrue(initialAccount.isTokenExpired(), "Initial token must report expired")

        val refreshCounter = AtomicInteger(0)
        val expectedNewToken = "renewed_access_token_999"

        // Mock-free real refresher function that simulates network delay
        val simulatedNetworkRefresher: suspend (SyncAccount) -> SyncAccount? = { oldAccount ->
            refreshCounter.incrementAndGet()
            delay(80) // Simulate HTTP round-trip latency under high concurrency
            oldAccount.copy(
                accessToken = expectedNewToken,
                refreshToken = "new_refresh_token_abc",
                expiresAtSec = (System.currentTimeMillis() / 1000L) + 3600
            )
        }

        // Action: Spawn 20 concurrent coroutines invoking getValidTokenWithMutex simultaneously
        val concurrencyCount = 20
        val deferredTokens = (1..concurrencyCount).map {
            async(Dispatchers.IO) {
                SyncManager.getValidTokenWithMutex("mal", simulatedNetworkRefresher)
            }
        }

        val results = deferredTokens.awaitAll()

        // Invariant 1: Exactly ONE refresh HTTP request / execution must have occurred
        assertEquals(1, refreshCounter.get(), "Single-Flight Mutex MUST execute refresh operation exactly once")

        // Invariant 2: ALL 20 concurrent callers must receive the renewed token
        assertEquals(concurrencyCount, results.size)
        results.forEach { token ->
            assertEquals(expectedNewToken, token, "All concurrent callers must receive the renewed token")
        }

        // Invariant 3: The account in storage must now hold the renewed token
        val storedAccount = SyncManager.getAccount("mal")
        assertNotNull(storedAccount)
        assertEquals(expectedNewToken, storedAccount?.accessToken)
        assertFalse(storedAccount?.isTokenExpired() ?: true, "Stored account token must now be valid")

        // Invariant 4: Subsequent immediate calls do not invoke refresher again
        val immediateToken = SyncManager.getValidTokenWithMutex("mal", simulatedNetworkRefresher)
        assertEquals(expectedNewToken, immediateToken)
        assertEquals(1, refreshCounter.get(), "Subsequent calls must not re-trigger refresh")
    }

    // =========================================================================
    // Test 3: RFC 8628 TV Device Code Polling State Transitions
    // =========================================================================

    @Test
    fun testRfc8628DeviceCodePollingStateTransitions() = runBlocking(Dispatchers.IO) {
        // Setup: Start real loopback HTTP server to verify RFC 8628 / Simkl PIN polling
        val pollingAttempt = AtomicInteger(0)

        testHttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/oauth/pin") { exchange ->
                handlePinAuth(exchange)
            }
            createContext("/oauth/pin/TEST-PIN-1234") { exchange ->
                handlePinPoll(exchange, pollingAttempt.incrementAndGet())
            }
            createContext("/users/settings") { exchange ->
                handleUserSettings(exchange)
            }
            start()
        }
        serverPort = testHttpServer!!.address.port

        val simkl = SimklApi(
            baseUrl = "http://127.0.0.1:$serverPort",
            clientId = "test_client_id"
        )

        // Step 1: Request TV device code
        val codeResponse = simkl.requestDeviceCode()
        assertEquals("TEST-PIN-1234", codeResponse.userCode)
        assertEquals("dev_code_9999", codeResponse.deviceCode)
        assertEquals("https://simkl.com/pin", codeResponse.verificationUrl)
        assertEquals(5, codeResponse.interval)

        // Step 2: Poll 1 -> returns authorization_pending
        val poll1 = simkl.pollDeviceCode("TEST-PIN-1234")
        assertTrue(poll1 is DevicePollingResult.Pending, "Poll 1 must report Pending")

        // Step 3: Poll 2 -> returns slow_down
        val poll2 = simkl.pollDeviceCode("TEST-PIN-1234")
        assertTrue(poll2 is DevicePollingResult.SlowDown, "Poll 2 must report SlowDown")

        // Step 4: Poll 3 -> returns 200 OK + access token
        val poll3 = simkl.pollDeviceCode("TEST-PIN-1234")
        assertTrue(poll3 is DevicePollingResult.TokenReceived, "Poll 3 must report TokenReceived")
        val receivedToken = (poll3 as DevicePollingResult.TokenReceived).accessToken
        assertEquals("simkl_live_access_token_888", receivedToken)

        // Step 5: User profile fetch and account binding
        val profile = simkl.fetchUserProfile(receivedToken)
        assertEquals("SimklTVTester", profile.username)
        assertEquals("45124", profile.id)

        // Step 6: Complete device auth in SyncManager
        SyncManager.saveAccount(profile)
        assertTrue(SyncManager.isAccountConnected("simkl"))
        assertEquals("SimklTVTester", SyncManager.getAccount("simkl")?.username)
    }

    private fun handlePinAuth(exchange: HttpExchange) {
        val respJson = """
            {
                "result": "OK",
                "device_code": "dev_code_9999",
                "user_code": "TEST-PIN-1234",
                "verification_url": "https://simkl.com/pin",
                "expires_in": 900,
                "interval": 5
            }
        """.trimIndent()
        sendJsonResponse(exchange, 200, respJson)
    }

    private fun handlePinPoll(exchange: HttpExchange, attempt: Int) {
        val respJson = when (attempt) {
            1 -> """{"result": "waiting", "message": "authorization_pending"}"""
            2 -> """{"result": "waiting", "message": "slow_down"}"""
            else -> """{"result": "OK", "access_token": "simkl_live_access_token_888"}"""
        }
        sendJsonResponse(exchange, 200, respJson)
    }

    private fun handleUserSettings(exchange: HttpExchange) {
        val authHeader = exchange.requestHeaders.getFirst("Authorization")
        if (authHeader != "Bearer simkl_live_access_token_888") {
            sendJsonResponse(exchange, 401, """{"error": "Unauthorized"}""")
            return
        }
        val respJson = """
            {
                "user": {
                    "name": "SimklTVTester",
                    "avatar": "https://simkl.in/avatars/45124.jpg"
                },
                "account": {
                    "id": 45124
                }
            }
        """.trimIndent()
        sendJsonResponse(exchange, 200, respJson)
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // =========================================================================
    // Test 4: LocalList Persistence, Categorized Shelves, & Outbox Spool
    // =========================================================================

    @Test
    fun testLocalListPersistenceAndLibraryShelves() = runBlocking(Dispatchers.IO) {
        val local = LocalListSyncProvider()

        // Create bookmarks across different categories
        val items = listOf(
            SyncEntry("id_1", "Attack on Titan", status = SyncWatchType.COMPLETED, watchedEpisodes = 25, score = 10),
            SyncEntry("id_2", "Steins;Gate", status = SyncWatchType.WATCHING, watchedEpisodes = 12, totalEpisodes = 24),
            SyncEntry("id_3", "Solo Leveling", status = SyncWatchType.PLANTOWATCH),
            SyncEntry("id_4", "Cyberpunk", status = SyncWatchType.ONHOLD, watchedEpisodes = 4),
            SyncEntry("id_5", "Berserk 2016", status = SyncWatchType.DROPPED, watchedEpisodes = 2),
            SyncEntry("id_6", "Hunter x Hunter", status = SyncWatchType.WATCHING, watchedEpisodes = 148, isFavorite = true)
        )

        items.forEach { local.saveBookmark(it) }

        // Invariant 1: Physical disk reload proves persistence
        val freshLocal = LocalListSyncProvider()
        val readSteinsGate = freshLocal.getBookmark("id_2")
        assertNotNull(readSteinsGate, "Steins;Gate must be persisted on disk")
        assertEquals(SyncWatchType.WATCHING, readSteinsGate?.status)
        assertEquals(12, readSteinsGate?.watchedEpisodes)
        assertEquals(24, readSteinsGate?.totalEpisodes)

        // Invariant 2: Library shelf categorization
        val library = freshLocal.library()
        assertNotNull(library)

        val watchingShelf = library.allLibraryLists.first { it.name == "Watching" }
        val completedShelf = library.allLibraryLists.first { it.name == "Completed" }
        val planToWatchShelf = library.allLibraryLists.first { it.name == "Plan to Watch" }
        val onHoldShelf = library.allLibraryLists.first { it.name == "On Hold" }
        val droppedShelf = library.allLibraryLists.first { it.name == "Dropped" }
        val favoritesShelf = library.allLibraryLists.first { it.name == "Favorites" }

        assertEquals(2, watchingShelf.items.size, "Watching shelf must have 2 items")
        assertEquals(1, completedShelf.items.size)
        assertEquals(1, planToWatchShelf.items.size)
        assertEquals(1, onHoldShelf.items.size)
        assertEquals(1, droppedShelf.items.size)
        assertEquals(1, favoritesShelf.items.size)
        assertEquals("Hunter x Hunter", favoritesShelf.items.first().name)

        // Invariant 3: Fuzzy search
        val searchResults = freshLocal.search("titan")
        assertEquals(1, searchResults.size)
        assertEquals("Attack on Titan", searchResults.first().name)

        // Invariant 4: Status mutation to NONE removes item unless favorite
        freshLocal.updateStatus("id_5", SyncStatus(status = SyncWatchType.NONE))
        assertNull(freshLocal.getBookmark("id_5"), "Non-favorite item set to NONE must be deleted")

        // Setting favorite item to NONE keeps the bookmark
        freshLocal.updateStatus("id_6", SyncStatus(status = SyncWatchType.NONE))
        val keptFavorite = freshLocal.getBookmark("id_6")
        assertNotNull(keptFavorite, "Favorite item must be kept even if watch status is NONE")
        assertEquals(SyncWatchType.NONE, keptFavorite?.status)
        assertTrue(keptFavorite?.isFavorite ?: false)
    }

    @Test
    fun testOfflineOutboxSpoolQueuePersistenceAndFlush() = runBlocking(Dispatchers.IO) {
        // Enqueue 2 offline scrobbles
        val queued1 = QueuedScrobble(
            providerPrefix = "simkl",
            mediaSyncId = "45124",
            episodeNumber = 12,
            seasonNumber = 1,
            status = SyncWatchType.WATCHING
        )
        val queued2 = QueuedScrobble(
            providerPrefix = "mal",
            mediaSyncId = "16498",
            episodeNumber = 24,
            status = SyncWatchType.COMPLETED
        )

        SyncOutboxQueue.enqueue(queued1)
        SyncOutboxQueue.enqueue(queued2)

        // Verify disk persistence
        val pending = SyncOutboxQueue.getAll()
        assertEquals(2, pending.size)
        assertTrue(pending.any { it.mediaSyncId == "45124" })
        assertTrue(pending.any { it.mediaSyncId == "16498" })

        // Register dummy provider that successfully receives flush
        var flushedSimkl = false
        val dummySimkl = object : SyncAPI() {
            override val name = "Simkl Dummy"
            override val idPrefix = "simkl"
            override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean {
                if (id == "45124") {
                    flushedSimkl = true
                    return true
                }
                return false
            }
        }
        SyncManager.registerProvider(dummySimkl)

        // Save a dummy active token so flush can proceed
        SyncManager.saveAccount(
            SyncAccount(
                id = "user_simkl",
                username = "Simkl User",
                providerPrefix = "simkl",
                accessToken = "valid_flush_token"
            )
        )

        // Flush outbox
        val flushedCount = SyncManager.flushOutbox()
        assertTrue(flushedCount >= 1, "At least 1 mutation must be flushed")
        assertTrue(flushedSimkl, "Simkl provider must receive the queued mutation")

        // Mutation 1 must be removed, mutation 2 (mal without token) kept
        val remaining = SyncOutboxQueue.getAll()
        assertFalse(remaining.any { it.mediaSyncId == "45124" }, "Successfully flushed item must be removed from queue")
        assertTrue(remaining.any { it.mediaSyncId == "16498" }, "Unflushed item must remain in queue")
    }

    // =========================================================================
    // Test 5: AniList GraphQL Mutation & MAL REST Scrobble Payloads
    // =========================================================================

    @Test
    fun testAniListGraphQLMutationPayload() = runBlocking(Dispatchers.IO) {
        var capturedAuthHeader: String? = null
        var capturedBody: String? = null

        testHttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
                capturedBody = exchange.requestBody.reader().readText()
                val respJson = """
                    {
                        "data": {
                            "SaveMediaListEntry": {
                                "id": 999,
                                "mediaId": 16498,
                                "status": "CURRENT",
                                "progress": 12,
                                "score": 85
                            }
                        }
                    }
                """.trimIndent()
                sendJsonResponse(exchange, 200, respJson)
            }
            start()
        }
        serverPort = testHttpServer!!.address.port

        val anilist = AniListApi(
            graphqlUrl = "http://127.0.0.1:$serverPort/"
        )

        val success = anilist.scrobble(
            accessToken = "anilist_secret_token_777",
            mediaId = 16498,
            progress = 12,
            status = SyncWatchType.WATCHING,
            scoreRaw = 85
        )

        assertTrue(success, "AniList scrobble mutation must succeed")
        assertEquals("Bearer anilist_secret_token_777", capturedAuthHeader)
        assertNotNull(capturedBody)

        val json = DesktopDataStore.mapper.readTree(capturedBody!!)
        assertTrue(json.has("query"))
        assertTrue(json.path("query").asText().contains("SaveMediaListEntry"))

        val vars = json.path("variables")
        assertEquals(16498, vars.path("mediaId").asInt())
        assertEquals("CURRENT", vars.path("status").asText())
        assertEquals(12, vars.path("progress").asInt())
        assertEquals(85, vars.path("scoreRaw").asInt())
    }

    @Test
    fun testMalPutStatusPayload() = runBlocking(Dispatchers.IO) {
        var capturedMethod: String? = null
        var capturedPath: String? = null
        var capturedAuthHeader: String? = null
        var capturedBody: String? = null

        testHttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/anime/16498/my_list_status") { exchange ->
                capturedMethod = exchange.requestMethod
                capturedPath = exchange.requestURI.path
                capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
                capturedBody = exchange.requestBody.reader().readText()
                val respJson = """{"status": "watching", "num_watched_episodes": 12, "score": 8}"""
                sendJsonResponse(exchange, 200, respJson)
            }
            start()
        }
        serverPort = testHttpServer!!.address.port

        val mal = MALApi(
            apiBaseUrl = "http://127.0.0.1:$serverPort"
        )

        val success = mal.scrobble(
            accessToken = "mal_secret_token_555",
            animeId = "16498",
            numWatchedEpisodes = 12,
            status = SyncWatchType.WATCHING,
            score = 8
        )

        assertTrue(success, "MAL scrobble must succeed")
        assertEquals("PUT", capturedMethod)
        assertEquals("/anime/16498/my_list_status", capturedPath)
        assertEquals("Bearer mal_secret_token_555", capturedAuthHeader)
        assertNotNull(capturedBody)

        val params = capturedBody!!.split("&").associate {
            val parts = it.split("=")
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        assertEquals("watching", params["status"])
        assertEquals("12", params["num_watched_episodes"])
        assertEquals("8", params["score"])
    }
}
