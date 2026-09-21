// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/SyncViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

import android.util.Log
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.throwAbleToResource
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.aniListApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.kitsuApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.malApi
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.simklApi
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.utils.SyncUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

data class CurrentSynced(
    val name: String,
    val idPrefix: String,
    val isSynced: Boolean,
    val hasAccount: Boolean,
    val icon: Int?,
)

class SyncViewModel(
    private val dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    companion object {
        const val TAG = "SYNCVM"
    }

    private val ioDispatcher: CoroutineDispatcher = dispatcher ?: Dispatchers.IO

    private val repos = AccountManager.syncApis

    private val _metaResponse = MutableStateFlow<Resource<SyncAPI.SyncResult>?>(null)
    val metadata: StateFlow<Resource<SyncAPI.SyncResult>?> = _metaResponse.asStateFlow()

    private val _userDataResponse = MutableStateFlow<Resource<SyncAPI.AbstractSyncStatus>?>(null)
    val userData: StateFlow<Resource<SyncAPI.AbstractSyncStatus>?> = _userDataResponse.asStateFlow()

    // prefix, id
    private val syncs = mutableMapOf<String, String>()

    fun getSyncs(): Map<String, String> {
        return syncs
    }

    private val _currentSynced = MutableStateFlow<List<CurrentSynced>>(getMissing())
    val synced: StateFlow<List<CurrentSynced>> = _currentSynced.asStateFlow()

    private fun getMissing(): List<CurrentSynced> {
        return repos.map {
            CurrentSynced(
                it.name,
                it.idPrefix,
                syncs.containsKey(it.idPrefix),
                it.authUser() != null,
                it.icon,
            )
        }
    }

    fun updateSynced() {
        Log.i(TAG, "updateSynced")
        _currentSynced.value = getMissing()
    }

    private fun addSync(idPrefix: String, id: String): Boolean {
        if (syncs[idPrefix] == id) return false
        Log.i(TAG, "addSync $idPrefix = $id")

        syncs[idPrefix] = id
        return true
    }

    fun addSyncs(map: Map<String, String>?): Boolean {
        var isValid = false

        map?.forEach { (prefix, id) ->
            isValid = addSync(prefix, id) || isValid
        }
        return isValid
    }

    private fun setMalId(id: String?): Boolean {
        return addSync(malApi.idPrefix, id ?: return false)
    }

    private fun setAniListId(id: String?): Boolean {
        return addSync(aniListApi.idPrefix, id ?: return false)
    }

    var hasAddedFromUrl: HashSet<String> = hashSetOf()

    fun addFromUrl(url: String?) = viewModelScope.launch(ioDispatcher) {
        Log.i(TAG, "addFromUrl = $url")

        if (url == null || hasAddedFromUrl.contains(url)) return@launch
        if (!url.startsWith("http")) return@launch

        SyncUtil.getIdsFromUrl(url)?.let { (malId, aniListId) ->
            hasAddedFromUrl.add(url)

            setMalId(malId)
            setAniListId(aniListId)
            updateSynced()
            if (malId != null || aniListId != null) {
                Log.i(TAG, "addFromUrl->updateMetaAndUser $malId $aniListId")
                updateMetaAndUser()
            }
        }
    }

    fun setEpisodesDelta(delta: Int) {
        Log.i(TAG, "setEpisodesDelta = $delta")

        val user = userData.value
        if (user is Resource.Success) {
            user.value.watchedEpisodes?.plus(
                delta
            )?.let { episode ->
                setEpisodes(episode)
            }
        }
    }

    fun setEpisodes(episodes: Int) {
        Log.i(TAG, "setEpisodes = $episodes")

        if (episodes < 0) return
        val meta = metadata.value
        if (meta is Resource.Success) {
            meta.value.totalEpisodes?.let { max ->
                if (episodes > max) {
                    setEpisodes(max)
                    return
                }
            }
        }

        val user = userData.value
        if (user is Resource.Success) {
            user.value.watchedEpisodes = episodes
            _userDataResponse.value = Resource.Success(user.value)
        }
    }

    fun setScore(score: Score?) {
        Log.i(TAG, "setScore = $score")
        val user = userData.value
        if (user is Resource.Success) {
            user.value.score = score
            _userDataResponse.value = Resource.Success(user.value)
        }
    }

    fun setStatus(which: Int) {
        Log.i(TAG, "setStatus = $which")
        if (which < -1 || which > 5) return // validate input
        val user = userData.value
        if (user is Resource.Success) {
            user.value.status = SyncWatchType.fromInternalId(which)
            _userDataResponse.value = Resource.Success(user.value)
        }
    }

    fun publishUserData() = viewModelScope.launch(ioDispatcher) {
        Log.i(TAG, "publishUserData")
        val user = userData.value
        _userDataResponse.value = Resource.Loading()
        if (user is Resource.Success) {
            syncs.forEach { (prefix, id) ->
                repos.firstOrNull { it.idPrefix == prefix }?.updateStatus(id, user.value)
            }
        }
        updateUserData()
    }

    /**
     * Modifies the maximum watched episode and dispatches scrobble updates to all active sync providers.
     * Invoked when playback progress reaches 80% (UPDATE_SYNC_PROGRESS_PERCENTAGE).
     * Guards against null or NONE watchStatus to prevent wiping out external sync status on MAL/AniList.
     */
    fun modifyMaxEpisode(episodeNum: Int) {
        Log.i(TAG, "modifyMaxEpisode = $episodeNum")
        if (episodeNum < 0) return
        modifyData { status ->
            status.watchedEpisodes = maxOf(
                episodeNum,
                status.watchedEpisodes ?: 0
            )
            // Guard null or NONE watchStatus to prevent wiping external sync status on MAL/AniList
            val currentWatchStatus = (status.status as SyncWatchType?)
            if (currentWatchStatus == null || currentWatchStatus == SyncWatchType.NONE) {
                status.status = SyncWatchType.WATCHING
            }
            status
        }
    }

    /// modifies the current sync data, return null if you don't want to change it
    private fun modifyData(update: ((SyncAPI.AbstractSyncStatus) -> (SyncAPI.AbstractSyncStatus?))) =
        viewModelScope.launch(ioDispatcher) {
            syncs.amap { (prefix, id) ->
                repos.firstOrNull { it.idPrefix == prefix }?.let { repo ->
                    val currentStatus = repo.status(id).getOrNull() ?: SyncAPI.SyncStatus(
                        score = null,
                        status = SyncWatchType.WATCHING,
                        isFavorite = null,
                        watchedEpisodes = null
                    )
                    // Guard status from repository in case watchStatus is null or NONE
                    if ((currentStatus.status as SyncWatchType?) == null || currentStatus.status == SyncWatchType.NONE) {
                        currentStatus.status = SyncWatchType.WATCHING
                    }
                    val result = update(currentStatus) ?: return@let null
                    // Ensure mutated status has a valid watchStatus fallback before dispatching to provider
                    if ((result.status as SyncWatchType?) == null || result.status == SyncWatchType.NONE) {
                        result.status = SyncWatchType.WATCHING
                    }
                    Log.i(TAG, "modifyData ${repo.name} => $result")
                    repo.updateStatus(id, result)
                }
            }
        }

    fun updateUserData() = viewModelScope.launch(ioDispatcher) {
        Log.i(TAG, "updateUserData")
        _userDataResponse.value = Resource.Loading()

        val status = syncs.firstNotNullOfOrNull { (prefix, id) ->
            repos.firstOrNull { it.idPrefix == prefix }
                ?.status(id)?.getOrNull()
        }

        if (status == null) {
            _userDataResponse.value = Resource.Failure(false, "No data")
        } else {
            _userDataResponse.value = Resource.Success(status)
        }
    }

    private fun updateMetadata() = viewModelScope.launch(ioDispatcher) {
        Log.i(TAG, "updateMetadata")

        _metaResponse.value = Resource.Loading()
        var lastError: Resource<SyncAPI.SyncResult> = Resource.Failure(false, "No data")
        val current = ArrayList(syncs.toList())

        // Sort anilist first, as it has trailers while mal does not
        if (syncs.containsKey(aniListApi.idPrefix)) {
            try {
                Collections.swap(
                    current,
                    current.indexOfFirst { it.first == aniListApi.idPrefix },
                    0
                )
            } catch (t: Throwable) {
                logError(t)
            }
        }

        current.forEach { (prefix, id) ->
            repos.firstOrNull { it.idPrefix == prefix }?.let { repo ->
                Log.i(TAG, "updateMetadata loading ${repo.idPrefix}")
                val result = repo.load(id)
                val resultValue = result.getOrNull()
                val resultError = result.exceptionOrNull()
                if (resultValue != null) {
                    _metaResponse.value = Resource.Success(resultValue)
                    return@launch
                } else if (resultError != null) {
                    lastError = throwAbleToResource(resultError)
                }
            }
        }
        _metaResponse.value = lastError
        setEpisodesDelta(0)
    }

    fun syncName(syncName: String): String? {
        val realName = when (syncName) {
            "MAL" -> malApi.idPrefix
            "Kitsu" -> kitsuApi.idPrefix
            "Simkl" -> simklApi.idPrefix
            "AniList" -> aniListApi.idPrefix
            else -> syncName
        }
        return repos.firstOrNull { it.idPrefix == realName }?.idPrefix
    }

    fun setSync(syncName: String, syncId: String) {
        syncs.clear()
        syncs[syncName] = syncId
    }

    fun clear() {
        syncs.clear()
        _metaResponse.value = null
        _currentSynced.value = getMissing()
        _userDataResponse.value = null
    }

    fun updateMetaAndUser() {
        _userDataResponse.value = Resource.Loading()
        _metaResponse.value = Resource.Loading()

        Log.i(TAG, "updateMetaAndUser")
        updateMetadata()
        updateUserData()
    }
}
