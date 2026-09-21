package com.lagradost.common.sync.providers

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.account.currentAccount
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Offline bookmark synchronization provider backed by DesktopDataStore.
 * First-class symmetric SyncAPI implementation supporting status updates,
 * retrieval, fuzzy search, and categorized library metadata.
 */
class LocalListSyncProvider : SyncAPI() {
    override val name: String = "Local List"
    override val idPrefix: String = "local"
    override val requiresLogin: Boolean = false
    override var requireLibraryRefresh: Boolean = true

    private val lock = Any()

    private val storageKey: String
        get() = "${DesktopDataStore.currentAccount}/local_bookmarks_v2"

    private val _bookmarksFlow = MutableStateFlow<Map<String, SyncEntry>>(emptyMap())
    val bookmarksFlow: StateFlow<Map<String, SyncEntry>> = _bookmarksFlow.asStateFlow()

    init {
        loadFromDataStore()
    }

    fun reload() {
        loadFromDataStore()
    }

    private fun loadFromDataStore() {
        synchronized(lock) {
            val key = storageKey
            val list = DesktopDataStore.getKey<List<SyncEntry>>(key)
                ?: DesktopDataStore.getKey<List<SyncEntry>>("local_bookmarks_v2")
                ?: emptyList()
            _bookmarksFlow.value = list.associateBy { it.id }
        }
    }

    private fun saveToDataStore() {
        synchronized(lock) {
            val list = _bookmarksFlow.value.values.toList()
            DesktopDataStore.setKey(storageKey, list)
            requireLibraryRefresh = true
        }
    }

    fun getBookmark(id: String): SyncEntry? {
        synchronized(lock) {
            val cached = _bookmarksFlow.value[id]
            if (cached != null) return cached
            loadFromDataStore()
            return _bookmarksFlow.value[id]
        }
    }

    fun getAllBookmarks(): List<SyncEntry> {
        synchronized(lock) {
            return _bookmarksFlow.value.values.toList()
        }
    }

    fun saveBookmark(bookmark: SyncEntry) {
        synchronized(lock) {
            val updated = _bookmarksFlow.value.toMutableMap()
            updated[bookmark.id] = bookmark.copy(updatedAt = System.currentTimeMillis())
            _bookmarksFlow.value = updated
            saveToDataStore()
            AppLogger.i("LocalListSyncProvider", "Saved bookmark: ${bookmark.name} (${bookmark.id})")
        }
    }

    fun deleteBookmark(id: String): Boolean {
        synchronized(lock) {
            val updated = _bookmarksFlow.value.toMutableMap()
            if (updated.remove(id) != null) {
                _bookmarksFlow.value = updated
                saveToDataStore()
                AppLogger.i("LocalListSyncProvider", "Deleted bookmark: $id")
                return true
            }
            return false
        }
    }

    override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            var existing = _bookmarksFlow.value[id]
            if (existing == null) {
                loadFromDataStore()
                existing = _bookmarksFlow.value[id]
            }

            if (existing == null) {
                // If bookmark doesn't exist, create minimal entry if not removing
                if (newStatus.status == SyncWatchType.NONE && newStatus.isFavorite != true) {
                    return@withContext false
                }
                val newEntry = SyncEntry(
                    id = id,
                    name = id,
                    status = newStatus.status,
                    score = newStatus.score,
                    watchedEpisodes = newStatus.watchedEpisodes ?: 0,
                    totalEpisodes = newStatus.maxEpisodes,
                    isFavorite = newStatus.isFavorite ?: false,
                    updatedAt = System.currentTimeMillis()
                )
                saveBookmark(newEntry)
                return@withContext true
            }

            val updated = existing.copy(
                status = newStatus.status,
                score = newStatus.score ?: existing.score,
                watchedEpisodes = newStatus.watchedEpisodes ?: existing.watchedEpisodes,
                isFavorite = newStatus.isFavorite ?: existing.isFavorite,
                totalEpisodes = newStatus.maxEpisodes ?: existing.totalEpisodes,
                updatedAt = System.currentTimeMillis()
            )

            if (newStatus.status == SyncWatchType.NONE && !updated.isFavorite) {
                deleteBookmark(id)
            } else {
                saveBookmark(updated)
            }
            true
        }
    }

    override suspend fun status(id: String): SyncStatus? = withContext(Dispatchers.IO) {
        val bookmark = getBookmark(id) ?: return@withContext null
        SyncStatus(
            status = bookmark.status,
            score = bookmark.score,
            watchedEpisodes = bookmark.watchedEpisodes,
            isFavorite = bookmark.isFavorite,
            maxEpisodes = bookmark.totalEpisodes,
            updatedAt = bookmark.updatedAt
        )
    }

    override suspend fun load(id: String): SyncEntry? = withContext(Dispatchers.IO) {
        getBookmark(id)
    }

    override suspend fun search(query: String): List<SyncEntry> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withContext emptyList()

        synchronized(lock) {
            _bookmarksFlow.value.values.filter {
                it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
            }
        }
    }

    override suspend fun library(): LibraryMetadata = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val all = _bookmarksFlow.value.values

            val watching = mutableListOf<LibraryItem>()
            val completed = mutableListOf<LibraryItem>()
            val onHold = mutableListOf<LibraryItem>()
            val dropped = mutableListOf<LibraryItem>()
            val planToWatch = mutableListOf<LibraryItem>()
            val favorites = mutableListOf<LibraryItem>()

            for (item in all) {
                val libItem = item.toLibraryItem()
                if (item.isFavorite) {
                    favorites.add(libItem)
                }
                when (item.status) {
                    SyncWatchType.WATCHING, SyncWatchType.REWATCHING -> watching.add(libItem)
                    SyncWatchType.COMPLETED -> completed.add(libItem)
                    SyncWatchType.ONHOLD -> onHold.add(libItem)
                    SyncWatchType.DROPPED -> dropped.add(libItem)
                    SyncWatchType.PLANTOWATCH -> planToWatch.add(libItem)
                    SyncWatchType.NONE -> Unit
                }
            }

            val lists = listOf(
                LibraryList(SyncWatchType.WATCHING.displayName, watching),
                LibraryList(SyncWatchType.COMPLETED.displayName, completed),
                LibraryList(SyncWatchType.PLANTOWATCH.displayName, planToWatch),
                LibraryList(SyncWatchType.ONHOLD.displayName, onHold),
                LibraryList(SyncWatchType.DROPPED.displayName, dropped),
                LibraryList("Favorites", favorites)
            )

            LibraryMetadata(
                allLibraryLists = lists,
                supportedListSorting = setOf(
                    ListSorting.UpdatedNew,
                    ListSorting.UpdatedOld,
                    ListSorting.AlphabeticalA,
                    ListSorting.AlphabeticalZ,
                    ListSorting.RatingHigh,
                    ListSorting.ReleaseDateNew
                )
            )
        }
    }
}
