package com.lagradost.cloudstream3.desktop.ui.screens.search

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isMovieType

/**
 * Standard Sort Options matching upstream and desktop search preferences.
 */
enum class SearchSortBy(val label: String) {
    RELEVANCE("Relevance (Default)"),
    RATING_DESC("Highest Rating (★)"),
    YEAR_DESC("Newest Release"),
    NAME_ASC("Title (A → Z)"),
    NAME_DESC("Title (Z → A)")
}

/**
 * Audio / Dubbing filter criteria.
 */
enum class DubStatusFilter(val label: String) {
    ALL("All Audio"),
    DUBBED_ONLY("Dubbed Only (DUB)"),
    SUBBED_ONLY("Subbed Only (SUB)")
}

/**
 * Immutable State for Search Filters & Preferences.
 */
data class SearchFilterState(
    val selectedTypes: Set<TvType> = emptySet(),
    val selectedGenres: Set<String> = emptySet(),
    val selectedYear: String? = null,
    val selectedProviders: Set<String> = emptySet(),
    val sortBy: SearchSortBy = SearchSortBy.RELEVANCE,
    val dubStatusFilter: DubStatusFilter = DubStatusFilter.ALL
) {
    val activeFilterCount: Int
        get() {
            var count = 0
            if (selectedTypes.isNotEmpty()) count += selectedTypes.size
            if (selectedGenres.isNotEmpty()) count += selectedGenres.size
            if (!selectedYear.isNullOrBlank() && selectedYear != "All") count++
            if (selectedProviders.isNotEmpty()) count += selectedProviders.size
            if (sortBy != SearchSortBy.RELEVANCE) count++
            if (dubStatusFilter != DubStatusFilter.ALL) count++
            return count
        }

    val hasActiveFilters: Boolean
        get() = activeFilterCount > 0
}

val GENRE_OPTIONS = listOf(
    "Action",
    "Adventure",
    "Animation",
    "Comedy",
    "Crime",
    "Documentary",
    "Drama",
    "Family",
    "Fantasy",
    "History",
    "Horror",
    "Music",
    "Mystery",
    "Romance",
    "Sci-Fi",
    "Thriller",
    "War",
    "Western"
)

val YEAR_OPTIONS = listOf(
    "All",
    "2025",
    "2024",
    "2023",
    "2022",
    "2021",
    "2020",
    "2010s",
    "2000s",
    "1990s",
    "Older"
)

/**
 * Extracts year from SearchResponse via AnimeSearchResponse property or regex matching in title.
 */
fun SearchResponse.extractYear(): Int? {
    if (this is AnimeSearchResponse && this.year != null) {
        return this.year
    }
    val match = "\\b(19\\d\\d|20\\d\\d)\\b".toRegex().find(this.name)
    return match?.value?.toIntOrNull()
}

/**
 * Extracts score from SearchResponse as a float out of 10.
 */
fun SearchResponse.extractScore(): Float? {
    return this.score?.toFloat(10)
}

/**
 * Extracts Dub/Sub badge label if available.
 */
fun SearchResponse.extractDubSubBadge(): String? {
    if (this is AnimeSearchResponse) {
        val dubs = this.dubStatus
        if (!dubs.isNullOrEmpty()) {
            val hasDub = dubs.contains(DubStatus.Dubbed)
            val hasSub = dubs.contains(DubStatus.Subbed)
            return when {
                hasDub && hasSub -> "DUB • SUB"
                hasDub -> "DUB"
                hasSub -> "SUB"
                else -> null
            }
        }
    }
    return null
}

/**
 * Applies all active [SearchFilterState] criteria to a list of [SearchResponse].
 */
fun List<SearchResponse>.applySearchFilters(filterState: SearchFilterState): List<SearchResponse> {
    var result = this

    // 1. TvType filter
    if (filterState.selectedTypes.isNotEmpty()) {
        val types = filterState.selectedTypes
        result = result.filter { item ->
            val itemType = item.type
            itemType != null && (
                itemType in types ||
                (types.contains(TvType.TvSeries) && (itemType == TvType.TvSeries || itemType == TvType.AsianDrama || itemType == TvType.Anime || itemType == TvType.Cartoon)) ||
                (types.contains(TvType.Movie) && itemType.isMovieType())
            )
        }
    }

    // 2. Provider filter
    if (filterState.selectedProviders.isNotEmpty()) {
        result = result.filter { item ->
            item.apiName in filterState.selectedProviders
        }
    }

    // 3. Dub / Sub audio filter
    if (filterState.dubStatusFilter != DubStatusFilter.ALL) {
        result = result.filter { item ->
            if (item is AnimeSearchResponse) {
                when (filterState.dubStatusFilter) {
                    DubStatusFilter.DUBBED_ONLY -> item.dubStatus?.contains(DubStatus.Dubbed) == true
                    DubStatusFilter.SUBBED_ONLY -> item.dubStatus?.contains(DubStatus.Subbed) == true
                    DubStatusFilter.ALL -> true
                }
            } else {
                true
            }
        }
    }

    // 4. Year filter
    if (!filterState.selectedYear.isNullOrBlank() && filterState.selectedYear != "All") {
        result = result.filter { item ->
            val yr = item.extractYear() ?: return@filter true
            when (filterState.selectedYear) {
                "2025" -> yr == 2025
                "2024" -> yr == 2024
                "2023" -> yr == 2023
                "2022" -> yr == 2022
                "2021" -> yr == 2021
                "2020" -> yr == 2020
                "2010s" -> yr in 2010..2019
                "2000s" -> yr in 2000..2009
                "1990s" -> yr in 1990..1999
                "Older" -> yr < 1990
                else -> true
            }
        }
    }

    // 5. Genre filter (matching keywords in title/name)
    if (filterState.selectedGenres.isNotEmpty()) {
        result = result.filter { item ->
            val nameLower = item.name.lowercase()
            filterState.selectedGenres.any { genre ->
                nameLower.contains(genre.lowercase())
            }
        }
    }

    // 6. Sorting
    result = when (filterState.sortBy) {
        SearchSortBy.RELEVANCE -> result
        SearchSortBy.RATING_DESC -> result.sortedByDescending { it.extractScore() ?: 0f }
        SearchSortBy.YEAR_DESC -> result.sortedByDescending { it.extractYear() ?: 0 }
        SearchSortBy.NAME_ASC -> result.sortedBy { it.name.lowercase() }
        SearchSortBy.NAME_DESC -> result.sortedByDescending { it.name.lowercase() }
    }

    return result
}
