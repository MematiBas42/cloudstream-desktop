package com.lagradost.common.sync

/**
 * Base contract for synchronization engines (LocalList, MAL, AniList, Kitsu, Simkl).
 */
interface SyncProvider {
    val name: String
    val idPrefix: String
    val requiresLogin: Boolean
    var requireLibraryRefresh: Boolean
    val supportedWatchTypes: Set<SyncWatchType>

    suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean
    suspend fun status(id: String): SyncStatus?
    suspend fun load(id: String): SyncEntry?
    suspend fun search(query: String): List<SyncEntry>
    suspend fun library(): LibraryMetadata?
    fun urlToId(url: String): String? = null
}

/**
 * Abstract SyncAPI with standard defaults.
 */
abstract class SyncAPI : SyncProvider {
    override val requiresLogin: Boolean = true
    override var requireLibraryRefresh: Boolean = true
    override val supportedWatchTypes: Set<SyncWatchType> = SyncWatchType.entries.toSet()

    override suspend fun updateStatus(id: String, newStatus: SyncStatus): Boolean = false
    override suspend fun status(id: String): SyncStatus? = null
    override suspend fun load(id: String): SyncEntry? = null
    override suspend fun search(query: String): List<SyncEntry> = emptyList()
    override suspend fun library(): LibraryMetadata? = null
    override fun urlToId(url: String): String? = null
}
