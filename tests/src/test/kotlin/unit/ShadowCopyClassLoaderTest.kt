package unit

import com.lagradost.cloudstream3.loader.PluginShadowManager
import com.lagradost.cloudstream3.loader.SafePluginClassLoader
import com.lagradost.common.platform.PlatformPaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Enterprise Windows Shadow Copy (Copy-on-Load) Validation Test Suite.
 *
 * Direct, zero-mock tests verifying that the JVM URLClassLoader never holds open
 * file handles on original plugin JAR files, preventing ERROR_SHARING_VIOLATION
 * lockouts on Windows NTFS during deletion, hot updates, and startup cleanup.
 */
class ShadowCopyClassLoaderTest {

    private lateinit var testWorkDir: Path
    private lateinit var pluginsDir: File
    private lateinit var shadowDir: File

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        testWorkDir = Files.createTempDirectory("cs3_shadow_test_")
        pluginsDir = File(testWorkDir.toFile(), "plugins").apply { mkdirs() }
        shadowDir = PlatformPaths.pluginsShadowDir.toFile().apply { mkdirs() }
        PluginShadowManager.customShadowDir = shadowDir
    }

    @AfterEach
    fun tearDown() {
        PluginShadowManager.cleanRuntimeCache()
        PluginShadowManager.customShadowDir = null
        testWorkDir.toFile().deleteRecursively()
    }

    /**
     * Creates a minimal valid test JAR file with a manifest and dummy resource.
     */
    private fun createTestJar(file: File, internalName: String, version: Int): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name("Plugin-Internal-Name")] = internalName
            mainAttributes[Attributes.Name("Plugin-Version")] = version.toString()
        }
        JarOutputStream(FileOutputStream(file), manifest).use { jos ->
            val manifestJson = """{"internalName":"$internalName","version":$version}"""
            jos.putNextEntry(JarEntry("manifest.json"))
            jos.write(manifestJson.toByteArray())
            jos.closeEntry()

            jos.putNextEntry(JarEntry("res/dummy.txt"))
            jos.write("CloudStream Test Payload v$version".toByteArray())
            jos.closeEntry()
        }
        return file
    }

    // =========================================================================
    // 1. Delete Original JAR While Loaded & Executing
    // =========================================================================

    @Test
    @DisplayName("Original JAR can be immediately deleted while plugin is loaded and executing")
    fun testDeleteOriginalJarWhilePluginIsLoadedAndExecuting() {
        val originalJar = File(pluginsDir, "ProviderA.jar")
        createTestJar(originalJar, "ProviderA", 1)
        assertTrue(originalJar.exists(), "Original JAR must exist on disk")

        // 1. Create shadow copy
        val shadowJar = PluginShadowManager.createShadowCopy(originalJar, "ProviderA")
        assertTrue(shadowJar.exists(), "Shadow copy JAR must be created")
        assertNotEquals(originalJar.absolutePath, shadowJar.absolutePath, "Shadow path must differ from original path")

        // 2. Instantiate ClassLoader pointing to shadow copy
        val parentLoader = SafePluginClassLoader(javaClass.classLoader, bypassReflection = true)
        val classLoader = URLClassLoader(arrayOf(shadowJar.toURI().toURL()), parentLoader)

        // 3. Read resource from shadow copy (activates file handle)
        val resourceStream = classLoader.getResourceAsStream("res/dummy.txt")
        assertNotNull(resourceStream, "Resource should be readable from shadow JAR")
        val content = resourceStream?.reader()?.readText()
        assertTrue(content?.contains("CloudStream Test Payload v1") == true)

        // 4. CRITICAL: Delete original JAR while ClassLoader is still actively open
        val deleteSuccess = originalJar.delete()
        assertTrue(deleteSuccess, "Original JAR must be deletable while plugin is loaded")
        assertFalse(originalJar.exists(), "Original JAR must be removed from disk")

        // 5. Plugin can still read resources from shadow copy
        val secondaryStream = classLoader.getResourceAsStream("manifest.json")
        assertNotNull(secondaryStream, "Shadow copy must remain accessible to classloader")

        // 6. Cleanup
        classLoader.close()
        shadowJar.delete()
    }

    // =========================================================================
    // 2. Hot Overwrite / Background Update
    // =========================================================================

    @Test
    @DisplayName("Original JAR can be overwritten in background during hot update while executing")
    fun testOverwriteOriginalJarDuringHotUpdate() {
        val originalJar = File(pluginsDir, "ProviderB.jar")
        createTestJar(originalJar, "ProviderB", 1)

        // Load version 1 via shadow copy
        val shadowJarV1 = PluginShadowManager.createShadowCopy(originalJar, "ProviderB")
        val classLoaderV1 = URLClassLoader(arrayOf(shadowJarV1.toURI().toURL()), javaClass.classLoader)
        assertNotNull(classLoaderV1.getResourceAsStream("res/dummy.txt"))

        // Overwrite original file with version 2 (simulating in-flight background update)
        assertDoesNotThrow {
            createTestJar(originalJar, "ProviderB", 2)
        }
        assertTrue(originalJar.exists(), "Original JAR should exist with updated version")

        // Create shadow copy for version 2
        val shadowJarV2 = PluginShadowManager.createShadowCopy(originalJar, "ProviderB")
        assertNotEquals(shadowJarV1.name, shadowJarV2.name, "New version must produce a distinct shadow filename")

        val classLoaderV2 = URLClassLoader(arrayOf(shadowJarV2.toURI().toURL()), javaClass.classLoader)
        val v2Content = classLoaderV2.getResourceAsStream("res/dummy.txt")?.reader()?.readText()
        assertTrue(v2Content?.contains("v2") == true, "New ClassLoader must load version 2 content")

        // Cleanup
        classLoaderV1.close()
        classLoaderV2.close()
    }

    // =========================================================================
    // 3. Stale Shadow Files Purge on Startup / Shutdown
    // =========================================================================

    @Test
    @DisplayName("Stale shadow files from prior sessions or crashes are purged by cleanRuntimeCache")
    fun testStartupStaleShadowFilesPurge() {
        val stale1 = File(shadowDir, "OldPlugin_100_abc.jar").apply { writeText("stale1") }
        val stale2 = File(shadowDir, "DeadPlugin_200_def.jar").apply { writeText("stale2") }
        assertTrue(stale1.exists() && stale2.exists(), "Stale test shadow files must exist")

        val purged = PluginShadowManager.cleanRuntimeCache()
        assertTrue(purged >= 2, "At least 2 stale shadow files must be purged")
        assertFalse(stale1.exists(), "stale1 must be deleted")
        assertFalse(stale2.exists(), "stale2 must be deleted")
    }

    // =========================================================================
    // 4. URLConnection Default Caching Disabled
    // =========================================================================

    @Test
    @DisplayName("JarURLConnection default caching must be disabled")
    fun testJarURLConnectionDefaultCachingDisabled() {
        val testUrl = URI("jar:file:/fake.jar!/").toURL()
        val conn = testUrl.openConnection()
        assertFalse(conn.defaultUseCaches, "JarURLConnection defaultUseCaches must be false")
    }

    // =========================================================================
    // 5. SafePluginClassLoader Constructor Shadowing
    // =========================================================================

    @Test
    @DisplayName("SafePluginClassLoader(jarFile) transparently uses shadow copy")
    fun testSafePluginClassLoaderUsesShadowCopy() {
        val originalJar = File(pluginsDir, "ProviderC.jar")
        createTestJar(originalJar, "ProviderC", 1)

        val loader = SafePluginClassLoader(originalJar, javaClass.classLoader, bypassReflection = true)
        val urls = loader.urLs
        assertTrue(urls.isNotEmpty(), "ClassLoader URLs must not be empty")

        val loadedFile = File(urls[0].toURI())
        assertTrue(PluginShadowManager.isShadowFile(loadedFile), "Loaded file must be in shadow directory")
        assertNotEquals(originalJar.absolutePath, loadedFile.absolutePath, "Loaded file must not be original JAR")

        // Original file must be immediately deletable
        assertTrue(originalJar.delete(), "Original JAR must be deletable while SafePluginClassLoader is open")

        loader.close()
    }

    // =========================================================================
    // 6. Cache Hit Reuses Identical Shadow File
    // =========================================================================

    @Test
    @DisplayName("Cache hit returns identical shadow file when original JAR is unchanged")
    fun testCacheHitReusesExistingShadowCopy() {
        val originalJar = File(pluginsDir, "ProviderD.jar")
        createTestJar(originalJar, "ProviderD", 1)

        val shadow1 = PluginShadowManager.createShadowCopy(originalJar, "ProviderD")
        val shadow2 = PluginShadowManager.createShadowCopy(originalJar, "ProviderD")

        assertEquals(shadow1.absolutePath, shadow2.absolutePath, "Unchanged JAR must reuse existing shadow copy")
    }

    // =========================================================================
    // 7. Delete Shadow Copies by Plugin Name
    // =========================================================================

    @Test
    @DisplayName("deleteShadowCopies deletes all shadow versions for given plugin")
    fun testDeleteShadowCopiesRemovesMatchingFiles() {
        val originalJar = File(pluginsDir, "ProviderE.jar")
        createTestJar(originalJar, "ProviderE", 1)

        val shadow = PluginShadowManager.createShadowCopy(originalJar, "ProviderE")
        assertTrue(shadow.exists(), "Shadow file must exist before deletion")

        val deleted = PluginShadowManager.deleteShadowCopies(originalJar, "ProviderE")
        assertTrue(deleted >= 1, "At least one shadow copy must be deleted")
        assertFalse(shadow.exists(), "Shadow copy must be removed from disk")
    }
}
