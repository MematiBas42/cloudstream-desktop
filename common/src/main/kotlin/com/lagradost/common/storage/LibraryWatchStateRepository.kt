package com.lagradost.common.storage

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * High-performance, reactive repository managing media library lifecycle,
 * categorized watch states, favorites, and episode subscriptions.
 *
 * Backed by [DesktopDataStore] adhering strictly to upstream schema:
 * - $currentAccount/result_watch_state/<id>
 * - $currentAccount/result_watch_state_data/<id>
 * - $currentAccount/result_favorites_state_data/<id>
 * - $currentAccount/result_subscribed_state_data/<id>
 */
object LibraryWatchStateRepository {

    const val RESULT_WATCH_STATE = "result_watch_state"
    const val RESULT_WATCH_STATE_DATA = "result_watch_state_data"
    const val RESULT_SUBSCRIBED_STATE_DATA = "result_subscribed_state_data"
    const val RESULT_FAVORITES_STATE_DATA = "result_favorites_state_data"

    /**
     * Active account identifier matching upstream AccountManager / DataStoreHelper.currentAccount.
     * Defaults to "0" (primary account).
     */
    @Volatile
    var currentAccount: String = "0"

    private val _libraryUpdatesFlow = MutableStateFlow(0)

    /**
     * Reactive StateFlow emitting incremental counter signals on any library state mutation.
     * Observers (such as TvLibraryViewModel or TV UI collections) collect this to trigger re-queries.
     */
    val libraryUpdatesFlow: StateFlow<Int> = _libraryUpdatesFlow.asStateFlow()

    private fun watchStateFolder(): String = "$currentAccount/$RESULT_WATCH_STATE"
    private fun watchStateDataFolder(): String = "$currentAccount/$RESULT_WATCH_STATE_DATA"
    private fun favoritesFolder(): String = "$currentAccount/$RESULT_FAVORITES_STATE_DATA"
    private fun subscribedFolder(): String = "$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA"

    private fun notifyMutation() {
        _libraryUpdatesFlow.value++
    }

    fun init() {
        // Force DesktopDataStore initialization
        DesktopDataStore.init()
    }

    // =========================================================================
    // 1. Categorized Watch State Management
    // =========================================================================

    /**
     * Retrieves the categorized watch state for the given title identifier.
     * Returns [DesktopWatchType.NONE] if no watch state is recorded.
     */
    fun getResultWatchState(id: String): DesktopWatchType {
        val folder = watchStateFolder()
        val raw = DesktopDataStore.getKey<Int>(folder, id)
            ?: DesktopDataStore.getKey<String>(folder, id)?.toIntOrNull()
        return DesktopWatchType.fromInternalId(raw)
    }

    fun getResultWatchState(id: Int): DesktopWatchType =
        getResultWatchState(id.toString())

    /**
     * Persists or updates the watch state and associated metadata for a title.
     * - If [state] is [DesktopWatchType.NONE], removes both watch state and metadata keys.
     * - Otherwise, persists state ID and metadata (if non-null).
     */
    fun setResultWatchState(id: String, state: DesktopWatchType, metadata: BookmarkedData? = null) {
        val stateFolder = watchStateFolder()
        val dataFolder = watchStateDataFolder()

        if (state == DesktopWatchType.NONE) {
            DesktopDataStore.removeKey(stateFolder, id)
            DesktopDataStore.removeKey(dataFolder, id)
        } else {
            DesktopDataStore.setKey(stateFolder, id, state.internalId)
            if (metadata != null) {
                DesktopDataStore.setKey(dataFolder, id, metadata)
            }
        }
        notifyMutation()
    }

    fun setResultWatchState(id: Int, state: DesktopWatchType, metadata: BookmarkedData? = null) =
        setResultWatchState(id.toString(), state, metadata)

    /**
     * Retrieves stored metadata for a bookmarked item.
     */
    fun getBookmarkedData(id: String): BookmarkedData? {
        return DesktopDataStore.getKey<BookmarkedData>(watchStateDataFolder(), id)
    }

    fun getBookmarkedData(id: Int): BookmarkedData? =
        getBookmarkedData(id.toString())

    /**
     * Directly persists bookmark metadata without altering current watch state.
     */
    fun setBookmarkedData(id: String, data: BookmarkedData) {
        DesktopDataStore.setKey(watchStateDataFolder(), id, data)
        notifyMutation()
    }

    fun setBookmarkedData(id: Int, data: BookmarkedData) =
        setBookmarkedData(id.toString(), data)

    /**
     * Removes bookmark metadata and watch state for the given title.
     */
    fun removeBookmarkedData(id: String) {
        DesktopDataStore.removeKey(watchStateFolder(), id)
        DesktopDataStore.removeKey(watchStateDataFolder(), id)
        notifyMutation()
    }

    fun removeBookmarkedData(id: Int) =
        removeBookmarkedData(id.toString())

    /**
     * Returns all title IDs currently registered with a non-NONE watch state.
     */
    fun getAllWatchStateIds(): List<String> {
        val folder = watchStateFolder()
        val prefix = "$folder/"
        return DesktopDataStore.getKeys(folder).mapNotNull { fullKey ->
            if (fullKey.startsWith(prefix)) {
                fullKey.removePrefix(prefix).takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
    }

    /**
     * Returns all bookmarked media items paired with their respective [DesktopWatchType].
     */
    fun getAllBookmarkedData(): List<Pair<DesktopWatchType, BookmarkedData>> {
        val ids = getAllWatchStateIds()
        return ids.mapNotNull { id ->
            val state = getResultWatchState(id)
            if (state == DesktopWatchType.NONE) return@mapNotNull null
            val data = getBookmarkedData(id) ?: return@mapNotNull null
            Pair(state, data)
        }
    }

    // =========================================================================
    // 2. Favorites Management
    // =========================================================================

    /**
     * Marks a title as favorite, storing rich metadata under the favorites namespace.
     */
    fun setFavorite(id: String, fav: FavoritesData) {
        DesktopDataStore.setKey(favoritesFolder(), id, fav)
        notifyMutation()
    }

    fun setFavorite(id: Int, fav: FavoritesData) =
        setFavorite(id.toString(), fav)

    /**
     * Returns true if the title is marked as a favorite.
     */
    fun isFavorite(id: String): Boolean {
        return DesktopDataStore.containsKey(favoritesFolder(), id)
    }

    fun isFavorite(id: Int): Boolean =
        isFavorite(id.toString())

    /**
     * Removes the favorite entry for the given title ID.
     */
    fun removeFavorite(id: String) {
        DesktopDataStore.removeKey(favoritesFolder(), id)
        notifyMutation()
    }

    fun removeFavorite(id: Int) =
        removeFavorite(id.toString())

    /**
     * Retrieves favorite metadata for the title ID.
     */
    fun getFavorite(id: String): FavoritesData? {
        return DesktopDataStore.getKey<FavoritesData>(favoritesFolder(), id)
    }

    fun getFavorite(id: Int): FavoritesData? =
        getFavorite(id.toString())

    /**
     * Returns all favorite titles for the active account.
     */
    fun getAllFavorites(): List<FavoritesData> {
        val folder = favoritesFolder()
        return DesktopDataStore.getKeys(folder).mapNotNull { fullKey ->
            DesktopDataStore.getKey<FavoritesData>(fullKey)
        }
    }

    // =========================================================================
    // 3. Subscriptions Management
    // =========================================================================

    /**
     * Records or updates a subscription with episode progress tracking.
     */
    fun setSubscription(id: String, sub: SubscriptionData) {
        DesktopDataStore.setKey(subscribedFolder(), id, sub)
        notifyMutation()
    }

    fun setSubscription(id: Int, sub: SubscriptionData) =
        setSubscription(id.toString(), sub)

    /**
     * Retrieves subscription metadata for the title ID.
     */
    fun getSubscription(id: String): SubscriptionData? {
        return DesktopDataStore.getKey<SubscriptionData>(subscribedFolder(), id)
    }

    fun getSubscription(id: Int): SubscriptionData? =
        getSubscription(id.toString())

    /**
     * Returns all active subscriptions for the current account.
     */
    fun getAllSubscriptions(): List<SubscriptionData> {
        val folder = subscribedFolder()
        return DesktopDataStore.getKeys(folder).mapNotNull { fullKey ->
            DesktopDataStore.getKey<SubscriptionData>(fullKey)
        }
    }

    /**
     * Removes an active subscription.
     */
    fun removeSubscription(id: String) {
        DesktopDataStore.removeKey(subscribedFolder(), id)
        notifyMutation()
    }

    fun removeSubscription(id: Int) =
        removeSubscription(id.toString())

    // =========================================================================
    // 4. Maintenance & Migration
    // =========================================================================

    /**
     * Purges all library entries (watch states, metadata, favorites, subscriptions)
     * for the current account.
     */
    fun clearAll() {
        DesktopDataStore.removeKeys(watchStateFolder())
        DesktopDataStore.removeKeys(watchStateDataFolder())
        DesktopDataStore.removeKeys(favoritesFolder())
        DesktopDataStore.removeKeys(subscribedFolder())
        notifyMutation()
    }

    /**
     * Migrates legacy flat bookmarks ("user_bookmarks") into categorized BookmarkedData
     * with [DesktopWatchType.PLANTOWATCH].
     */
    fun migrateLegacyBookmarks() {
        val legacy = DesktopDataStore.getBookmarks()
        if (legacy.isEmpty()) return

        AppLogger.i("LibraryWatchStateRepository", "Migrating ${legacy.size} legacy bookmarks to categorized watch states")
        for (b in legacy) {
            val targetId = b.id
            if (getResultWatchState(targetId) == DesktopWatchType.NONE) {
                val data = BookmarkedData(
                    id = targetId,
                    name = b.title,
                    url = b.url ?: "",
                    apiName = b.apiName ?: "Unknown",
                    posterUrl = b.posterUrl,
                    bookmarkedTime = b.createdAt,
                    latestUpdatedTime = b.createdAt
                )
                setResultWatchState(targetId, DesktopWatchType.PLANTOWATCH, data)
            }
        }
        DesktopDataStore.removeKey("user_bookmarks")
    }
}
