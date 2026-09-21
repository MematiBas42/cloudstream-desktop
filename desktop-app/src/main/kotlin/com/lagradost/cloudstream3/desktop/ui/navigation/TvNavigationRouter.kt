package com.lagradost.cloudstream3.desktop.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.components.TvRailDestination

typealias TvRailDestination = com.lagradost.cloudstream3.desktop.ui.components.TvRailDestination

/**
 * Enhanced typed destinations supporting deep state serialization and focus restoration anchors.
 * Traced 1:1 to Domain 01 specification (docs/specs/01-tv-navigation-shell.md:983-1001).
 */
sealed class TvRoute {
    object Home : TvRoute()
    data class Search(val initialQuery: String? = null) : TvRoute()
    object Library : TvRoute()
    object Downloads : TvRoute()
    object Settings : TvRoute()
    data class Details(
        val provider: MainAPI,
        val url: String,
        val preloadedName: String? = null,
        val preloadedPoster: String? = null,
        val preloadedBg: String? = null
    ) : TvRoute()
    data class CategoryGrid(
        val provider: MainAPI,
        val title: String,
        val items: List<SearchResponse>
    ) : TvRoute()
}

/**
 * Historical backstack entry preserving active destination and focus anchor ID.
 */
data class BackstackRecord(
    val route: TvRoute,
    val savedFocusKey: String? = null
)

/**
 * Production-grade TV Navigation Router with focus anchor restoration.
 */
@Stable
class TvNavigationRouter(
    initialRoute: TvRoute = TvRoute.Home
) {
    var currentRoute by mutableStateOf(initialRoute)
        private set

    var activeRailDestination by mutableStateOf(TvRailDestination.HOME)
        private set

    private val backstack = mutableStateListOf<BackstackRecord>()

    fun navigate(newRoute: TvRoute, currentFocusKey: String? = null) {
        if (newRoute == currentRoute) return
        backstack.add(BackstackRecord(currentRoute, currentFocusKey))
        currentRoute = newRoute
        syncRailDestination(newRoute)
    }

    fun popBackstack(): String? {
        val previousRecord = backstack.removeLastOrNull() ?: return null
        currentRoute = previousRecord.route
        syncRailDestination(previousRecord.route)
        return previousRecord.savedFocusKey
    }

    fun canGoBack(): Boolean = backstack.isNotEmpty()

    private fun syncRailDestination(route: TvRoute) {
        when (route) {
            is TvRoute.Home -> activeRailDestination = TvRailDestination.HOME
            is TvRoute.Search -> activeRailDestination = TvRailDestination.SEARCH
            is TvRoute.Library, is TvRoute.Downloads -> activeRailDestination = TvRailDestination.LIBRARY
            is TvRoute.Settings -> activeRailDestination = TvRailDestination.SETTINGS
            else -> Unit // Sub-screens keep the parent rail destination highlighted
        }
    }
}
