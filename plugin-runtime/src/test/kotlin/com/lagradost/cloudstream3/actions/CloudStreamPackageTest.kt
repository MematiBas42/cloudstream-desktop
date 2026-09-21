package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage.MinimalSubtitleLink
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage.MinimalVideoLink
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudStreamPackageTest {

    @Test
    fun `verify class hierarchy and default properties`() {
        val action = CloudStreamPackage()
        assertTrue(action is OpenInAppAction, "CloudStreamPackage must inherit from OpenInAppAction")
        assertTrue(action is VideoClickAction, "CloudStreamPackage must inherit from VideoClickAction")
        assertFalse(action.oneSource, "oneSource must be false for playlist support")
        assertTrue(action.isPlayer, "isPlayer must be true")
        assertEquals(BuildConfig.APPLICATION_ID, action.packageName)
        assertEquals("com.lagradost.cloudstream3", action.packageName)
    }

    @Test
    fun `verify companion object extra constants`() {
        assertEquals("subs", CloudStreamPackage.SUBTITLE_EXTRA)
        assertEquals("links", CloudStreamPackage.LINKS_EXTRA)
        assertEquals("title", CloudStreamPackage.TITLE_EXTRA)
        assertEquals("id", CloudStreamPackage.ID_EXTRA)
        assertEquals("pos", CloudStreamPackage.POSITION_EXTRA)
        assertEquals("dur", CloudStreamPackage.DURATION_EXTRA)
    }

    @Test
    fun `verify MinimalVideoLink conversion and serialization roundtrip`() = runBlocking {
        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p Stream",
            url = "https://example.com/video.mp4",
            referer = "https://example.com/",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO,
            headers = mapOf("User-Agent" to "TestAgent")
        )

        val minimal = MinimalVideoLink.fromExtractor(link)
        assertEquals("https://example.com/video.mp4", minimal.url)
        assertEquals("1080p Stream", minimal.name)
        assertEquals("video/mp4", minimal.mimeType)
        assertEquals(Qualities.P1080.value, minimal.quality)
        assertEquals("https://example.com/", minimal.headers["referer"])
        assertEquals("TestAgent", minimal.headers["User-Agent"])

        val json = minimal.toJson()
        val parsed = parseJson<MinimalVideoLink>(json)
        assertEquals(minimal.url, parsed.url)
        assertEquals(minimal.name, parsed.name)
        assertEquals(minimal.mimeType, parsed.mimeType)
        assertEquals(minimal.quality, parsed.quality)

        val (extractedLink, _) = minimal.toExtractorLink()
        assertNotNull(extractedLink)
        assertEquals("https://example.com/video.mp4", extractedLink?.url)
        assertEquals("1080p Stream", extractedLink?.name)
        assertEquals(Qualities.P1080.value, extractedLink?.quality)
    }

    @Test
    fun `verify MinimalSubtitleLink conversion and serialization roundtrip`() {
        val sub = SubtitleData(
            url = "https://example.com/sub.vtt",
            nameSuffix = "",
            mimeType = "text/vtt",
            originalName = "English",
            headers = mapOf("Cookie" to "session=123"),
            origin = SubtitleOrigin.URL,
            languageCode = "en"
        )

        val minimal = MinimalSubtitleLink.fromSubtitle(sub)
        assertEquals("https://example.com/sub.vtt", minimal.url)
        assertEquals("English", minimal.name)
        assertEquals("text/vtt", minimal.mimeType)
        assertEquals("session=123", minimal.headers["Cookie"])

        val json = minimal.toJson()
        val parsed = parseJson<MinimalSubtitleLink>(json)
        assertEquals(minimal.url, parsed.url)
        assertEquals(minimal.name, parsed.name)
        assertEquals(minimal.mimeType, parsed.mimeType)

        val restoredSub = minimal.toSubtitleData()
        assertEquals(sub.url, restoredSub.url)
        assertEquals(sub.originalName, restoredSub.originalName)
        assertEquals(sub.mimeType, restoredSub.mimeType)
        assertEquals(SubtitleOrigin.URL, restoredSub.origin)
    }

    @Test
    fun `verify putExtra populates Intent with links and subtitles`() = runBlocking {
        val action = CloudStreamPackage()
        val context = Context()
        val intent = Intent()

        val episode = ResultEpisode(
            headerName = "Season 1",
            name = "Episode 1 - The Beginning",
            poster = null,
            episode = 1,
            season = 1,
            data = "ep1_data",
            id = 42
        )

        val link = ExtractorLink(
            source = "SourceA",
            name = "720p",
            url = "https://example.com/ep1.mp4",
            referer = "",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )

        val sub = SubtitleData(
            url = "https://example.com/ep1_en.vtt",
            nameSuffix = "",
            mimeType = "text/vtt",
            originalName = "English",
            headers = emptyMap(),
            origin = SubtitleOrigin.URL,
            languageCode = "en"
        )

        val linkLoadingResult = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(sub),
            syncData = hashMapOf("provider" to "test")
        )

        action.putExtra(context, intent, episode, linkLoadingResult, null)

        assertEquals(42, intent.getIntExtra(CloudStreamPackage.ID_EXTRA, 0))
        assertEquals("Episode 1 - The Beginning", intent.getStringExtra(CloudStreamPackage.TITLE_EXTRA))

        // Ensure onResult executes safely without throwing
        action.onResult(Activity(), intent)
    }
}
