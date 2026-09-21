package com.lagradost.cloudstream3.actions.temp

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActionsTempTest {

    private fun createDummyVideo(id: Int = 100): ResultEpisode {
        return ResultEpisode(
            headerName = "Test Series",
            name = "Episode 1",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/ep1",
            apiName = "TestProvider",
            id = id,
            index = 0,
            tvType = TvType.TvSeries
        )
    }

    private fun createDummyResult(): LinkLoadingResult {
        val link1 = ExtractorLink(
            source = "ProviderA",
            name = "1080p Mirror",
            url = "https://example.com/stream1.m3u8",
            referer = "https://example.com",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.M3U8
        )
        val link2 = ExtractorLink(
            source = "ProviderB",
            name = "720p Mirror",
            url = "https://example.com/stream2.mp4",
            referer = "https://example.com",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )
        val sub = SubtitleData(
            originalName = "English",
            url = "https://example.com/sub_en.srt"
        )
        return LinkLoadingResult(
            links = listOf(link1, link2),
            subs = listOf(sub),
            syncData = hashMapOf<String, String>()
        )
    }

    @Test
    fun testPlayMirrorActionPropertiesAndExecution() = runBlocking {
        val action = PlayMirrorAction()
        assertTrue(action is VideoClickAction, "PlayMirrorAction must extend VideoClickAction")
        assertTrue(action.oneSource, "oneSource must be true")
        assertTrue(action.isPlayer, "isPlayer must be true")
        assertEquals(LOADTYPE_INAPP, action.sourceTypes)
        assertTrue(action.shouldShow(null, null))

        val video = createDummyVideo()
        val result = createDummyResult()

        // Test running without Activity context safely returns without crashing
        action.runAction(null, video, result, 0)

        // Test running with dummy activity
        val dummyActivity = Activity()
        action.runAction(dummyActivity, video, result, 0)
    }

    @Test
    fun testPlayInBrowserActionPropertiesAndExecution() = runBlocking {
        val action = PlayInBrowserAction()
        assertTrue(action is VideoClickAction, "PlayInBrowserAction must extend VideoClickAction")
        assertTrue(action.oneSource, "oneSource must be true")
        assertTrue(action.isPlayer, "isPlayer must be true")
        assertTrue(action.sourceTypes.contains(ExtractorLinkType.VIDEO))
        assertTrue(action.sourceTypes.contains(ExtractorLinkType.DASH))
        assertTrue(action.sourceTypes.contains(ExtractorLinkType.M3U8))
        assertTrue(action.shouldShow(null, null))

        val video = createDummyVideo()
        val result = createDummyResult()

        // runAction will trigger CloudStreamApp.openBrowser which falls back gracefully
        action.runAction(null, video, result, 0)
    }

    @Test
    fun testViewM3U8ActionPlaylistBuildingAndIntent() = runBlocking {
        val action = ViewM3U8Action()
        assertTrue(action is VideoClickAction, "ViewM3U8Action must extend VideoClickAction")
        assertTrue(action.isPlayer, "isPlayer must be true")
        assertTrue(action.shouldShow(null, null))

        val video = createDummyVideo()
        val result = createDummyResult()

        val playlistText = ViewM3U8Action.buildM3U8Playlist(result)
        assertTrue(playlistText.startsWith("#EXTM3U\n#EXT-X-VERSION:3"))
        assertTrue(playlistText.contains("#EXTINF:0,1080p Mirror\nhttps://example.com/stream1.m3u8"))
        assertTrue(playlistText.contains("#EXTINF:0,720p Mirror\nhttps://example.com/stream2.mp4"))
        assertTrue(playlistText.endsWith("#EXT-X-ENDLIST"))

        val context = Context()
        val intent = Intent()
        ViewM3U8Action.makeTempM3U8Intent(context, intent, result)
        assertEquals("application/x-mpegURL", intent.type)
        assertNotNull(intent.data)

        // Single link case
        val singleResult = LinkLoadingResult(links = listOf(result.links.first()), subs = emptyList(), syncData = hashMapOf())
        val singleIntent = Intent()
        ViewM3U8Action.makeTempM3U8Intent(context, singleIntent, singleResult)
        assertEquals("video/*", singleIntent.type)
        assertEquals("https://example.com/stream1.m3u8", singleIntent.data?.toString())

        // runAction execution
        action.runAction(context, video, result, 0)
    }

    @Test
    fun testCopyClipboardActionPropertiesAndExecution() = runBlocking {
        val action = CopyClipboardAction()
        assertTrue(action is VideoClickAction, "CopyClipboardAction must extend VideoClickAction")
        assertTrue(action.oneSource, "oneSource must be true")
        assertTrue(action.shouldShow(null, null))

        val video = createDummyVideo()
        val result = createDummyResult()

        // Run action
        action.runAction(null, video, result, 0)

        // Directly verify clipboard helper
        CopyClipboardAction.clipboardHelper(txt("Test Label"), "https://example.com/test-copy-url")
    }
}
