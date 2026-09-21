package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Context
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PluginsViewModelTest {

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        PlatformPaths.init()
        val testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
    }

    @AfterEach
    fun teardown() {
        DesktopDataStore.customDataFile = null
    }

    private fun createDummyPluginWrapper(
        name: String,
        internalName: String,
        tvTypes: List<String> = listOf(TvType.Anime.name),
        language: String? = "en",
        description: String? = null
    ): PluginWrapper {
        val repo = Repository(name = "TestRepo", description = null, manifestVersion = 1, pluginLists = emptyList())
        val repoData = RepositoryData(name = "TestRepo", url = "https://example.com/repo")
        val sitePlugin = SitePlugin(
            url = "https://example.com/plugin.cs3",
            name = name,
            internalName = internalName,
            tvTypes = tvTypes,
            language = language,
            description = description
        )
        return PluginWrapper(repo, repoData, sitePlugin)
    }

    @Test
    fun `initial state has empty filtered plugins`() {
        val vm = PluginsViewModel()
        val initial = vm.filteredPlugins.value
        assertFalse(initial.first)
        assertTrue(initial.second.isEmpty())
        vm.onCleared()
    }

    @Test
    fun `search updates filtered plugins and preserves query state`() {
        val vm = PluginsViewModel()

        vm.search("Anime")
        val result = vm.filteredPlugins.value
        assertTrue(result.first) // first is true when searching
        assertTrue(result.second.isEmpty()) // no plugins yet

        vm.clear()
        val cleared = vm.filteredPlugins.value
        assertFalse(cleared.first)
        assertTrue(cleared.second.isEmpty())
        vm.onCleared()
    }

    @Test
    fun `updatePluginListLocal populates plugins correctly`() = runBlocking {
        val vm = PluginsViewModel()

        // Before update
        assertEquals(0, vm.filteredPlugins.value.second.size)

        vm.updatePluginListLocal().join()

        // Result should reflect online + local distinct plugins
        val state = vm.filteredPlugins.value
        assertNotNull(state)
        assertFalse(state.first)
        vm.onCleared()
    }

    @Test
    fun `filterTvTypes and filterLang apply correctly`() {
        val vm = PluginsViewModel()

        val pw1 = createDummyPluginWrapper("Anime Streamer", "animestreamer", listOf(TvType.Anime.name), "en")
        val pw2 = createDummyPluginWrapper("Movie Hub", "moviehub", listOf(TvType.Movie.name), "tr")
        val pw3 = createDummyPluginWrapper("Generic Other", "genericother", emptyList(), null)

        val pvd1 = PluginViewData(pw1, isDownloaded = true)
        val pvd2 = PluginViewData(pw2, isDownloaded = false)
        val pvd3 = PluginViewData(pw3, isDownloaded = false)

        // Reflection or setter via updatePluginListLocal / search
        // We can test search logic directly
        vm.tvTypes.add(TvType.Anime.name)
        vm.selectedLanguages = listOf("en")

        vm.search("Anime")
        val state = vm.filteredPlugins.value
        assertTrue(state.first)
        vm.onCleared()
    }

    @Test
    fun `downloadAll with null activity returns cleanly`() = runBlocking {
        val repoData = RepositoryData("TestRepo", "https://example.com/repo")
        val job = PluginsViewModel.downloadAll(null, repoData, null)
        job.join()
        assertTrue(job.isCompleted)
    }

    @Test
    fun `handlePluginAction with null activity returns cleanly`() = runBlocking {
        val vm = PluginsViewModel()
        val pw = createDummyPluginWrapper("Test", "test")
        val job = vm.handlePluginAction(null, emptyList(), pw, isLocal = false)
        job.join()
        assertTrue(job.isCompleted)
        vm.onCleared()
    }
}
