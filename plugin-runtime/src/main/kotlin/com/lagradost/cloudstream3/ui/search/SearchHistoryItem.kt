// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchHistoryAdaptor.kt", upstreamCommit = "9feeaedee64b3efc150a3f05df18f955abe170d0")
package com.lagradost.cloudstream3.ui.search

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchHistoryItem(
    @JsonProperty("searchedAt") @SerialName("searchedAt") val searchedAt: Long,
    @JsonProperty("searchText") @SerialName("searchText") val searchText: String,
    @JsonProperty("type") @SerialName("type") val type: List<TvType>,
    @JsonProperty("key") @SerialName("key") val key: String,
)

data class SearchHistoryCallback(
    val item: SearchHistoryItem?,
    val clickAction: Int,
)

const val SEARCH_HISTORY_OPEN = 0
const val SEARCH_HISTORY_REMOVE = 1
const val SEARCH_HISTORY_CLEAR = 2
