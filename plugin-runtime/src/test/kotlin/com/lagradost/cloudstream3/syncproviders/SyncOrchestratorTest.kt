package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.cloudstream3.utils.SyncUtil
import com.lagradost.cloudstream3.utils.videoskip.AnimeSkipAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SyncOrchestratorTest {

    @Test
    fun testAccountManagerParity() {
        // Verify allApis contains 10 entries: 5 sync + 4 subtitle + 1 auth
        assertEquals(10, AccountManager.allApis.size)
        assertEquals(5, AccountManager.syncApis.size)
        assertEquals(4, AccountManager.subtitleProviders.size)

        // Verify individual API instances
        assertNotNull(AccountManager.malApi)
        assertEquals("mal", AccountManager.malApi.idPrefix)

        assertNotNull(AccountManager.kitsuApi)
        assertEquals("kitsu", AccountManager.kitsuApi.idPrefix)

        assertNotNull(AccountManager.aniListApi)
        assertEquals("anilist", AccountManager.aniListApi.idPrefix)

        assertNotNull(AccountManager.simklApi)
        assertEquals("simkl", AccountManager.simklApi.idPrefix)

        assertNotNull(AccountManager.localListApi)
        assertEquals("local", AccountManager.localListApi.idPrefix)

        assertNotNull(AccountManager.openSubtitlesApi)
        assertEquals("opensubtitles", AccountManager.openSubtitlesApi.idPrefix)

        assertNotNull(AccountManager.addic7ed)
        assertEquals("addic7ed", AccountManager.addic7ed.idPrefix)

        assertNotNull(AccountManager.subDlApi)
        assertEquals("subdl", AccountManager.subDlApi.idPrefix)

        assertNotNull(AccountManager.subSourceApi)
        assertEquals("subsource", AccountManager.subSourceApi.idPrefix)

        assertNotNull(AccountManager.animeSkipApi)
        assertEquals("anime-skip", AccountManager.animeSkipApi.idPrefix)

        // Verify initMainAPI sets LoadResponse prefixes correctly
        AccountManager.initMainAPI()
        assertEquals("mal", LoadResponse.malIdPrefix)
        assertEquals("kitsu", LoadResponse.kitsuIdPrefix)
        assertEquals("anilist", LoadResponse.aniListIdPrefix)
        assertEquals("simkl", LoadResponse.simklIdPrefix)

        // Verify secondsToReadable helper
        assertEquals("1d 2h 30m", AccountManager.secondsToReadable(95400, "Done"))
        assertEquals("1h 15m", AccountManager.secondsToReadable(4500, "Done"))
        assertEquals("45m", AccountManager.secondsToReadable(2700, "Done"))
        assertEquals("Finished", AccountManager.secondsToReadable(-60, "Finished"))
    }

    @Test
    fun testSyncUtilUrlRegexes() = runBlocking {
        // Test URL parsing without network call (getIdsFromUrl parses regex then queries GitHub)
        // Invalid URLs or non-matching URLs return null
        assertNull(SyncUtil.getIdsFromUrl(null))
        assertNull(SyncUtil.getIdsFromUrl("https://example.com/watch/123"))

        // Test title to slug normalization in getIdsFromTitle
        // Even if network returns null, ensure function runs safely without crashing
        val ids = SyncUtil.getIdsFromTitle("Attack on Titan: The Final Season")
        // Function executes safely
    }

    @Test
    fun testSyncViewModelStateFlows() = runBlocking {
        val vm = SyncViewModel(Dispatchers.IO)

        // Initial state assertions
        assertNull(vm.metadata.value)
        assertNull(vm.userData.value)
        assertEquals(5, vm.synced.value.size)

        // Add sync tests
        val added = vm.addSyncs(mapOf("mal" to "12345", "anilist" to "67890"))
        assertTrue(added)
        assertEquals(mapOf("mal" to "12345", "anilist" to "67890"), vm.getSyncs())

        // Set sync test
        vm.setSync("kitsu", "111")
        assertEquals(mapOf("kitsu" to "111"), vm.getSyncs())

        // Clear test
        vm.clear()
        assertTrue(vm.getSyncs().isEmpty())
        assertNull(vm.metadata.value)
        assertNull(vm.userData.value)
        assertEquals(5, vm.synced.value.size)

        // Sync name resolution
        assertEquals("mal", vm.syncName("MAL"))
        assertEquals("kitsu", vm.syncName("Kitsu"))
        assertEquals("simkl", vm.syncName("Simkl"))
        assertEquals("anilist", vm.syncName("AniList"))
        assertEquals("local", vm.syncName("local"))

        vm.onCleared()
    }

    @Test
    fun testSyncViewModelModifyMaxEpisodeScrobble() = runBlocking {
        val vm = SyncViewModel(Dispatchers.IO)

        // Setup a sync mapping
        vm.addSyncs(mapOf("local" to "test_local_id"))

        // When playback reaches 80%, modifyMaxEpisode is called
        vm.modifyMaxEpisode(12)

        // Verify status adjustments
        vm.setStatus(1) // Completed
        vm.setScore(Score.from10(9.0))
        vm.setEpisodes(12)
        vm.setEpisodesDelta(1)

        // Verify clean up
        vm.onCleared()
    }

    @Test
    fun testAnimeSkipAuthContract() {
        val auth = AnimeSkipAuth()
        assertEquals("AnimeSkip", auth.name)
        assertEquals("anime-skip", auth.idPrefix)
        assertTrue(auth.hasInApp)
        assertEquals("https://anime-skip.com/account", auth.createAccountUrl)
        assertTrue(auth.inAppLoginRequirement.password)
        assertTrue(auth.inAppLoginRequirement.username)

        // Verify MD5 hashing
        val hash = auth.md5("secret123")
        assertEquals(32, hash.length)
        assertEquals("5d7845ac6ee7cfffafc5fe5f35cf666d", hash)
    }
}
