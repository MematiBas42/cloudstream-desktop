package com.lagradost.common.sync

import com.lagradost.common.account.currentAccount
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore

/**
 * Crash-resilient offline outbox queue.
 * Persists failed scrobbles to DesktopDataStore so mutations are never lost during network drops.
 */
object SyncOutboxQueue {
    private const val OUTBOX_KEY = "sync_outbox_mutations_v2"
    private val lock = Any()

    private val storageKey: String
        get() = "${DesktopDataStore.currentAccount}/$OUTBOX_KEY"

    fun enqueue(mutation: QueuedScrobble) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            list.removeAll { it.providerPrefix == mutation.providerPrefix && it.mediaSyncId == mutation.mediaSyncId && it.episodeNumber == mutation.episodeNumber }
            list.add(mutation)
            DesktopDataStore.setKey(storageKey, list)
            AppLogger.i("SyncOutboxQueue", "Enqueued scrobble ${mutation.id} for ${mutation.providerPrefix}:${mutation.mediaSyncId} Ep ${mutation.episodeNumber}")
        }
    }

    fun getAll(): List<QueuedScrobble> = synchronized(lock) {
        DesktopDataStore.getKey<List<QueuedScrobble>>(storageKey)
            ?: DesktopDataStore.getKey<List<QueuedScrobble>>(OUTBOX_KEY)
            ?: emptyList()
    }

    fun remove(mutationId: String) {
        synchronized(lock) {
            val list = getAll().toMutableList()
            if (list.removeAll { it.id == mutationId }) {
                DesktopDataStore.setKey(storageKey, list)
            }
        }
    }

    fun incrementAttempt(mutationId: String) {
        synchronized(lock) {
            val list = getAll().map {
                if (it.id == mutationId) it.copy(retryCount = it.retryCount + 1) else it
            }
            DesktopDataStore.setKey(storageKey, list)
        }
    }

    fun clear() {
        synchronized(lock) {
            DesktopDataStore.removeKey(storageKey)
            DesktopDataStore.removeKey(OUTBOX_KEY)
        }
    }
}
