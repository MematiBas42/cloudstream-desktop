package com.lagradost.cloudstream3.actions.temp

import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.OpenInAppAction
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.SubtitleOrigin
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class Aria2PackageTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            PlatformPaths.init()
        }
    }

    @Test
    fun testInheritanceAndProperties() {
        val aria2 = Aria2Package()
        assertTrue(aria2 is OpenInAppAction, "Aria2Package must inherit from OpenInAppAction")
        assertEquals("com.gianlu.aria2android", aria2.packageName)
        assertEquals("com.gianlu.aria2android.MainActivity", aria2.intentClass)
        assertTrue(aria2.oneSource, "oneSource must be true")
        assertTrue(aria2.isPlayer, "isPlayer is inherited as true from OpenInAppAction")
    }

    @Test
    fun testIsAria2Available() {
        // shouldShow uses isAria2Available() which checks PATH
        val aria2 = Aria2Package()
        val show = aria2.shouldShow(null, null)
        assertEquals(Aria2Package.isAria2Available(), show)
    }

    @Test
    fun testGetDownloadDir() {
        val downloadDir = Aria2Package.getDownloadDir()
        assertNotNull(downloadDir)
        assertTrue(downloadDir.exists(), "Download directory must exist or be created")
    }

    @Test
    fun testPutExtra() = runBlocking {
        val aria2 = Aria2Package()
        val context = Context()
        val intent = Intent()

        val video = ResultEpisode(
            headerName = "Test Show",
            name = "Episode 1",
            poster = null,
            episode = 1,
            season = 1,
            data = "https://example.com/ep1",
            apiName = "TestProvider",
            id = 54321,
            index = 0,
            tvType = TvType.TvSeries
        )

        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p",
            url = "https://example.com/video.mp4",
            referer = "https://example.com/ref",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )

        val sub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://example.com/sub.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap(),
            languageCode = "en"
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(sub),
            syncData = hashMapOf()
        )

        aria2.putExtra(context, intent, video, result, 0)

        assertEquals("https://example.com/video.mp4", intent.getStringExtra("url"))
        assertEquals("https://example.com/ref", intent.getStringExtra("referer"))
        val dir = intent.getStringExtra("dir")
        assertNotNull(dir)
        assertTrue(dir!!.isNotBlank(), "Directory extra should not be blank")
    }

    @Test
    fun testOnResultHasPlatformQuarantineAnnotation() {
        val method = Aria2Package::class.java.declaredMethods.firstOrNull { it.name == "onResult" }
        assertNotNull(method, "onResult method must exist")

        val annotation = method!!.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(annotation, "onResult must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, annotation.status)
        assertTrue(annotation.reason.contains("Aria2 on Linux") || annotation.reason.contains("RPC"))
    }
}
