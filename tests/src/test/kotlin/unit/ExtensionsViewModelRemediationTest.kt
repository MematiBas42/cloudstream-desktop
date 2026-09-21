package unit

import android.content.Context
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.ExtensionsViewModel
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Concrete parity and remediation test suite for [ExtensionsViewModel] (Cluster C31_ExtensionsViewModel).
 *
 * Verifies:
 * 1. Initial StateFlow exposures: repositories and pluginStats.
 * 2. Repository loading contract: combines persistent storage (REPOSITORIES_KEY) and PREBUILT_REPOSITORIES.
 * 3. PluginStats computation and mathematical invariant:
 *    downloaded + notDownloaded + disabled == total.
 * 4. Localized UI text generation for downloaded, disabled, and not-downloaded states.
 * 5. Outdated plugin tracking aligned with DesktopPluginManager update channels.
 * 6. Event-driven auto-refresh: MainActivity.afterPluginsLoadedEvent automatically triggers loadStats() and loadRepositories().
 * 7. Deterministic lifecycle cleanup: onCleared() unregisters event listeners and cancels coroutine scope.
 * 8. RepositoryData contract: JSON serialization, primary and secondary constructors.
 * 9. DesktopPluginManager update channel APIs (syncRepositories, updateAllPlugins, downloadMissingPlugins, checkAndUpdatePluginsManually).
 */
class ExtensionsViewModelRemediationTest {

    private lateinit var testContext: Context
    private lateinit var viewModel: ExtensionsViewModel

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        val testDataFile = tempDir.resolve("extensions_vm_datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        testContext = Context()
        viewModel = ExtensionsViewModel(Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        viewModel.onCleared()
        DesktopDataStore.customDataFile = null
    }

    @Test
    @DisplayName("Initial StateFlow values conform to upstream specification")
    fun testInitialState() {
        val repos = viewModel.repositories.value
        assertNotNull(repos)
        // Before explicit load, repositories defaults to empty array
        assertTrue(repos.isEmpty())

        // Initial plugin stats is null before loadStats()
        assertNull(viewModel.pluginStats.value)
    }

    @Test
    @DisplayName("loadRepositories populates repositories from DataStore and PREBUILT_REPOSITORIES")
    fun testLoadRepositoriesFromDataStore() {
        val customRepo1 = RepositoryData(
            iconUrl = "https://example.com/icon1.png",
            name = "Community Alpha Repo",
            url = "https://example.com/repo1/repo.json"
        )
        val customRepo2 = RepositoryData(
            name = "Community Beta Repo",
            url = "https://example.com/repo2/repo.json"
        )

        // Persist to DataStore under canonical REPOSITORIES_KEY
        CloudStreamApp.setKey(REPOSITORIES_KEY, arrayOf(customRepo1, customRepo2))

        viewModel.loadRepositories()

        val loaded = viewModel.repositories.value
        assertNotNull(loaded)
        assertTrue(loaded.size >= 2)
        val urls = loaded.map { it.url }
        assertTrue(urls.contains(customRepo1.url))
        assertTrue(urls.contains(customRepo2.url))
    }

    @Test
    @DisplayName("loadStats executes safely and produces consistent zero-baseline stats when offline")
    fun testLoadStatsZeroBaseline() = runBlocking {
        // Clear repositories in storage
        CloudStreamApp.setKey(REPOSITORIES_KEY, emptyArray<RepositoryData>())

        val job = viewModel.loadStats()
        job?.join()

        val stats = viewModel.pluginStats.value
        assertNotNull(stats)
        assertEquals(0, stats?.total)
        assertEquals(0, stats?.downloaded)
        assertEquals(0, stats?.disabled)
        assertEquals(0, stats?.notDownloaded)

        // Mathematical invariant: downloaded + notDownloaded + disabled == total
        val s = stats!!
        assertEquals(s.total, s.downloaded + s.notDownloaded + s.disabled)
    }

    @Test
    @DisplayName("PluginStats model enforces mathematical invariant and formats UiText")
    fun testPluginStatsModelAndInvariant() {
        val total = 15
        val downloaded = 8
        val disabled = 2
        val notDownloaded = 5

        assertEquals(total, downloaded + notDownloaded + disabled)

        val stats = ExtensionsViewModel.PluginStats(
            total = total,
            downloaded = downloaded,
            disabled = disabled,
            notDownloaded = notDownloaded,
            downloadedText = com.lagradost.cloudstream3.utils.txt(com.lagradost.cloudstream3.R.string.plugins_downloaded, downloaded),
            disabledText = com.lagradost.cloudstream3.utils.txt(com.lagradost.cloudstream3.R.string.plugins_disabled, disabled),
            notDownloadedText = com.lagradost.cloudstream3.utils.txt(com.lagradost.cloudstream3.R.string.plugins_not_downloaded, notDownloaded),
            outdated = 3,
            outdatedText = com.lagradost.cloudstream3.utils.txt(com.lagradost.cloudstream3.R.string.plugins_updated, 3)
        )

        assertEquals(15, stats.total)
        assertEquals(8, stats.downloaded)
        assertEquals(2, stats.disabled)
        assertEquals(5, stats.notDownloaded)
        assertEquals(3, stats.outdated)

        // Text formatting verification
        val downloadedString = stats.downloadedText.asString(testContext)
        val disabledString = stats.disabledText.asString(testContext)
        val notDownloadedString = stats.notDownloadedText.asString(testContext)
        val outdatedString = stats.outdatedText?.asString(testContext)

        assertTrue(downloadedString.contains("8"), "Downloaded text should contain count '8'")
        assertTrue(disabledString.contains("2"), "Disabled text should contain count '2'")
        assertTrue(notDownloadedString.contains("5"), "Not-downloaded text should contain count '5'")
        assertNotNull(outdatedString)
        assertTrue(outdatedString!!.contains("3"), "Outdated text should contain count '3'")
    }

    @Test
    @DisplayName("PluginStats secondary constructor maintains 1:1 backward compatibility")
    fun testPluginStatsSecondaryConstructor() {
        val stats = ExtensionsViewModel.PluginStats(
            total = 10,
            downloaded = 6,
            disabled = 1,
            notDownloaded = 3,
            downloadedText = com.lagradost.cloudstream3.utils.txt("Downloaded 6"),
            disabledText = com.lagradost.cloudstream3.utils.txt("Disabled 1"),
            notDownloadedText = com.lagradost.cloudstream3.utils.txt("Not Downloaded 3")
        )

        assertEquals(10, stats.total)
        assertEquals(6, stats.downloaded)
        assertEquals(1, stats.disabled)
        assertEquals(3, stats.notDownloaded)
        assertEquals(0, stats.outdated)
        assertNull(stats.outdatedText)
    }

    @Test
    @DisplayName("MainActivity.afterPluginsLoadedEvent automatically refreshes repositories and stats")
    fun testAfterPluginsLoadedEventAutoRefresh() = runBlocking {
        val customRepo = RepositoryData(name = "AutoRefreshRepo", url = "https://example.com/autorefresh.json")
        CloudStreamApp.setKey(REPOSITORIES_KEY, arrayOf(customRepo))

        // Trigger afterPluginsLoadedEvent bus
        MainActivity.afterPluginsLoadedEvent.invoke(false)

        val loadedRepos = viewModel.repositories.value
        assertTrue(loadedRepos.any { it.url == customRepo.url })
    }

    @Test
    @DisplayName("Deterministic onCleared() unregisters event listeners and cancels coroutine scope")
    fun testLifecycleCleanupOnCleared() {
        assertFalse(viewModel.isCleared)

        viewModel.onCleared()
        assertTrue(viewModel.isCleared)

        // Firing event after onCleared must be completely safe without throwing or leaking
        assertDoesNotThrow {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        }
    }

    @Test
    @DisplayName("RepositoryData constructors and Jackson roundtrip serialization")
    fun testRepositoryDataConstructorsAndSerialization() {
        // Primary constructor
        val repoWithIcon = RepositoryData(
            iconUrl = "https://example.com/icon.png",
            name = "Primary Repo",
            url = "https://example.com/repo.json"
        )
        assertEquals("https://example.com/icon.png", repoWithIcon.iconUrl)
        assertEquals("Primary Repo", repoWithIcon.name)
        assertEquals("https://example.com/repo.json", repoWithIcon.url)

        // Secondary constructor
        val repoWithoutIcon = RepositoryData(
            name = "Secondary Repo",
            url = "https://example.com/repo2.json"
        )
        assertNull(repoWithoutIcon.iconUrl)
        assertEquals("Secondary Repo", repoWithoutIcon.name)
        assertEquals("https://example.com/repo2.json", repoWithoutIcon.url)

        // JSON Serialization / Deserialization
        val json = DesktopDataStore.mapper.writeValueAsString(repoWithIcon)
        val deserialized = DesktopDataStore.mapper.readValue(json, RepositoryData::class.java)
        assertEquals(repoWithIcon, deserialized)
    }

    @Test
    @DisplayName("DesktopPluginManager update channel APIs execute safely without exceptions")
    fun testDesktopPluginManagerUpdateChannelAPIs() = runBlocking {
        // syncRepositories
        assertDoesNotThrow {
            val job = viewModel.syncRepositories(testContext)
            runBlocking { job?.join() }
        }

        // updateAllPlugins
        assertDoesNotThrow {
            val job = viewModel.updateAllPlugins(testContext)
            runBlocking { job?.join() }
        }

        // downloadMissingPlugins
        assertDoesNotThrow {
            val job = viewModel.downloadMissingPlugins(testContext, AutoDownloadMode.FilterByLang)
            runBlocking { job?.join() }
        }

        // checkAndUpdatePluginsManually
        assertDoesNotThrow {
            val job = viewModel.checkAndUpdatePluginsManually(testContext)
            runBlocking { job?.join() }
        }
    }
}
