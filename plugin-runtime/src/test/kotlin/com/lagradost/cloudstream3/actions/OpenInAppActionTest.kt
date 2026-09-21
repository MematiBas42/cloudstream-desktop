package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenInAppActionTest {

    private class TestAppAction : OpenInAppAction(
        appName = txt("TestApp"),
        packageName = "echo",
        intentClass = "org.example.PlayerActivity"
    ) {
        var putExtraCalled = false
        var onResultCalled = false

        override suspend fun putExtra(
            context: Context,
            intent: Intent,
            video: ResultEpisode,
            result: LinkLoadingResult,
            index: Int?
        ) {
            putExtraCalled = true
            intent.putExtra("test_extra", 123)
        }

        override fun onResult(activity: Activity, intent: Intent?) {
            onResultCalled = true
        }
    }

    @Test
    fun testOpenInAppActionProperties() {
        val action = TestAppAction()
        assertEquals(true, action.isPlayer)
        assertEquals("echo", action.packageName)
        assertEquals("org.example.PlayerActivity", action.intentClass)
        assertNotNull(action.name)
    }

    @Test
    fun testMakeTempM3U8IntentSingleLink() {
        val context = Context()
        val intent = Intent()
        val link = ExtractorLink(
            source = "Test",
            name = "Stream 1",
            url = "https://example.com/stream.mp4",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val result = LinkLoadingResult(
            links = listOf(link),
            subs = emptyList(),
            syncData = hashMapOf()
        )

        makeTempM3U8Intent(context, intent, result)
        assertEquals("video/*", intent.type)
        assertEquals("https://example.com/stream.mp4", intent.data?.toString())
    }

    @Test
    fun testMakeTempM3U8IntentMultiLink() {
        val context = Context()
        val intent = Intent()
        val link1 = ExtractorLink(
            source = "Test1",
            name = "Stream 1",
            url = "https://example.com/stream1.mp4",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val link2 = ExtractorLink(
            source = "Test2",
            name = "Stream 2",
            url = "https://example.com/stream2.mp4",
            referer = "",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )
        val result = LinkLoadingResult(
            links = listOf(link1, link2),
            subs = emptyList(),
            syncData = hashMapOf()
        )

        makeTempM3U8Intent(context, intent, result)
        assertEquals("application/x-mpegURL", intent.type)
        assertNotNull(intent.data)
        assertTrue(intent.data.toString().endsWith(".m3u8"))
    }

    @Test
    fun testRunActionAndLaunch() = runBlocking {
        val action = TestAppAction()
        val context = Context()
        val episode = ResultEpisode(
            headerName = "Test Show",
            name = "Episode 1",
            poster = "",
            season = 1,
            episode = 1,
            data = "data1",
            apiName = "TestProvider",
            id = 101,
            index = 0
        )
        val link = ExtractorLink(
            source = "Test",
            name = "Stream",
            url = "https://example.com/video.mp4",
            referer = "",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val result = LinkLoadingResult(
            links = listOf(link),
            subs = emptyList(),
            syncData = hashMapOf()
        )

        action.runAction(context, episode, result, 0)
        assertTrue(action.putExtraCalled)

        val lastOpened = getKey<ResultEpisode>("last_opened")
        assertEquals(101, lastOpened?.id)
    }

    @Test
    fun testUpdateDurationAndPosition() {
        val episode = ResultEpisode(
            headerName = "Test Show",
            name = "Episode 2",
            poster = "",
            season = 1,
            episode = 2,
            data = "data2",
            apiName = "TestProvider",
            id = 202,
            index = 1
        )
        setKey("last_opened", episode)

        updateDurationAndPosition(5000L, 100000L)
        val pos = DataStoreHelper.getViewPos(202)
        assertEquals(5000L, pos?.position)
        assertEquals(100000L, pos?.duration)
    }

    @Test
    fun testOnResultSafeExceptionHandling() {
        val throwingAction = object : OpenInAppAction(
            appName = txt("CrashApp"),
            packageName = "com.example.crash"
        ) {
            override suspend fun putExtra(
                context: Context,
                intent: Intent,
                video: ResultEpisode,
                result: LinkLoadingResult,
                index: Int?
            ) {}

            override fun onResult(activity: Activity, intent: Intent?) {
                throw RuntimeException("Intent error")
            }
        }

        // Must not throw
        throwingAction.onResultSafe(Activity(), Intent())
    }
}
