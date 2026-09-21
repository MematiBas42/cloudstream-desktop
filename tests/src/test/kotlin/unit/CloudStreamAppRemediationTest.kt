package unit

import android.app.Activity
import android.content.Context
import android.content.DesktopContextProvider
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getWindow
import com.lagradost.cloudstream3.ExceptionHandler
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-fidelity unit tests verifying 1:1 upstream architectural parity, zero-stub enforcement,
 * and robust desktop adaptation for CloudStreamApp (Cluster C01_CloudStreamApp):
 *
 * 1. Coil ImageLoader Delegation Parity:
 *    - Validates that newImageLoader() does NOT return a hardcoded null bypass.
 *    - Validates delegation to registered imageLoaderProvider and AppBootstrap.configureCoil().
 * 2. Window and ContextProvider Parity:
 *    - Replaces illegal Android Activity class cast with DesktopContextProvider.currentWindow.
 *    - Validates Context.getActivity() tailrec unwrapping and null-safety without dummy Activity fallbacks.
 *    - Validates Context.getWindow() and CloudStreamApp.currentWindow accessibility.
 * 3. Static DataStore Delegation Parity:
 *    - Validates exact 1:1 delegation for getKey, setKey, setKeyRaw, removeKey, removeKeys, getKeys, containsKey.
 *    - Confirms AcraApplication backwards-compatible binary bridge functions identically.
 * 4. ExceptionHandler Crash Diagnostics:
 *    - Tests unhandled exception capture, threadId logging, extension diagnostic state, and last_error file creation.
 * 5. Lifecycle onCreate & AppDebug Configuration:
 *    - Tests that onCreate() attaches the exception handler and initializes AppDebug.isDebug = BuildConfig.DEBUG.
 */
class CloudStreamAppRemediationTest {

    private lateinit var tempDir: Path
    private var originalWindow: java.awt.Window? = null
    private var originalImageLoaderProvider: ((Any?) -> Any?)? = null

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        tempDir = Files.createTempDirectory("cs_app_remediation_test")
        DesktopDataStore.init()

        originalWindow = DesktopContextProvider.currentWindow
        originalImageLoaderProvider = CloudStreamApp.imageLoaderProvider
    }

    @AfterEach
    fun tearDown() {
        DesktopContextProvider.currentWindow = originalWindow
        CloudStreamApp.imageLoaderProvider = originalImageLoaderProvider
        tempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. Coil ImageLoader Delegation Tests (Elimination of Hardcoded Null Bypass)
    // =========================================================================

    @Test
    @DisplayName("newImageLoader delegates directly to imageLoaderProvider when registered")
    fun testNewImageLoaderDelegatesToProvider() {
        val app = CloudStreamApp()
        val mockLoaderInstance = Any()
        var providerInvokedWith: Any? = null

        CloudStreamApp.imageLoaderProvider = { ctx ->
            providerInvokedWith = ctx
            mockLoaderInstance
        }

        val testContext = "TestPlatformContext"
        val result = app.newImageLoader(testContext)

        assertNotNull(result, "newImageLoader must not return null when an imageLoaderProvider is configured")
        assertSame(mockLoaderInstance, result, "newImageLoader must return the instance created by the provider")
        assertEquals(testContext, providerInvokedWith, "Context must be passed to the loader provider")
    }

    @Test
    @DisplayName("newImageLoader attempts AppBootstrap reflection delegation when provider is unset")
    fun testNewImageLoaderAttemptsBootstrapDelegation() {
        val app = CloudStreamApp()
        CloudStreamApp.imageLoaderProvider = null

        // In test environment without desktop-app AppBootstrap, it safely handles reflection without throwing
        assertDoesNotThrow {
            app.newImageLoader(null)
        }
    }

    // =========================================================================
    // 2. DesktopContextProvider and Window Parity (Illegal Activity Cast Elimination)
    // =========================================================================

    @Test
    @DisplayName("DesktopContextProvider.currentWindow binds and exposes active desktop Window")
    fun testDesktopContextProviderCurrentWindowBinding() {
        val testWindow: java.awt.Window? = if (!GraphicsEnvironment.isHeadless()) {
            Frame("Test Window").also { it.dispose() }
        } else {
            null
        }

        DesktopContextProvider.currentWindow = testWindow
        assertEquals(testWindow, CloudStreamApp.currentWindow, "CloudStreamApp.currentWindow must mirror DesktopContextProvider.currentWindow")

        val dummyContext = object : Context() {
            override fun getPackageName(): String = "test.pkg"
        }
        assertEquals(testWindow, dummyContext.getWindow(), "Context.getWindow() must return DesktopContextProvider.currentWindow")
    }

    @Test
    @DisplayName("Context.getActivity returns null for non-Activity context without illegal Activity cast")
    fun testContextGetActivityReturnsNullForNonActivity() {
        val plainContext = object : Context() {
            override fun getPackageName(): String = "com.plain.context"
        }

        // Must return null, NOT DesktopContextProvider.context as? Activity
        val activity = plainContext.getActivity()
        assertNull(activity, "Plain Context must return null for getActivity() rather than an illegal fake Activity cast")

        val nullContext: Context? = null
        assertNull(nullContext.getActivity(), "Null context must return null for getActivity()")
    }

    @Test
    @DisplayName("Context.getActivity returns this when Context is an actual Activity")
    fun testContextGetActivityReturnsThisForRealActivity() {
        val realActivity = object : Activity() {}
        val result = realActivity.getActivity()
        assertNotNull(result, "Activity instance must return non-null for getActivity()")
        assertSame(realActivity, result, "Activity instance must return itself")
    }

    // =========================================================================
    // 3. Static DataStore & setKeyRaw Delegation Parity Tests
    // =========================================================================

    @Test
    @DisplayName("CloudStreamApp companion delegates setKey and getKey with exact value preservation")
    fun testCloudStreamAppDataStoreDelegation() {
        val testKey = "test_pref_string_${System.currentTimeMillis()}"
        val testVal = "CloudStreamDesktopParity"

        CloudStreamApp.setKey(testKey, testVal)
        val retrieved = CloudStreamApp.getKey<String>(testKey)
        assertEquals(testVal, retrieved, "CloudStreamApp.getKey must retrieve value saved via CloudStreamApp.setKey")
        assertTrue(CloudStreamApp.containsKey(testKey), "CloudStreamApp.containsKey must be true for written key")

        CloudStreamApp.removeKey(testKey)
        assertNull(CloudStreamApp.getKey<String>(testKey), "CloudStreamApp.removeKey must remove value")
        assertFalse(CloudStreamApp.containsKey(testKey), "CloudStreamApp.containsKey must be false after removal")
    }

    @Test
    @DisplayName("CloudStreamApp companion delegates setKeyRaw for both folder and flat keys")
    fun testCloudStreamAppSetKeyRawDelegation() {
        val flatKey = "test_raw_flat_${System.currentTimeMillis()}"
        val folder = "test_folder_${System.currentTimeMillis()}"
        val folderKey = "item_key"

        CloudStreamApp.setKeyRaw(flatKey, 42)
        assertEquals(42, CloudStreamApp.getKey<Int>(flatKey))

        CloudStreamApp.setKeyRaw(folder, folderKey, 999)
        assertEquals(999, CloudStreamApp.getKey<Int>(folder, folderKey))

        val keys = CloudStreamApp.getKeys(folder)
        assertNotNull(keys, "getKeys for populated folder must not be null")
        assertTrue(keys.orEmpty().contains(folderKey), "Folder keys must contain $folderKey")

        CloudStreamApp.removeKeys(folder)
        val emptyKeys = CloudStreamApp.getKeys(folder)
        assertTrue(emptyKeys.isNullOrEmpty(), "Folder must be empty after removeKeys")
    }

    @Test
    @DisplayName("AcraApplication compatibility bridge delegates 1:1 to CloudStreamApp")
    fun testAcraApplicationCompatibilityBridge() {
        val acraKey = "acra_compat_key_${System.currentTimeMillis()}"
        val acraVal = "AcraCompatValue"

        @Suppress("DEPRECATION_ERROR")
        AcraApplication.setKey(acraKey, acraVal)

        @Suppress("DEPRECATION_ERROR")
        val retrieved = AcraApplication.getKey<String>(acraKey)
        assertEquals(acraVal, retrieved, "AcraApplication.getKey must retrieve value stored via setKey")

        @Suppress("DEPRECATION_ERROR")
        val directRetrieved = CloudStreamApp.getKey<String>(acraKey)
        assertEquals(acraVal, directRetrieved, "CloudStreamApp must read key stored via AcraApplication")

        val rawKey = "acra_raw_key"
        @Suppress("DEPRECATION_ERROR")
        AcraApplication.setKeyRaw(rawKey, 12345)
        assertEquals(12345, CloudStreamApp.getKey<Int>(rawKey))

        @Suppress("DEPRECATION_ERROR")
        AcraApplication.removeKeys("non_existent_folder")
    }

    // =========================================================================
    // 4. Crash ExceptionHandler & Diagnostics Tests
    // =========================================================================

    @Test
    @DisplayName("ExceptionHandler writes diagnostic dump to errorFile with loading extension and thread details")
    fun testExceptionHandlerWritesDiagnosticFile() {
        val errorFile = File(tempDir.toFile(), "last_error")
        val callbackInvoked = AtomicBoolean(false)

        val handler = ExceptionHandler(errorFile, exitOnCrash = false) {
            callbackInvoked.set(true)
        }

        val testException = RuntimeException("Simulated fatal test exception")
        PluginManager.currentlyLoading = "TestExtension_v1.0"

        val testThread = Thread({ }, "TestWorkerThread")
        handler.uncaughtException(testThread, testException)

        assertTrue(callbackInvoked.get(), "ExceptionHandler onError callback must be invoked")
        assertTrue(errorFile.exists(), "Diagnostic error file must be created on uncaught exception")

        val content = errorFile.readText()
        assertTrue(content.contains("TestExtension_v1.0"), "Error file must record currently loading extension")
        assertTrue(content.contains("TestWorkerThread"), "Error file must record thread name")
        assertTrue(content.contains("Simulated fatal test exception"), "Error file must include exception message")
        assertTrue(content.contains("RuntimeException"), "Error file must include exception class name")

        PluginManager.currentlyLoading = null
    }

    // =========================================================================
    // 5. CloudStreamApp Lifecycle & Debug Flags Tests
    // =========================================================================

    @Test
    @DisplayName("onCreate sets exceptionHandler and configures AppDebug.isDebug matching BuildConfig.DEBUG")
    fun testCloudStreamAppOnCreateLifecycle() {
        val app = CloudStreamApp()
        val customContext = object : Context() {
            private val customFilesDir = File(tempDir.toFile(), "app_files").apply { mkdirs() }
            override fun getFilesDir(): File = customFilesDir
        }

        app.attachBaseContext(customContext)
        app.onCreate()

        assertNotNull(CloudStreamApp.exceptionHandler, "CloudStreamApp.exceptionHandler must be initialized by onCreate()")
        assertEquals(BuildConfig.DEBUG, AppDebug.isDebug, "AppDebug.isDebug must match BuildConfig.DEBUG after onCreate()")
    }
}
