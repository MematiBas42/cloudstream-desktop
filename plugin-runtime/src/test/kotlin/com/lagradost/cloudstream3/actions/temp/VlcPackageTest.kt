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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.findAnnotation

class VlcPackageTest {

    @Test
    fun testInheritanceAndProperties() {
        val vlc = VlcPackage()
        assertTrue(vlc is OpenInAppAction, "VlcPackage must inherit from OpenInAppAction")
        assertEquals("org.videolan.vlc", vlc.packageName)
        assertEquals("vlc", vlc.executableName)
        assertTrue(vlc.oneSource, "VLC oneSource must be true")
        assertTrue(vlc.isPlayer, "VLC isPlayer must be true")

        val nightly = VlcNightlyPackage()
        assertTrue(nightly is VlcPackage, "VlcNightlyPackage must inherit from VlcPackage")
        assertEquals("org.videolan.vlc.debug", nightly.packageName)
        assertEquals("vlc-nightly", nightly.executableName)
        assertTrue(nightly.oneSource, "VLC Nightly oneSource must be true")
    }

    @Test
    fun testIsBinaryInPath() {
        // "sh" and "ls" are standard POSIX binaries that always exist in PATH
        assertTrue(VlcPackage.isBinaryInPath("sh"), "sh should be found in PATH")
        assertTrue(VlcPackage.isBinaryInPath("ls"), "ls should be found in PATH")

        // Non-existent binary should return false
        assertFalse(
            VlcPackage.isBinaryInPath("non_existent_binary_12345_cloudstream"),
            "Non-existent binary should not be found in PATH"
        )
    }

    @Test
    fun testPutExtraSingleLink() = runBlocking {
        val vlc = VlcPackage()
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
            id = 12345,
            index = 0,
            tvType = TvType.TvSeries
        )

        val link = ExtractorLink(
            source = "TestProvider",
            name = "1080p",
            url = "https://example.com/video.mp4",
            referer = "https://example.com",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )

        val sub = SubtitleData(
            originalName = "English",
            nameSuffix = "",
            url = "https://example.com/sub.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "text/vtt",
            headers = emptyMap<String, String>(),
            languageCode = "en"
        )

        val result = LinkLoadingResult(
            links = listOf(link),
            subs = listOf(sub),
            syncData = hashMapOf()
        )

        vlc.putExtra(context, intent, video, result, 0)

        assertEquals("Episode 1", intent.getStringExtra("title"))
        assertEquals("https://example.com/video.mp4", intent.data?.toString())
        assertEquals(false, intent.getBooleanExtra("from_start", true))
        assertEquals(true, intent.getBooleanExtra("secure_uri", false))
        assertEquals("https://example.com/sub.srt", intent.getStringExtra("subtitles_location"))
    }

    @Test
    fun testOnResultHasPlatformQuarantineAnnotation() {
        val method = VlcPackage::class.java.declaredMethods.firstOrNull { it.name == "onResult" }
        assertNotNull(method, "onResult method must exist")

        val annotation = method!!.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(annotation, "onResult must be annotated with @PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, annotation.status)
        assertTrue(annotation.reason.contains("Linux desktop") || annotation.reason.contains("VLC CLI"))
    }
}
