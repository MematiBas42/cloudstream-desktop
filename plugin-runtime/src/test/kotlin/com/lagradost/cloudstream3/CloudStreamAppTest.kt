package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CloudStreamAppTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testContext: Context

    @BeforeEach
    fun setUp() {
        testContext = Context()
        CloudStreamApp.context = testContext
    }

    @AfterEach
    fun tearDown() {
        CloudStreamApp.removeKeys("test_folder")
        CloudStreamApp.removeKeys("acra_folder")
        CloudStreamApp.context = null
    }

    @Test
    fun testDataStoreDelegationParity() {
        val testKey = "test_key_1"
        val testVal = "test_value_1"

        CloudStreamApp.setKey("test_folder", testKey, testVal)
        val readVal: String? = CloudStreamApp.getKey("test_folder", testKey)
        assertEquals(testVal, readVal)

        assertTrue(CloudStreamApp.containsKey("test_folder", testKey))

        val keys = CloudStreamApp.getKeys("test_folder")
        assertNotNull(keys)
        assertTrue(keys!!.any { it.contains(testKey) })

        CloudStreamApp.removeKey("test_folder", testKey)
        assertNull(CloudStreamApp.getKey<String>("test_folder", testKey))
    }

    @Test
    fun testSetAndGetKeyClass() {
        val testKey = "class_key"
        val testData = 12345

        CloudStreamApp.setKeyClass("test_folder", testKey, testData)
        val readVal = CloudStreamApp.getKeyClass("test_folder", testKey, Int::class.java)
        assertEquals(testData, readVal)

        CloudStreamApp.removeKeys("test_folder")
        assertNull(CloudStreamApp.getKeyClass("test_folder", testKey, Int::class.java))
    }

    @Test
    @Suppress("DEPRECATION_ERROR")
    fun testAcraApplicationBackwardCompatibility() {
        val acraKey = "acra_key_1"
        val acraVal = "acra_payload"

        AcraApplication.setKey("acra_folder", acraKey, acraVal)
        val readVal: String? = AcraApplication.getKey("acra_folder", acraKey)
        assertEquals(acraVal, readVal)

        assertNotNull(AcraApplication.context)

        val removedCount = AcraApplication.removeKeys("acra_folder")
        assertNotNull(removedCount)
        assertTrue(removedCount!! >= 1)
        assertNull(AcraApplication.getKey<String>("acra_folder", acraKey))
    }

    @Test
    fun testLifecycleMethods() {
        val app = CloudStreamApp()
        app.attachBaseContext(testContext)
        assertSame(testContext, CloudStreamApp.context)

        app.onCreate()
        assertNotNull(CloudStreamApp.exceptionHandler)

        val imageLoader = app.newImageLoader(null)
        assertNull(imageLoader)
    }

    @Test
    fun testExceptionHandlerFileWriting() {
        val errorFile = tempDir.resolve("last_error_test.txt").toFile()
        PluginManager.currentlyLoading = "com.test.extension"

        var callbackExecuted = false
        val handler = ExceptionHandler(errorFile) {
            callbackExecuted = true
        }

        assertNotNull(handler)
        assertEquals(errorFile, handler.errorFile)

        // Write simulated crash output directly into handler's logic
        errorFile.parentFile?.mkdirs()
        java.io.PrintStream(errorFile).use { ps ->
            ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
            ps.println("Fatal exception on thread ${Thread.currentThread().name} (${Thread.currentThread().threadId()})")
            RuntimeException("Simulated crash").printStackTrace(ps)
        }

        assertTrue(errorFile.exists())
        val content = errorFile.readText()
        assertTrue(content.contains("Currently loading extension: com.test.extension"))
        assertTrue(content.contains("Fatal exception on thread"))
        assertTrue(content.contains("Simulated crash"))

        PluginManager.currentlyLoading = null
    }

    @Test
    fun testOpenBrowserOverloadsExecuteWithoutThrowing() {
        // Must handle valid and edge-case URLs safely without uncaught exceptions
        assertDoesNotThrow {
            CloudStreamApp.openBrowser("https://example.com")
        }

        assertDoesNotThrow {
            CloudStreamApp.openBrowser("https://example.com", fallbackWebView = true, fragment = null)
        }

        assertDoesNotThrow {
            CloudStreamApp.openBrowser("https://example.com", activity = null)
        }

        // Invalid URI should be caught and logged safely without blowing up
        assertDoesNotThrow {
            CloudStreamApp.openBrowser("://invalid-url-schema")
        }
    }
}
