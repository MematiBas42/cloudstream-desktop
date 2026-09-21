// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SyncSearchViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.DesktopViewModel
import kotlinx.coroutines.CoroutineDispatcher

class SyncSearchViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    data class SyncSearchResultSearchResponse(
        override val name: String,
        override val url: String,
        override val apiName: String,
        override var type: TvType?,
        override var posterUrl: String?,
        override var id: Int?,
        override var quality: SearchQuality? = null,
        override var posterHeaders: Map<String, String>? = null,
        override var score: Score? = null,
    ) : SearchResponse {
        fun toSyncSearchResult(syncId: String = ""): SyncAPI.SyncSearchResult {
            return SyncAPI.SyncSearchResult(
                name = name,
                apiName = apiName,
                syncId = syncId,
                url = url,
                posterUrl = posterUrl,
                type = type,
                quality = quality,
                posterHeaders = posterHeaders,
                id = id,
                score = score
            )
        }

        companion object {
            fun fromSyncSearchResult(result: SyncAPI.SyncSearchResult): SyncSearchResultSearchResponse {
                return SyncSearchResultSearchResponse(
                    name = result.name,
                    url = result.url,
                    apiName = result.apiName,
                    type = result.type,
                    posterUrl = result.posterUrl,
                    id = result.id,
                    quality = result.quality,
                    posterHeaders = result.posterHeaders,
                    score = result.score
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}

fun SyncAPI.SyncSearchResult.toSearchResponse(): SyncSearchViewModel.SyncSearchResultSearchResponse {
    return SyncSearchViewModel.SyncSearchResultSearchResponse.fromSyncSearchResult(this)
}
