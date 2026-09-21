package integration

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.PreferenceDelegate
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HierarchicalDataStoreTest {

    private lateinit var tempDir: Path
    private lateinit var testDataFile: File

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        tempDir = Files.createTempDirectory("cs_datastore_test_${UUID.randomUUID()}")
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testHierarchicalFolderNameFormatting() {
        assertEquals("folder/path", DesktopDataStore.getFolderName("folder", "path"))
        assertEquals("folder/path", DesktopDataStore.getFolderName("folder/", "path"))
        assertEquals("folder/path", DesktopDataStore.getFolderName("folder", "/path"))
        assertEquals("folder/path", DesktopDataStore.getFolderName("folder/", "/path"))
        assertEquals("0/watch_state/101", DesktopDataStore.getFolderName("0/watch_state", "101"))
        assertEquals("folder/path", DataStore.getFolderName("folder", "path"))
    }

    @Test
    fun testStrictTrailingSlashPrefixIsolation() {
        DesktopDataStore.setKey("plugin_test", "key1", "val1")
        DesktopDataStore.setKey("plugin_test_extra", "key2", "val2")
        DesktopDataStore.setKey("plugin_test/sub", "key3", "val3")

        val keys = DesktopDataStore.getKeys("plugin_test")

        assertTrue(keys.contains("plugin_test/key1"), "Must contain direct child")
        assertTrue(keys.contains("plugin_test/sub/key3"), "Must contain nested child")
        assertFalse(keys.contains("plugin_test_extra/key2"), "Must NOT contain sibling folder with matching prefix!")
    }

    @Test
    fun testGetKeysReturnsFullKeysAndMatchesUpstreamConsumption() {
        val account = "0"
        val folder = "$account/result_resume_watching_2"
        DesktopDataStore.setKey(folder, "101", "resume_payload_101")
        DesktopDataStore.setKey(folder, "102", "resume_payload_102")

        val keys = CloudStreamApp.Companion.getKeys(folder)
        assertNotNull(keys)
        assertEquals(2, keys!!.size)

        // Verify upstream consumption pattern: it.removePrefix("$folder/").toIntOrNull()
        val ids = keys.mapNotNull { it.removePrefix("$folder/").toIntOrNull() }
        assertEquals(setOf(101, 102), ids.toSet())

        // Verify that full keys can be fetched directly
        for (k in keys) {
            val payload = CloudStreamApp.Companion.getKey<String>(k)
            assertNotNull(payload)
            assertTrue(payload!!.startsWith("resume_payload_"))
        }
    }

    @Test
    fun testRemoveKeysNamespaceBatchPurge() {
        val folder = "test_purge_namespace"
        DesktopDataStore.setKey(folder, "item1", "val1")
        DesktopDataStore.setKey(folder, "item2", "val2")
        DesktopDataStore.setKey(folder, "item3", "val3")
        DesktopDataStore.setKey("other_namespace", "itemA", "valA")

        val removed = DesktopDataStore.removeKeys(folder)
        assertEquals(3, removed)

        val remaining = DesktopDataStore.getKeys(folder)
        assertTrue(remaining.isEmpty())
        assertTrue(DesktopDataStore.containsKey("other_namespace/itemA"))
        assertEquals(0, DesktopDataStore.removeKeys("non_existent_folder"))
    }

    @Test
    fun testCrossPluginNamespaceIsolation() {
        // Plugin A writes auth_token
        DesktopDataStore.setKey("PluginAlpha", "auth_token", "token_alpha_123")
        // Plugin B writes auth_token under its own folder
        DesktopDataStore.setKey("PluginBeta", "auth_token", "token_beta_456")

        assertEquals("token_alpha_123", DesktopDataStore.getKey<String>("PluginAlpha", "auth_token"))
        assertEquals("token_beta_456", DesktopDataStore.getKey<String>("PluginBeta", "auth_token"))

        // Plugin A removes its token
        DesktopDataStore.removeKey("PluginAlpha", "auth_token")
        assertNull(DesktopDataStore.getKey<String>("PluginAlpha", "auth_token"))
        assertEquals("token_beta_456", DesktopDataStore.getKey<String>("PluginBeta", "auth_token"), "Plugin B must remain untouched!")
    }

    @Test
    fun testBytecodeLinkageNoNoSuchMethodError() {
        // Test CloudStreamApp.Companion reflective resolution
        val companion = CloudStreamApp.Companion

        // Check getKeys method reflection
        val getKeysMethod = companion::class.java.getMethod("getKeys", String::class.java)
        assertNotNull(getKeysMethod)

        // Check removeKeys method reflection
        val removeKeysMethod = companion::class.java.getMethod("removeKeys", String::class.java)
        assertNotNull(removeKeysMethod)

        // Check setKeyClass method reflection
        val setKeyClassMethod = companion::class.java.getMethod("setKeyClass", String::class.java, Any::class.java)
        assertNotNull(setKeyClassMethod)

        // Check getKeyClass method reflection
        val getKeyClassMethod = companion::class.java.getMethod("getKeyClass", String::class.java, Class::class.java)
        assertNotNull(getKeyClassMethod)

        // Invoke dynamically
        setKeyClassMethod.invoke(companion, "reflect_folder/reflect_key", "reflect_val")
        val result = getKeysMethod.invoke(companion, "reflect_folder") as? List<*>
        assertNotNull(result)
        assertTrue(result!!.contains("reflect_folder/reflect_key"))
        assertEquals("reflect_val", getKeyClassMethod.invoke(companion, "reflect_folder/reflect_key", String::class.java))

        // Check DataStore object reflective methods
        val dataStoreClass = DataStore::class.java
        val dsGetFolderName = dataStoreClass.getMethod("getFolderName", String::class.java, String::class.java)
        assertNotNull(dsGetFolderName)
        assertEquals("test/path", dsGetFolderName.invoke(null, "test", "path"))
    }

    @Test
    fun testConcurrentWritesWithBatchEditorStress() {
        val threadCount = 8
        val keysPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        val startTime = System.currentTimeMillis()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    val editor = DesktopDataStore.edit()
                    for (k in 0 until keysPerThread) {
                        editor.setKey("concurrent_folder_$t", "key_$k", "val_${t}_$k")
                    }
                    editor.apply()
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Batch concurrent writes must complete within 5 seconds")
        assertEquals(0, errors.get(), "Zero thread write errors allowed")
        executor.shutdown()

        val elapsed = System.currentTimeMillis() - startTime
        println("Completed 200 concurrent hierarchical writes in ${elapsed}ms")

        for (t in 0 until threadCount) {
            for (k in 0 until keysPerThread) {
                assertEquals("val_${t}_$k", DesktopDataStore.getKey<String>("concurrent_folder_$t", "key_$k"))
            }
        }
    }

    @Test
    fun testPersistenceAcrossProcessRestartsUsingRealFiles() {
        // Write keys to the datastore
        DesktopDataStore.setKey("persist_folder", "str_key", "persisted_string_value")
        DesktopDataStore.setKey("persist_folder", "int_key", 42)
        DesktopDataStore.setKey("persist_folder", "bool_key", true)
        DesktopDataStore.setKey("persist_folder/sub", "nested_key", "nested_val")

        // Verify physical file on disk exists and contains content
        assertTrue(testDataFile.exists(), "Target file must exist on disk")
        assertTrue(testDataFile.length() > 0, "Target file on disk must not be empty")

        // Verify no leftover .tmp files
        val tmpFiles = tempDir.toFile().listFiles { _, name -> name.contains(".tmp.") }
        assertNotNull(tmpFiles)
        assertEquals(0, tmpFiles!!.size, "All temporary write files must be cleaned up")

        // Simulate complete process restart by clearing memory cache and reloading from file
        DesktopDataStore.reload()

        // Verify restored values
        assertEquals("persisted_string_value", DesktopDataStore.getKey<String>("persist_folder", "str_key"))
        assertEquals(42, DesktopDataStore.getKey<Int>("persist_folder", "int_key"))
        assertEquals(true, DesktopDataStore.getKey<Boolean>("persist_folder", "bool_key"))
        assertEquals("nested_val", DesktopDataStore.getKey<String>("persist_folder/sub", "nested_key"))

        val folderKeys = DesktopDataStore.getKeys("persist_folder")
        assertEquals(4, folderKeys.size)
        assertTrue(folderKeys.contains("persist_folder/str_key"))
        assertTrue(folderKeys.contains("persist_folder/int_key"))
        assertTrue(folderKeys.contains("persist_folder/bool_key"))
        assertTrue(folderKeys.contains("persist_folder/sub/nested_key"))
    }

    @Test
    fun testPreferenceDelegate() {
        var token: String by PreferenceDelegate("test_pref_token", "default_token")
        assertEquals("default_token", token)

        token = "updated_token_xyz"
        assertEquals("updated_token_xyz", token)
        assertEquals("updated_token_xyz", DesktopDataStore.getKey<String>("test_pref_token"))

        val delegate = PreferenceDelegate("test_pref_token_null", "initial")
        var nullableProp: String? by delegate
        assertEquals("initial", nullableProp)
        nullableProp = "val"
        assertEquals("val", DesktopDataStore.getKey<String>("test_pref_token_null"))
        nullableProp = null
        assertNull(DesktopDataStore.getKey<String>("test_pref_token_null"))
    }
}
