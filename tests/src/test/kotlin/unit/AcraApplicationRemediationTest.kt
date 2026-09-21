package unit

import android.content.Context
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.ExceptionHandler
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@Suppress("DEPRECATION_ERROR")
class AcraApplicationRemediationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var testContext: Context
    private var originalUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun setUp() {
        originalUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        PlatformPaths.init()
        DesktopDataStore.init()
        testContext = Context()
        CloudStreamApp.context = testContext
    }

    @AfterEach
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalUncaughtExceptionHandler)
        CloudStreamApp.removeKeys("test_acra_folder")
        CloudStreamApp.removeKeys("test_folder_2")
        CloudStreamApp.context = null
        PluginManager.currentlyLoading = null
    }

    @Test
    fun testAcraApplicationContextDelegation() {
        assertSame(CloudStreamApp.context, AcraApplication.context)
        assertNotNull(AcraApplication.context)

        val customContext = Context()
        CloudStreamApp.context = customContext
        assertSame(customContext, AcraApplication.context)
    }

    @Test
    fun testAcraApplicationKeyValueOverloadsParity() {
        val folder = "test_acra_folder"
        val path1 = "key_simple"
        val path2 = "key_with_def"
        val val1 = "acra_data_123"
        val val2 = "acra_data_456"

        // 1. Single-arg and 2-arg setKey & getKey without folder
        AcraApplication.setKey(path1, val1)
        val read1: String? = AcraApplication.getKey(path1)
        assertEquals(val1, read1)

        val readDefFallback: String? = AcraApplication.getKey("non_existent_key", "default_val")
        assertEquals("default_val", readDefFallback)

        // 2. Folder-scoped setKey & getKey with exact overloads
        AcraApplication.setKey(folder, path2, val2)
        val read2: String? = AcraApplication.getKey(folder, path2)
        assertEquals(val2, read2)

        val readFolderDefFallback: String? = AcraApplication.getKey(folder, "non_existent_path", "folder_default")
        assertEquals("folder_default", readFolderDefFallback)

        // 3. removeKey and removeKeys
        AcraApplication.removeKey(path1)
        assertNull(AcraApplication.getKey<String>(path1))

        val removed = AcraApplication.removeKeys(folder)
        assertNotNull(removed)
        assertTrue(removed!! >= 1)
        assertNull(AcraApplication.getKey<String>(folder, path2))
    }

    @Test
    fun testAcraApplicationDesktopInitializationAndExceptionHandlerParity() {
        // Test AcraApplication.init() sets Thread.setDefaultUncaughtExceptionHandler
        AcraApplication.init(testContext)

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(handler, "Thread.defaultUncaughtExceptionHandler must be initialized")
        assertTrue(handler is ExceptionHandler, "Handler must be an instance of ExceptionHandler")
        assertNotNull(CloudStreamApp.exceptionHandler, "CloudStreamApp.exceptionHandler must be populated")

        // Test re-initialization via initializeExceptionHandler alias
        AcraApplication.initializeExceptionHandler(testContext)
        assertNotNull(CloudStreamApp.exceptionHandler)

        // Test instance onCreate() lifecycle call
        val acraApp = AcraApplication()
        assertDoesNotThrow {
            acraApp.onCreate()
        }
    }

    @Test
    fun testUnhandledExceptionLogsToAppLogWithoutCrashingJvm() {
        val errorFile = tempDir.resolve("last_error_remediation.txt").toFile()
        var callbackInvoked = false

        val handler = ExceptionHandler(
            errorFile = errorFile,
            exitOnCrash = false,
            onError = { callbackInvoked = true }
        )

        val syntheticException = RuntimeException("CRITICAL_SIMULATED_DESKTOP_EXCEPTION_TEST")
        val workerThread = Thread({
            // Empty runnable for thread metadata
        }, "TestWorkerThread-42")

        // Trigger uncaughtException - MUST NOT CRASH JVM PROCESS
        assertDoesNotThrow {
            handler.uncaughtException(workerThread, syntheticException)
        }

        // 1. Verify onError callback was executed
        assertTrue(callbackInvoked, "onError callback must be executed on unhandled exception")

        // 2. Verify errorFile (filesDir/last_error) was written
        assertTrue(errorFile.exists(), "Diagnostic errorFile must exist")
        val errorContent = errorFile.readText()
        assertTrue(errorContent.contains("Fatal exception on thread TestWorkerThread-42"), "Error file must record thread")
        assertTrue(errorContent.contains("CRITICAL_SIMULATED_DESKTOP_EXCEPTION_TEST"), "Error file must record exception message")
        assertTrue(errorContent.contains("RuntimeException"), "Error file must record exception class")

        // 3. Verify PlatformPaths.logDir/app.log was written and formatted with stack traces
        val appLogFile = PlatformPaths.logDir.resolve("app.log").toFile()
        assertTrue(appLogFile.exists(), "PlatformPaths.logDir/app.log must exist")
        val logContent = appLogFile.readText()
        assertTrue(logContent.contains("[FATAL]"), "app.log must contain [FATAL] level tag")
        assertTrue(logContent.contains("TestWorkerThread-42"), "app.log must record worker thread name")
        assertTrue(logContent.contains("CRITICAL_SIMULATED_DESKTOP_EXCEPTION_TEST"), "app.log must record exception message")
        assertTrue(logContent.contains("RuntimeException"), "app.log must record exception class")
        assertTrue(logContent.contains("at unit.AcraApplicationRemediationTest"), "app.log must include formatted stack trace")
    }

    @Test
    fun testUnhandledExceptionCapturesLoadingExtension() {
        val errorFile = tempDir.resolve("last_error_plugin_test.txt").toFile()
        val pluginId = "com.lagradost.remediation.testplugin"
        PluginManager.currentlyLoading = pluginId

        val handler = ExceptionHandler(errorFile = errorFile, exitOnCrash = false)
        val syntheticException = IllegalStateException("Plugin failed during parsing")

        handler.uncaughtException(Thread.currentThread(), syntheticException)

        // Verify currently loading extension in error file
        val errorContent = errorFile.readText()
        assertTrue(errorContent.contains("Currently loading extension: $pluginId"), "errorFile must record loading extension")

        // Verify currently loading extension in app.log
        val appLogFile = PlatformPaths.logDir.resolve("app.log").toFile()
        val logContent = appLogFile.readText()
        assertTrue(logContent.contains("Currently loading extension: $pluginId"), "app.log must record loading extension")
    }

    @Test
    fun testExceptionHandlerConstructorOverloads() {
        val errorFile = tempDir.resolve("ctor_test.txt").toFile()

        // 1-arg constructor
        val h1 = ExceptionHandler(errorFile)
        assertEquals(errorFile, h1.errorFile)
        assertFalse(h1.exitOnCrash)

        // 2-arg constructor matching upstream (errorFile, onError)
        var called2 = false
        val h2 = ExceptionHandler(errorFile) { called2 = true }
        assertEquals(errorFile, h2.errorFile)
        assertFalse(h2.exitOnCrash)
        h2.onError.invoke()
        assertTrue(called2)

        // 3-arg constructor (errorFile, exitOnCrash, onError)
        val h3 = ExceptionHandler(errorFile, exitOnCrash = true) {}
        assertTrue(h3.exitOnCrash)
    }

    @Test
    fun testAcraApplicationDeprecationAnnotationParity() {
        // Verify class-level deprecation
        val classDeprecated = AcraApplication::class.java.getAnnotation(Deprecated::class.java)
        assertNotNull(classDeprecated, "AcraApplication class must be annotated with @Deprecated")
        assertEquals(DeprecationLevel.ERROR, classDeprecated!!.level)
        assertEquals("com.lagradost.cloudstream3.CloudStreamApp", classDeprecated.replaceWith.expression)
    }
}
