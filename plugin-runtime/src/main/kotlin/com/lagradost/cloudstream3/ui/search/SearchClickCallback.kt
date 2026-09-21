// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchAdaptor.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.search

import com.lagradost.cloudstream3.SearchResponse

const val SEARCH_ACTION_LOAD = 0
const val SEARCH_ACTION_SHOW_METADATA = 1
const val SEARCH_ACTION_PLAY_FILE = 2
const val SEARCH_ACTION_SEARCH_TAG = 3
const val SEARCH_ACTION_FOCUSED = 4

class SearchClickCallback(
    val action: Int,
    val view: Any? = null,
    val position: Int = -1,
    val card: SearchResponse
)
