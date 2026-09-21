package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class RepoLinkGeneratorTest {

    private class TestLinkProvider : MainAPI() {
        override var name = "TestLinkProvider"
        override var mainUrl = "https://linkprovider.local"
        override val supportedTypes = setOf(TvType.Movie)

        var loadLinksCount = AtomicInteger(0)

        override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
        ): Boolean {
            loadLinksCount.incrementAndGet()
            subtitleCallback(newSubtitleFile("<b>English</b>", "https://linkprovider.local/en.vtt"))
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Stream",
                    url = "https://linkprovider.local/stream.m3u8",
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }
    }

    private val provider = TestLinkProvider()

    @BeforeEach
    fun setup() {
        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)
        RepoLinkGenerator.cache.clear()
    }

    @Test
    fun `test RepoLinkGenerator delegates to APIRepository and caches results`() = runBlocking {
        val episode = ResultEpisode(
            headerName = "Season 1",
            name = "Episode 1",
            poster = null,
            episode = 1,
            data = "ep-1-data",
            apiName = provider.name,
            id = 1001,
            index = 0,
            parentId = 500
        )

        val generator = RepoLinkGenerator(listOf(episode))

        assertTrue(generator.hasCache)
        assertTrue(generator.canSkipLoading)
        assertEquals(1001, generator.getId(0))
        assertFalse(generator.hasNext(0))
        assertFalse(generator.hasPrev(0))

        val links = mutableListOf<Pair<ExtractorLink?, ExtractorUri?>>()
        val subs = mutableListOf<SubtitleData>()

        // 1. Initial generation: should invoke provider.loadLinks
        val success = generator.generateLinks(
            clearCache = false,
            sourceTypes = setOf(ExtractorLinkType.M3U8),
            callback = { links.add(it) },
            subtitleCallback = { subs.add(it) },
            offset = 0,
            isCasting = false
        )

        assertTrue(success)
        assertEquals(1, provider.loadLinksCount.get())
        assertEquals(1, links.size)
        assertEquals("https://linkprovider.local/stream.m3u8", links[0].first?.url)

        assertEquals(1, subs.size)
        assertEquals("English", subs[0].originalName) // HTML tags stripped by html().toString()
        assertEquals("1", subs[0].nameSuffix) // First occurrence has suffix 1
        assertEquals("https://linkprovider.local/en.vtt", subs[0].url)

        // 2. Second generation with clearCache = false: should use cache without calling loadLinks
        val cachedLinks = mutableListOf<Pair<ExtractorLink?, ExtractorUri?>>()
        val cachedSubs = mutableListOf<SubtitleData>()

        val cachedSuccess = generator.generateLinks(
            clearCache = false,
            sourceTypes = setOf(ExtractorLinkType.M3U8),
            callback = { cachedLinks.add(it) },
            subtitleCallback = { cachedSubs.add(it) },
            offset = 0,
            isCasting = false
        )

        assertTrue(cachedSuccess)
        assertEquals(1, provider.loadLinksCount.get(), "loadLinks must not be called when cached and saturated")
        assertEquals(1, cachedLinks.size)
        assertEquals(1, cachedSubs.size)

        // 3. Generation with clearCache = true: cache invalidated, loadLinks called again
        val refreshedLinks = mutableListOf<Pair<ExtractorLink?, ExtractorUri?>>()
        val refreshedSubs = mutableListOf<SubtitleData>()

        val refreshedSuccess = generator.generateLinks(
            clearCache = true,
            sourceTypes = setOf(ExtractorLinkType.M3U8),
            callback = { refreshedLinks.add(it) },
            subtitleCallback = { refreshedSubs.add(it) },
            offset = 0,
            isCasting = false
        )

        assertTrue(refreshedSuccess)
        assertEquals(2, provider.loadLinksCount.get(), "loadLinks must be called again when clearCache is true")
        assertEquals(1, refreshedLinks.size)
        assertEquals(1, refreshedSubs.size)
    }

    @Test
    fun `test RepoLinkGenerator cache invalidation on 20 minute expiry`() = runBlocking {
        val episode = ResultEpisode(
            headerName = "Season 1",
            name = "Episode 2",
            poster = null,
            episode = 2,
            data = "ep-2-data",
            apiName = provider.name,
            id = 1002,
            index = 1,
            parentId = 500
        )

        val generator = RepoLinkGenerator(listOf(episode))

        // Initial generation
        generator.generateLinks(
            clearCache = false,
            sourceTypes = setOf(ExtractorLinkType.M3U8),
            callback = {},
            subtitleCallback = {},
            offset = 0,
            isCasting = false
        )
        assertEquals(1, provider.loadLinksCount.get())

        // Manually backdate the cache timestamp by 21 minutes (1260 seconds)
        val cacheEntry = RepoLinkGenerator.cache[provider.name to 1002]
        assertFalse(cacheEntry == null)
        cacheEntry!!.lastCachedTimestamp -= 21 * 60

        // Generating again should detect outdated cache and call loadLinks again
        generator.generateLinks(
            clearCache = false,
            sourceTypes = setOf(ExtractorLinkType.M3U8),
            callback = {},
            subtitleCallback = {},
            offset = 0,
            isCasting = false
        )
        assertEquals(2, provider.loadLinksCount.get(), "loadLinks should be called when cache timestamp is outdated")
    }

    @Test
    fun `test LinkGenerator and SubtitleData helpers`() = runBlocking {
        val basicLink = BasicLink("https://example.com/video.mp4", "Direct Video")
        val linkGen = LinkGenerator(listOf(basicLink), extract = false, id = 999)

        val links = mutableListOf<Pair<ExtractorLink?, ExtractorUri?>>()
        val success = linkGen.generateLinks(
            clearCache = false,
            sourceTypes = LOADTYPE_ALL,
            callback = { links.add(it) },
            subtitleCallback = {},
            offset = 0,
            isCasting = false
        )

        assertTrue(success)
        assertEquals(1, links.size)
        assertEquals("Direct Video", links[0].first?.name)
        assertEquals("https://example.com/video.mp4", links[0].first?.url)

        // SubtitleData helpers
        val subData = SubtitleData(
            originalName = "Spanish",
            nameSuffix = "1",
            url = "//example.com/sub.srt",
            origin = SubtitleOrigin.URL,
            mimeType = "application/x-subrip",
            headers = emptyMap(),
            languageCode = "es"
        )

        assertEquals("https://example.com/sub.srt", subData.getFixedUrl())
        assertEquals("Spanish 1", subData.name)
        assertEquals("//example.com/sub.srt|Spanish 1", subData.getId())
        assertTrue(subData.matchesLanguageCode("es"))
    }
}
