package unit

import android.content.Context
import android.content.LinuxSharedPreferences
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.lagradost.common.platform.PlatformPaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Base64 as JavaBase64

/**
 * Unit tests for android-shims verifying strict POSIX compliance and anti-mock behavior:
 * - Real filesystem directories and temporary paths
 * - Thread-safe atomic SharedPreferences persistence on disk
 * - Real scheduled Handler/Looper task dispatching
 * - Robust URI parsing with query parameters and percent-encoding
 * - JDK-compatible Base64 encoding/decoding
 */
class AndroidShimsTest {

    private lateinit var testTempDir: Path

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        testTempDir = Files.createTempDirectory("cs_test_shims")
    }

    @AfterEach
    fun tearDown() {
        testTempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. Context Tests (Real OS files and directories)
    // =========================================================================

    @Test
    fun testContextFilesDirAndCacheDirWithRealTempDirectory() {
        val customContext = object : Context() {
            private val customFilesDir = File(testTempDir.toFile(), "files").apply { mkdirs() }
            private val customCacheDir = File(testTempDir.toFile(), "cache").apply { mkdirs() }

            override fun getFilesDir(): File = customFilesDir
            override fun getCacheDir(): File = customCacheDir
            override fun getPackageName(): String = "com.lagradost.cloudstream3.test"
        }

        val filesDir = customContext.getFilesDir()
        val cacheDir = customContext.getCacheDir()

        assertTrue(filesDir.exists(), "Context.getFilesDir() must exist on disk")
        assertTrue(filesDir.isDirectory, "Context.getFilesDir() must be a directory")
        assertTrue(cacheDir.exists(), "Context.getCacheDir() must exist on disk")
        assertTrue(cacheDir.isDirectory, "Context.getCacheDir() must be a directory")

        // Perform real file I/O within the resolved paths
        val testPayload = "CloudStream POSIX Native Test Payload ${UUID.randomUUID()}"
        val sampleFile = File(filesDir, "sample_data.bin")
        sampleFile.writeText(testPayload, StandardCharsets.UTF_8)

        assertTrue(sampleFile.exists())
        assertEquals(testPayload, sampleFile.readText(StandardCharsets.UTF_8))

        val cacheFile = File(cacheDir, "sample_cache.tmp")
        cacheFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x7F))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x7F), cacheFile.readBytes())

        // Test Context.getDir() creates directory on disk
        val customSubDir = customContext.getDir("sub_storage", Context.MODE_PRIVATE)
        assertTrue(customSubDir.exists() && customSubDir.isDirectory)

        assertEquals("com.lagradost.cloudstream3.test", customContext.getPackageName())
        assertSame(customContext, customContext.getApplicationContext())
    }

    @Test
    fun testDefaultContextUsesValidPlatformPaths() {
        val context = Context()
        val filesDir = context.getFilesDir()
        val cacheDir = context.getCacheDir()

        assertNotNull(filesDir)
        assertNotNull(cacheDir)
        assertTrue(filesDir.exists() && filesDir.isDirectory)
        assertTrue(cacheDir.exists() && cacheDir.isDirectory)
        assertEquals("com.lagradost.cloudstream3", context.getPackageName())
    }

    // =========================================================================
    // 2. LinuxSharedPreferences Tests (Concurrent & Atomic Persistence)
    // =========================================================================

    @Test
    fun testLinuxSharedPreferencesTypedGettersAndDefaults() {
        val prefName = "test_prefs_typed_${UUID.randomUUID()}"
        val prefs = LinuxSharedPreferences(prefName)

        // Verify default values when keys are missing
        assertEquals("default_str", prefs.getString("missing_str", "default_str"))
        assertNull(prefs.getString("null_str", null))
        assertEquals(42, prefs.getInt("missing_int", 42))
        assertEquals(100L, prefs.getLong("missing_long", 100L))
        assertEquals(3.14f, prefs.getFloat("missing_float", 3.14f))
        assertTrue(prefs.getBoolean("missing_bool", true))
        assertFalse(prefs.getBoolean("missing_bool_false", false))
        assertNull(prefs.getStringSet("missing_set", null))

        // Write typed values
        prefs.edit()
            .putString("key_string", "CloudStream Linux")
            .putInt("key_int", 1337)
            .putLong("key_long", 9876543210L)
            .putFloat("key_float", 2.718f)
            .putBoolean("key_bool_true", true)
            .putBoolean("key_bool_false", false)
            .putStringSet("key_set", setOf("alpha", "beta", "gamma"))
            .commit()

        // Verify typed getters
        assertEquals("CloudStream Linux", prefs.getString("key_string", null))
        assertEquals(1337, prefs.getInt("key_int", 0))
        assertEquals(9876543210L, prefs.getLong("key_long", 0L))
        assertEquals(2.718f, prefs.getFloat("key_float", 0f))
        assertTrue(prefs.getBoolean("key_bool_true", false))
        assertFalse(prefs.getBoolean("key_bool_false", true))

        val retrievedSet = prefs.getStringSet("key_set", null)
        assertNotNull(retrievedSet)
        assertEquals(setOf("alpha", "beta", "gamma"), retrievedSet)

        assertTrue(prefs.contains("key_string"))
        assertFalse(prefs.contains("non_existent_key"))
    }

    @Test
    fun testLinuxSharedPreferencesConcurrentWritesAndAtomicPersistence() {
        val prefName = "test_prefs_concurrent_${UUID.randomUUID()}"
        val prefs = LinuxSharedPreferences(prefName)

        val threadCount = 8
        val keysPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (threadIndex in 0 until threadCount) {
            executor.submit {
                try {
                    for (k in 0 until keysPerThread) {
                        val key = "th_${threadIndex}_key_$k"
                        prefs.edit()
                            .putString("${key}_str", "val_${threadIndex}_$k")
                            .putInt("${key}_int", threadIndex * 1000 + k)
                            .putBoolean("${key}_bool", k % 2 == 0)
                            .apply()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent writes should finish within timeout")
        assertEquals(0, errors.get(), "No thread should encounter write errors")
        executor.shutdown()

        // Verify values in memory
        for (threadIndex in 0 until threadCount) {
            for (k in 0 until keysPerThread) {
                val key = "th_${threadIndex}_key_$k"
                assertEquals("val_${threadIndex}_$k", prefs.getString("${key}_str", null))
                assertEquals(threadIndex * 1000 + k, prefs.getInt("${key}_int", -1))
                assertEquals(k % 2 == 0, prefs.getBoolean("${key}_bool", !(k % 2 == 0)))
            }
        }

        // Verify atomic write to physical file on disk
        val diskFile = PlatformPaths.pluginPrefsDir.resolve("$prefName.json").toFile()
        assertTrue(diskFile.exists(), "Preference file must be atomically written to disk at ${diskFile.absolutePath}")
        assertTrue(diskFile.length() > 0, "Preference file on disk must not be empty")

        val rawDiskContent = diskFile.readText(StandardCharsets.UTF_8)
        assertTrue(rawDiskContent.contains("th_0_key_0_str"), "File on disk must contain written keys")

        // Instantiate fresh LinuxSharedPreferences instance to verify cold load from disk
        val reloadedPrefs = LinuxSharedPreferences(prefName)
        for (threadIndex in 0 until threadCount) {
            val key = "th_${threadIndex}_key_0"
            assertEquals("val_${threadIndex}_0", reloadedPrefs.getString("${key}_str", null))
            assertEquals(threadIndex * 1000, reloadedPrefs.getInt("${key}_int", -1))
        }

        // Test remove and clear operations
        prefs.edit().remove("th_0_key_0_str").commit()
        assertNull(prefs.getString("th_0_key_0_str", null))

        prefs.edit().clear().commit()
        assertTrue(prefs.getAll().isEmpty())

        // Cleanup test file
        diskFile.delete()
    }

    @Test
    fun testLinuxSharedPreferencesListenerNotification() {
        val prefName = "test_prefs_listener_${UUID.randomUUID()}"
        val prefs = LinuxSharedPreferences(prefName)

        val changedKeys = mutableListOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) changedKeys.add(key)
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("notify_key", "value").apply()

        assertTrue(changedKeys.contains("notify_key"))

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        changedKeys.clear()
        prefs.edit().putString("notify_key_2", "value_2").apply()

        assertFalse(changedKeys.contains("notify_key_2"))

        // Cleanup
        PlatformPaths.pluginPrefsDir.resolve("$prefName.json").toFile().delete()
    }

    // =========================================================================
    // 3. Handler and Looper Tests (Real Task Scheduling & Execution)
    // =========================================================================

    @Test
    fun testHandlerPostExecutesPromptly() {
        val looper = Looper.getMainLooper()
        assertNotNull(looper)
        assertNotNull(looper.getThread())

        val handler = Handler(looper)
        val executed = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val posted = handler.post {
            executed.set(true)
            latch.countDown()
        }

        assertTrue(posted)
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Handler.post runnable should execute within 3 seconds")
        assertTrue(executed.get())
    }

    @Test
    fun testHandlerPostDelayedExecutesAfterSpecifiedDuration() {
        val handler = Handler()
        val executed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val delayMillis = 60L
        val startTime = System.currentTimeMillis()

        handler.postDelayed({
            executed.set(true)
            latch.countDown()
        }, delayMillis)

        assertFalse(executed.get(), "Delayed task should not run immediately")

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Handler.postDelayed should execute")
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(executed.get())
        assertTrue(elapsed >= delayMillis - 15, "Elapsed time ($elapsed ms) must be approximately >= delay ($delayMillis ms)")
    }

    @Test
    fun testHandlerRemoveCallbacksPreventsExecution() {
        val handler = Handler()
        val executed = AtomicBoolean(false)
        val runnable = Runnable {
            executed.set(true)
        }

        handler.postDelayed(runnable, 150L)
        handler.removeCallbacks(runnable)

        Thread.sleep(250L)
        assertFalse(executed.get(), "Removed callback must not execute")
    }

    // =========================================================================
    // 4. Uri.parse Tests (Complex URLs, Query Parameters, Percent-Encoding)
    // =========================================================================

    @Test
    fun testUriParseWithMultipleQueryParamsAndEncoding() {
        val rawUrl = "https://api.cloudstream.app:8443/v2/stream/watch?title=Attack%20on%20Titan&season=4&ep=12&genres=action%2Bdark_fantasy&subtitles=true&filter=hd%264k#player"
        val uri = Uri.parse(rawUrl)

        assertEquals("https", uri.getScheme())
        assertEquals("api.cloudstream.app", uri.getHost())
        assertEquals(8443, uri.getPort())
        assertEquals("/v2/stream/watch", uri.getPath())
        assertEquals("watch", uri.getLastPathSegment())
        assertEquals(listOf("v2", "stream", "watch"), uri.getPathSegments())
        assertEquals("player", uri.getFragment())

        // Validate decoded query parameters
        assertEquals("Attack on Titan", uri.getQueryParameter("title"))
        assertEquals("4", uri.getQueryParameter("season"))
        assertEquals("12", uri.getQueryParameter("ep"))
        assertEquals("action+dark_fantasy", uri.getQueryParameter("genres"))
        assertEquals("hd&4k", uri.getQueryParameter("filter"))
        assertTrue(uri.getBooleanQueryParameter("subtitles", false))
        assertFalse(uri.getBooleanQueryParameter("non_existent_flag", false))

        val paramNames = uri.getQueryParameterNames()
        assertTrue(paramNames.containsAll(listOf("title", "season", "ep", "genres", "subtitles", "filter")))
    }

    @Test
    fun testUriBuilderAndAppendParameters() {
        val builtUri = Uri.Builder()
            .scheme("https")
            .authority("cloudstream.io")
            .appendPath("search")
            .appendQueryParameter("q", "Cyberpunk: Edgerunners")
            .appendQueryParameter("year", "2022")
            .fragment("results")
            .build()

        assertEquals("https", builtUri.getScheme())
        assertEquals("cloudstream.io", builtUri.getHost())
        assertEquals("/search", builtUri.getPath())
        assertEquals("Cyberpunk: Edgerunners", builtUri.getQueryParameter("q"))
        assertEquals("2022", builtUri.getQueryParameter("year"))
        assertEquals("results", builtUri.getFragment())

        val modifiedUri = builtUri.appendQueryParameter("page", "1")
        assertEquals("1", modifiedUri.getQueryParameter("page"))
        assertEquals("Cyberpunk: Edgerunners", modifiedUri.getQueryParameter("q"))
    }

    // =========================================================================
    // 5. Base64 Tests (Encoding/Decoding Flags Matching Java Base64)
    // =========================================================================

    @Test
    fun testBase64EncodingAndDecodingFlagsMatchingJavaBase64() {
        val testString = "CloudStream Linux Desktop POSIX Native Test 2026! ?&#/+ ÿ"
        val rawBytes = testString.toByteArray(StandardCharsets.UTF_8)

        // 1. DEFAULT Flag (Standard RFC 4648 with padding)
        val encodedDefault = Base64.encodeToString(rawBytes, Base64.DEFAULT)
        val javaDefault = JavaBase64.getEncoder().encodeToString(rawBytes)
        assertEquals(javaDefault, encodedDefault)

        val decodedDefault = Base64.decode(encodedDefault, Base64.DEFAULT)
        assertArrayEquals(rawBytes, decodedDefault)

        // 2. URL_SAFE Flag (RFC 4648 URL safe with - and _)
        val encodedUrl = Base64.encodeToString(rawBytes, Base64.URL_SAFE)
        val javaUrl = JavaBase64.getUrlEncoder().encodeToString(rawBytes)
        assertEquals(javaUrl, encodedUrl)
        assertFalse(encodedUrl.contains("+"))
        assertFalse(encodedUrl.contains("/"))

        val decodedUrl = Base64.decode(encodedUrl, Base64.URL_SAFE)
        assertArrayEquals(rawBytes, decodedUrl)

        // 3. NO_PADDING Flag (Standard Base64 without '=' padding)
        val encodedNoPad = Base64.encodeToString(rawBytes, Base64.NO_PADDING)
        val javaNoPad = JavaBase64.getEncoder().withoutPadding().encodeToString(rawBytes)
        assertEquals(javaNoPad, encodedNoPad)
        assertFalse(encodedNoPad.endsWith("="))

        val decodedNoPad = Base64.decode(encodedNoPad, Base64.NO_PADDING)
        assertArrayEquals(rawBytes, decodedNoPad)

        // 4. URL_SAFE combined with NO_PADDING
        val encodedUrlNoPad = Base64.encodeToString(rawBytes, Base64.URL_SAFE or Base64.NO_PADDING)
        val javaUrlNoPad = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        assertEquals(javaUrlNoPad, encodedUrlNoPad)
        assertFalse(encodedUrlNoPad.endsWith("="))
        assertFalse(encodedUrlNoPad.contains("+"))
        assertFalse(encodedUrlNoPad.contains("/"))

        val decodedUrlNoPad = Base64.decode(encodedUrlNoPad, Base64.URL_SAFE or Base64.NO_PADDING)
        assertArrayEquals(rawBytes, decodedUrlNoPad)

        // 5. Binary byte range 0..255 round-trip
        val binaryData = ByteArray(256) { it.toByte() }
        val encodedBinary = Base64.encodeToString(binaryData, Base64.DEFAULT)
        val decodedBinary = Base64.decode(encodedBinary, Base64.DEFAULT)
        assertArrayEquals(binaryData, decodedBinary)
    }
}
