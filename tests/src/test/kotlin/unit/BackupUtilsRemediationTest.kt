package unit

import android.content.Context
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.BackupUtils.isTransferable
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager.QUEUE_KEY
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_DOWNLOAD_INFO
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.BackupRestoreManager
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Architectural Remediation & 1:1 Upstream Parity Test Suite for [BackupUtils] (Cluster C34_BackupUtils).
 *
 * Validates:
 * 1. Download Header Cache Whitelist Parity (L95 Defect):
 *    - DOWNLOAD_HEADER_CACHE and DOWNLOAD_HEADER_CACHE_BACKUP MUST be transferable (isTransferable == true).
 *    - Offline download headers and resume-watching metadata are retained during backup/restore.
 *    - Both [BackupUtils] and [BackupRestoreManager] maintain identical whitelist/blacklist behavior (zero-drift).
 * 2. Strict Non-Transferable Key Prohibitions:
 *    - Ephemeral episode caches (DOWNLOAD_EPISODE_CACHE, DOWNLOAD_EPISODE_CACHE_BACKUP).
 *    - In-flight download task states (KEY_DOWNLOAD_INFO, KEY_RESUME_IN_QUEUE, KEY_RESUME_PACKAGES, QUEUE_KEY).
 *    - Platform-specific or sensitive secrets (biometric_key, nginx_user, download_path_key, tokens).
 *    - Plugin installations (PLUGINS_KEY, PLUGINS_KEY_LOCAL, auto_download_plugins_key2).
 * 3. Exact 1:1 Upstream Model Contract:
 *    - [BackupUtils.BackupVars] and [BackupUtils.BackupFile] serialization/deserialization across all 6 data types.
 *    - Full Jackson and kotlinx.serialization interoperability.
 * 4. Data Extraction & Restitution:
 *    - [BackupUtils.getBackup] extracts transferable preferences and categorizes by data type.
 *    - [BackupUtils.restore] writes back maps and triggers requireLibraryRefresh on all sync APIs.
 * 5. Desktop File System & SafeFile Path Resolution:
 *    - [BackupUtils.getDefaultBackupDir] and [BackupUtils.getCurrentBackupDir] POSIX resolution.
 */
class BackupUtilsRemediationTest {

    private lateinit var tempDir: Path
    private val context = Context()

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        tempDir = Files.createTempDirectory("cs_backup_remediation_test")
        val customFile = File(tempDir.toFile(), "datastore_backup_test.json")
        DesktopDataStore.customDataFile = customFile
        DesktopDataStore.init()
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.customDataFile = null
        tempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. Download Header Cache Whitelist Parity (L95 Defect Resolution)
    // =========================================================================

    @Test
    @DisplayName("L95: DOWNLOAD_HEADER_CACHE and its backup key must be transferable in BackupUtils")
    fun testDownloadHeaderCacheIsTransferableInBackupUtils() {
        assertTrue(
            DOWNLOAD_HEADER_CACHE.isTransferable(),
            "DOWNLOAD_HEADER_CACHE ('download_header_cache') must be transferable so offline resume watching is retained"
        )
        assertTrue(
            DOWNLOAD_HEADER_CACHE_BACKUP.isTransferable(),
            "DOWNLOAD_HEADER_CACHE_BACKUP ('BACKUP_download_header_cache') must be transferable"
        )
        assertTrue(
            "account_0/download_header_cache/99812".isTransferable(),
            "Prefixed or sub-path download header cache keys must remain transferable"
        )
    }

    @Test
    @DisplayName("L95: BackupRestoreManager must also allow DOWNLOAD_HEADER_CACHE without pruning")
    fun testDownloadHeaderCacheIsTransferableInBackupRestoreManager() {
        BackupRestoreManager.run {
            assertTrue(
                DOWNLOAD_HEADER_CACHE.isTransferable(),
                "BackupRestoreManager.isTransferable must return true for DOWNLOAD_HEADER_CACHE"
            )
            assertTrue(
                DOWNLOAD_HEADER_CACHE_BACKUP.isTransferable(),
                "BackupRestoreManager.isTransferable must return true for DOWNLOAD_HEADER_CACHE_BACKUP"
            )
            assertTrue(
                "download_header_cache".isTransferable(),
                "Raw string 'download_header_cache' must not be in nonTransferableKeys"
            )
        }
    }

    // =========================================================================
    // 2. Strict Non-Transferable Key Prohibitions
    // =========================================================================

    @Test
    @DisplayName("Verify prohibited download and episode cache keys are strictly non-transferable")
    fun testProhibitedDownloadKeysAreFiltered() {
        assertFalse(
            DOWNLOAD_EPISODE_CACHE.isTransferable(),
            "DOWNLOAD_EPISODE_CACHE must be filtered out"
        )
        assertFalse(
            DOWNLOAD_EPISODE_CACHE_BACKUP.isTransferable(),
            "DOWNLOAD_EPISODE_CACHE_BACKUP must be filtered out"
        )
        assertFalse(
            KEY_DOWNLOAD_INFO.isTransferable(),
            "KEY_DOWNLOAD_INFO ('download_info') must be filtered out"
        )
        assertFalse(
            KEY_RESUME_IN_QUEUE.isTransferable(),
            "KEY_RESUME_IN_QUEUE must be filtered out to prevent auto-starting downloads"
        )
        assertFalse(
            KEY_RESUME_PACKAGES.isTransferable(),
            "KEY_RESUME_PACKAGES must be filtered out to prevent auto-starting downloads"
        )
        assertFalse(
            QUEUE_KEY.isTransferable(),
            "QUEUE_KEY must be filtered out"
        )
    }

    @Test
    @DisplayName("Verify platform-specific, plugin, and sensitive authentication keys are non-transferable")
    fun testSensitiveAndPlatformKeysAreFiltered() {
        // Platform specific keys
        assertFalse("biometric_key".isTransferable())
        assertFalse("nginx_user".isTransferable())
        assertFalse("download_path_key".isTransferable())
        assertFalse("download_path_key_visual".isTransferable())
        assertFalse("backup_path_key".isTransferable())
        assertFalse("backup_dir_path_key".isTransferable())

        // Plugin keys
        assertFalse(PLUGINS_KEY.isTransferable())
        assertFalse(PLUGINS_KEY_LOCAL.isTransferable())
        assertFalse("auto_download_plugins_key2".isTransferable())

        // Account & token secrets
        assertFalse(AccountManager.ACCOUNT_TOKEN.isTransferable())
        assertFalse(AccountManager.ACCOUNT_IDS.isTransferable())
        assertFalse("anilist_token".isTransferable())
        assertFalse("anilist_user".isTransferable())
        assertFalse("mal_token".isTransferable())
        assertFalse("mal_user".isTransferable())
        assertFalse("mal_refresh_token".isTransferable())
        assertFalse("mal_unixtime".isTransferable())
        assertFalse("open_subtitles_user".isTransferable())
        assertFalse("subdl_user".isTransferable())
        assertFalse("simkl_token".isTransferable())
    }

    @Test
    @DisplayName("Verify legitimate user preferences and watch states remain transferable")
    fun testUserPreferencesAndWatchStatesAreTransferable() {
        assertTrue("default/video_pos_dur".isTransferable())
        assertTrue("result_watch_state".isTransferable())
        assertTrue("result_resume_watching".isTransferable())
        assertTrue("user_bookmarks".isTransferable())
        assertTrue("search_history".isTransferable())
        assertTrue("app_theme_key".isTransferable())
        assertTrue("sub_size".isTransferable())
        assertTrue("subscribed_series".isTransferable())
    }

    // =========================================================================
    // 3. BackupVars and BackupFile Serialization Contract
    // =========================================================================

    @Test
    @DisplayName("BackupVars and BackupFile roundtrip serialization via AppUtils JSON")
    fun testBackupFileJsonSerialization() {
        val testBoolMap = mapOf("setting_auto_update" to true, "sub_enabled" to false)
        val testIntMap = mapOf("app_theme" to 1, "account_index" to 3)
        val testStringMap = mapOf(
            DOWNLOAD_HEADER_CACHE to "{\"title\":\"Cyberpunk\",\"poster\":\"https://example.com/poster.jpg\"}",
            "default/video_pos_dur" to "{\"pos\":120000,\"dur\":1500000}"
        )
        val testFloatMap = mapOf("playback_speed" to 1.25f)
        val testLongMap = mapOf("last_view_time" to 1700000000L)
        val testStringSetMap = mapOf("favorite_tags" to setOf("Action", "Sci-Fi"))

        val dataVars = BackupUtils.BackupVars(
            bool = testBoolMap,
            int = testIntMap,
            string = testStringMap,
            float = testFloatMap,
            long = testLongMap,
            stringSet = testStringSetMap
        )

        val settingsVars = BackupUtils.BackupVars(
            bool = mapOf("hardware_accel" to true),
            int = mapOf("buffer_size" to 50),
            string = mapOf("language" to "en"),
            float = null,
            long = null,
            stringSet = null
        )

        val originalBackup = BackupUtils.BackupFile(
            datastore = dataVars,
            settings = settingsVars
        )

        val jsonString = originalBackup.toJson()
        assertNotNull(jsonString)
        assertTrue(jsonString.contains(DOWNLOAD_HEADER_CACHE))
        assertTrue(jsonString.contains("_Bool"))
        assertTrue(jsonString.contains("_Int"))
        assertTrue(jsonString.contains("_String"))
        assertTrue(jsonString.contains("_Float"))
        assertTrue(jsonString.contains("_Long"))
        assertTrue(jsonString.contains("_StringSet"))

        val deserialized = parseJson<BackupUtils.BackupFile>(jsonString)
        assertEquals(testBoolMap, deserialized.datastore.bool)
        assertEquals(testIntMap, deserialized.datastore.int)
        assertEquals(testStringMap, deserialized.datastore.string)
        assertEquals(testFloatMap, deserialized.datastore.float)
        assertEquals(testLongMap, deserialized.datastore.long)
        assertEquals(testStringSetMap, deserialized.datastore.stringSet)
        assertEquals(true, deserialized.settings.bool?.get("hardware_accel"))
        assertEquals("en", deserialized.settings.string?.get("language"))
    }

    // =========================================================================
    // 4. Extraction & Restoration Flow
    // =========================================================================

    @Test
    @DisplayName("getBackup extracts transferable preferences and retains DOWNLOAD_HEADER_CACHE")
    fun testGetBackupPreservesDownloadHeaderCache() {
        val editor = DataStore.editor(context)
        // Add transferable items
        editor.setKeyRaw(DOWNLOAD_HEADER_CACHE, "{\"episode\":\"E01\",\"cached\":true}")
        editor.setKeyRaw("custom_user_bookmark", "BookmarkData")
        editor.setKeyRaw("watch_progress_int", 95)
        editor.setKeyRaw("flag_active_bool", true)

        // Add non-transferable items
        editor.setKeyRaw(DOWNLOAD_EPISODE_CACHE, "{\"cached_ep\":1}")
        editor.setKeyRaw("biometric_key", "secret_biometric_token")
        editor.setKeyRaw(KEY_DOWNLOAD_INFO, "temp_download_info")
        editor.apply()

        val backup = BackupUtils.getBackup(context)

        // Transferable check
        assertNotNull(backup.datastore.string)
        assertTrue(
            backup.datastore.string!!.containsKey(DOWNLOAD_HEADER_CACHE),
            "getBackup MUST include DOWNLOAD_HEADER_CACHE in datastore.string"
        )
        assertTrue(backup.datastore.string!!.containsKey("custom_user_bookmark"))
        assertTrue(backup.datastore.int!!.containsKey("watch_progress_int"))
        assertTrue(backup.datastore.bool!!.containsKey("flag_active_bool"))

        // Non-transferable check
        assertFalse(
            backup.datastore.string!!.containsKey(DOWNLOAD_EPISODE_CACHE),
            "getBackup MUST omit DOWNLOAD_EPISODE_CACHE"
        )
        assertFalse(
            backup.datastore.string!!.containsKey("biometric_key"),
            "getBackup MUST omit biometric_key"
        )
        assertFalse(
            backup.datastore.string!!.containsKey(KEY_DOWNLOAD_INFO),
            "getBackup MUST omit KEY_DOWNLOAD_INFO"
        )
    }

    @Test
    @DisplayName("restore applies restored data across maps and flags requireLibraryRefresh on all sync APIs")
    fun testRestoreAppliesMapsAndTriggersSyncApis() {
        // Reset requireLibraryRefresh flags
        for (api in AccountManager.syncApis) {
            api.requireLibraryRefresh = false
        }

        val backup = BackupUtils.BackupFile(
            datastore = BackupUtils.BackupVars(
                string = mapOf(
                    DOWNLOAD_HEADER_CACHE to "{\"series\":\"TestSeries\",\"headers\":{}}",
                    "prohibited_key_download_info" to "should_not_be_restored"
                ),
                int = mapOf("restored_int_key" to 42),
                bool = mapOf("restored_bool_key" to true),
                float = mapOf("restored_float_key" to 3.14f),
                long = mapOf("restored_long_key" to 99999999L),
                stringSet = mapOf("restored_set_key" to setOf("Alpha", "Beta"))
            ),
            settings = BackupUtils.BackupVars(
                string = mapOf("restored_pref_str" to "desktop_val"),
                int = mapOf("restored_pref_int" to 10)
            )
        )

        BackupUtils.restore(
            context = context,
            backupFile = backup,
            restoreSettings = true,
            restoreDataStore = true
        )

        // Verify requireLibraryRefresh flag set
        for (api in AccountManager.syncApis) {
            assertTrue(
                api.requireLibraryRefresh,
                "api.requireLibraryRefresh MUST be true after restore for API: ${api.name}"
            )
        }

        // Verify restored keys in shared preferences
        val sharedPrefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
        assertEquals("{\"series\":\"TestSeries\",\"headers\":{}}", sharedPrefs.getString(DOWNLOAD_HEADER_CACHE, null))
        assertEquals(42, sharedPrefs.getInt("restored_int_key", 0))
        assertEquals(true, sharedPrefs.getBoolean("restored_bool_key", false))
        assertEquals(10, sharedPrefs.getInt("restored_pref_int", 0))
        assertEquals("desktop_val", sharedPrefs.getString("restored_pref_str", null))
        assertFalse(sharedPrefs.contains("prohibited_key_download_info"))
    }

    @Test
    @DisplayName("restore with null context is a safe no-op")
    fun testRestoreNullContextNoOp() {
        assertDoesNotThrow {
            BackupUtils.restore(
                context = null,
                backupFile = BackupUtils.BackupFile(BackupUtils.BackupVars(), BackupUtils.BackupVars()),
                restoreSettings = true,
                restoreDataStore = true
            )
        }
    }

    // =========================================================================
    // 5. SafeFile & Desktop Backup Directory Resolution
    // =========================================================================

    @Test
    @DisplayName("getDefaultBackupDir and getCurrentBackupDir resolve non-null SafeFile on desktop")
    fun testBackupDirectoryResolution() {
        val defaultDir = BackupUtils.getDefaultBackupDir(context)
        assertNotNull(defaultDir, "getDefaultBackupDir must return a valid SafeFile")
        assertTrue(defaultDir!!.exists() || defaultDir.javaFile.mkdirs(), "Backup dir must exist or be creatable")

        val currentPair = BackupUtils.getCurrentBackupDir(context)
        assertNotNull(currentPair.first, "getCurrentBackupDir.first must resolve to a valid SafeFile")
    }

    // =========================================================================
    // 6. Dual-Engine Cross-Parity (BackupUtils vs BackupRestoreManager)
    // =========================================================================

    @Test
    @DisplayName("BackupRestoreManager JSON export retains DOWNLOAD_HEADER_CACHE and supports type restoration")
    fun testBackupRestoreManagerDualEngineParity() {
        // Seed DesktopDataStore
        DesktopDataStore.setKey(DOWNLOAD_HEADER_CACHE, "{\"channel\":\"offline_headers\"}")
        DesktopDataStore.setKey("sample_user_bookmark", "Bookmark#1")
        DesktopDataStore.setKey(DOWNLOAD_EPISODE_CACHE, "ep_cache_data")

        val baos = ByteArrayOutputStream()
        BackupRestoreManager.createUpstreamJsonBackup(baos)
        val jsonOutput = baos.toString(StandardCharsets.UTF_8)

        // Verify whitelist retention
        assertTrue(jsonOutput.contains(DOWNLOAD_HEADER_CACHE), "BackupRestoreManager JSON MUST contain DOWNLOAD_HEADER_CACHE")
        assertTrue(jsonOutput.contains("sample_user_bookmark"), "BackupRestoreManager JSON MUST contain normal user keys")
        assertFalse(jsonOutput.contains(DOWNLOAD_EPISODE_CACHE), "BackupRestoreManager JSON MUST NOT contain DOWNLOAD_EPISODE_CACHE")

        // Test restore
        DesktopDataStore.removeKey(DOWNLOAD_HEADER_CACHE)
        assertNull(DesktopDataStore.getKey<String>(DOWNLOAD_HEADER_CACHE))

        val bais = ByteArrayInputStream(jsonOutput.toByteArray(StandardCharsets.UTF_8))
        val restoreSuccess = BackupRestoreManager.restore(bais)
        assertTrue(restoreSuccess, "BackupRestoreManager.restore must return true for valid JSON backup")

        val restoredHeader = DesktopDataStore.getKey<String>(DOWNLOAD_HEADER_CACHE)
        assertNotNull(restoredHeader, "DOWNLOAD_HEADER_CACHE must be restored in DesktopDataStore")
        assertEquals("{\"channel\":\"offline_headers\"}", restoredHeader)
    }
}
