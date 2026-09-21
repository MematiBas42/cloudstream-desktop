package com.lagradost.common.storage

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Thread-safe key-value store with crash-resilient atomic persistence.
 * Backed by Jackson ObjectMapper and POSIX atomic renames.
 * Fully compatible with Upstream CloudStream $currentAccount/$folder/$path schema.
 */
object DesktopDataStore {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    val cache = ConcurrentHashMap<String, String>()

    fun getRaw(key: String): String? = cache[key]

    fun getAll(): Map<String, String> = cache.toMap()

    private val diskLock = ReentrantLock()

    @Volatile
    var customDataFile: File? = null

    val dataFile: File
        get() = customDataFile ?: PlatformPaths.dataDir.resolve("datastore.json").toFile()

    val pluginUpdatesFlow = MutableStateFlow(0)

    @Volatile
    var currentAccount: String = "0"

    val historyUpdates: MutableStateFlow<Int>
        get() = WatchHistoryRepository.historyUpdates

    init {
        loadFromFile()
    }

    /**
     * Early initialization trigger to force static loading outside plugin sandboxes.
     */
    fun init() {
        try {
            val file = dataFile
            if (!file.exists()) {
                file.parentFile?.mkdirs()
            }
            WatchHistoryRepository.init()
        } catch (e: Exception) {
            AppLogger.w("DesktopDataStore", "Initialization warning: ${e.message}")
        }
    }

    /**
     * Reload cache from disk, clearing existing in-memory state.
     */
    fun reload() {
        diskLock.withLock {
            cache.clear()
            loadFromFile()
        }
    }

    // =========================================================================
    // 1. Hierarchical Path Formatter
    // =========================================================================

    fun getFolderName(folder: String, path: String): String {
        val cleanFolder = folder.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$cleanFolder/$cleanPath"
    }

    // =========================================================================
    // 2. Serialization Helpers
    // =========================================================================

    fun <T : Any> serializeValue(value: T): String {
        return mapper.writeValueAsString(value)
    }

    fun <T : Any> deserializeValue(json: String, clazz: Class<T>): T? {
        return try {
            mapper.readValue(json, clazz)
        } catch (e: Exception) {
            if (clazz == String::class.java) {
                @Suppress("UNCHECKED_CAST")
                json as T
            } else {
                AppLogger.e("DesktopDataStore", "Failed to deserialize JSON to ${clazz.simpleName}", e)
                null
            }
        }
    }

    /**
     * Crash-resilient atomic write operation:
     * 1. Writes bytes to temporary file: <targetFile>.tmp.<randomUUID> on the same filesystem.
     * 2. Flushes buffers and invokes FileChannel.force(true) for metadata/content disk sync.
     * 3. Renames the temporary file to targetPath using ATOMIC_MOVE with REPLACE_EXISTING.
     * 4. Automatically removes temporary file if an unhandled exception occurs.
     */
    fun atomicWrite(targetPath: Path, content: ByteArray) {
        val parentDir = targetPath.parent
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir)
        }

        val tmpPath = targetPath.resolveSibling("${targetPath.fileName}.tmp.${UUID.randomUUID()}")

        try {
            FileOutputStream(tmpPath.toFile()).use { fos ->
                val channel = fos.channel
                fos.write(content)
                fos.flush()
                channel.force(true)
            }

            try {
                Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Fallback for filesystems lacking atomic rename support
                Files.move(
                    tmpPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(tmpPath)
            } catch (t: Throwable) {
                AppLogger.w("DesktopDataStore", "Failed to delete temporary file $tmpPath: ${t.message}")
            }
            throw e
        }
    }

    fun atomicWrite(targetFile: File, content: ByteArray) {
        atomicWrite(targetFile.toPath(), content)
    }

    private fun loadFromFile() {
        diskLock.withLock {
            try {
                val file = dataFile
                if (file.exists() && file.length() > 0) {
                    val map: Map<String, String> = mapper.readValue(file)
                    cache.putAll(map)
                }
            } catch (e: Exception) {
                AppLogger.e("DesktopDataStore", "Failed to load datastore from ${dataFile.absolutePath}", e)
            }
        }
    }

    private fun saveToFile() {
        diskLock.withLock {
            try {
                val snapshot = HashMap(cache)
                val bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot)
                atomicWrite(dataFile, bytes)
            } catch (e: Exception) {
                AppLogger.e("DesktopDataStore", "Failed to persist datastore to ${dataFile.absolutePath}", e)
            }
        }
    }

    // =========================================================================
    // 3. Core Key-Value Operations
    // =========================================================================

    fun setKey(key: String, value: Any?) {
        if (value == null) {
            removeKey(key)
            return
        }

        try {
            val json = serializeValue(value)
            cache[key] = json
            saveToFile()
        } catch (e: Exception) {
            AppLogger.e("DesktopDataStore", "Failed to serialize key: $key", e)
        }
    }

    fun setKey(folder: String, path: String, value: Any?) {
        setKey(getFolderName(folder, path), value)
    }

    fun <T : Any> getKey(key: String, clazz: Class<T>): T? {
        val json = cache[key] ?: return null
        return deserializeValue(json, clazz)
    }

    fun <T : Any> getKey(folder: String, path: String, clazz: Class<T>): T? {
        return getKey(getFolderName(folder, path), clazz)
    }

    inline fun <reified T> getKey(key: String, defVal: T? = null): T? {
        val json = cache[key] ?: return defVal
        return try {
            mapper.readValue<T>(json)
        } catch (e: Exception) {
            if (String::class == T::class) {
                @Suppress("UNCHECKED_CAST")
                json as T
            } else {
                defVal
            }
        }
    }

    inline fun <reified T> getKey(folder: String, path: String, defVal: T? = null): T? {
        return getKey(getFolderName(folder, path), defVal)
    }

    fun removeKey(key: String) {
        if (cache.remove(key) != null) {
            saveToFile()
        }
    }

    fun removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    fun containsKey(key: String): Boolean {
        return cache.containsKey(key)
    }

    fun containsKey(folder: String, path: String): Boolean {
        return containsKey(getFolderName(folder, path))
    }

    // =========================================================================
    // 4. Hierarchical Folder Operations (Upstream Parity)
    // =========================================================================

    /**
     * Returns all full keys stored under the given folder prefix.
     * Enforces trailing slash matching to prevent partial prefix collisions.
     */
    fun getKeys(folder: String): List<String> {
        val fixedFolder = folder.trimEnd('/') + "/"
        return cache.keys.filter { it.startsWith(fixedFolder) }
    }

    /**
     * Atomically removes all keys under the given folder prefix.
     * Returns the count of removed keys.
     */
    fun removeKeys(folder: String): Int {
        val fixedFolder = folder.trimEnd('/') + "/"
        val matchingKeys = cache.keys.filter { it.startsWith(fixedFolder) }
        if (matchingKeys.isEmpty()) return 0

        var removedCount = 0
        for (k in matchingKeys) {
            if (cache.remove(k) != null) {
                removedCount++
            }
        }
        if (removedCount > 0) {
            saveToFile()
        }
        return removedCount
    }

    // =========================================================================
    // 5. Batch Editor Support (Zero-Thrashing I/O)
    // =========================================================================

    class BatchEditor {
        private val staging = ConcurrentHashMap<String, String?>()

        fun setKey(key: String, value: Any?): BatchEditor {
            if (value == null) {
                staging[key] = null
            } else {
                staging[key] = serializeValue(value)
            }
            return this
        }

        fun setKey(folder: String, path: String, value: Any?): BatchEditor {
            return setKey(getFolderName(folder, path), value)
        }

        fun removeKey(key: String): BatchEditor {
            staging[key] = null
            return this
        }

        fun removeKey(folder: String, path: String): BatchEditor {
            return removeKey(getFolderName(folder, path))
        }

        fun apply() {
            var modified = false
            for ((key, value) in staging) {
                if (value == null) {
                    if (cache.remove(key) != null) modified = true
                } else {
                    cache[key] = value
                    modified = true
                }
            }
            if (modified) {
                saveToFile()
            }
        }

        fun commit(): Boolean {
            apply()
            return true
        }
    }

    fun edit(): BatchEditor = BatchEditor()

    // --- Bookmarks Convenience Layer ---

    private const val BOOKMARKS_KEY = "user_bookmarks"

    fun getBookmarks(): List<Bookmark> {
        val json = cache[BOOKMARKS_KEY] ?: return emptyList()
        return try {
            mapper.readValue(json, object : TypeReference<List<Bookmark>>() {})
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        val current = getBookmarks().toMutableList()
        current.removeAll { it.id == bookmark.id }
        current.add(bookmark)
        setKey(BOOKMARKS_KEY, current)
    }

    fun removeBookmark(id: String) {
        val current = getBookmarks().toMutableList()
        if (current.removeAll { it.id == id }) {
            setKey(BOOKMARKS_KEY, current)
        }
    }

    fun isBookmarked(id: String): Boolean {
        return getBookmarks().any { it.id == id }
    }

    // --- Settings Convenience Layer ---

    private const val SETTINGS_KEY = "app_settings"

    fun getSettings(): AppSettings {
        return getKey<AppSettings>(SETTINGS_KEY) ?: AppSettings()
    }

    fun setSettings(settings: AppSettings) {
        setKey(SETTINGS_KEY, settings)
    }

    // --- Plugin Updates History ---

    private const val UPDATES_HISTORY_KEY = "plugin_updates_history_v2"
    private const val UNREAD_UPDATES_KEY = "unread_plugin_updates"

    fun getUpdatesHistory(): List<PluginUpdateRecord> {
        val json = cache[UPDATES_HISTORY_KEY] ?: return emptyList()
        return try {
            mapper.readValue(json, object : TypeReference<List<PluginUpdateRecord>>() {})
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addUpdateHistory(history: List<PluginUpdateRecord>) {
        if (history.isEmpty()) return

        val consolidatedHistory = history.sortedByDescending { it.timestamp }
            .distinctBy { it.pluginName }

        val current = getUpdatesHistory().toMutableList()
        val incomingNames = consolidatedHistory.map { it.pluginName }.toSet()
        current.removeAll { it.pluginName in incomingNames }

        current.addAll(0, consolidatedHistory)
        current.sortByDescending { it.timestamp }
        if (current.size > 50) {
            current.subList(50, current.size).clear()
        }
        setKey(UPDATES_HISTORY_KEY, current)
        pluginUpdatesFlow.value++
    }

    fun hasUnreadUpdates(): Boolean {
        return getKey<Boolean>(UNREAD_UPDATES_KEY) ?: false
    }

    fun setUnreadUpdates(hasUnread: Boolean) {
        setKey(UNREAD_UPDATES_KEY, hasUnread)
        pluginUpdatesFlow.value++
    }

    // --- Watch History Facade (delegates to WatchHistoryRepository) ---

    fun watchHistoryId(
        apiName: String,
        showUrl: String,
        season: Int? = null,
        episode: Int? = null,
        episodeData: String? = null,
    ): String {
        val base = "${apiName}_${showUrl.hashCode()}"
        return if (season != null || episode != null || !episodeData.isNullOrBlank()) {
            "${base}_s${season ?: 0}_e${episode ?: 0}_${episodeData?.hashCode() ?: 0}"
        } else {
            base
        }
    }

    fun getAllWatchHistory(): List<WatchHistory> =
        WatchHistoryRepository.getAllWatchHistory()

    fun setLastWatched(history: WatchHistory) =
        WatchHistoryRepository.setLastWatched(history)

    fun getLastWatched(parentId: String?, episodeId: String? = null): WatchHistory? =
        WatchHistoryRepository.getLastWatched(parentId, episodeId)

    fun getLatestWatchHistoryForShow(showUrl: String): WatchHistory? =
        WatchHistoryRepository.getLastWatched(showUrl, null)

    fun getEpisodeWatched(parentId: String, episodeId: String?): WatchHistory? =
        WatchHistoryRepository.getLastWatched(parentId, episodeId)

    fun removeWatchHistory(url: String) =
        WatchHistoryRepository.removeWatchHistory(url)

    fun clearAllWatchHistory() =
        WatchHistoryRepository.clearAll()
}
