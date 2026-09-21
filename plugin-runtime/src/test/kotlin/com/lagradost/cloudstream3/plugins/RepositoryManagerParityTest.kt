package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.VotingApi.canVote
import com.lagradost.cloudstream3.plugins.VotingApi.getVotes
import com.lagradost.cloudstream3.plugins.VotingApi.hasVoted
import com.lagradost.cloudstream3.plugins.VotingApi.vote
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

class RepositoryManagerParityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val mapper = ObjectMapper().registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private var server: HttpServer? = null
    private var serverPort: Int = 0
    private var originalApiDomain = VotingApi.API_DOMAIN

    private class TestContext(baseDir: File) : Context() {
        private val customFiles = File(baseDir, "files").apply { mkdirs() }
        private val customCache = File(baseDir, "cache").apply { mkdirs() }

        override fun getFilesDir(): File = customFiles
        override fun getCacheDir(): File = customCache
    }

    @BeforeEach
    fun setup(@TempDir tempDir: Path) {
        PlatformPaths.init()
        val testDataFile = tempDir.resolve("datastore_repo_test.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        CloudStreamApp.context = TestContext(tempDir.toFile())
        PluginManager.urlPlugins.clear()
        originalApiDomain = VotingApi.API_DOMAIN
    }

    @AfterEach
    fun teardown() {
        server?.stop(0)
        server = null
        VotingApi.API_DOMAIN = originalApiDomain
        DesktopDataStore.customDataFile = null
        PluginManager.urlPlugins.clear()
    }

    // =========================================================================
    // 1. Cryptographic Hashing Tests (sha256)
    // =========================================================================

    @Test
    fun testSha256Hashing(@TempDir tempDir: Path) {
        val testFile = tempDir.resolve("sample_plugin.cs3").toFile()
        val content = "CloudStream 3 Linux Native Plugin Binary Payload"
        testFile.writeText(content, StandardCharsets.UTF_8)

        val computedHash = RepositoryManager.sha256(testFile)

        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val expectedHash = "sha256-$expectedDigest"

        assertTrue(computedHash.startsWith("sha256-"))
        assertEquals(expectedHash, computedHash)
    }

    // =========================================================================
    // 2. jsDelivr CDN Proxy Transformation Tests
    // =========================================================================

    @Test
    fun testConvertRawGitUrlWithProxyDisabled() {
        DesktopDataStore.setKey("jsdelivr_proxy_key", false)

        val rawUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions/master/repo.json"
        val converted = RepositoryManager.convertRawGitUrl(rawUrl)

        assertEquals(rawUrl, converted)
    }

    @Test
    fun testConvertRawGitUrlWithProxyEnabled() {
        DesktopDataStore.setKey("jsdelivr_proxy_key", true)

        val rawUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions/master/repo.json"
        val converted = RepositoryManager.convertRawGitUrl(rawUrl)
        val expected = "https://cdn.jsdelivr.net/gh/recloudstream/cloudstream-extensions@master/repo.json"

        assertEquals(expected, converted)
    }

    @Test
    fun testConvertRawGitUrlWithForceFlag() {
        DesktopDataStore.setKey("jsdelivr_proxy_key", false)

        val rawUrl = "https://raw.githubusercontent.com/author/repo/v2.1.0/plugins.json"
        val converted = RepositoryManager.convertRawGitUrl(rawUrl, force = true)
        val expected = "https://cdn.jsdelivr.net/gh/author/repo@v2.1.0/plugins.json"

        assertEquals(expected, converted)
    }

    @Test
    fun testConvertRawGitUrlWithNonGithubUrl() {
        DesktopDataStore.setKey("jsdelivr_proxy_key", true)

        val directUrl = "https://gitlab.com/user/project/raw/main/repo.json"
        val converted = RepositoryManager.convertRawGitUrl(directUrl)

        assertEquals(directUrl, converted)
    }

    // =========================================================================
    // 3. Shortener & Repository URL Parsing Tests (parseRepoUrl)
    // =========================================================================

    @Test
    fun testParseRepoUrlSchemes() = runBlocking {
        // Direct HTTPS URL
        val httpsUrl = "https://raw.githubusercontent.com/user/repo/master/repo.json"
        assertEquals(httpsUrl, RepositoryManager.parseRepoUrl(httpsUrl))

        // Direct HTTP URL
        val httpUrl = "http://example.com/repo.json"
        assertEquals(httpUrl, RepositoryManager.parseRepoUrl(httpUrl))

        // cloudstreamrepo:// protocol
        val csRepoScheme = "cloudstreamrepo://raw.githubusercontent.com/user/repo/master/repo.json"
        assertEquals("https://raw.githubusercontent.com/user/repo/master/repo.json", RepositoryManager.parseRepoUrl(csRepoScheme))

        // Invalid non-url characters
        val invalidUrl = "invalid string with spaces"
        assertNull(RepositoryManager.parseRepoUrl(invalidUrl))
    }

    // =========================================================================
    // 4. Repository Storage and Lifecycle (addRepository & removeRepository)
    // =========================================================================

    @Test
    fun testAddAndRemoveRepositoryLifecycle(@TempDir tempDir: Path) = runBlocking {
        val context = TestContext(tempDir.toFile())
        val repo1 = RepositoryData(iconUrl = "https://icon1.png", name = "Test Repo 1", url = "https://test1.com/repo.json")
        val repo2 = RepositoryData(iconUrl = "https://icon2.png", name = "Test Repo 2", url = "https://test2.com/repo.json")

        assertEquals(0, RepositoryManager.getRepositories().size)

        // Add repo 1
        RepositoryManager.addRepository(repo1)
        var repos = RepositoryManager.getRepositories()
        assertEquals(1, repos.size)
        assertEquals("Test Repo 1", repos[0].name)
        assertEquals("https://test1.com/repo.json", repos[0].url)

        // Add duplicate repo 1 -> size should remain 1
        RepositoryManager.addRepository(repo1)
        repos = RepositoryManager.getRepositories()
        assertEquals(1, repos.size)

        // Add repo 2
        RepositoryManager.addRepository(repo2)
        repos = RepositoryManager.getRepositories()
        assertEquals(2, repos.size)

        // Remove repo 1
        RepositoryManager.removeRepository(context, repo1)
        repos = RepositoryManager.getRepositories()
        assertEquals(1, repos.size)
        assertEquals("Test Repo 2", repos[0].name)

        // Clean up remaining
        RepositoryManager.removeRepository(context, repo2)
        assertEquals(0, RepositoryManager.getRepositories().size)
    }

    // =========================================================================
    // 5. Atomic Binary Plugin Download with SHA-256 Validation
    // =========================================================================

    @Test
    fun testDownloadPluginToFileWithHashVerification(@TempDir tempDir: Path) = runBlocking {
        val pluginPayload = "Compiled-CloudStream-DEX-Transpiled-JAR-Bytes".toByteArray(StandardCharsets.UTF_8)
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(pluginPayload)
            .joinToString("") { "%02x".format(it) }
        val expectedHash = "sha256-$expectedSha"

        // Setup local HTTP server to serve the plugin binary
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server!!.address.port
        server!!.createContext("/plugin.cs3") { exchange ->
            exchange.sendResponseHeaders(200, pluginPayload.size.toLong())
            exchange.responseBody.use { it.write(pluginPayload) }
        }
        server!!.start()

        val context = TestContext(tempDir.toFile())
        val targetFile = tempDir.resolve("downloaded_plugin.cs3").toFile()
        val pluginUrl = "http://127.0.0.1:$serverPort/plugin.cs3"

        // 1. Download with matching hash -> succeeds
        val downloaded = RepositoryManager.downloadPluginToFile(
            context = context,
            pluginUrl = pluginUrl,
            file = targetFile,
            expectedFileHash = expectedHash
        )

        assertNotNull(downloaded)
        assertTrue(targetFile.exists())
        assertArrayEquals(pluginPayload, targetFile.readBytes())

        // 2. Download with mismatched hash -> returns null and does not corrupt target file
        val invalidTarget = tempDir.resolve("invalid_plugin.cs3").toFile()
        val failedDownload = RepositoryManager.downloadPluginToFile(
            context = context,
            pluginUrl = pluginUrl,
            file = invalidTarget,
            expectedFileHash = "sha256-0000000000000000000000000000000000000000000000000000000000000000"
        )
        assertNull(failedDownload)
        assertFalse(invalidTarget.exists())
    }

    // =========================================================================
    // 6. Manifest Parsing and getRepoPlugins
    // =========================================================================

    @Test
    fun testParseRepositoryAndGetRepoPlugins() = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server!!.address.port

        val repoJson = """
            {
                "name": "Live Test Repository",
                "description": "Integration test repository",
                "manifestVersion": 1,
                "pluginLists": [
                    "http://127.0.0.1:$serverPort/plugins.json"
                ]
            }
        """.trimIndent()

        val pluginsJson = """
            [
                {
                    "url": "http://127.0.0.1:$serverPort/plugin1.cs3",
                    "status": 1,
                    "version": 2,
                    "apiVersion": 1,
                    "name": "Alpha Provider",
                    "internalName": "AlphaProvider",
                    "authors": ["Author1"],
                    "description": "First test provider",
                    "repositoryUrl": "http://127.0.0.1:$serverPort/repo.json",
                    "fileSize": 1024,
                    "fileHash": "sha256-abc123"
                }
            ]
        """.trimIndent()

        server!!.createContext("/repo.json") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = repoJson.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.createContext("/plugins.json") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = pluginsJson.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val repoData = RepositoryData(name = "Live Test Repository", url = "http://127.0.0.1:$serverPort/repo.json")
        val parsedRepo = RepositoryManager.parseRepository(repoData.url)

        assertNotNull(parsedRepo)
        assertEquals("Live Test Repository", parsedRepo?.name)
        assertEquals(1, parsedRepo?.pluginLists?.size)

        val wrappers = RepositoryManager.getRepoPlugins(repoData)
        assertNotNull(wrappers)
        assertEquals(1, wrappers?.size)

        val wrapper = wrappers!![0]
        assertEquals("Alpha Provider", wrapper.plugin.name)
        assertEquals("AlphaProvider", wrapper.plugin.internalName)
        assertEquals(2, wrapper.plugin.version)
        assertEquals(1024L, wrapper.plugin.fileSize)
        assertEquals("sha256-abc123", wrapper.plugin.fileHash)
        assertEquals("Live Test Repository", wrapper.repository.name)
    }

    // =========================================================================
    // 7. VotingApi Parity & Countify Hashing Tests
    // =========================================================================

    @Test
    fun testVotingApiTransformUrlHash() {
        val pluginUrl = "https://example.com/plugins/super_provider.cs3"
        val transformed = VotingApi.transformUrl(pluginUrl)

        val expected = MessageDigest.getInstance("SHA-256")
            .digest("${pluginUrl}#funny-salt".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, transformed)
    }

    @Test
    fun testVotingApiCanVoteAndHasVoted() {
        val pluginUrl = "https://example.com/plugins/alpha.cs3"
        val sitePlugin = SitePlugin(
            url = pluginUrl,
            status = 1,
            version = 1,
            apiVersion = 1,
            name = "Alpha",
            internalName = "Alpha"
        )

        // 1. Not in urlPlugins -> canVote is false
        assertFalse(VotingApi.canVote(pluginUrl))
        assertFalse(sitePlugin.canVote())

        // 2. Add dummy plugin to urlPlugins -> canVote is true
        val dummyBasePlugin = object : BasePlugin() {
            override fun load() {}
        }
        PluginManager.urlPlugins[pluginUrl] = dummyBasePlugin

        assertTrue(VotingApi.canVote(pluginUrl))
        assertTrue(sitePlugin.canVote())

        // 3. Not voted yet -> hasVoted is false
        assertFalse(VotingApi.hasVoted(pluginUrl))
        assertFalse(sitePlugin.hasVoted())

        // 4. Mark voted in DesktopDataStore
        val voteKey = "cs3-votes/${VotingApi.transformUrl(pluginUrl)}"
        DesktopDataStore.setKey(voteKey, true)

        assertTrue(VotingApi.hasVoted(pluginUrl))
        assertTrue(sitePlugin.hasVoted())
    }

    @Test
    fun testVotingApiVoteFlowWithMockServer() = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server!!.address.port
        VotingApi.API_DOMAIN = "http://127.0.0.1:$serverPort"

        var currentVotes = 5
        server!!.createContext("/get-total/") { exchange ->
            val response = """{"id":"test","count":$currentVotes}"""
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.createContext("/increment/") { exchange ->
            currentVotes++
            val response = """{"id":"test","count":$currentVotes}"""
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val pluginUrl = "https://example.com/mock.cs3"
        val sitePlugin = SitePlugin(
            url = pluginUrl,
            status = 1,
            version = 1,
            apiVersion = 1,
            name = "Mock",
            internalName = "Mock"
        )

        // 1. Uninstalled -> cannot vote, returns getVotes()
        val initialVotes = sitePlugin.vote()
        assertEquals(5, initialVotes)
        assertFalse(sitePlugin.hasVoted())

        // 2. Install plugin -> can vote
        PluginManager.urlPlugins[pluginUrl] = object : BasePlugin() { override fun load() {} }
        assertTrue(sitePlugin.canVote())

        // 3. Cast vote -> increments and persists
        val newVotes = sitePlugin.vote()
        assertEquals(6, newVotes)
        assertTrue(sitePlugin.hasVoted())

        // 4. Vote again -> already voted, does not increment
        val duplicateVotes = sitePlugin.vote()
        assertEquals(6, duplicateVotes)
    }

    // =========================================================================
    // 8. Data Models Parity and Serialization Tests
    // =========================================================================

    @Test
    fun testRepositorySerializationParity() {
        val repo = Repository(
            iconUrl = "https://example.com/icon.png",
            name = "Test Repository",
            description = "Community plugins",
            manifestVersion = 1,
            pluginLists = listOf("https://example.com/plugins.json")
        )

        // Kotlinx serialization roundtrip
        val kotlinxJson = json.encodeToString(Repository.serializer(), repo)
        val decodedKotlinx = json.decodeFromString(Repository.serializer(), kotlinxJson)
        assertEquals(repo, decodedKotlinx)

        // Jackson serialization roundtrip
        val jacksonJson = mapper.writeValueAsString(repo)
        val decodedJackson = mapper.readValue(jacksonJson, Repository::class.java)
        assertEquals(repo, decodedJackson)
    }

    @Test
    fun testSitePluginSerializationParity() {
        val sitePlugin = SitePlugin(
            url = "https://example.com/ext.cs3",
            status = 1,
            version = 3,
            apiVersion = 1,
            name = "Test Provider",
            internalName = "TestProvider",
            authors = listOf("Dev1", "Dev2"),
            description = "A fast provider",
            repositoryUrl = "https://example.com/repo.json",
            tvTypes = listOf("Movie", "TvSeries"),
            language = "en",
            iconUrl = "https://example.com/icon.png",
            fileSize = 2048576L,
            fileHash = "sha256-1234567890abcdef"
        )

        val kotlinxJson = json.encodeToString(SitePlugin.serializer(), sitePlugin)
        val decodedKotlinx = json.decodeFromString(SitePlugin.serializer(), kotlinxJson)
        assertEquals(sitePlugin, decodedKotlinx)

        val jacksonJson = mapper.writeValueAsString(sitePlugin)
        val decodedJackson = mapper.readValue(jacksonJson, SitePlugin::class.java)
        assertEquals(sitePlugin, decodedJackson)
    }

    @Test
    fun testPluginWrapperLocalWrapperHelper() {
        val sitePlugin = SitePlugin(
            url = "https://example.com/local.cs3",
            status = 1,
            version = 1,
            apiVersion = 1,
            name = "Local Provider",
            internalName = "LocalProvider"
        )

        val wrapper = PluginWrapper.getLocalPluginWrapper(sitePlugin)
        assertEquals(sitePlugin, wrapper.plugin)
        assertEquals("", wrapper.repository.name)
        assertEquals("", wrapper.repositoryData.url)
    }

    @Test
    fun testPluginDataToSitePluginConversion() {
        val pluginData = PluginData(
            internalName = "LocalPlugin",
            url = "https://example.com/local.cs3",
            isOnline = true,
            filePath = "/tmp/local.cs3",
            version = 5
        )

        val sitePlugin = pluginData.toSitePlugin()
        assertEquals("LocalPlugin", sitePlugin.internalName)
        assertEquals(5, sitePlugin.version)
        assertEquals(1, sitePlugin.status)
        assertEquals(1, sitePlugin.apiVersion)
    }
}
