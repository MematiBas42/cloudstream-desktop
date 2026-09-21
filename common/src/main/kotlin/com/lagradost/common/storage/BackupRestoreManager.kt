package com.lagradost.common.storage

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.account.Account
import com.lagradost.common.account.AccountManagerDesktop
import com.lagradost.common.logging.AppLogger
import java.io.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Dual-engine backup and restore manager.
 *
 * Implements:
 * 1. 100% Upstream Android CloudStream JSON compatibility (CS3_Backup_*.json / .txt).
 * 2. Linux Multi-File ZIP Archive (.cs3backup) containing partitioned JSON stores and SHA-256 manifest.
 * 3. Strict non-transferable key filtering to prevent biometric keys, authentication tokens,
 *    and platform-specific storage paths from leaking across machine boundaries.
 */
object BackupRestoreManager {

    private val nonTransferableKeys = listOf(
        "anilist_cached_list",
        "mal_cached_list",
        "kitsu_cached_list",
        "plugins",
        "plugins_local",
        "account_token",
        "account_ids",
        "biometric_key",
        "nginx_user",
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",
        "anilist_token",
        "anilist_user",
        "mal_user",
        "mal_token",
        "mal_refresh_token",
        "mal_unixtime",
        "open_subtitles_user",
        "subdl_user",
        "simkl_token",
        "download_episode_cache",
        // Download headers are used in the resume watching system and MUST NOT be pruned
        // "download_header_cache",
        "download_info",
        "download_resume_queue_key",
        "download_resume_2",
        "download_queue_key",
        "auto_download_plugins_key2"
    )

    fun String.isTransferable(): Boolean {
        val lower = this.lowercase()
        return !nonTransferableKeys.any { lower.contains(it) }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UpstreamBackupVars(
        @param:JsonProperty("_Bool") val bool: Map<String, Boolean>? = null,
        @param:JsonProperty("_Int") val int: Map<String, Int>? = null,
        @param:JsonProperty("_String") val string: Map<String, String>? = null,
        @param:JsonProperty("_Float") val float: Map<String, Float>? = null,
        @param:JsonProperty("_Long") val long: Map<String, Long>? = null,
        @param:JsonProperty("_StringSet") val stringSet: Map<String, Set<String>?>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UpstreamBackupFile(
        @param:JsonProperty("datastore") val datastore: UpstreamBackupVars = UpstreamBackupVars(),
        @param:JsonProperty("settings") val settings: UpstreamBackupVars = UpstreamBackupVars(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class BackupManifest(
        @param:JsonProperty("version") val version: Int = 1,
        @param:JsonProperty("appVersion") val appVersion: String = "1.0.0",
        @param:JsonProperty("os") val os: String = "Linux",
        @param:JsonProperty("timestamp") val timestamp: Long = System.currentTimeMillis(),
        @param:JsonProperty("checksums") val checksums: Map<String, String> = emptyMap()
    )

    /**
     * Creates an upstream-compatible single JSON backup file.
     */
    fun createUpstreamJsonBackup(outputStream: OutputStream) {
        val transferableData = DesktopDataStore.cache.filter { it.key.isTransferable() }
        val settings = DesktopDataStore.getSettings()

        val backupFile = UpstreamBackupFile(
            datastore = UpstreamBackupVars(string = transferableData),
            settings = UpstreamBackupVars(
                string = mapOf("app_settings" to DesktopDataStore.mapper.writeValueAsString(settings))
            )
        )

        outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(DesktopDataStore.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(backupFile))
        }
    }

    /**
     * Creates a Linux multi-file ZIP archive containing explicit partitions and SHA-256 manifest.
     */
    fun createZipBackup(outputStream: OutputStream) {
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            // 1. History
            val historyList = WatchHistoryRepository.getAllWatchHistory()
            val historyBytes = DesktopDataStore.mapper.writeValueAsBytes(historyList)
            addZipEntry(zip, "history.json", historyBytes)

            // 2. Bookmarks
            val bookmarksList = DesktopDataStore.getBookmarks()
            val bookmarksBytes = DesktopDataStore.mapper.writeValueAsBytes(bookmarksList)
            addZipEntry(zip, "bookmarks.json", bookmarksBytes)

            // 3. User Accounts (sanitize PIN hash, salt, and lock flags for safe device transfer)
            val accounts = AccountManagerDesktop.getAccounts().map {
                it.copy(pinHash = null, pinSalt = null, isLocked = false)
            }
            val accountsBytes = DesktopDataStore.mapper.writeValueAsBytes(accounts)
            addZipEntry(zip, "accounts.json", accountsBytes)

            // 4. Raw DataStore (strictly transferable keys only)
            val dataStoreMap = DesktopDataStore.cache.filter { it.key.isTransferable() }
            val dataStoreBytes = DesktopDataStore.mapper.writeValueAsBytes(dataStoreMap)
            addZipEntry(zip, "datastore.json", dataStoreBytes)

            // 5. App Settings
            val settings = DesktopDataStore.getSettings()
            val settingsBytes = DesktopDataStore.mapper.writeValueAsBytes(settings)
            addZipEntry(zip, "settings.json", settingsBytes)

            // 6. Upstream compatibility JSON
            val upstreamBaos = ByteArrayOutputStream()
            createUpstreamJsonBackup(upstreamBaos)
            val upstreamBytes = upstreamBaos.toByteArray()
            addZipEntry(zip, "backup.json", upstreamBytes)

            // 7. Manifest with SHA-256 verification hashes
            val checksums = mapOf(
                "history.json" to sha256(historyBytes),
                "bookmarks.json" to sha256(bookmarksBytes),
                "accounts.json" to sha256(accountsBytes),
                "datastore.json" to sha256(dataStoreBytes),
                "settings.json" to sha256(settingsBytes),
                "backup.json" to sha256(upstreamBytes)
            )
            val manifest = BackupManifest(checksums = checksums)
            val manifestBytes = DesktopDataStore.mapper.writeValueAsBytes(manifest)
            addZipEntry(zip, "manifest.json", manifestBytes)
        }
    }

    private fun addZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun sha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Universal restore: detects ZIP or raw upstream JSON and restores watch history,
     * bookmarks, accounts, DataStore keys, and settings.
     */
    fun restore(inputStream: InputStream): Boolean {
        val buffered = BufferedInputStream(inputStream)
        buffered.mark(10)
        val magic = ByteArray(4)
        val read = buffered.read(magic)
        buffered.reset()

        // PK\x03\x04 indicates ZIP archive
        return if (read >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        ) {
            restoreZip(buffered)
        } else {
            restoreUpstreamJson(buffered)
        }
    }

    private fun restoreZip(inputStream: InputStream): Boolean {
        val extractedFiles = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    extractedFiles[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        return try {
            // 1. Restore Datastore
            extractedFiles["datastore.json"]?.let { bytes ->
                val map: Map<String, String> = DesktopDataStore.mapper.readValue(
                    bytes,
                    object : TypeReference<Map<String, String>>() {}
                )
                map.forEach { (k, v) ->
                    if (k.isTransferable()) {
                        DesktopDataStore.cache[k] = v
                    }
                }
            }

            // 2. Restore History
            extractedFiles["history.json"]?.let { bytes ->
                val list: List<WatchHistory> = DesktopDataStore.mapper.readValue(
                    bytes,
                    object : TypeReference<List<WatchHistory>>() {}
                )
                list.forEach { WatchHistoryRepository.setLastWatched(it) }
            }

            // 3. Restore Bookmarks
            extractedFiles["bookmarks.json"]?.let { bytes ->
                val list: List<Bookmark> = DesktopDataStore.mapper.readValue(
                    bytes,
                    object : TypeReference<List<Bookmark>>() {}
                )
                list.forEach { DesktopDataStore.addBookmark(it) }
            }

            // 4. Restore Accounts
            extractedFiles["accounts.json"]?.let { bytes ->
                val list: List<Account> = DesktopDataStore.mapper.readValue(
                    bytes,
                    object : TypeReference<List<Account>>() {}
                )
                list.forEach { AccountManagerDesktop.updateAccount(it) }
            }

            // 5. Restore Settings
            extractedFiles["settings.json"]?.let { bytes ->
                val settings = DesktopDataStore.mapper.readValue(bytes, AppSettings::class.java)
                DesktopDataStore.setSettings(settings)
            }

            // Persist restored datastore to disk
            val snapshot = HashMap(DesktopDataStore.cache)
            val snapshotBytes = DesktopDataStore.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot)
            DesktopDataStore.atomicWrite(DesktopDataStore.dataFile, snapshotBytes)

            AppLogger.i("BackupRestoreManager", "Successfully restored ZIP backup archive")
            true
        } catch (e: Exception) {
            AppLogger.e("BackupRestoreManager", "Failed to restore ZIP backup archive", e)
            false
        }
    }

    private fun restoreUpstreamJson(inputStream: InputStream): Boolean {
        return try {
            val content = inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            val backup = DesktopDataStore.mapper.readValue(content, UpstreamBackupFile::class.java)

            // Restore transferable keys across all types
            backup.datastore.string?.forEach { (key, value) ->
                if (key.isTransferable()) {
                    DesktopDataStore.setKey(key, value)
                }
            }
            backup.datastore.bool?.forEach { (key, value) ->
                if (key.isTransferable()) {
                    DesktopDataStore.setKey(key, value.toString())
                }
            }
            backup.datastore.int?.forEach { (key, value) ->
                if (key.isTransferable()) {
                    DesktopDataStore.setKey(key, value.toString())
                }
            }
            backup.datastore.long?.forEach { (key, value) ->
                if (key.isTransferable()) {
                    DesktopDataStore.setKey(key, value.toString())
                }
            }
            backup.datastore.float?.forEach { (key, value) ->
                if (key.isTransferable()) {
                    DesktopDataStore.setKey(key, value.toString())
                }
            }
            backup.datastore.stringSet?.forEach { (key, value) ->
                if (key.isTransferable() && value != null) {
                    DesktopDataStore.setKey(key, DesktopDataStore.mapper.writeValueAsString(value))
                }
            }

            // Restore settings if present
            val settingsJson = backup.settings.string?.get("app_settings")
            if (!settingsJson.isNullOrBlank()) {
                val settings = DesktopDataStore.mapper.readValue(settingsJson, AppSettings::class.java)
                DesktopDataStore.setSettings(settings)
            } else {
                backup.settings.string?.forEach { (key, value) ->
                    if (key.isTransferable()) {
                        DesktopDataStore.setKey(key, value)
                    }
                }
                backup.settings.bool?.forEach { (key, value) ->
                    if (key.isTransferable()) {
                        DesktopDataStore.setKey(key, value.toString())
                    }
                }
                backup.settings.int?.forEach { (key, value) ->
                    if (key.isTransferable()) {
                        DesktopDataStore.setKey(key, value.toString())
                    }
                }
            }

            // Persist restored datastore to disk
            val snapshot = HashMap(DesktopDataStore.cache)
            val snapshotBytes = DesktopDataStore.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot)
            DesktopDataStore.atomicWrite(DesktopDataStore.dataFile, snapshotBytes)

            AppLogger.i("BackupRestoreManager", "Successfully restored upstream JSON backup")
            true
        } catch (e: Exception) {
            AppLogger.e("BackupRestoreManager", "Failed to restore upstream JSON backup", e)
            false
        }
    }
}
