// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/services/BackupWorkManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.services

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.BackupRestoreManager
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val BACKUP_CHANNEL_ID = "cloudstream3.backups"
const val BACKUP_WORK_NAME = "work_backup"
const val BACKUP_CHANNEL_NAME = "Backups"
const val BACKUP_CHANNEL_DESCRIPTION = "Notifications for background backups"
const val BACKUP_NOTIFICATION_ID = 938712898 // Random unique

const val DEFAULT_MIN_STORAGE_SPACE_BYTES: Long = 50L * 1024 * 1024 // 50 MiB
const val BACKUP_DIR_PATH_KEY: String = "backup_dir_path_key"

/**
 * Linux-native headless background backup scheduler.
 *
 * Implements:
 * 1. Periodic coroutine daemon timer (schedulePeriodicBackup / cancelPeriodicBackup).
 * 2. Atomic dual backup creation (Upstream-compatible JSON and Linux Multi-Partition .cs3backup ZIP).
 * 3. Filesystem storage headroom validation (requiresStorageNotLow) to prevent disk exhaustion.
 * 4. Desktop Freedesktop notification integration via NotificationManagerCompat and XDG logging.
 */
object BackupScheduler {
    private const val TAG = "BackupScheduler"
    private val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var scheduledJob: Job? = null

    val isScheduled: Boolean
        get() = scheduledJob?.isActive == true

    @Volatile
    var currentIntervalHours: Long = 0L
        private set

    @Volatile
    var lastBackupTimestamp: Long = 0L
        private set

    @Volatile
    var lastBackupSuccess: Boolean = false
        private set

    @Volatile
    var lastBackupFilePaths: List<File> = emptyList()
        private set

    /**
     * Resolves the active backup destination directory.
     * Uses custom user-specified directory if set in DesktopDataStore,
     * otherwise falls back to XDG-compliant PlatformPaths.backupsDir.
     */
    fun getBackupDirectory(): File {
        val customPath = DesktopDataStore.getKey<String>(BACKUP_DIR_PATH_KEY)
        if (!customPath.isNullOrBlank()) {
            val customDir = File(customPath)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
            AppLogger.w(TAG, "Configured backup directory '$customPath' inaccessible. Falling back to default.")
        }
        val defaultDir = PlatformPaths.backupsDir.toFile().apply { mkdirs() }
        return defaultDir
    }

    /**
     * Checks if the filesystem volume hosting the backup directory has sufficient free storage space.
     * Equivalent to WorkManager's Constraints.setRequiresStorageNotLow(true).
     */
    fun isStorageNotLow(
        dir: File = getBackupDirectory(),
        minFreeBytes: Long = DEFAULT_MIN_STORAGE_SPACE_BYTES
    ): Boolean {
        return try {
            val target = if (dir.exists()) dir else (dir.parentFile?.takeIf { it.exists() } ?: File("/"))
            val usable = target.usableSpace
            if (usable <= 0L) {
                target.canWrite()
            } else {
                usable >= minFreeBytes
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not verify storage space: ${e.message}")
            true
        }
    }

    /**
     * Schedules periodic backup execution at the specified hourly interval.
     * If intervalHours is 0 or negative, existing schedules are cancelled.
     */
    fun schedulePeriodicBackup(
        intervalHours: Long,
        context: Context = Context(),
        initialDelayMs: Long = intervalHours * 3600 * 1000L
    ): Job? {
        if (intervalHours <= 0L) {
            cancelPeriodicBackup()
            return null
        }

        cancelPeriodicBackup()
        currentIntervalHours = intervalHours

        val job = schedulerScope.launch {
            AppLogger.i(TAG, "Scheduled periodic backup every $intervalHours hours (initial delay: ${initialDelayMs}ms)")
            if (initialDelayMs > 0) {
                delay(initialDelayMs)
            }
            while (isActive) {
                try {
                    performBackup(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.e(TAG, "Periodic backup execution encountered error", e)
                }
                delay(intervalHours * 3600 * 1000L)
            }
        }
        scheduledJob = job
        return job
    }

    /**
     * Cancels any active periodic backup coroutine daemon.
     */
    fun cancelPeriodicBackup() {
        scheduledJob?.cancel()
        scheduledJob = null
        currentIntervalHours = 0L
        AppLogger.i(TAG, "Cancelled periodic backup scheduler.")
    }

    /**
     * Performs an atomic backup (both upstream-compatible JSON and Linux .cs3backup ZIP archive).
     *
     * @param context Application context for notification dispatch and channel creation.
     * @param requiresStorageNotLow Whether to verify adequate storage headroom before creating backups.
     * @param minFreeBytes Minimum free bytes required if storage check is enabled.
     * @return true if backups were successfully generated, false otherwise.
     */
    suspend fun performBackup(
        context: Context = Context(),
        requiresStorageNotLow: Boolean = true,
        minFreeBytes: Long = DEFAULT_MIN_STORAGE_SPACE_BYTES
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.createNotificationChannel(
                BACKUP_CHANNEL_ID,
                BACKUP_CHANNEL_NAME,
                BACKUP_CHANNEL_DESCRIPTION
            )

            val backupDir = getBackupDirectory()
            if (!backupDir.exists() && !backupDir.mkdirs()) {
                val errMsg = "Failed to create backup directory: ${backupDir.absolutePath}"
                AppLogger.e(TAG, errMsg)
                sendNotification(context, success = false, message = errMsg)
                lastBackupSuccess = false
                return@withContext false
            }

            if (requiresStorageNotLow && !isStorageNotLow(backupDir, minFreeBytes)) {
                val usableMb = backupDir.usableSpace / (1024 * 1024)
                val requiredMb = minFreeBytes / (1024 * 1024)
                val errMsg = "Storage low ($usableMb MB available, required $requiredMb MB). Backup skipped."
                AppLogger.w(TAG, errMsg)
                sendNotification(context, success = false, message = errMsg)
                lastBackupSuccess = false
                return@withContext false
            }

            val date = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date(System.currentTimeMillis()))
            val baseName = "CS3_Backup_$date"

            // 1. Atomic Upstream JSON backup
            val jsonFile = File(backupDir, "$baseName.json")
            val jsonBaos = ByteArrayOutputStream()
            BackupRestoreManager.createUpstreamJsonBackup(jsonBaos)
            DesktopDataStore.atomicWrite(jsonFile, jsonBaos.toByteArray())

            // 2. Atomic Multi-Partition Linux ZIP backup (.cs3backup)
            val zipFile = File(backupDir, "$baseName.cs3backup")
            val tmpZipPath = zipFile.toPath().resolveSibling("${zipFile.name}.tmp.${UUID.randomUUID()}")
            try {
                FileOutputStream(tmpZipPath.toFile()).use { fos ->
                    BackupRestoreManager.createZipBackup(fos)
                }
                try {
                    Files.move(
                        tmpZipPath,
                        zipFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        tmpZipPath,
                        zipFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                try {
                    Files.deleteIfExists(tmpZipPath)
                } catch (t: Throwable) {
                    AppLogger.d(TAG, "Failed to delete temporary zip file: ${t.message}")
                }
            }

            lastBackupTimestamp = System.currentTimeMillis()
            lastBackupSuccess = true
            lastBackupFilePaths = listOf(jsonFile, zipFile)

            AppLogger.i(TAG, "Successfully created atomic backups: ${jsonFile.name}, ${zipFile.name}")
            sendNotification(context, success = true, message = context.getString(R.string.backup_success))
            true
        } catch (e: Exception) {
            lastBackupSuccess = false
            AppLogger.e(TAG, "Backup failed with exception", e)
            val failFormat = context.getString(R.string.backup_failed)
            sendNotification(context, success = false, message = "$failFormat: ${e.message}")
            false
        }
    }

    private fun sendNotification(context: Context, success: Boolean, message: String) {
        try {
            val title = context.getString(R.string.pref_category_backup)
            val notification = NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(if (success) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)
                .build()

            NotificationManagerCompat.from(context).notify(BACKUP_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not send desktop backup notification: ${e.message}")
        }
    }
}

/**
 * Headless Linux adaptation of upstream BackupWorkManager.
 * Preserves 1:1 API compatibility with upstream callers while delegating
 * scheduling to BackupScheduler.
 */
class BackupWorkManager(val context: Context, val workerParams: Any? = null) {
    companion object {
        fun enqueuePeriodicWork(context: Context?, intervalHours: Long) {
            if (context == null) return
            if (intervalHours == 0L) {
                cancelPeriodicBackup()
                return
            }
            schedulePeriodicBackup(intervalHours, context)
        }

        fun schedulePeriodicBackup(
            intervalHours: Long,
            context: Context = Context(),
            initialDelayMs: Long = intervalHours * 3600 * 1000L
        ): Job? = BackupScheduler.schedulePeriodicBackup(intervalHours, context, initialDelayMs)

        fun cancelPeriodicBackup() = BackupScheduler.cancelPeriodicBackup()

        suspend fun performBackup(
            context: Context = Context(),
            requiresStorageNotLow: Boolean = true,
            minFreeBytes: Long = DEFAULT_MIN_STORAGE_SPACE_BYTES
        ): Boolean = BackupScheduler.performBackup(context, requiresStorageNotLow, minFreeBytes)

        suspend fun createBackup(context: Context = Context()): Boolean = performBackup(context)

        suspend fun restoreBackup(context: Context = Context(), file: File): Boolean = withContext(Dispatchers.IO) {
            try {
                java.io.FileInputStream(file).use { fis ->
                    BackupRestoreManager.restore(fis)
                }
            } catch (e: Exception) {
                AppLogger.e("BackupWorkManager", "Failed to restore backup from ${file.absolutePath}", e)
                false
            }
        }
    }

    private val backupNotificationBuilder =
        NotificationCompat.Builder(context, BACKUP_CHANNEL_ID)
            .setColorized(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentTitle(context.getString(R.string.pref_category_backup))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(context.colorFromAttribute(R.attr.colorPrimary))
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome_big)

    suspend fun doWork(): Boolean {
        context.createNotificationChannel(
            BACKUP_CHANNEL_ID,
            BACKUP_CHANNEL_NAME,
            BACKUP_CHANNEL_DESCRIPTION
        )

        val notification = backupNotificationBuilder.build()
        NotificationManagerCompat.from(context).notify(BACKUP_NOTIFICATION_ID, notification)

        return performBackup(context)
    }
}
