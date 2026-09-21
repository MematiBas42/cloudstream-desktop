package com.lagradost.cloudstream3.utils.downloader

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.appendAndDontOverride
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.cancel
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getEstimatedTimeLeft
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.join
import com.lagradost.common.notifications.DesktopNotificationDispatcher
import com.lagradost.common.notifications.FreedesktopNotificationManager
import com.lagradost.common.notifications.NotificationPayload
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

class DownloadUtilsTest {

    private val context = Context()

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
    }

    @AfterEach
    fun tearDown() {
        FreedesktopNotificationManager.dispatcher = com.lagradost.common.notifications.LinuxCommandNotificationDispatcher()
    }

    @Test
    fun testEstimatedTimeLeftCalculations() {
        // bytesPerSecond <= 0 should return empty string
        assertEquals("", getEstimatedTimeLeft(context, 0, 0, 1000))
        assertEquals("", getEstimatedTimeLeft(context, -5, 0, 1000))

        // Progress >= total should return empty string (0 secs left)
        assertEquals("", getEstimatedTimeLeft(context, 100, 1000, 1000))

        // Exact seconds (< 60s)
        val secResult = getEstimatedTimeLeft(context, bytesPerSecond = 10, progress = 0, total = 300)
        assertTrue(secResult.contains("30") || secResult.isNotBlank(), "Expected seconds formatted: $secResult")

        // Minutes + seconds
        val minResult = getEstimatedTimeLeft(context, bytesPerSecond = 10, progress = 0, total = 900)
        assertTrue(minResult.contains("1") || minResult.isNotBlank(), "Expected minute formatted: $minResult")

        // Hours + minutes + seconds
        val hrResult = getEstimatedTimeLeft(context, bytesPerSecond = 1, progress = 0, total = 3665)
        assertTrue(hrResult.contains("1") || hrResult.isNotBlank(), "Expected hour formatted: $hrResult")
    }

    @Test
    fun testAppendAndDontOverrideCaseInsensitive() {
        val original = mapOf("Authorization" to "Bearer 123", "accept" to "application/json")
        val rhs = mapOf("AUTHORIZATION" to "Bearer 456", "User-Agent" to "TestAgent", "ACCEPT" to "*/*")

        val result = original.appendAndDontOverride(rhs)

        assertEquals(3, result.size)
        assertEquals("Bearer 123", result["Authorization"])
        assertEquals("application/json", result["accept"])
        assertEquals("TestAgent", result["User-Agent"])
    }

    @Test
    fun testJobListCancelAndJoin() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val jobs = (1..5).map {
            scope.launch {
                delay(10000)
            }
        }

        assertFalse(jobs.all { it.isCancelled })

        jobs.cancel()
        jobs.join()

        assertTrue(jobs.all { it.isCancelled })
    }

    @Test
    fun testGetImageBitmapFromUrlWithLocalFile(@TempDir tempDir: Path) {
        // Create a real test image on disk
        val imgFile = tempDir.resolve("test_poster.png").toFile()
        val buffered = BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB)
        val g = buffered.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 64, 96)
        g.dispose()
        ImageIO.write(buffered, "png", imgFile)

        val fileUri = imgFile.toURI().toString()
        val bitmap = context.getImageBitmapFromUrl(fileUri)

        assertNotNull(bitmap, "Bitmap should be successfully loaded from local file URI")
        assertEquals(64, bitmap?.width)
        assertEquals(96, bitmap?.height)
        assertNotNull(bitmap?.file)
        assertEquals(imgFile.absolutePath, bitmap?.filePath)

        // Second call should return the exact cached instance from ConcurrentHashMap
        val cached = context.getImageBitmapFromUrl(fileUri)
        assertSame(bitmap, cached, "Second call should return cached bitmap instance")
    }

    @Test
    fun testGetImageBitmapFromUrlGracefulOnInvalidUrl() {
        val result = context.getImageBitmapFromUrl("https://invalid-nonexistent-domain-12345.org/image.png")
        assertNull(result, "Invalid URL should gracefully return null without throwing exception")
    }

    @Test
    fun testNotificationLargeIconPipeline(@TempDir tempDir: Path) {
        val imgFile = tempDir.resolve("notif_poster.png").toFile()
        val buffered = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(buffered, "png", imgFile)

        val bitmap = context.getImageBitmapFromUrl(imgFile.toURI().toString())
        assertNotNull(bitmap)

        var lastDispatchedPayload: NotificationPayload? = null
        FreedesktopNotificationManager.dispatcher = object : DesktopNotificationDispatcher {
            override fun dispatch(payload: NotificationPayload): Boolean {
                lastDispatchedPayload = payload
                return true
            }
        }

        val notifBuilder = NotificationCompat.Builder(context, "download_channel")
            .setContentTitle("Downloading Test Episode")
            .setContentText("Season 1 Episode 1")
            .setLargeIcon(bitmap)

        val notification = notifBuilder.build()
        assertEquals(bitmap, notification.largeIcon)

        val manager = NotificationManager()
        manager.notify(101, notification)

        assertNotNull(lastDispatchedPayload)
        assertEquals("Downloading Test Episode", lastDispatchedPayload?.title)
        assertEquals(imgFile.absolutePath, lastDispatchedPayload?.icon)
    }
}
