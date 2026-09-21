// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/quicksearch/QuickSearchFragment.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.quicksearch

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.Event
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@PlatformQuarantine(
    reason = "Android Fragment/XML ViewBinding replaced by Compose Desktop SearchScreen; headless search routing preserved",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/quicksearch/QuickSearchFragment.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
open class QuickSearchFragment {
    companion object {
        const val AUTOSEARCH_KEY = "autosearch"
        const val PROVIDER_KEY = "providers"

        val quickSearchEvent = Event<Pair<String?, Array<String>?>>()

        private val _quickSearchFlow = MutableSharedFlow<Pair<String?, Array<String>?>>(
            replay = 1,
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val quickSearchFlow: SharedFlow<Pair<String?, Array<String>?>> = _quickSearchFlow.asSharedFlow()

        var clickCallback: ((SearchClickCallback) -> Unit)? = null

        fun reset() {
            clickCallback = null
            _quickSearchFlow.resetReplayCache()
        }

        /**
         * Sanitizes search query string according to upstream parity rules:
         * Trims whitespace and strips (DUB)/(SUB) variants to ensure clean matching
         * for providers and canonical metadata resolution (e.g. Kitsu).
         */
        fun sanitizeQuery(query: String?): String? {
            if (query == null) return null
            return query.trim()
                .removeSuffix("(DUB)")
                .removeSuffix("(SUB)")
                .removeSuffix("(Dub)")
                .removeSuffix("(Sub)")
                .replace(Regex("""\s*\((?:dub|sub)\)\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        fun pushSearch(
            autoSearch: String? = null,
            providers: Array<String>? = null
        ) {
            val sanitized = sanitizeQuery(autoSearch)
            val payload = sanitized to providers
            quickSearchEvent.invoke(payload)
            _quickSearchFlow.tryEmit(payload)
        }

        fun pushSearch(
            activity: Activity?,
            autoSearch: String? = null,
            providers: Array<String>? = null
        ) {
            pushSearch(autoSearch, providers)
        }

        /**
         * Upstream parity helper for search bar query hint.
         * Formats site hint when a single provider is selected, matching R.string.search_hint_site.
         */
        fun getQueryHint(firstProvider: String?): String {
            return if (firstProvider != null) {
                "Search $firstProvider..."
            } else {
                "Search movies, series, anime, live... "
            }
        }
    }

    var providers: Set<String>? = null
    var searchViewModel: SearchViewModel? = null

    val isSingleProvider: Boolean get() = providers?.size == 1
    val firstProvider: String? get() = providers?.firstOrNull()
    val isSingleProviderQuickSearch: Boolean
        get() = if (isSingleProvider) {
            getApiFromNameNull(firstProvider)?.hasQuickSearch ?: false
        } else false

    open fun onDestroy() {
        clickCallback = null
    }

    open fun search(context: Context?, query: String, isQuickSearch: Boolean): Boolean {
        val vm = searchViewModel ?: return false
        val active = providers ?: context?.filterProviderByPreferredMedia(hasHomePageIsRequired = false)
            ?.map { it.name }?.toSet() ?: return false

        vm.searchAndCancel(
            query = query,
            ignoreSettings = false,
            providersActive = active,
            isQuickSearch = isQuickSearch
        )
        return true
    }

    open fun onQueryTextChange(newText: String, context: Context? = null): Boolean {
        if (isSingleProviderQuickSearch) {
            search(context, newText, isQuickSearch = true)
        }
        return true
    }

    open fun onQueryTextSubmit(query: String, context: Context? = null): Boolean {
        return search(context, query, isQuickSearch = false)
    }

    open fun handleAutoSearch(autoSearch: String?, context: Context? = null) {
        val sanitized = sanitizeQuery(autoSearch)
        if (!sanitized.isNullOrBlank()) {
            onQueryTextSubmit(sanitized, context)
        }
    }

    open fun initFromBundle(providersList: Array<String>?, autoSearch: String? = null, context: Context? = null) {
        providers = providersList?.toSet()
        handleAutoSearch(autoSearch, context)
    }
}
