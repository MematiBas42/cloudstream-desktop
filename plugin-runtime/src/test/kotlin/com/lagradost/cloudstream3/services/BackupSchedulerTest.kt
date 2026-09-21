package com.lagradost.cloudstream3.services

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.lagradost.cloudstream3.R
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.BackupRestoreManager
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileInputStream
import java.nio.file.Path

class BackupSchedulerTest {

    private lateinit var testDataFile: File
    private lateinit var testBackupDir: File
    private val context = Context()

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        PlatformPaths.init()
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()

        testBackupDir = tempDir.resolve("backups").toFile().apply { mkdirs() }
        DesktopDataStore.setKey(BACKUP_DIR_PATH_KEY, testBackupDir.absolutePath)

        // Seed initial watch history and data
        WatchHistoryRepository.setLastWatched(
            WatchHistory(
                url = "https://example.com/frieren/ep1",
                parentId = "frieren_show",
                episodeId = "ep1",
                title = "Frieren: Beyond Journey's End",
                position = 45000L,
                duration = 1420000L,
                updateTime = System.currentTimeMillis()
            )
        )
        DesktopDataStore.setKey("sample_user_pref", "dark_mode_enabled")
    }

    @AfterEach
    fun tearDown() {
        BackupScheduler.cancelPeriodicBackup()
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
    }

    @Test
    fun testPerformBackupCreatesAtomicJsonAndZipBackups() = runBlocking {
        val success = BackupScheduler.performBackup(context, requiresStorageNotLow = true)
        assertTrue(success, "performBackup must succeed under normal conditions")
        assertTrue(BackupScheduler.lastBackupSuccess, "lastBackupSuccess must be true")
        assertTrue(BackupScheduler.lastBackupTimestamp > 0, "lastBackupTimestamp must be set")

        val generatedFiles = BackupScheduler.lastBackupFilePaths
        assertEquals(2, generatedFiles.size, "Must generate exactly 2 backup archives (JSON and ZIP)")

        val jsonFile = generatedFiles.firstOrNull { it.name.endsWith(".json") }
        val zipFile = generatedFiles.firstOrNull { it.name.endsWith(".cs3backup") }

        assertNotNull(jsonFile, "Upstream JSON backup must be produced")
        assertNotNull(zipFile, "Linux .cs3backup ZIP archive must be produced")

        assertTrue(jsonFile!!.exists(), "JSON backup file must exist on disk")
        assertTrue(jsonFile.length() > 0, "JSON backup file must not be empty")

        assertTrue(zipFile!!.exists(), "ZIP backup file must exist on disk")
        assertTrue(zipFile.length() > 0, "ZIP backup file must not be empty")

        // Validate restoration from generated ZIP archive
        FileInputStream(zipFile).use { fis ->
            val restored = BackupRestoreManager.restore(fis)
            assertTrue(restored, "BackupRestoreManager must restore valid archive")
        }

        val restoredHistory = WatchHistoryRepository.getAllWatchHistory()
        assertTrue(
            restoredHistory.any { it.title == "Frieren: Beyond Journey's End" },
            "Restored watch history must contain pre-backup records"
        )
    }

    @Test
    fun testStorageLowHeadroomValidationAbortsBackup() = runBlocking {
        // Assert storage low check with impossible high threshold
        val isNotLow = BackupScheduler.isStorageNotLow(testBackupDir, minFreeBytes = Long.MAX_VALUE)
        assertFalse(isNotLow, "isStorageNotLow must return false when minFreeBytes exceeds capacity")

        val success = BackupScheduler.performBackup(
            context = context,
            requiresStorageNotLow = true,
            minFreeBytes = Long.MAX_VALUE
        )
        assertFalse(success, "Backup must be aborted when storage space is low")
        assertFalse(BackupScheduler.lastBackupSuccess, "lastBackupSuccess must record failure")
    }

    @Test
    fun testDesktopNotificationDispatchedOnBackup() = runBlocking {
        BackupScheduler.performBackup(context)

        val activeNotifications = NotificationManagerCompat.from(context).activeNotifications
        val backupNotif = activeNotifications.firstOrNull { it.id == BACKUP_NOTIFICATION_ID }

        assertNotNull(backupNotif, "Backup notification must be registered with NotificationManagerCompat")
        assertEquals(context.getString(R.string.pref_category_backup), backupNotif!!.notification.title)
    }

    @Test
    fun testSchedulePeriodicBackupLifecycle() = runBlocking {
        assertFalse(BackupScheduler.isScheduled, "Initial state should not be scheduled")

        // Schedule with small initial delay for test verification
        val job = BackupScheduler.schedulePeriodicBackup(
            intervalHours = 12,
            context = context,
            initialDelayMs = 50
        )
        assertNotNull(job, "Scheduled job must not be null")
        assertTrue(BackupScheduler.isScheduled, "isScheduled must be true")
        assertEquals(12L, BackupScheduler.currentIntervalHours, "Interval must be 12 hours")

        // Wait for initial delay execution and backup completion
        val start = System.currentTimeMillis()
        while (!BackupScheduler.lastBackupSuccess && System.currentTimeMillis() - start < 3000) {
            delay(50)
        }
        assertTrue(BackupScheduler.lastBackupSuccess, "Scheduled coroutine must execute performBackup")

        // Cancel
        BackupScheduler.cancelPeriodicBackup()
        assertFalse(BackupScheduler.isScheduled, "isScheduled must be false after cancel")
        assertEquals(0L, BackupScheduler.currentIntervalHours, "Interval must reset to 0")
    }

    @Test
    fun testSchedulePeriodicBackupZeroIntervalCancels() {
        BackupScheduler.schedulePeriodicBackup(intervalHours = 6, context = context, initialDelayMs = 100000)
        assertTrue(BackupScheduler.isScheduled)

        val resultJob = BackupScheduler.schedulePeriodicBackup(intervalHours = 0, context = context)
        assertNull(resultJob, "Scheduling 0 hours must return null job")
        assertFalse(BackupScheduler.isScheduled, "Scheduling 0 hours must cancel active schedule")
        assertEquals(0L, BackupScheduler.currentIntervalHours)
    }

    @Test
    fun testBackupWorkManagerParityDelegation() = runBlocking {
        // Test companion enqueuePeriodicWork with positive interval
        BackupWorkManager.enqueuePeriodicWork(context, 8L)
        assertTrue(BackupScheduler.isScheduled)
        assertEquals(8L, BackupScheduler.currentIntervalHours)

        // Test companion enqueuePeriodicWork with 0L cancels
        BackupWorkManager.enqueuePeriodicWork(context, 0L)
        assertFalse(BackupScheduler.isScheduled)

        // Test direct performBackup via BackupWorkManager
        val success = BackupWorkManager.performBackup(context)
        assertTrue(success)

        // Test BackupWorkManager instance doWork
        val worker = BackupWorkManager(context)
        val workResult = worker.doWork()
        assertTrue(workResult)
    }

    @Test
    fun testCustomBackupDirectoryConfigured() {
        val resolvedDir = BackupScheduler.getBackupDirectory()
        assertEquals(testBackupDir.absolutePath, resolvedDir.absolutePath)
    }
}
