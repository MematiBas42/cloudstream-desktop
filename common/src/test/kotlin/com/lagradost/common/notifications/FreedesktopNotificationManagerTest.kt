package com.lagradost.common.notifications

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class FreedesktopNotificationManagerTest {

    private val testDispatcher = TestNotificationDispatcher()

    class TestNotificationDispatcher : DesktopNotificationDispatcher {
        val dispatched = mutableListOf<NotificationPayload>()
        override fun dispatch(payload: NotificationPayload): Boolean {
            dispatched.add(payload)
            return true
        }
    }

    @BeforeEach
    fun setUp() {
        testDispatcher.dispatched.clear()
        FreedesktopNotificationManager.dispatcher = testDispatcher
    }

    @AfterEach
    fun tearDown() {
        FreedesktopNotificationManager.dispatcher = LinuxCommandNotificationDispatcher()
    }

    @Test
    fun testShowNotificationDispatchesPayloadCorrectly() {
        val success = FreedesktopNotificationManager.showNotification(
            title = "Solo Leveling",
            body = "Episode 12 released!",
            icon = "cloudstream",
            timeoutMs = 7000,
            notificationId = 555,
            urgency = NotificationUrgency.CRITICAL,
            category = "media",
            appName = "CloudStream Desktop"
        )

        assertTrue(success, "showNotification must return true when dispatcher succeeds")
        assertEquals(1, testDispatcher.dispatched.size)

        val payload = testDispatcher.dispatched.first()
        assertEquals("CloudStream Desktop", payload.appName)
        assertEquals("Solo Leveling", payload.title)
        assertEquals("Episode 12 released!", payload.body)
        assertEquals("cloudstream", payload.icon)
        assertEquals(7000, payload.timeoutMs)
        assertEquals(555, payload.replacesId)
        assertEquals(NotificationUrgency.CRITICAL, payload.urgency)
        assertEquals("media", payload.category)
    }

    @Test
    fun testBlankNotificationReturnsFalse() {
        val success = FreedesktopNotificationManager.showNotification(
            title = "   ",
            body = ""
        )

        assertFalse(success, "showNotification must return false for blank notifications")
        assertEquals(0, testDispatcher.dispatched.size, "Nothing should be dispatched for blank notification")
    }

    @Test
    fun testPosterPathResolution(@TempDir tempDir: Path) {
        assertNull(FreedesktopNotificationManager.resolvePosterPath(null))
        assertNull(FreedesktopNotificationManager.resolvePosterPath(""))
        assertNull(FreedesktopNotificationManager.resolvePosterPath("   "))

        // Theme icon name
        assertEquals("video-x-generic", FreedesktopNotificationManager.resolvePosterPath("video-x-generic"))

        // Local file
        val localPoster = tempDir.resolve("poster.png").toFile().apply {
            writeText("dummy image data")
        }
        assertEquals(localPoster.absolutePath, FreedesktopNotificationManager.resolvePosterPath(localPoster.absolutePath))
        assertEquals(localPoster.absolutePath, FreedesktopNotificationManager.resolvePosterPath("file://${localPoster.absolutePath}"))
    }

    @Test
    fun testShowNotificationWithPoster(@TempDir tempDir: Path) = runBlocking {
        val localPoster = tempDir.resolve("poster.jpg").toFile().apply {
            writeText("image content")
        }

        val success = FreedesktopNotificationManager.showNotificationWithPoster(
            title = "Frieren",
            body = "New Episode",
            posterUrl = localPoster.absolutePath,
            notificationId = 101,
            urgency = NotificationUrgency.LOW
        )

        assertTrue(success)
        assertEquals(1, testDispatcher.dispatched.size)
        val payload = testDispatcher.dispatched.first()
        assertEquals("Frieren", payload.title)
        assertEquals("New Episode", payload.body)
        assertEquals(localPoster.absolutePath, payload.icon)
        assertEquals(101, payload.replacesId)
        assertEquals(NotificationUrgency.LOW, payload.urgency)
    }
}
