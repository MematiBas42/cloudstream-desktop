package com.lagradost.cloudstream3.desktop.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.storage.WatchHistory

/**
 * Top-level application destinations and dynamic parameterized screens.
 */
sealed class Screen {
    object Home : Screen()
    data class Search(
        val initialQuery: String? = null,
        val providers: Set<String>? = null,
    ) : Screen()
    object Extensions : Screen()
    object Library : Screen()
    object Downloads : Screen()
    object IPTV : Screen()
    object Settings : Screen()
    data class Details(
        val provider: MainAPI,
        val url: String,
        val preloadedName: String? = null,
        val preloadedPoster: String? = null,
        val preloadedBg: String? = null,
    ) : Screen()
    data class CategoryGrid(
        val provider: MainAPI,
        val title: String,
        val items: List<SearchResponse>,
    ) : Screen()
}

/**
 * Simple stack-based navigation controller for Compose Desktop screens.
 * Integrates with [SearchHelper.searchClickEvent] for unified, decoupled search navigation.
 */
class NavController {
    var currentScreen: Screen by mutableStateOf(Screen.Home)
        private set

    private val backStack = mutableListOf<Screen>()

    private val searchListener: (SearchClickCallback) -> Unit = { callback ->
        if (callback.action == SEARCH_ACTION_LOAD || callback.action == SEARCH_ACTION_SHOW_METADATA) {
            val card = callback.card
            val provider = APIHolder.getApiFromNameNull(card.apiName)
                ?: APIHolder.getApiFromUrlNull(card.url)
                ?: APIHolder.apis.firstOrNull { it.name == card.apiName }
                ?: APIHolder.allProviders.firstOrNull { it.name == card.apiName }

            if (provider != null) {
                val detailsScreen = Screen.Details(
                    provider = provider,
                    url = card.url,
                    preloadedName = card.name,
                    preloadedPoster = card.posterUrl,
                    preloadedBg = null
                )
                if (java.awt.EventQueue.isDispatchThread()) {
                    navigate(detailsScreen)
                } else {
                    java.awt.EventQueue.invokeLater {
                        navigate(detailsScreen)
                    }
                }
            }
        }
    }

    private val searchIntentListener: (String?) -> Unit = { query ->
        val searchScreen = Screen.Search(initialQuery = query)
        if (java.awt.EventQueue.isDispatchThread()) {
            navigate(searchScreen)
        } else {
            java.awt.EventQueue.invokeLater {
                navigate(searchScreen)
            }
        }
    }

    private val downloadsIntentListener: (Boolean) -> Unit = { _ ->
        if (java.awt.EventQueue.isDispatchThread()) {
            navigate(Screen.Downloads)
        } else {
            java.awt.EventQueue.invokeLater {
                navigate(Screen.Downloads)
            }
        }
    }

    private val loadResultIntentListener: (Triple<String, String, String>) -> Unit = { (url, apiName, _) ->
        val provider = APIHolder.getApiFromNameNull(apiName)
            ?: APIHolder.getApiFromUrlNull(url)
            ?: APIHolder.apis.firstOrNull { it.name == apiName }
            ?: APIHolder.allProviders.firstOrNull { it.name == apiName }

        if (provider != null) {
            val detailsScreen = Screen.Details(
                provider = provider,
                url = url
            )
            if (java.awt.EventQueue.isDispatchThread()) {
                navigate(detailsScreen)
            } else {
                java.awt.EventQueue.invokeLater {
                    navigate(detailsScreen)
                }
            }
        }
    }

    private val quickSearchListener: (Pair<String?, Array<String>?>) -> Unit = { (query, providers) ->
        val searchScreen = Screen.Search(
            initialQuery = query,
            providers = providers?.toSet()
        )
        if (java.awt.EventQueue.isDispatchThread()) {
            navigate(searchScreen)
        } else {
            java.awt.EventQueue.invokeLater {
                navigate(searchScreen)
            }
        }
    }

    private val resumeWatchingIntentListener: (Int) -> Unit = { id ->
        ioSafe {
            val card = com.lagradost.cloudstream3.ui.home.HomeViewModel.getResumeWatching()?.firstOrNull { it.id == id }
            if (card != null) {
                val provider = APIHolder.getApiFromNameNull(card.apiName)
                    ?: APIHolder.getApiFromUrlNull(card.url)
                    ?: APIHolder.apis.firstOrNull { it.name == card.apiName }
                    ?: APIHolder.allProviders.firstOrNull { it.name == card.apiName }

                if (provider != null) {
                    val detailsScreen = Screen.Details(
                        provider = provider,
                        url = card.url,
                        preloadedName = card.name,
                        preloadedPoster = card.posterUrl,
                        preloadedBg = null
                    )
                    if (java.awt.EventQueue.isDispatchThread()) {
                        navigate(detailsScreen)
                    } else {
                        java.awt.EventQueue.invokeLater {
                            navigate(detailsScreen)
                        }
                    }
                }
            }
        }
    }

    private val backPressedListener: (Unit) -> Unit = {
        if (java.awt.EventQueue.isDispatchThread()) {
            if (canGoBack()) goBack()
        } else {
            java.awt.EventQueue.invokeLater {
                if (canGoBack()) goBack()
            }
        }
    }

    init {
        SearchHelper.searchClickEvent += searchListener
        MainActivity.searchIntentEvent += searchIntentListener
        MainActivity.navigateDownloadsEvent += downloadsIntentListener
        MainActivity.loadResultIntentEvent += loadResultIntentListener
        MainActivity.resumeWatchingIntentEvent += resumeWatchingIntentListener
        MainActivity.backPressedEvent += backPressedListener
        QuickSearchFragment.quickSearchEvent += quickSearchListener
    }

    fun destroy() {
        SearchHelper.searchClickEvent -= searchListener
        MainActivity.searchIntentEvent -= searchIntentListener
        MainActivity.navigateDownloadsEvent -= downloadsIntentListener
        MainActivity.loadResultIntentEvent -= loadResultIntentListener
        MainActivity.resumeWatchingIntentEvent -= resumeWatchingIntentListener
        MainActivity.backPressedEvent -= backPressedListener
        QuickSearchFragment.quickSearchEvent -= quickSearchListener
    }

    fun navigate(screen: Screen) {
        if (screen == currentScreen) return
        backStack.add(currentScreen)
        currentScreen = screen
    }

    fun goBack() {
        currentScreen = backStack.removeLastOrNull() ?: Screen.Home
    }

    fun canGoBack(): Boolean = backStack.isNotEmpty()
}

val LocalVideoPlayer = staticCompositionLocalOf<(VideoLaunchData?) -> Unit> { { } }
