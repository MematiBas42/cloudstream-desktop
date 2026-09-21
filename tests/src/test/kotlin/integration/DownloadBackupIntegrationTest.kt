package integration

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.account.Account
import com.lagradost.common.account.AccountManagerDesktop
import com.lagradost.common.download.*
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Domain 24: Real POSIX Filesystem & Network Loopback Integration Test Suite
 * for Download Engine, HLS Recording & Backup/Restore Subsystem.
 *
 * Anti-mock architectural verification:
 * - Real JDK HttpServer loopback serving HTTP 206 Partial Content, Range headers, and M3U8/TS playlists.
 * - Real physical disk I/O on temp directories with Freedesktop XDG layout.
 * - Multi-chunk 10 MiB parallel assembly with SHA-256 bit-exact reassembly.
 * - HLS progressive stream recording with sequential TS segment appending.
 * - In-flight mirror failover upon network drops.
 * - Crash-recovery queue reconstruction via DataStore.
 * - Real ZIP archive export/import roundtrip with SHA-256 manifest and non-transferable key filtering.
 * - Upstream Android CloudStream single JSON backup ingestion.
 */
class DownloadBackupIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var testDataStoreFile: File
    private lateinit var testDownloadsDir: Path
    private var httpServer: HttpServer? = null
    private var serverPort: Int = 0

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        tempDir = Files.createTempDirectory("cs_domain24_test_${UUID.randomUUID()}")
        testDataStoreFile = tempDir.resolve("datastore.json").toFile()
        testDownloadsDir = tempDir.resolve("downloads")
        Files.createDirectories(testDownloadsDir)

        DesktopDataStore.customDataFile = testDataStoreFile
        DesktopDataStore.reload()
        AccountManagerDesktop.reset()
        WatchHistoryRepository.clearAll()
        LinuxDownloadStorage.customDownloadsDir = testDownloadsDir
        LinuxDownloadManager.reset()
    }

    @AfterEach
    fun tearDown() {
        LinuxDownloadManager.reset()
        WatchHistoryRepository.clearAll()
        httpServer?.stop(0)
        httpServer = null
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        LinuxDownloadStorage.customDownloadsDir = null
        tempDir.toFile().deleteRecursively()
    }

    private fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // Test Case 1: Multi-Connection Segmented Download & Bit-Exact Reassembly
    // =========================================================================
    @Test
    fun testMultiChunkDownloadAndReassembly() = runBlocking {
        // 22 MiB payload to trigger multiple 10 MiB chunk boundaries (chunk 1: 0-10MB, chunk 2: 10-20MB, chunk 3: 20-22MB)
        val payloadSize = 22 * 1024 * 1024
        val payload = ByteArray(payloadSize) { (it % 251).toByte() }
        val expectedSha = sha256(payload)

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/media.mp4") { exchange ->
                val method = exchange.requestMethod
                if (method.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("Content-Length", payloadSize.toString())
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                    return@createContext
                }

                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                    val parts = rangeSpec.split("-")
                    val start = parts[0].toInt()
                    val end = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toInt() else payloadSize - 1
                    val length = (end - start + 1).coerceAtLeast(0)

                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$payloadSize")
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.responseHeaders.add("Content-Length", length.toString())
                    exchange.sendResponseHeaders(206, length.toLong())

                    exchange.responseBody.write(payload, start, length)
                    exchange.responseBody.flush()
                    exchange.close()
                } else {
                    exchange.responseHeaders.add("Content-Length", payloadSize.toString())
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(200, payloadSize.toLong())
                    exchange.responseBody.write(payload)
                    exchange.responseBody.flush()
                    exchange.close()
                }
            }
            start()
        }
        serverPort = httpServer!!.address.port

        val targetFile = testDownloadsDir.resolve("Movies/TestMovie.mp4").toFile()
        val link = DownloadLink(
            url = "http://127.0.0.1:$serverPort/media.mp4",
            name = "Test CDN"
        )

        var lastReportedDownloaded = 0L
        val downloader = LinuxStreamDownloader(
            client = OkHttpClient(),
            targetFile = targetFile,
            link = link,
            parallelConnections = 3,
            chunkSize = 10L * 1024L * 1024L,
            onProgress = { down, _, _ -> lastReportedDownloaded = down }
        )

        val success = downloader.download()
        assertTrue(success, "Downloader should return true on successful multi-chunk download")
        assertTrue(targetFile.exists(), "Target file must exist on disk")
        assertFalse(downloader.partFile.exists(), "Temporary .part file must be cleaned up after commit")
        assertEquals(payloadSize.toLong(), targetFile.length(), "Target file size must match payload size")
        assertEquals(payloadSize.toLong(), lastReportedDownloaded, "Progress callback should report full payload")

        val downloadedBytes = targetFile.readBytes()
        val actualSha = sha256(downloadedBytes)
        assertEquals(expectedSha, actualSha, "Downloaded file SHA-256 must match payload bit-for-bit")
    }

    // =========================================================================
    // Test Case 2: Resume Capability from Interrupted .part Stream
    // =========================================================================
    @Test
    fun testResumeInterruptedPartStream() = runBlocking {
        val payloadSize = 15 * 1024 * 1024 // 15 MiB
        val payload = ByteArray(payloadSize) { ((it * 7) % 256).toByte() }
        val expectedSha = sha256(payload)

        val rangeRequests = mutableListOf<String>()

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            createContext("/resume.mp4") { exchange ->
                val method = exchange.requestMethod
                if (method.equals("HEAD", ignoreCase = true)) {
                    exchange.responseHeaders.add("Content-Length", payloadSize.toString())
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                    return@createContext
                }

                val rangeHeader = exchange.requestHeaders.getFirst("Range")
                if (rangeHeader != null) {
                    rangeRequests.add(rangeHeader)
                    val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                    val parts = rangeSpec.split("-")
                    val start = parts[0].toInt()
                    val end = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toInt() else payloadSize - 1
                    val length = (end - start + 1).coerceAtLeast(0)

                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$payloadSize")
                    exchange.responseHeaders.add("Content-Length", length.toString())
                    exchange.sendResponseHeaders(206, length.toLong())

                    exchange.responseBody.write(payload, start, length)
                    exchange.responseBody.flush()
                    exchange.close()
                } else {
                    exchange.responseHeaders.add("Content-Length", payloadSize.toString())
                    exchange.sendResponseHeaders(200, payloadSize.toLong())
                    exchange.responseBody.write(payload)
                    exchange.responseBody.flush()
                    exchange.close()
                }
            }
            start()
        }
        serverPort = httpServer!!.address.port

        val targetFile = testDownloadsDir.resolve("Movies/ResumeTest.mp4").toFile()
        val partFile = LinuxDownloadStorage.resolvePartFile(targetFile)

        // Simulate an interrupted download by pre-writing the first 5 MiB into the .part file
        val partialLength = 5 * 1024 * 1024
        partFile.parentFile.mkdirs()
        partFile.writeBytes(payload.copyOfRange(0, partialLength))
        assertEquals(partialLength.toLong(), partFile.length())

        val link = DownloadLink(url = "http://127.0.0.1:$serverPort/resume.mp4")
        val downloader = LinuxStreamDownloader(
            client = OkHttpClient(),
            targetFile = targetFile,
            link = link,
            parallelConnections = 3,
            chunkSize = 10L * 1024L * 1024L,
            onProgress = { _, _, _ -> }
        )

        val success = downloader.download()
        assertTrue(success, "Resume download must complete successfully")
        assertTrue(targetFile.exists(), "Target file must exist after resume commit")
        assertFalse(partFile.exists(), ".part file must be removed after successful commit")
        assertEquals(payloadSize.toLong(), targetFile.length(), "Total file length must equal full 15 MiB")

        // Verify that range request started from the 5 MiB offset
        assertTrue(
            rangeRequests.any { it.startsWith("bytes=$partialLength-") || it.startsWith("bytes=$partialLength") },
            "At least one HTTP range request must start at $partialLength offset: $rangeRequests"
        )

        assertEquals(expectedSha, sha256(targetFile.readBytes()), "Resumed file must match payload SHA-256")
    }

    // =========================================================================
    // Test Case 3: HLS Progressive Stream Demuxing & Sequential Segment Append
    // =========================================================================
    @Test
    fun testHlsDemuxingAndSequentialAssembly() = runBlocking {
        // Create 4 distinct TS segments
        val segmentCount = 4
        val segmentSize = 256 * 1024 // 256 KB each
        val segments = List(segmentCount) { idx ->
            ByteArray(segmentSize) { ((it + idx * 31) % 256).toByte() }
        }

        val allBytesStream = ByteArrayOutputStream()
        segments.forEach { allBytesStream.write(it) }
        val expectedHlsBytes = allBytesStream.toByteArray()
        val expectedSha = sha256(expectedHlsBytes)

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            // Master playlist
            createContext("/master.m3u8") { exchange ->
                val manifest = """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                    media_low.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
                    media_high.m3u8
                """.trimIndent()
                val bytes = manifest.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }

            // Media playlist (media_high.m3u8)
            createContext("/media_high.m3u8") { exchange ->
                val sb = StringBuilder()
                sb.append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-VERSION:3\n")
                for (i in 0 until segmentCount) {
                    sb.append("#EXTINF:4.0,\nseg_$i.ts\n")
                }
                sb.append("#EXT-X-ENDLIST\n")
                val bytes = sb.toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }

            // TS segment handler
            for (i in 0 until segmentCount) {
                createContext("/seg_$i.ts") { exchange ->
                    val segData = segments[i]
                    exchange.responseHeaders.add("Content-Type", "video/mp2t")
                    exchange.sendResponseHeaders(200, segData.size.toLong())
                    exchange.responseBody.write(segData)
                    exchange.close()
                }
            }
            start()
        }
        serverPort = httpServer!!.address.port

        val targetFile = testDownloadsDir.resolve("Movies/HlsMovie.mp4").toFile()
        val link = DownloadLink(
            url = "http://127.0.0.1:$serverPort/master.m3u8",
            name = "HLS Stream",
            type = "m3u8"
        )

        var reportedSegments = 0
        val hlsDownloader = LinuxHlsDownloader(
            client = OkHttpClient(),
            targetFile = targetFile,
            link = link,
            initialSegmentIndex = 0,
            onProgress = { _, _, _, currentSeg, _ -> reportedSegments = currentSeg }
        )

        val success = hlsDownloader.download()
        assertTrue(success, "HLS downloader should succeed")
        assertTrue(targetFile.exists(), "Final HLS file must exist on disk")
        assertFalse(hlsDownloader.partFile.exists(), ".part file must be cleaned up")
        assertEquals(segmentCount, reportedSegments, "All segments should be downloaded")
        assertEquals(expectedHlsBytes.size.toLong(), targetFile.length(), "File size must equal sum of segment sizes")
        assertEquals(expectedSha, sha256(targetFile.readBytes()), "Concatenated TS bytes must match expected SHA-256")
    }

    // =========================================================================
    // Test Case 4: In-Flight Mirror Failover
    // =========================================================================
    @Test
    fun testInFlightMirrorFailover() = runBlocking {
        val payloadSize = 2 * 1024 * 1024 // 2 MiB
        val payload = ByteArray(payloadSize) { ((it + 13) % 256).toByte() }
        val expectedSha = sha256(payload)

        val mirror1Attempts = AtomicInteger(0)
        val mirror2Attempts = AtomicInteger(0)

        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4)
            // Mirror 1: fails with HTTP 500
            createContext("/mirror1") { exchange ->
                mirror1Attempts.incrementAndGet()
                exchange.sendResponseHeaders(500, 0)
                exchange.close()
            }
            // Mirror 2: succeeds with full video
            createContext("/mirror2") { exchange ->
                mirror2Attempts.incrementAndGet()
                exchange.responseHeaders.add("Content-Length", payloadSize.toString())
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(200, payloadSize.toLong())
                exchange.responseBody.write(payload)
                exchange.close()
            }
            start()
        }
        serverPort = httpServer!!.address.port

        val item = DownloadItem(
            id = 4201,
            parentId = 100,
            titleName = "Mirror Test Show",
            episodeName = "Pilot",
            season = 1,
            episode = 1,
            links = listOf(
                DownloadLink(url = "http://127.0.0.1:$serverPort/mirror1", name = "Failing Mirror 1"),
                DownloadLink(url = "http://127.0.0.1:$serverPort/mirror2", name = "Healthy Mirror 2")
            ),
            type = "TvSeries"
        )

        LinuxDownloadManager.init()
        LinuxDownloadManager.enqueue(item)

        // Await completion with 10s timeout
        withTimeout(10_000) {
            while (true) {
                val progress = LinuxDownloadManager.progressMap.value[item.id]
                if (progress?.state == DownloadStatus.Completed) break
                if (progress?.state == DownloadStatus.Failed) {
                    org.junit.jupiter.api.Assertions.fail<Nothing>("Download failed with: ${progress.errorMessage}")
                }
                delay(100)
            }
        }

        // Assertions
        assertTrue(mirror1Attempts.get() >= 1, "Mirror 1 must have been attempted")
        assertTrue(mirror2Attempts.get() >= 1, "Mirror 2 must have been reached via failover")

        val completedInfo = LinuxDownloadManager.getCompletedFile(item.id)
        assertNotNull(completedInfo, "Completed metadata must be recorded in DataStore")
        val completedFile = File(completedInfo!!.absoluteFilePath)
        assertTrue(completedFile.exists(), "Completed video file must exist on disk")
        assertEquals(expectedSha, sha256(completedFile.readBytes()), "File bytes must match Mirror 2 payload")
    }

    // =========================================================================
    // Test Case 5: Crash-Recovery Queue Reconstruction
    // =========================================================================
    @Test
    fun testCrashRecoveryQueueReconstruction() {
        val item1 = DownloadItem(
            id = 501,
            parentId = 10,
            titleName = "Recovered Show 1",
            episodeName = "Ep 1",
            season = 1,
            episode = 1,
            links = listOf(DownloadLink(url = "http://example.com/1.mp4")),
            type = "Anime"
        )

        val item2 = DownloadItem(
            id = 502,
            parentId = 10,
            titleName = "Recovered Show 2",
            episodeName = "Ep 2",
            season = 1,
            episode = 2,
            links = listOf(DownloadLink(url = "http://example.com/2.mp4")),
            type = "Anime"
        )

        // Seed DataStore with unstarted item in QUEUE_KEY and in-flight item in RESUME_KEY
        DesktopDataStore.setKey(LinuxDownloadManager.QUEUE_KEY, listOf(item1))
        DesktopDataStore.setKey(
            "${LinuxDownloadManager.RESUME_KEY}/${item2.id}",
            DownloadResumePackage(item = item2, linkIndex = 0, bytesDownloaded = 1024L)
        )

        // Trigger crash recovery initialization
        LinuxDownloadManager.init()

        val currentQueue = LinuxDownloadManager.queue.value
        val queueIds = currentQueue.map { it.id }.toSet()

        assertTrue(queueIds.contains(501), "Item 1 must be recovered from queue key")
        assertTrue(queueIds.contains(502), "Item 2 must be recovered from resume packages")

        val p1 = LinuxDownloadManager.progressMap.value[501]
        val p2 = LinuxDownloadManager.progressMap.value[502]
        assertNotNull(p1, "Progress for item 1 must be initialized")
        assertNotNull(p2, "Progress for item 2 must be initialized")
    }

    // =========================================================================
    // Test Case 6: Real Backup ZIP Export & Import Roundtrip on Physical Disk
    // =========================================================================
    @Test
    fun testRealBackupZipRoundtrip() {
        // 1. Populate DesktopDataStore, WatchHistory, Bookmarks, and Accounts
        val history1 = WatchHistory(
            url = "https://stream.example.com/anime/frieren",
            parentId = "anime_frieren",
            episodeId = "ep_1",
            title = "Sousou no Frieren",
            season = 1,
            episode = 1,
            position = 120_000L,
            duration = 1_400_000L,
            isCompleted = false
        )
        val history2 = WatchHistory(
            url = "https://stream.example.com/movie/oppenheimer",
            parentId = "movie_oppenheimer",
            title = "Oppenheimer",
            position = 9_000_000L,
            duration = 10_000_000L,
            isCompleted = true
        )
        WatchHistoryRepository.setLastWatched(history1)
        WatchHistoryRepository.setLastWatched(history2)

        val bookmark1 = Bookmark(
            id = "bm_1",
            name = "Steins;Gate",
            url = "https://stream.example.com/anime/steins-gate",
            apiName = "AnimeProvider",
            posterUrl = "https://img.example.com/sg.jpg"
        )
        DesktopDataStore.addBookmark(bookmark1)

        val customAccount = AccountManagerDesktop.createAccount("Guest Profile", pin = "1234")

        val customSettings = AppSettings(
            hwdec = "vaapi",
            vo = "gpu",
            subSize = 52,
            subColor = "#FFCC00",
            dohProvider = "quad9"
        )
        DesktopDataStore.setSettings(customSettings)

        // Seed transferable and non-transferable data keys
        DesktopDataStore.setKey("user_pref_volume", "85")
        DesktopDataStore.setKey("anilist_token", "SECRET_OAUTH_TOKEN_DO_NOT_LEAK")
        DesktopDataStore.setKey("download_path_key", "/tmp/invalid/path")

        // 2. Export to physical ZIP file on disk
        val zipFile = tempDir.resolve("CS3_Backup_Test.cs3backup").toFile()
        FileOutputStream(zipFile).use { fos ->
            BackupRestoreManager.createZipBackup(fos)
        }

        assertTrue(zipFile.exists(), "Exported ZIP file must exist on disk")
        assertTrue(zipFile.length() > 0, "Exported ZIP file must not be empty")

        // 3. Inspect ZIP structure and verify SHA-256 manifest
        val zip = ZipFile(zipFile)
        val entryNames = zip.entries().asSequence().map { it.name }.toSet()

        assertTrue(entryNames.contains("manifest.json"), "Must contain manifest.json")
        assertTrue(entryNames.contains("history.json"), "Must contain history.json")
        assertTrue(entryNames.contains("bookmarks.json"), "Must contain bookmarks.json")
        assertTrue(entryNames.contains("accounts.json"), "Must contain accounts.json")
        assertTrue(entryNames.contains("datastore.json"), "Must contain datastore.json")
        assertTrue(entryNames.contains("settings.json"), "Must contain settings.json")
        assertTrue(entryNames.contains("backup.json"), "Must contain upstream backup.json")

        // Verify Manifest Checksums
        val manifestBytes = zip.getInputStream(zip.getEntry("manifest.json")).readBytes()
        val manifest = DesktopDataStore.mapper.readValue(manifestBytes, BackupRestoreManager.BackupManifest::class.java)
        assertEquals("Linux", manifest.os)

        manifest.checksums.forEach { (entryName, expectedChecksum) ->
            val entry = zip.getEntry(entryName)
            assertNotNull(entry, "Entry $entryName declared in manifest must exist in archive")
            val entryBytes = zip.getInputStream(entry).readBytes()
            val actualChecksum = BackupRestoreManager.sha256(entryBytes)
            assertEquals(expectedChecksum, actualChecksum, "Checksum mismatch for $entryName")
        }
        zip.close()

        // 4. Wipe DataStore, History, and Accounts
        testDataStoreFile.writeText("{}")
        DesktopDataStore.reload()
        WatchHistoryRepository.clearAll()
        AccountManagerDesktop.reset()

        assertEquals(0, WatchHistoryRepository.getAllWatchHistory().size, "History must be empty after wipe")
        assertEquals(0, DesktopDataStore.getBookmarks().size, "Bookmarks must be empty after wipe")
        assertNull(DesktopDataStore.getKey<String>("user_pref_volume"), "Custom data key must be empty after wipe")

        // 5. Restore from physical ZIP file
        val restoreSuccess = zipFile.inputStream().use { fis ->
            BackupRestoreManager.restore(fis)
        }
        assertTrue(restoreSuccess, "Restore operation must report success")

        // 6. Assert fidelity of restored data
        val restoredHistory = WatchHistoryRepository.getAllWatchHistory()
        assertEquals(2, restoredHistory.size, "Both watch history entries must be restored")
        assertTrue(restoredHistory.any { it.title == "Sousou no Frieren" && it.position == 120_000L })
        assertTrue(restoredHistory.any { it.title == "Oppenheimer" && it.isCompleted })

        val restoredBookmarks = DesktopDataStore.getBookmarks()
        assertEquals(1, restoredBookmarks.size, "Bookmark must be restored")
        assertEquals("Steins;Gate", restoredBookmarks[0].title)

        val restoredSettings = DesktopDataStore.getSettings()
        assertEquals("vaapi", restoredSettings.hwdec)
        assertEquals(52, restoredSettings.subSize)
        assertEquals("#FFCC00", restoredSettings.subColor)
        assertEquals("quad9", restoredSettings.dohProvider)

        val restoredAccounts = AccountManagerDesktop.getAccounts()
        assertTrue(restoredAccounts.any { it.name == "Guest Profile" }, "Guest Profile account must be restored")

        // 7. Non-transferable security invariants
        assertEquals("85", DesktopDataStore.getKey<String>("user_pref_volume"), "Transferable key must be restored")
        assertNull(DesktopDataStore.getKey<String>("anilist_token"), "Sensitive anilist_token must be stripped")
        assertNull(DesktopDataStore.getKey<String>("download_path_key"), "Device download_path_key must be stripped")
    }

    // =========================================================================
    // Test Case 7: Upstream Android CloudStream Single JSON Backup Ingestion
    // =========================================================================
    @Test
    fun testUpstreamAndroidJsonBackupIngestion() {
        val upstreamJson = """
            {
              "datastore": {
                "_String": {
                  "default/video_pos_dur": "{\"position\":120000,\"duration\":1400000}",
                  "default/result_watch_state": "{\"1234\":0}",
                  "anilist_token": "LEAKED_OAUTH_TOKEN",
                  "download_path_key": "/storage/emulated/0/Download"
                }
              },
              "settings": {
                "_String": {
                  "app_settings": "{\"hwdec\":\"vaapi\",\"vo\":\"gpu\",\"subSize\":48,\"subColor\":\"#00FF00\",\"dohProvider\":\"adguard\"}"
                }
              }
            }
        """.trimIndent()

        val inputStream = ByteArrayInputStream(upstreamJson.toByteArray(Charsets.UTF_8))
        val restored = BackupRestoreManager.restore(inputStream)
        assertTrue(restored, "Upstream JSON backup must be successfully ingested")

        // Transferable keys must be restored
        assertEquals("{\"position\":120000,\"duration\":1400000}", DesktopDataStore.getKey<String>("default/video_pos_dur"))
        assertEquals("{\"1234\":0}", DesktopDataStore.getKey<String>("default/result_watch_state"))

        // Settings must be restored
        val settings = DesktopDataStore.getSettings()
        assertEquals("vaapi", settings.hwdec)
        assertEquals("gpu", settings.vo)
        assertEquals(48, settings.subSize)
        assertEquals("#00FF00", settings.subColor)
        assertEquals("adguard", settings.dohProvider)

        // Non-transferable keys must NOT be present
        assertNull(DesktopDataStore.getKey<String>("anilist_token"), "anilist_token must be stripped from upstream backup")
        assertNull(DesktopDataStore.getKey<String>("download_path_key"), "download_path_key must be stripped from upstream backup")
    }
}
