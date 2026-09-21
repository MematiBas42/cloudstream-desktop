// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/search/SearchHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.search

import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

object SearchHelper {
    val searchClickEvent = Event<SearchClickCallback>()
    val downloadClickEvent = Event<DownloadClickEvent>()

    fun handleSearchClickCallback(callback: SearchClickCallback) {
        val card = callback.card
        searchClickEvent.invoke(callback)
        when (callback.action) {
            SEARCH_ACTION_LOAD -> {
                loadSearchResult(card)
            }

            SEARCH_ACTION_PLAY_FILE -> {
                if (card is DataStoreHelper.ResumeWatchingResult) {
                    val id = card.id
                    if (id == null) {
                        showToast(R.string.error_invalid_id, Toast.LENGTH_SHORT)
                    } else {
                        if (card.isFromDownload) {
                            downloadClickEvent.invoke(
                                DownloadClickEvent(
                                    DOWNLOAD_ACTION_PLAY_FILE,
                                    DownloadObjects.DownloadEpisodeCached(
                                        name = card.name,
                                        poster = card.posterUrl,
                                        episode = card.episode ?: 0,
                                        season = card.season,
                                        id = id,
                                        parentId = card.parentId ?: return,
                                        score = null,
                                        description = null,
                                        cacheTime = System.currentTimeMillis(),
                                    )
                                )
                            )
                            loadSearchResult(card, START_ACTION_LOAD_EP, id)
                        } else {
                            loadSearchResult(card, START_ACTION_LOAD_EP, id)
                        }
                    }
                } else {
                    handleSearchClickCallback(
                        SearchClickCallback(
                            action = SEARCH_ACTION_LOAD,
                            view = callback.view,
                            position = -1,
                            card = callback.card
                        )
                    )
                }
            }

            SEARCH_ACTION_SHOW_METADATA -> {
                (activity as? MainActivity?)?.let {
                    showToast(callback.card.name, Toast.LENGTH_SHORT)
                } ?: kotlin.run {
                    showToast(callback.card.name, Toast.LENGTH_SHORT)
                }
            }
        }
    }
}
