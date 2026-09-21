package com.lagradost.common.sync

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.account.currentAccount
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import com.lagradost.common.sync.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified synchronization coordinator managing connected accounts (MAL, AniList, Kitsu, Simkl, LocalList).
 *
 * Implements:
 * 1. Multi-account credential storage with token refresh mutex (anti-race single-flight).
 * 2. Automated 85% playback scrobble pipeline with duration guards, deduplication, and session idempotency.
 * 3. RFC 8628 TV Device Code authentication coordination and polling.
 * 4. Offline resilience and persistent spool outbox dispatch.
 */
object SyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private const val ACCOUNTS_KEY = "sync_accounts_v2"
    private val storageKey: String
        get() = "${DesktopDataStore.currentAccount}/$ACCOUNTS_KEY"

    // --- Provider Registry ---
    private val providers = ConcurrentHashMap<String, SyncProvider>()

    val localListProvider = LocalListSyncProvider()
    val simklApi = SimklApi(tokenProvider = { getAccount("simkl")?.accessToken })
    val anilistApi = AniListApi(tokenProvider = { getAccount("anilist")?.accessToken })
    val malApi = MALApi(tokenProvider = { getAccount("mal")?.accessToken })
    val kitsuApi = KitsuApi(tokenProvider = { getAccount("kitsu")?.accessToken })

    // --- State Flows ---
    private val _accountsFlow = MutableStateFlow<Map<String, SyncAccount>>(emptyMap())
    val accountsFlow: StateFlow<Map<String, SyncAccount>> = _accountsFlow.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryMetadata?>(null)
    val libraryState: StateFlow<LibraryMetadata?> = _libraryState.asStateFlow()

    // --- Single-Flight Mutexes for Token Refresh ---
    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()

    // --- Session Idempotency Cache for Scrobbling ---
    private val scrobbledSessions = ConcurrentHashMap.newKeySet<String>()

    private var historyObserverJob: Job? = null

    init {
        registerProvider(localListProvider)
        registerProvider(simklApi)
        registerProvider(anilistApi)
        registerProvider(malApi)
        registerProvider(kitsuApi)

        loadAccountsFromStorage()
        observeWatchHistoryForScrobbling()
    }

    fun init() {
        loadAccountsFromStorage()
        localListProvider.reload()
    }

    fun registerProvider(provider: SyncProvider) {
        providers[provider.idPrefix] = provider
        refreshMutexes.putIfAbsent(provider.idPrefix, Mutex())
        AppLogger.i("SyncManager", "Registered sync provider: ${provider.name} (${provider.idPrefix})")
    }

    fun getProvider(prefix: String): SyncProvider? = providers[prefix]
    fun getAllProviders(): List<SyncProvider> = providers.values.toList()

    // =========================================================================
    // 1. Account & Credential Management
    // =========================================================================

    private fun loadAccountsFromStorage() {
        synchronized(lock) {
            val key = storageKey
            val list = DesktopDataStore.getKey<List<SyncAccount>>(key)
                ?: DesktopDataStore.getKey<List<SyncAccount>>(ACCOUNTS_KEY)
                ?: emptyList()
            _accountsFlow.value = list.associateBy { it.providerPrefix }
        }
    }

    private fun persistAccounts(map: Map<String, SyncAccount>) {
        synchronized(lock) {
            _accountsFlow.value = map
            DesktopDataStore.setKey(storageKey, map.values.toList())
        }
    }

    fun getAccount(providerPrefix: String): SyncAccount? {
        return _accountsFlow.value[providerPrefix]
    }

    fun getAccounts(): List<SyncAccount> {
        return _accountsFlow.value.values.toList()
    }

    fun isAccountConnected(providerPrefix: String): Boolean {
        val acc = getAccount(providerPrefix) ?: return false
        return !acc.accessToken.isNullOrBlank()
    }

    suspend fun saveAccount(account: SyncAccount) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val updated = _accountsFlow.value.toMutableMap()
            updated[account.providerPrefix] = account
            persistAccounts(updated)
            AppLogger.i("SyncManager", "Saved sync account: ${account.username} for ${account.providerPrefix}")
        }
    }

    suspend fun removeAccount(providerPrefix: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val updated = _accountsFlow.value.toMutableMap()
            if (updated.remove(providerPrefix) != null) {
                persistAccounts(updated)
                AppLogger.i("SyncManager", "Removed sync account for $providerPrefix")
            }
        }
    }

    fun clearAccounts() {
        synchronized(lock) {
            persistAccounts(emptyMap())
        }
    }

    // =========================================================================
    // 2. Token Refresh with Single-Flight Mutex Protection
    // =========================================================================

    /**
     * Obtains a valid non-expired access token.
     * Uses a per-provider Mutex with double-checked locking to guarantee that
     * concurrent requests only trigger a single token refresh race-free.
     */
    suspend fun getValidTokenWithMutex(
        prefix: String,
        customRefresher: (suspend (SyncAccount) -> SyncAccount?)? = null
    ): String? = withContext(Dispatchers.IO) {
        val initialAccount = getAccount(prefix) ?: return@withContext null
        if (!initialAccount.isTokenExpired()) {
            return@withContext initialAccount.accessToken
        }

        val mutex = refreshMutexes.computeIfAbsent(prefix) { Mutex() }
        mutex.withLock {
            // Re-read account after acquiring lock (double-checked locking)
            val currentAccount = getAccount(prefix) ?: return@withLock null
            if (!currentAccount.isTokenExpired()) {
                return@withLock currentAccount.accessToken
            }

            AppLogger.i("SyncManager", "Refreshing expired access token for $prefix with single-flight mutex")

            val refreshedAccount = when {
                customRefresher != null -> customRefresher(currentAccount)
                prefix == "mal" && currentAccount.refreshToken != null -> {
                    malApi.refreshToken(currentAccount.refreshToken)
                }
                else -> null
            }

            if (refreshedAccount != null) {
                saveAccount(refreshedAccount)
                refreshedAccount.accessToken
            } else {
                // If refresh failed or not supported, return existing token if present
                currentAccount.accessToken
            }
        }
    }

    // =========================================================================
    // 3. Automated 85% Playback Scrobble Pipeline
    // =========================================================================

    fun isSessionScrobbled(sessionKey: String): Boolean =
        scrobbledSessions.contains(sessionKey)

    fun resetScrobbledSessions() {
        scrobbledSessions.clear()
    }

    /**
     * Evaluates playback progress and triggers scrobble if:
     * 1. Media duration >= 120s (ignore previews/clips)
     * 2. Progress >= 85% (0.85) or marked completed
     * 3. Session idempotency: episode has not yet been scrobbled in this session.
     */
    suspend fun processPlaybackProgress(progress: ScrobbleProgress): Boolean = withContext(Dispatchers.IO) {
        // 1. Guard: Duration and threshold
        if (!progress.shouldScrobble()) {
            return@withContext false
        }

        // 2. Guard: Idempotency deduplication
        val key = progress.sessionKey()
        if (!scrobbledSessions.add(key)) {
            AppLogger.d("SyncManager", "Session $key already scrobbled, skipping.")
            return@withContext false
        }

        AppLogger.i(
            "SyncManager",
            "Scrobble triggered for ${progress.mediaId} Ep ${progress.episodeNumber} at ${(progress.progressRatio * 100).toInt()}%"
        )

        // 3. Update LocalListSyncProvider
        val currentBookmark = localListProvider.getBookmark(progress.mediaId)
        val targetStatus = if (currentBookmark?.totalEpisodes != null && progress.episodeNumber >= currentBookmark.totalEpisodes) {
            SyncWatchType.COMPLETED
        } else {
            SyncWatchType.WATCHING
        }

        val localStatus = SyncStatus(
            status = targetStatus,
            watchedEpisodes = progress.episodeNumber,
            maxEpisodes = currentBookmark?.totalEpisodes
        )
        localListProvider.updateStatus(progress.mediaId, localStatus)

        // 4. Remote Accounts Dispatch
        val activeAccounts = getAccounts().filter { !it.accessToken.isNullOrBlank() }
        for (account in activeAccounts) {
            if (account.providerPrefix == "local") continue
            if (progress.providerPrefix != null && progress.providerPrefix != "all" && progress.providerPrefix != account.providerPrefix) {
                continue
            }

            val prefix = account.providerPrefix
            val provider = providers[prefix]
            if (provider == null) continue

            val token = getValidTokenWithMutex(prefix)
            if (token == null) {
                AppLogger.w("SyncManager", "No valid token for $prefix, enqueuing outbox.")
                enqueueOutbox(prefix, progress.mediaId, progress.episodeNumber, progress.seasonNumber, targetStatus)
                continue
            }

            try {
                val syncStatus = SyncStatus(
                    status = targetStatus,
                    watchedEpisodes = progress.episodeNumber,
                    maxEpisodes = currentBookmark?.totalEpisodes
                )
                val ok = provider.updateStatus(progress.mediaId, syncStatus)
                if (!ok) {
                    enqueueOutbox(prefix, progress.mediaId, progress.episodeNumber, progress.seasonNumber, targetStatus)
                }
            } catch (e: Exception) {
                AppLogger.w("SyncManager", "Remote scrobble failed for $prefix, enqueuing outbox: ${e.message}")
                enqueueOutbox(prefix, progress.mediaId, progress.episodeNumber, progress.seasonNumber, targetStatus)
            }
        }

        refreshLibrary()
        true
    }

    private fun enqueueOutbox(
        prefix: String,
        mediaId: String,
        episode: Int,
        season: Int?,
        status: SyncWatchType
    ) {
        SyncOutboxQueue.enqueue(
            QueuedScrobble(
                providerPrefix = prefix,
                mediaSyncId = mediaId,
                episodeNumber = episode,
                seasonNumber = season,
                status = status
            )
        )
    }

    // =========================================================================
    // 4. WatchHistory Reactive Pipeline
    // =========================================================================

    private fun observeWatchHistoryForScrobbling() {
        historyObserverJob?.cancel()
        historyObserverJob = scope.launch {
            WatchHistoryRepository.historyUpdates.collect {
                val latest = WatchHistoryRepository.getAllWatchHistory().firstOrNull() ?: return@collect
                if (latest.isWatched || latest.isCompleted) {
                    val durationSec = latest.duration / 1000.0
                    val positionSec = latest.position / 1000.0
                    val ep = latest.episode ?: 1
                    val parentId = latest.parentId ?: latest.url

                    val progress = ScrobbleProgress(
                        mediaId = parentId,
                        episodeNumber = ep,
                        seasonNumber = latest.season ?: 1,
                        positionSec = positionSec,
                        durationSec = durationSec,
                        isCompleted = latest.isCompleted,
                        title = latest.title
                    )

                    processPlaybackProgress(progress)
                }
            }
        }
    }

    // =========================================================================
    // 5. Offline Outbox Flush
    // =========================================================================

    suspend fun flushOutbox(): Int = withContext(Dispatchers.IO) {
        val pending = SyncOutboxQueue.getAll()
        if (pending.isEmpty()) return@withContext 0

        var flushedCount = 0
        for (item in pending) {
            val token = getValidTokenWithMutex(item.providerPrefix)
            if (token == null) {
                SyncOutboxQueue.incrementAttempt(item.id)
                continue
            }

            val provider = providers[item.providerPrefix]
            if (provider == null) {
                SyncOutboxQueue.incrementAttempt(item.id)
                continue
            }

            try {
                val syncStatus = SyncStatus(
                    status = item.status,
                    score = item.score,
                    watchedEpisodes = item.episodeNumber
                )
                val success = provider.updateStatus(item.mediaSyncId, syncStatus)

                if (success) {
                    SyncOutboxQueue.remove(item.id)
                    flushedCount++
                    AppLogger.i("SyncManager", "Flushed outbox mutation ${item.id} for ${item.providerPrefix}")
                } else {
                    SyncOutboxQueue.incrementAttempt(item.id)
                }
            } catch (e: Exception) {
                SyncOutboxQueue.incrementAttempt(item.id)
                AppLogger.w("SyncManager", "Failed to flush outbox item ${item.id}: ${e.message}")
            }
        }
        flushedCount
    }

    // =========================================================================
    // 6. RFC 8628 TV Device Code Authentication Flow
    // =========================================================================

    suspend fun requestDeviceCode(providerPrefix: String): DeviceCodeResponse = withContext(Dispatchers.IO) {
        when (providerPrefix) {
            "simkl" -> simklApi.requestDeviceCode()
            "anilist" -> anilistApi.requestDeviceCode()
            else -> throw IllegalArgumentException("Device code flow not supported for $providerPrefix")
        }
    }

    suspend fun pollDeviceCode(providerPrefix: String, userCode: String): DevicePollingResult = withContext(Dispatchers.IO) {
        when (providerPrefix) {
            "simkl" -> simklApi.pollDeviceCode(userCode)
            "anilist" -> anilistApi.pollDeviceCode(userCode)
            else -> DevicePollingResult.Failure("Unsupported provider: $providerPrefix")
        }
    }

    suspend fun completeDeviceAuth(providerPrefix: String, accessToken: String): SyncAccount = withContext(Dispatchers.IO) {
        val profile = when (providerPrefix) {
            "simkl" -> simklApi.fetchUserProfile(accessToken)
            "anilist" -> anilistApi.fetchUserProfile(accessToken)
            "mal" -> malApi.fetchUserProfile(accessToken)
            else -> SyncAccount(
                id = "${providerPrefix}_user",
                username = "${providerPrefix.uppercase()} User",
                providerPrefix = providerPrefix,
                accessToken = accessToken
            )
        }
        saveAccount(profile)
        profile
    }

    // =========================================================================
    // 7. Unified Library Management
    // =========================================================================

    suspend fun updateWatchStatus(
        mediaId: String,
        status: SyncWatchType,
        score: Int? = null,
        watchedEpisodes: Int? = null,
        isFavorite: Boolean? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val syncStatus = SyncStatus(
            status = status,
            score = score,
            watchedEpisodes = watchedEpisodes,
            isFavorite = isFavorite
        )

        val localSuccess = localListProvider.updateStatus(mediaId, syncStatus)

        // Find associated external sync IDs if any
        val bookmark = localListProvider.getBookmark(mediaId)
        val mappings = bookmark?.syncMappings ?: emptyMap()

        for ((prefix, externalId) in mappings) {
            val remoteProvider = providers[prefix] ?: continue
            try {
                remoteProvider.updateStatus(externalId, syncStatus)
            } catch (e: Exception) {
                AppLogger.w("SyncManager", "Failed to update status on $prefix for $externalId. Enqueuing outbox.")
                enqueueOutbox(prefix, externalId, watchedEpisodes ?: 0, null, status)
            }
        }

        refreshLibrary()
        localSuccess
    }

    suspend fun refreshLibrary(): LibraryMetadata? = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        try {
            val meta = localListProvider.library()
            _libraryState.value = meta
            meta
        } catch (e: Exception) {
            AppLogger.e("SyncManager", "Failed to refresh library: ${e.message}")
            null
        } finally {
            _isSyncing.value = false
        }
    }
}
