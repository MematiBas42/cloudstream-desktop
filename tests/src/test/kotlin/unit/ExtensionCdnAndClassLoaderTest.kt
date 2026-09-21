package unit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.repo.Repository
import com.lagradost.cloudstream3.desktop.repo.SitePlugin
import com.lagradost.cloudstream3.loader.SafePluginClassLoader
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.loader.ExtensionLoader
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Industrial-grade anti-mock test suite for Domains 21 & 22:
 * 1. jsDelivr URL rewriting across diverse branch, commit, tag, and nested paths.
 * 2. %size% and %exact_size% icon token substitution across 1x, 2x, 3x display densities.
 * 3. ClassLoader clean unloading confirming complete Metaspace/heap garbage collection eligibility.
 * 4. Parallel repository synchronization with error isolation.
 * 5. Cryptographic SHA-256 integrity verification.
 * 6. Repository teardown lifecycle.
 */
class ExtensionCdnAndClassLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val jsonMapper: ObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setup() {
        System.setProperty("user.home", tempDir.toString())
        PlatformPaths.init()
        DesktopDataStore.init()

        APIHolder.apis.withLock {
            APIHolder.apis = atomicListOf()
        }
        APIHolder.allProviders.withLock {
            APIHolder.allProviders.clear()
        }
        ExtensionLoader.plugins.clear()
        ExtensionLoader.classLoaders.clear()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.stop(0)
    }

    // =========================================================================
    // 1. jsDelivr URL Rewriting Across Diverse Branch/Commit Schemes
    // =========================================================================

    @Test
    fun testJsDelivrUrlRewritingVariousBranchesAndCommits() {
        // Test cases: (Input URL, Expected jsDelivr CDN URL)
        val testCases = listOf(
            // Standard master branch
            "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions/master/repo.json" to
                "https://cdn.jsdelivr.net/gh/recloudstream/cloudstream-extensions@master/repo.json",

            // Standard main branch
            "https://raw.githubusercontent.com/user-name/my_repo/main/plugins.json" to
                "https://cdn.jsdelivr.net/gh/user-name/my_repo@main/plugins.json",

            // Full 40-character commit SHA
            "https://raw.githubusercontent.com/org-team/extensions-v2/4a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b/plugins/provider.cs3" to
                "https://cdn.jsdelivr.net/gh/org-team/extensions-v2@4a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b/plugins/provider.cs3",

            // Semantic version release tag
            "https://raw.githubusercontent.com/author/repo/v2.1.0/builds/plugin.jar" to
                "https://cdn.jsdelivr.net/gh/author/repo@v2.1.0/builds/plugin.jar",

            // Nested branch name with slashes
            "https://raw.githubusercontent.com/team/core/feature/experimental-ui/metadata.json" to
                "https://cdn.jsdelivr.net/gh/team/core@feature/experimental-ui/metadata.json",

            // Repository name containing dots, dashes, and underscores
            "https://raw.githubusercontent.com/alpha-beta/repo.name_v3/release-1.0.0-rc1/data.json" to
                "https://cdn.jsdelivr.net/gh/alpha-beta/repo.name_v3@release-1.0.0-rc1/data.json",

            // Deep directory path
            "https://raw.githubusercontent.com/owner/repo/main/a/b/c/d/e/file.txt" to
                "https://cdn.jsdelivr.net/gh/owner/repo@main/a/b/c/d/e/file.txt"
        )

        // Gate 1: When jsDelivr proxy is DISABLED, URLs MUST remain unmodified
        DesktopRepositoryManager.setJsDelivrProxyEnabled(false)
        assertFalse(DesktopRepositoryManager.isJsDelivrProxyEnabled())
        for ((rawUrl, _) in testCases) {
            val rewritten = DesktopRepositoryManager.convertRawGitUrl(rawUrl)
            assertEquals(rawUrl, rewritten, "Disabled proxy must not alter raw URL: $rawUrl")
        }

        // Gate 2: When jsDelivr proxy is ENABLED, URLs MUST be deterministically converted
        DesktopRepositoryManager.setJsDelivrProxyEnabled(true)
        assertTrue(DesktopRepositoryManager.isJsDelivrProxyEnabled())
        for ((rawUrl, expectedCdn) in testCases) {
            val rewritten = DesktopRepositoryManager.convertRawGitUrl(rawUrl)
            assertEquals(expectedCdn, rewritten, "Enabled proxy must convert to jsDelivr CDN: $rawUrl")
        }

        // Gate 3: Force flag overrides disabled state
        DesktopRepositoryManager.setJsDelivrProxyEnabled(false)
        for ((rawUrl, expectedCdn) in testCases) {
            val forced = DesktopRepositoryManager.convertRawGitUrl(rawUrl, force = true)
            assertEquals(expectedCdn, forced, "Forced rewrite must convert even if proxy is disabled")
        }

        // Gate 4: Non-GitHub URLs MUST remain unchanged regardless of proxy state
        DesktopRepositoryManager.setJsDelivrProxyEnabled(true)
        val nonGithubUrls = listOf(
            "https://gitlab.com/user/repo/raw/main/repo.json",
            "https://cs3-repo.vercel.app/repo.json",
            "https://cs3-repo.onrender.com/repo.json",
            "http://127.0.0.1:8080/manifest.json",
            "https://example.com/raw.githubusercontent.com/fake/path",
            "https://cdn.jsdelivr.net/gh/user/repo@main/repo.json"
        )
        for (url in nonGithubUrls) {
            assertEquals(url, DesktopRepositoryManager.convertRawGitUrl(url), "Non-GitHub URL must remain untouched: $url")
        }
    }

    // =========================================================================
    // 2. %size% and %exact_size% Token Substitution at 1x, 2x, 3x Densities
    // =========================================================================

    @Test
    fun testFindClosestBase2BoundaryValues() {
        // Values <= 16 clamp to 16
        assertEquals(16, SafePluginClassLoader.findClosestBase2(-10))
        assertEquals(16, SafePluginClassLoader.findClosestBase2(0))
        assertEquals(16, SafePluginClassLoader.findClosestBase2(1))
        assertEquals(16, SafePluginClassLoader.findClosestBase2(15))
        assertEquals(16, SafePluginClassLoader.findClosestBase2(16))

        // Range 17..32 -> 32
        assertEquals(32, SafePluginClassLoader.findClosestBase2(17))
        assertEquals(32, SafePluginClassLoader.findClosestBase2(24))
        assertEquals(32, SafePluginClassLoader.findClosestBase2(32))

        // Range 33..64 -> 64
        assertEquals(64, SafePluginClassLoader.findClosestBase2(33))
        assertEquals(64, SafePluginClassLoader.findClosestBase2(48))
        assertEquals(64, SafePluginClassLoader.findClosestBase2(50))
        assertEquals(64, SafePluginClassLoader.findClosestBase2(64))

        // Range 65..128 -> 128
        assertEquals(128, SafePluginClassLoader.findClosestBase2(65))
        assertEquals(128, SafePluginClassLoader.findClosestBase2(96))
        assertEquals(128, SafePluginClassLoader.findClosestBase2(100))
        assertEquals(128, SafePluginClassLoader.findClosestBase2(128))

        // Range 129..256 -> 256
        assertEquals(256, SafePluginClassLoader.findClosestBase2(129))
        assertEquals(256, SafePluginClassLoader.findClosestBase2(150))
        assertEquals(256, SafePluginClassLoader.findClosestBase2(200))
        assertEquals(256, SafePluginClassLoader.findClosestBase2(256))

        // Range 257..512 -> 512
        assertEquals(512, SafePluginClassLoader.findClosestBase2(257))
        assertEquals(512, SafePluginClassLoader.findClosestBase2(384))
        assertEquals(512, SafePluginClassLoader.findClosestBase2(512))

        // Values > 512 clamp to 512
        assertEquals(512, SafePluginClassLoader.findClosestBase2(513))
        assertEquals(512, SafePluginClassLoader.findClosestBase2(1024))
        assertEquals(512, SafePluginClassLoader.findClosestBase2(2048))
    }

    @Test
    fun testIconUrlEvaluationAcrossDensities() {
        val dualTemplate = "https://cdn.jsdelivr.net/gh/user/repo@main/icons/%size%/icon_%exact_size%.png"
        val sizeOnlyTemplate = "https://cdn.example.com/assets/%size%/logo.png"
        val exactOnlyTemplate = "https://cdn.example.com/img?w=%exact_size%&h=%exact_size%"

        // ---------------------------------------------------------------------
        // 1x Density (1.0f)
        // ---------------------------------------------------------------------
        // 32dp @ 1x: exact = 32, base2 = 32
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/32/icon_32.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 32, density = 1.0f)
        )
        // 48dp @ 1x: exact = 48, base2 = 64
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/64/icon_48.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 48, density = 1.0f)
        )
        // 50dp @ 1x: exact = 50, base2 = 64
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/64/icon_50.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 50, density = 1.0f)
        )

        // ---------------------------------------------------------------------
        // 2x Density (2.0f) - 1080p / 2K Display Scaling
        // ---------------------------------------------------------------------
        // 32dp @ 2x: exact = 64, base2 = 64
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/64/icon_64.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 32, density = 2.0f)
        )
        // 48dp @ 2x: exact = 96, base2 = 128
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/128/icon_96.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 48, density = 2.0f)
        )
        // 50dp @ 2x: exact = 100, base2 = 128
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/128/icon_100.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 50, density = 2.0f)
        )

        // ---------------------------------------------------------------------
        // 3x Density (3.0f) - 4K TV UI Scaling
        // ---------------------------------------------------------------------
        // 32dp @ 3x: exact = 96, base2 = 128
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/128/icon_96.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 32, density = 3.0f)
        )
        // 48dp @ 3x: exact = 144, base2 = 256
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/256/icon_144.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 48, density = 3.0f)
        )
        // 50dp @ 3x: exact = 150, base2 = 256
        assertEquals(
            "https://cdn.jsdelivr.net/gh/user/repo@main/icons/256/icon_150.png",
            SafePluginClassLoader.resolveIconUrl(dualTemplate, targetDp = 50, density = 3.0f)
        )

        // ---------------------------------------------------------------------
        // Single Token & Edge Case Verification
        // ---------------------------------------------------------------------
        assertEquals(
            "https://cdn.example.com/assets/128/logo.png",
            SafePluginClassLoader.resolveIconUrl(sizeOnlyTemplate, targetDp = 32, density = 3.0f)
        )
        assertEquals(
            "https://cdn.example.com/img?w=100&h=100",
            SafePluginClassLoader.resolveIconUrl(exactOnlyTemplate, targetDp = 50, density = 2.0f)
        )

        // Static URL without tokens must return unmodified
        val staticUrl = "https://static.example.com/logo.png"
        assertEquals(staticUrl, SafePluginClassLoader.resolveIconUrl(staticUrl, 32, 2.0f))

        // Null and blank handling
        assertNull(SafePluginClassLoader.resolveIconUrl(null, 32))
        assertNull(SafePluginClassLoader.resolveIconUrl("", 32))
        assertNull(SafePluginClassLoader.resolveIconUrl("   ", 32))

        // evaluateIconUrl alias behaves identically
        assertEquals(
            "https://cdn.example.com/assets/64/logo.png",
            SafePluginClassLoader.evaluateIconUrl(sizeOnlyTemplate, targetDp = 32, density = 2.0f)
        )
    }

    // =========================================================================
    // 3. ClassLoader Disposal Confirming Garbage Collection Eligibility
    // =========================================================================

    @Test
    fun testClassLoaderDisposalAndGarbageCollectionEligibility() {
        val jarFile = tempDir.resolve("gc-test-plugin.jar").toFile()
        val className = "com.test.dynamic.GcTestPlugin"
        val internalClassName = className.replace('.', '/')

        // 1. Generate real bytecode for a class extending BasePlugin using ASM
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalClassName,
            null,
            "com/lagradost/cloudstream3/plugins/BasePlugin",
            null
        )

        // Default constructor
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/lagradost/cloudstream3/plugins/BasePlugin", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()

        // Static singleton field to verify static cache wiping
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "STATIC_INSTANCE", "L$internalClassName;", null, null).visitEnd()

        cw.visitEnd()
        val classBytes = cw.toByteArray()

        val manifestContent = """
            {
                "name": "GC Test Plugin",
                "internalName": "GcTestPlugin",
                "pluginClassName": "$className",
                "version": 1
            }
        """.trimIndent()

        createZipArchive(
            jarFile,
            mapOf(
                "manifest.json" to manifestContent.toByteArray(),
                "$internalClassName.class" to classBytes
            )
        )

        // 2. Load plugin using ExtensionLoader
        var pluginInstance: BasePlugin? = ExtensionLoader.loadAndInit(jarFile)
        assertNotNull(pluginInstance)
        assertTrue(ExtensionLoader.isPluginLoaded(jarFile.absolutePath))

        // Set static instance field on the class
        var loadedClass: Class<*>? = pluginInstance!!.javaClass
        var staticField: java.lang.reflect.Field? = loadedClass!!.getDeclaredField("STATIC_INSTANCE")
        staticField!!.isAccessible = true
        staticField!!.set(null, pluginInstance)
        assertNotNull(staticField!!.get(null))

        var classLoader: ClassLoader? = loadedClass!!.classLoader
        assertNotNull(classLoader)

        // 3. Establish WeakReferences to assert GC reclamation
        val weakInstance = WeakReference(pluginInstance)
        val weakClass = WeakReference(loadedClass)
        val weakClassLoader = WeakReference(classLoader)

        // Verify references are initially alive
        assertNotNull(weakInstance.get())
        assertNotNull(weakClass.get())
        assertNotNull(weakClassLoader.get())

        // 4. Trigger clean unloading
        val unloaded = ExtensionLoader.unloadPlugin(jarFile.absolutePath)
        assertTrue(unloaded, "Plugin must report successful unload")
        assertFalse(ExtensionLoader.isPluginLoaded(jarFile.absolutePath))

        // 5. Clear all local strong references
        pluginInstance = null
        loadedClass = null
        classLoader = null
        staticField = null

        // 6. Force GC stress cycles to reclaim heap & Metaspace
        for (i in 1..20) {
            System.gc()
            System.runFinalization()
            if (weakClassLoader.get() == null && weakClass.get() == null && weakInstance.get() == null) {
                break
            }
            Thread.sleep(50)
        }

        // 7. Assert total reclamation: ClassLoader, Class, and Instance must all be eligible and collected
        assertNull(weakInstance.get(), "Plugin instance leaked in heap memory!")
        assertNull(weakClass.get(), "Plugin Class<?> leaked in JVM Metaspace!")
        assertNull(weakClassLoader.get(), "Plugin ClassLoader leaked in JVM Metaspace!")
    }

    // =========================================================================
    // 4. Parallel Repository Synchronization with Error Isolation
    // =========================================================================

    @Test
    fun testParallelRepositorySynchronizationWithErrorIsolation() = runBlocking {
        // Setup Repo 1: Healthy repository returning 2 plugins
        val repo1Plugins = listOf(
            SitePlugin(name = "Repo1PluginA", internalName = "R1A", url = "http://127.0.0.1:$serverPort/p1.cs3"),
            SitePlugin(name = "Repo1PluginB", internalName = "R1B", url = "http://127.0.0.1:$serverPort/p2.cs3")
        )
        server.createContext("/repo1/repo.json") { exchange ->
            val manifest = Repository(name = "Repo 1", pluginLists = listOf("http://127.0.0.1:$serverPort/repo1/plugins.json"))
            val bytes = jsonMapper.writeValueAsBytes(manifest)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.createContext("/repo1/plugins.json") { exchange ->
            val bytes = jsonMapper.writeValueAsBytes(repo1Plugins)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        // Setup Repo 2: Broken/Failing repository (simulating DNS block, 500 error, or timeout)
        server.createContext("/repo2/repo.json") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.close()
        }

        // Setup Repo 3: Healthy repository returning 3 plugins
        val repo3Plugins = listOf(
            SitePlugin(name = "Repo3PluginA", internalName = "R3A", url = "http://127.0.0.1:$serverPort/p3.cs3"),
            SitePlugin(name = "Repo3PluginB", internalName = "R3B", url = "http://127.0.0.1:$serverPort/p4.cs3"),
            SitePlugin(name = "Repo3PluginC", internalName = "R3C", url = "http://127.0.0.1:$serverPort/p5.cs3")
        )
        server.createContext("/repo3/repo.json") { exchange ->
            val manifest = Repository(name = "Repo 3", pluginLists = listOf("http://127.0.0.1:$serverPort/repo3/plugins.json"))
            val bytes = jsonMapper.writeValueAsBytes(manifest)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.createContext("/repo3/plugins.json") { exchange ->
            val bytes = jsonMapper.writeValueAsBytes(repo3Plugins)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }

        // Register all three repositories in DataStore
        val repo1Data = RepositoryData(name = "Repo 1", url = "http://127.0.0.1:$serverPort/repo1/repo.json")
        val repo2Data = RepositoryData(name = "Repo 2 (Failing)", url = "http://127.0.0.1:$serverPort/repo2/repo.json")
        val repo3Data = RepositoryData(name = "Repo 3", url = "http://127.0.0.1:$serverPort/repo3/repo.json")

        DesktopDataStore.setKey(DesktopRepositoryManager.REPOSITORIES_KEY, arrayOf(repo1Data, repo2Data, repo3Data))
        DesktopRepositoryManager.saveRepository(repo1Data)
        DesktopRepositoryManager.saveRepository(repo2Data)
        DesktopRepositoryManager.saveRepository(repo3Data)

        // Execute parallel synchronization
        val initialGeneration = DesktopRepositoryManager.syncGeneration.value
        val totalSynced = DesktopRepositoryManager.syncAll()

        // Assertions:
        // Repo 2's HTTP 500 error must NOT impede Repo 1 and Repo 3.
        // Total synced plugins should be 2 (from Repo 1) + 0 (from Repo 2) + 3 (from Repo 3) = 5.
        assertEquals(5, totalSynced, "Parallel sync must isolate Repo 2's failure and sync Repos 1 and 3")
        assertTrue(DesktopRepositoryManager.syncGeneration.value > initialGeneration, "syncGeneration must increment after syncAll")

        val allPlugins = DesktopRepositoryManager.getAllPlugins()
        assertEquals(5, allPlugins.size)
        assertTrue(allPlugins.any { it.second.name == "Repo1PluginA" })
        assertTrue(allPlugins.any { it.second.name == "Repo3PluginC" })
    }

    // =========================================================================
    // 5. Cryptographic SHA-256 Validation and Atomic File Move
    // =========================================================================

    @Test
    fun testCryptographicSha256IntegrityValidation() = runBlocking {
        val dummyContent = "Encrypted-Or-Plain-Bytecode-Payload-Data".toByteArray()
        val dummyFile = tempDir.resolve("temp-plugin.cs3").toFile().apply { writeBytes(dummyContent) }
        val correctHash = DesktopRepositoryManager.sha256(dummyFile)

        assertTrue(correctHash.startsWith("sha256-"))

        server.createContext("/download/plugin.cs3") { exchange ->
            exchange.sendResponseHeaders(200, dummyContent.size.toLong())
            exchange.responseBody.write(dummyContent)
            exchange.close()
        }

        val pluginUrl = "http://127.0.0.1:$serverPort/download/plugin.cs3"
        val repoUrl = "http://127.0.0.1:$serverPort/repo.json"

        // Scenario A: Checksum Mismatch -> Download must be rejected and temp file deleted
        val corruptedPlugin = SitePlugin(
            name = "CorruptedPlugin",
            internalName = "CorruptedPlugin",
            url = pluginUrl,
            fileHash = "sha256-0000000000000000000000000000000000000000000000000000000000000000"
        )
        val resultA = DesktopRepositoryManager.downloadPlugin(repoUrl, corruptedPlugin)
        assertNull(resultA, "Corrupted plugin download with hash mismatch must fail")

        // Scenario B: Matching Checksum on a valid plugin archive -> Download and injection must succeed
        val validJar = tempDir.resolve("valid-package.cs3").toFile()
        createValidPluginJar(validJar, "com.sample.ValidPlugin")
        val validJarBytes = validJar.readBytes()
        val validJarHash = DesktopRepositoryManager.sha256(validJar)

        server.createContext("/download/valid.cs3") { exchange ->
            exchange.sendResponseHeaders(200, validJarBytes.size.toLong())
            exchange.responseBody.write(validJarBytes)
            exchange.close()
        }

        val goodPlugin = SitePlugin(
            name = "ValidPlugin",
            internalName = "ValidPlugin",
            url = "http://127.0.0.1:$serverPort/download/valid.cs3",
            fileHash = validJarHash
        )
        val resultB = DesktopRepositoryManager.downloadPlugin(repoUrl, goodPlugin)
        assertNotNull(resultB, "Verified plugin binary must download successfully")
        assertTrue(resultB!!.exists())
        assertEquals(validJarHash, DesktopRepositoryManager.sha256(resultB))
    }

    // =========================================================================
    // 6. Complete Repository Deletion & Teardown Lifecycle
    // =========================================================================

    @Test
    fun testRepositoryTeardownFullLifecycle() = runBlocking {
        val repoUrl = "http://127.0.0.1:$serverPort/teardown/repo.json"
        val repoData = RepositoryData("Teardown Repo", repoUrl)

        // 1. Add repository
        DesktopRepositoryManager.saveRepository(repoData)
        assertTrue(DesktopRepositoryManager.getSavedRepositories().any { it.url == repoUrl })

        // 2. Install a dummy plugin into the repository folder
        val repoFolder = DesktopRepositoryManager.getRepositoryFolder(repoUrl).apply { mkdirs() }
        val pluginFile = File(repoFolder, "${DesktopRepositoryManager.getPluginSanitizedFileName("TeardownPlugin")}.jar")
        createValidPluginJar(pluginFile, "com.teardown.TeardownPlugin")

        // 3. Register dummy provider in APIHolder to simulate active execution
        val dummyProvider = object : MainAPI() {
            override var name = "TeardownProvider"
            override var mainUrl = "https://teardown.com"
            override var supportedTypes = setOf(TvType.Movie)
        }
        dummyProvider.sourcePlugin = pluginFile.absolutePath
        APIHolder.allProviders.add(dummyProvider)
        APIHolder.addPluginMapping(dummyProvider)

        assertTrue(APIHolder.allProviders.any { it.sourcePlugin == pluginFile.absolutePath })
        assertTrue(APIHolder.apis.any { it.sourcePlugin == pluginFile.absolutePath })

        // 4. Trigger Full Teardown
        DesktopRepositoryManager.removeRepository(repoData)

        // 5. Assert Lifecycle Postconditions:
        // - Evicted from DataStore
        assertFalse(DesktopRepositoryManager.getSavedRepositories().any { it.url == repoUrl }, "Repo must be evicted from DataStore")
        // - Physical directory deleted from disk
        assertFalse(repoFolder.exists(), "Repository folder must be deleted from filesystem")
        assertFalse(pluginFile.exists(), "Plugin file must be deleted")
        // - Provider purged from APIHolder
        assertFalse(APIHolder.allProviders.any { it.sourcePlugin == pluginFile.absolutePath }, "Provider must be removed from APIHolder.allProviders")
        assertFalse(APIHolder.apis.any { it.sourcePlugin == pluginFile.absolutePath }, "Provider must be removed from APIHolder.apis")
    }

    // =========================================================================
    // Helper Utilities
    // =========================================================================

    private fun createZipArchive(destFile: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(destFile)).use { zos ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun createValidPluginJar(destFile: File, pluginClassName: String) {
        val internalClassName = pluginClassName.replace('.', '/')
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            internalClassName,
            null,
            "com/lagradost/cloudstream3/plugins/BasePlugin",
            null
        )
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/lagradost/cloudstream3/plugins/BasePlugin", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()

        val classBytes = cw.toByteArray()
        val manifest = """
            {
                "name": "${destFile.nameWithoutExtension}",
                "pluginClassName": "$pluginClassName",
                "version": 1
            }
        """.trimIndent()

        createZipArchive(
            destFile,
            mapOf(
                "manifest.json" to manifest.toByteArray(),
                "$internalClassName.class" to classBytes
            )
        )
    }
}
