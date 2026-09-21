package com.lagradost.cloudstream3.ui.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class OfflineAndTorrentPlayerTest {

    @BeforeEach
    fun setup() {
        PlatformPaths.init()
        val context = Context()
        CloudStreamApp.context = context
        CommonActivity.setActivityInstance(Activity())
    }

    @Test
    fun testDownloadFileGeneratorPropertiesAndId() {
        val testUri = Uri.parse("file:///tmp/test_video.mp4")
        val extractorUri = ExtractorUri(
            uri = testUri,
            name = "Test Video",
            id = 12345
        )
        val generator = DownloadFileGenerator(listOf(extractorUri))

        assertFalse(generator.hasCache, "DownloadFileGenerator.hasCache must be false")
        assertFalse(generator.canSkipLoading, "DownloadFileGenerator.canSkipLoading must be false")
        assertEquals(12345, generator.getId(0))
        assertNull(generator.getId(1))
    }

    @Test
    fun testDownloadFileGeneratorLinkGeneration() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cs3_test_download_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val videoFile = File(tempDir, "Episode_01.mp4").apply { writeText("dummy video") }
            val subFile = File(tempDir, "Episode_01 English 1.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHello") }

            val extractorUri = ExtractorUri(
                uri = Uri.fromFile(videoFile),
                name = "Episode_01",
                basePath = tempDir.absolutePath,
                relativePath = "",
                displayName = "Episode_01.mp4",
                id = 999
            )
            val generator = DownloadFileGenerator(listOf(extractorUri))

            val links = mutableListOf<Pair<ExtractorLink?, ExtractorUri?>>()
            val subs = mutableListOf<SubtitleData>()

            val success = generator.generateLinks(
                clearCache = false,
                sourceTypes = setOf(ExtractorLinkType.VIDEO),
                callback = { links.add(it) },
                subtitleCallback = { subs.add(it) },
                offset = 0,
                isCasting = false
            )

            assertTrue(success)
            assertEquals(1, links.size)
            assertEquals(Uri.fromFile(videoFile), links[0].second?.uri)

            // Verify subtitle discovery
            assertTrue(subs.isNotEmpty(), "Matching subtitle should be discovered in the directory")
            val discoveredSub = subs.first()
            assertEquals(SubtitleOrigin.DOWNLOADED_FILE, discoveredSub.origin)
            assertTrue(discoveredSub.url.contains("Episode_01 English 1.srt"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testOfflinePlaybackHelperPlayUriAndIntent() {
        val activity = Activity()

        // 1. Play Magnet URI
        val magnetUri = Uri.parse("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Ubuntu")
        assertDoesNotThrow {
            OfflinePlaybackHelper.playUri(activity, magnetUri)
        }

        // 2. Play Local File URI
        val localUri = Uri.parse("file:///tmp/downloaded_sample.mp4")
        assertDoesNotThrow {
            OfflinePlaybackHelper.playUri(activity, localUri)
        }

        // 3. Play CloudStreamPackage Intent
        val intent = Intent().apply {
            val minimalLink = CloudStreamPackage.MinimalVideoLink(
                uri = null,
                url = "https://example.com/video.mp4",
                name = "Direct Link",
                mimeType = "video/mp4",
                headers = emptyMap(),
                quality = 1080
            )
            val minimalSub = CloudStreamPackage.MinimalSubtitleLink(
                url = "https://example.com/sub.vtt",
                mimeType = "text/vtt",
                name = "English",
                headers = emptyMap()
            )
            putExtra(CloudStreamPackage.LINKS_EXTRA, arrayOf(minimalLink.toJson()))
            putExtra(CloudStreamPackage.SUBTITLE_EXTRA, arrayOf(minimalSub.toJson()))
            putExtra(CloudStreamPackage.ID_EXTRA, 42)
            putExtra(CloudStreamPackage.POSITION_EXTRA, 10000L)
            putExtra(CloudStreamPackage.DURATION_EXTRA, 60000L)
        }

        val played = OfflinePlaybackHelper.playIntent(activity, intent)
        assertTrue(played, "playIntent should successfully process valid CloudStreamPackage intent")
    }

    @Test
    fun testTorrentStatusStreamUrl() {
        val status = Torrent.TorrentStatus(
            title = "Test Torrent",
            poster = "",
            data = null,
            timestamp = System.currentTimeMillis(),
            name = "Test Movie 1080p",
            hash = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2",
            stat = 3,
            statString = "Working",
            loadedSize = 1024L,
            torrentSize = 2048L,
            preloadedBytes = 0L,
            preloadSize = 0L,
            downloadSpeed = 50000.0,
            uploadSpeed = 1000.0,
            totalPeers = 20,
            pendingPeers = 5,
            activePeers = 15,
            connectedSeeders = 10,
            halfOpenPeers = 0,
            bytesWritten = 1024L,
            bytesWrittenData = 1024L,
            bytesRead = 1024L,
            bytesReadData = 1024L,
            bytesReadUsefulData = 1024L,
            chunksWritten = 10L,
            chunksRead = 10L,
            chunksReadUseful = 10L,
            chunksReadWasted = 0L,
            piecesDirtiedGood = 0L,
            piecesDirtiedBad = 0L,
            durationSeconds = 120.0,
            bitRate = "5000",
            fileStats = listOf(
                Torrent.TorrentFileStat(id = 0, path = "Movie.2024.1080p.mkv", length = 2048L)
            ),
            trackers = listOf("udp://tracker.opentrackr.org:1337/announce")
        )

        val streamUrl = status.streamUrl("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&index=0")
        assertTrue(streamUrl.contains("/stream/Movie.2024.1080p.mkv"), "Stream URL should include encoded filename")
        assertTrue(streamUrl.contains("link=c12fe1c06bba254a9dc9f519b335de7ece74f6d2"), "Stream URL should include hash")
        assertTrue(streamUrl.contains("index=0"), "Stream URL should include index")
        assertTrue(streamUrl.contains("play"), "Stream URL should include play flag")
    }

    @Test
    fun testTorrentDeleteAllFilesAndTransformErrorWhenOffline() {
        val deleted = Torrent.deleteAllFiles()
        assertTrue(deleted, "deleteAllFiles should succeed")

        runBlocking {
            val link = newExtractorLink(
                source = "TorrentProvider",
                name = "Test Torrent",
                url = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2",
                type = ExtractorLinkType.TORRENT
            )

            assertThrows(ErrorLoadingException::class.java) {
                runBlocking {
                    Torrent.transformLink(link)
                }
            }
        }
    }
}
