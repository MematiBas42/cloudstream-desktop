package com.lagradost.cloudstream3.desktop.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.RegisterTvFocusItem
import com.lagradost.cloudstream3.desktop.ui.components.desktopMouseSwipeable
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.rememberCloseableViewModel
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SearchHistoryItem
import com.lagradost.cloudstream3.ui.search.SearchViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.launch

enum class SearchViewMode {
    INTERLEAVED,
    GROUPED_BY_PROVIDER
}

private data class TvTypeChipDefinition(
    val label: String,
    val type: TvType?
)

private val TV_TYPE_CHIPS = listOf(
    TvTypeChipDefinition("All", null),
    TvTypeChipDefinition("Movies", TvType.Movie),
    TvTypeChipDefinition("TV Series", TvType.TvSeries),
    TvTypeChipDefinition("Anime", TvType.Anime),
    TvTypeChipDefinition("Asian Drama", TvType.AsianDrama),
    TvTypeChipDefinition("Cartoons", TvType.Cartoon),
    TvTypeChipDefinition("Documentaries", TvType.Documentary),
    TvTypeChipDefinition("Live Streams", TvType.Live)
)

/**
 * Desktop Search Screen directly connected to canonical SearchViewModel.
 *
 * Full Feature Parity & Ergonomics:
 * - Direct keyboard shortcut: press '/' to focus search from anywhere on screen
 * - Escape key to clear search query or dismiss dialogs / go back
 * - 300ms debounced TMDB autocomplete suggestions via [SearchViewModel.fetchSuggestions]
 * - Persistent search history with single-item deletion, right-click removal, and clear-all
 * - Advanced Filters Dialog (TvTypes, Genres, Release Years, Providers with language flags, Sort order, Dub/Sub audio)
 * - Persistent search preferences via [DataStoreHelper.searchPreferenceProviders] and [DataStoreHelper.searchPreferenceTags]
 * - Round-robin interleaved search results via [SearchViewModel.searchResponse]
 * - Grouped provider search results via [SearchViewModel.currentSearch] with horizontal pagination
 * - Fluid hover scaling, multi-badge status tags, and right-click desktop context menu
 * - Navigation to Screen.Details via [SearchHelper.handleSearchClickCallback]
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposeSearchScreen(
    navController: NavController,
    initialQuery: String? = null,
    providers: Set<String>? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel = rememberCloseableViewModel("canonical_search_view_model") { SearchViewModel() }

    // StateFlows from canonical SearchViewModel
    val searchResponse by viewModel.searchResponse.collectAsState()
    val currentSearch by viewModel.currentSearch.collectAsState()
    val currentHistory by viewModel.currentHistory.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()

    var query by remember { mutableStateOf(initialQuery ?: "") }
    var viewMode by remember { mutableStateOf(SearchViewMode.GROUPED_BY_PROVIDER) }
    var isFilterDialogOpen by remember { mutableStateOf(false) }

    // Initialize filter state from persistent DataStoreHelper and caller params
    var filterState by remember {
        val savedTypes = DataStoreHelper.searchPreferenceTags.toSet()
        val savedProviders = providers ?: DataStoreHelper.searchPreferenceProviders.toSet()
        mutableStateOf(
            SearchFilterState(
                selectedTypes = savedTypes,
                selectedProviders = savedProviders
            )
        )
    }

    val searchInputFocusRequester = remember { FocusRequester() }
    var isSearchInputFocused by remember { mutableStateOf(false) }

    // Initial load / parameter change: update history and run search if initialQuery present
    LaunchedEffect(initialQuery, providers) {
        viewModel.updateHistory()
        if (!initialQuery.isNullOrBlank()) {
            query = initialQuery
            val activeProviders = providers ?: filterState.selectedProviders
            if (providers != null) {
                filterState = filterState.copy(selectedProviders = providers)
            }
            viewModel.searchAndCancel(
                query = initialQuery,
                providersActive = activeProviders
            )
        } else {
            try {
                searchInputFocusRequester.requestFocus()
            } catch (e: Throwable) {
                // ignore initial focus error
            }
        }
    }

    // Direct event listener for QuickSearchFragment autocomplete / instant search events
    DisposableEffect(Unit) {
        val quickSearchListener: (Pair<String?, Array<String>?>) -> Unit = { (incomingQuery, incomingProviders) ->
            if (!incomingQuery.isNullOrBlank()) {
                query = incomingQuery
                val activeProviders = incomingProviders?.toSet() ?: filterState.selectedProviders
                if (incomingProviders != null) {
                    filterState = filterState.copy(selectedProviders = activeProviders)
                }
                viewModel.clearSuggestions()
                viewModel.searchAndCancel(
                    query = incomingQuery,
                    providersActive = activeProviders
                )
            }
        }
        QuickSearchFragment.quickSearchEvent += quickSearchListener
        onDispose {
            QuickSearchFragment.quickSearchEvent -= quickSearchListener
        }
    }

    val isSearching = searchResponse is Resource.Loading

    // Interleaved items filtered and sorted by filterState
    val rawInterleavedList = (searchResponse as? Resource.Success)?.value?.list ?: emptyList()
    val filteredInterleavedResults = remember(rawInterleavedList, filterState) {
        rawInterleavedList.applySearchFilters(filterState)
    }

    // Global Key Interceptor: '/' to focus search, 'Esc' to clear query / go back
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.AmoledBackground)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Slash -> {
                            if (!isSearchInputFocused && !isFilterDialogOpen) {
                                searchInputFocusRequester.requestFocus()
                                true
                            } else {
                                false
                            }
                        }
                        Key.Escape -> {
                            if (isFilterDialogOpen) {
                                isFilterDialogOpen = false
                                true
                            } else if (query.isNotEmpty()) {
                                query = ""
                                viewModel.clearSuggestions()
                                viewModel.clearSearch()
                                viewModel.updateHistory()
                                true
                            } else if (navController.canGoBack()) {
                                navController.goBack()
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Row 1: Search Bar Header with Filters button and view mode toggles
            SearchBarHeader(
                query = query,
                isSearching = isSearching,
                hasActiveFilters = filterState.hasActiveFilters,
                activeFilterCount = filterState.activeFilterCount,
                firstProvider = filterState.selectedProviders.singleOrNull(),
                onQueryChange = { newQuery ->
                    query = newQuery
                    val singleProvider = filterState.selectedProviders.singleOrNull()
                    val api = singleProvider?.let { APIHolder.getApiFromNameNull(it) }
                    if (api?.hasQuickSearch == true && newQuery.length >= 2) {
                        viewModel.searchAndCancel(
                            query = newQuery,
                            providersActive = filterState.selectedProviders,
                            isQuickSearch = true
                        )
                    } else if (newQuery.length >= 2) {
                        viewModel.fetchSuggestions(newQuery)
                    } else {
                        viewModel.clearSuggestions()
                        if (newQuery.isBlank()) {
                            viewModel.clearSearch()
                            viewModel.updateHistory()
                        }
                    }
                },
                onSearch = {
                    if (query.isNotBlank()) {
                        viewModel.clearSuggestions()
                        viewModel.searchAndCancel(
                            query = query,
                            providersActive = filterState.selectedProviders
                        )
                    }
                },
                onClear = {
                    query = ""
                    viewModel.clearSuggestions()
                    viewModel.clearSearch()
                    viewModel.updateHistory()
                },
                onBack = { navController.goBack() },
                canGoBack = navController.canGoBack(),
                onOpenFilters = { isFilterDialogOpen = true },
                focusRequester = searchInputFocusRequester,
                onFocusChanged = { isSearchInputFocused = it }
            )

            // Row 2: TMDB Autocomplete Suggestions (Debounced 300ms)
            if (searchSuggestions.isNotEmpty()) {
                SearchSuggestionsRow(
                    suggestions = searchSuggestions,
                    onSuggestionClick = { suggestion ->
                        query = suggestion
                        viewModel.clearSuggestions()
                        viewModel.searchAndCancel(
                            query = suggestion,
                            providersActive = filterState.selectedProviders
                        )
                    }
                )
            }

            // Row 3: TvType Filter Chips & Quick View Mode Toggles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvTypeFilterChipsRow(
                    selectedTypes = filterState.selectedTypes,
                    onToggleType = { type ->
                        val newTypes = if (type == null) {
                            emptySet()
                        } else if (type in filterState.selectedTypes) {
                            filterState.selectedTypes - type
                        } else {
                            filterState.selectedTypes + type
                        }
                        filterState = filterState.copy(selectedTypes = newTypes)
                        DataStoreHelper.searchPreferenceTags = newTypes.toList()
                    },
                    modifier = Modifier.weight(1f, fill = false)
                )

                // View Mode & Quick Sort Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quick Filter Button
                    OutlinedButton(
                        onClick = { isFilterDialogOpen = true },
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                        border = BorderStroke(
                            1.dp,
                            if (filterState.hasActiveFilters) TvColors.FocusElectricBlue else TvColors.BorderSubtle
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (filterState.hasActiveFilters) TvColors.SurfaceElevated else Color.Transparent
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = "Filters",
                            tint = if (filterState.hasActiveFilters) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (filterState.hasActiveFilters) "Filters (${filterState.activeFilterCount})" else "Filters",
                            color = if (filterState.hasActiveFilters) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                            style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    // View Mode Toggle (Interleaved vs Grouped)
                    if (searchResponse is Resource.Success && rawInterleavedList.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier
                                .background(TvColors.SurfaceCard, RoundedCornerShape(TvDimensions.CornerRadiusPill))
                                .padding(2.dp)
                        ) {
                            IconButton(
                                onClick = { viewMode = SearchViewMode.INTERLEAVED },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Interleaved Grid",
                                    tint = if (viewMode == SearchViewMode.INTERLEAVED) TvColors.FocusElectricBlue else TvColors.TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { viewMode = SearchViewMode.GROUPED_BY_PROVIDER },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.List,
                                    contentDescription = "Grouped by Provider",
                                    tint = if (viewMode == SearchViewMode.GROUPED_BY_PROVIDER) TvColors.FocusElectricBlue else TvColors.TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Row 4: Active Filter Chips Bar (Visible when any non-type filter is active)
            if (filterState.hasActiveFilters) {
                ActiveFilterChipsBar(
                    filterState = filterState,
                    onRemoveGenre = { genre ->
                        filterState = filterState.copy(selectedGenres = filterState.selectedGenres - genre)
                    },
                    onRemoveYear = {
                        filterState = filterState.copy(selectedYear = null)
                    },
                    onRemoveProviders = {
                        filterState = filterState.copy(selectedProviders = emptySet())
                        DataStoreHelper.searchPreferenceProviders = emptyList()
                        if (query.isNotBlank()) {
                            viewModel.searchAndCancel(query, emptySet())
                        }
                    },
                    onResetSort = {
                        filterState = filterState.copy(sortBy = SearchSortBy.RELEVANCE)
                    },
                    onResetDub = {
                        filterState = filterState.copy(dubStatusFilter = DubStatusFilter.ALL)
                    },
                    onClearAll = {
                        filterState = SearchFilterState()
                        DataStoreHelper.searchPreferenceTags = emptyList()
                        DataStoreHelper.searchPreferenceProviders = emptyList()
                        if (query.isNotBlank()) {
                            viewModel.searchAndCancel(query, emptySet())
                        }
                    }
                )
            }

            // Main Content Area: History or Results
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    // 1. Initial loading state (only when no provider results have arrived yet)
                    isSearching && currentSearch.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = TvColors.FocusElectricBlue,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Searching across installed scrapers...",
                                    style = TvTypography.Section.copy(color = TvColors.TextSecondary)
                                )
                            }
                        }
                    }

                    // 2. Search Results (Grouped by Provider View - Asynchronous Live Progressive Rendering)
                    viewMode == SearchViewMode.GROUPED_BY_PROVIDER && currentSearch.isNotEmpty() -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (isSearching) {
                                LinearProgressIndicator(
                                    color = TvColors.FocusElectricBlue,
                                    trackColor = TvColors.SurfaceCard,
                                    modifier = Modifier.fillMaxWidth().height(3.dp)
                                )
                            }
                            SearchGroupedByProviderSection(
                                groupedSearches = currentSearch,
                                filterState = filterState,
                                onCardClick = { item ->
                                    SearchHelper.handleSearchClickCallback(
                                        SearchClickCallback(
                                            action = SEARCH_ACTION_LOAD,
                                            card = item
                                        )
                                    )
                                },
                                onExpandProvider = { providerName ->
                                    coroutineScope.launch {
                                        viewModel.expandAndReturn(providerName)
                                    }
                                }
                            )
                        }
                    }

                    // 3. Search Results Grid (Interleaved View)
                    viewMode == SearchViewMode.INTERLEAVED && filteredInterleavedResults.isNotEmpty() -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (isSearching) {
                                LinearProgressIndicator(
                                    color = TvColors.FocusElectricBlue,
                                    trackColor = TvColors.SurfaceCard,
                                    modifier = Modifier.fillMaxWidth().height(3.dp)
                                )
                            }
                            SearchResultsGrid(
                                results = filteredInterleavedResults,
                                onCardClick = { item ->
                                    SearchHelper.handleSearchClickCallback(
                                        SearchClickCallback(
                                            action = SEARCH_ACTION_LOAD,
                                            card = item
                                        )
                                    )
                                },
                                onSearchSimilar = { similarTitle ->
                                    query = similarTitle
                                    viewModel.searchAndCancel(
                                        query = similarTitle,
                                        providersActive = filterState.selectedProviders
                                    )
                                }
                            )
                        }
                    }

                    // 4. Search History (Visible when query is blank and no search active)
                    query.isBlank() && searchResponse == null -> {
                        SearchHistorySection(
                            history = currentHistory,
                            onSelect = { item ->
                                query = item.searchText
                                viewModel.clearSuggestions()
                                viewModel.searchAndCancel(
                                    query = item.searchText,
                                    providersActive = filterState.selectedProviders
                                )
                            },
                            onRemove = { item ->
                                viewModel.removeHistoryItem(item)
                            },
                            onClearAll = {
                                viewModel.clearHistory()
                            }
                        )
                    }

                    // 5. Empty Results Notice
                    !isSearching && query.isNotBlank() && (rawInterleavedList.isEmpty() || filteredInterleavedResults.isEmpty()) -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = TvColors.TextMuted,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    text = "No results found for '$query'",
                                    style = TvTypography.Headline.copy(fontSize = 20.sp, color = TvColors.TextPrimary)
                                )
                                if (filterState.hasActiveFilters) {
                                    Text(
                                        text = "Active filters may be hiding results. Try clearing some filters.",
                                        style = TvTypography.Body.copy(color = TvColors.TextSecondary)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Button(
                                        onClick = {
                                            filterState = SearchFilterState()
                                            DataStoreHelper.searchPreferenceTags = emptyList()
                                            DataStoreHelper.searchPreferenceProviders = emptyList()
                                            viewModel.searchAndCancel(query, emptySet())
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated)
                                    ) {
                                        Text("Reset All Filters", color = TvColors.FocusElectricBlue)
                                    }
                                } else {
                                    Text(
                                        text = "Try different keywords or check if scrapers for this content are enabled in Extensions.",
                                        style = TvTypography.Body.copy(color = TvColors.TextMuted)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Modal Search Filter Dialog
        if (isFilterDialogOpen) {
            SearchFilterDialog(
                initialState = filterState,
                onDismiss = { isFilterDialogOpen = false },
                onApply = { newFilters ->
                    val providersChanged = filterState.selectedProviders != newFilters.selectedProviders
                    filterState = newFilters
                    isFilterDialogOpen = false

                    if (providersChanged && query.isNotBlank()) {
                        viewModel.searchAndCancel(
                            query = query,
                            providersActive = newFilters.selectedProviders
                        )
                    }
                }
            )
        }
    }
}

/**
 * Backward-compatibility alias for ComposeSearchScreen.
 */
@Composable
fun ComposeTvSearchScreen(
    navController: NavController,
    initialQuery: String? = null,
    providers: Set<String>? = null,
    modifier: Modifier = Modifier
) = ComposeSearchScreen(navController, initialQuery, providers, modifier)

/**
 * Search Bar Header with search input, clear button, filters button, and spinner.
 */
@Composable
private fun SearchBarHeader(
    query: String,
    isSearching: Boolean,
    hasActiveFilters: Boolean,
    activeFilterCount: Int,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean,
    onOpenFilters: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    firstProvider: String? = null,
    modifier: Modifier = Modifier
) {
    var isInputFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (canGoBack) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp).focusable()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TvColors.TextPrimary
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(
                width = if (isInputFocused) 2.dp else 1.dp,
                color = if (isInputFocused) TvColors.FocusElectricBlue else TvColors.BorderSubtle
            ),
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isInputFocused = state.isFocused
                    onFocusChanged(state.isFocused)
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (isInputFocused) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(12.dp))

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TvTypography.Section.copy(color = TvColors.TextPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(TvColors.FocusElectricBlue),
                    modifier = Modifier.weight(1f).focusable(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        QuickSearchFragment.getQueryHint(firstProvider),
                                        style = TvTypography.Section.copy(color = TvColors.TextMuted, fontSize = 16.sp)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = TvColors.SurfaceCard,
                                        border = BorderStroke(1.dp, TvColors.BorderSubtle)
                                    ) {
                                        Text(
                                            text = "/",
                                            color = TvColors.TextMuted,
                                            style = TvTypography.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            innerTextField()
                        }
                    }
                )

                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = TvColors.FocusElectricBlue
                    )
                } else if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp).focusable()
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = TvColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Dedicated Filter Icon Button on Header
        IconButton(
            onClick = onOpenFilters,
            modifier = Modifier
                .size(48.dp)
                .background(
                    if (hasActiveFilters) TvColors.FocusElectricBlue.copy(alpha = 0.15f) else TvColors.SurfaceElevated,
                    RoundedCornerShape(TvDimensions.CornerRadiusPill)
                )
                .focusable()
        ) {
            BadgedBox(
                badge = {
                    if (hasActiveFilters) {
                        Badge(containerColor = TvColors.FocusElectricBlue) {
                            Text(activeFilterCount.toString())
                        }
                    }
                }
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Filters",
                    tint = if (hasActiveFilters) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Horizontal bar displaying currently active filter tags with individual dismiss buttons.
 */
@Composable
private fun ActiveFilterChipsBar(
    filterState: SearchFilterState,
    onRemoveGenre: (String) -> Unit,
    onRemoveYear: () -> Unit,
    onRemoveProviders: () -> Unit,
    onResetSort: () -> Unit,
    onResetDub: () -> Unit,
    onClearAll: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            Text(
                text = "Active Filters:",
                style = TvTypography.Caption.copy(color = TvColors.TextMuted, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // Active Genres
        items(filterState.selectedGenres.toList()) { genre ->
            ActiveFilterChip(
                label = "Genre: $genre",
                onRemove = { onRemoveGenre(genre) }
            )
        }

        // Active Year
        if (!filterState.selectedYear.isNullOrBlank() && filterState.selectedYear != "All") {
            item {
                ActiveFilterChip(
                    label = "Year: ${filterState.selectedYear}",
                    onRemove = onRemoveYear
                )
            }
        }

        // Active Providers
        if (filterState.selectedProviders.isNotEmpty()) {
            item {
                ActiveFilterChip(
                    label = "${filterState.selectedProviders.size} Providers",
                    onRemove = onRemoveProviders
                )
            }
        }

        // Active Sort
        if (filterState.sortBy != SearchSortBy.RELEVANCE) {
            item {
                ActiveFilterChip(
                    label = "Sort: ${filterState.sortBy.label}",
                    onRemove = onResetSort
                )
            }
        }

        // Active Dub Status
        if (filterState.dubStatusFilter != DubStatusFilter.ALL) {
            item {
                ActiveFilterChip(
                    label = filterState.dubStatusFilter.label,
                    onRemove = onResetDub
                )
            }
        }

        item {
            TextButton(
                onClick = onClearAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Clear All",
                    color = TvColors.ErrorRed,
                    style = TvTypography.Caption.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(
    label: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
        color = TvColors.SurfaceElevated,
        border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.6f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
        ) {
            Text(
                text = label,
                style = TvTypography.Caption.copy(color = TvColors.FocusElectricBlue, fontWeight = FontWeight.Medium)
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = TvColors.TextSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/**
 * 300ms Debounced TMDB Autocomplete Suggestions Row.
 */
@Composable
private fun SearchSuggestionsRow(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(suggestions) { _, suggestion ->
            val interaction = remember { MutableInteractionSource() }
            Surface(
                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                color = TvColors.SurfaceElevated,
                border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusPill))
                    .clickable(interactionSource = interaction, indication = null) {
                        onSuggestionClick(suggestion)
                    }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = TvColors.FocusElectricBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = suggestion,
                        style = TvTypography.Caption.copy(color = TvColors.TextPrimary),
                    )
                }
            }
        }
    }
}

/**
 * Filter chips row for media types (Movies, TV Series, Anime, etc.).
 */
@Composable
private fun TvTypeFilterChipsRow(
    selectedTypes: Set<TvType>,
    onToggleType: (TvType?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(TV_TYPE_CHIPS) { _, chip ->
            val isSelected = if (chip.type == null) {
                selectedTypes.isEmpty()
            } else {
                chip.type in selectedTypes
            }

            val requester = remember { FocusRequester() }
            var isFocused by remember { mutableStateOf(false) }

            val animatedScale by animateFloatAsState(
                targetValue = if (isFocused) 1.05f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "ChipScale"
            )

            val backgroundColor by animateColorAsState(
                targetValue = when {
                    isFocused -> TvColors.FocusElectricBlue
                    isSelected -> TvColors.SurfaceElevated
                    else -> TvColors.SurfaceCard
                },
                label = "ChipBg"
            )

            val shape = RoundedCornerShape(TvDimensions.CornerRadiusPill)
            val interaction = remember { MutableInteractionSource() }

            Surface(
                shape = shape,
                color = backgroundColor,
                border = BorderStroke(
                    1.dp,
                    if (isFocused) TvColors.BorderGlowActive else if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle
                ),
                modifier = Modifier
                    .scale(animatedScale)
                    .clip(shape)
                    .focusRequester(requester)
                    .onFocusChanged { state -> isFocused = state.isFocused }
                    .focusable(interactionSource = interaction)
                    .clickable(interactionSource = interaction, indication = null) {
                        onToggleType(chip.type)
                    }
            ) {
                Text(
                    text = chip.label,
                    style = TvTypography.Button.copy(
                        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                        color = if (isFocused) TvColors.TextPrimary else if (isSelected) TvColors.FocusElectricBlue else TvColors.TextSecondary
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Adaptive responsive poster grid for interleaved search results using enhanced [SearchResultCard].
 */
@Composable
private fun SearchResultsGrid(
    results: List<SearchResponse>,
    onCardClick: (SearchResponse) -> Unit,
    onSearchSimilar: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalTvFocusManager.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 175.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        modifier = modifier.fillMaxSize()
    ) {
        itemsIndexed(results, key = { index, item -> "${item.apiName}_${item.url}_$index" }) { index, item ->
            val row = 3 + (index / 4)
            val col = index % 4

            SearchResultCard(
                item = item,
                row = row,
                col = col,
                focusManager = focusManager,
                onSearchSimilar = onSearchSimilar,
                onClick = { onCardClick(item) }
            )
        }
    }
}

/**
 * Grouped by provider search results section with horizontal pagination per provider.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchGroupedByProviderSection(
    groupedSearches: Map<String, com.lagradost.cloudstream3.ui.search.ExpandableSearchList>,
    filterState: SearchFilterState,
    onCardClick: (SearchResponse) -> Unit,
    onExpandProvider: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchColumnState = rememberLazyListState()
    val pinnedOrder = remember { DataStoreHelper.pinnedProviders.reversedArray() }

    // Upstream 1:1 Parity: Order pinned providers first, followed by others
    val sortedEntries = remember(groupedSearches, pinnedOrder) {
        groupedSearches.entries.sortedWith(compareBy { (providerName, _) ->
            val index = pinnedOrder.indexOf(providerName)
            if (index == -1) Int.MAX_VALUE else index
        })
    }

    LazyColumn(
        state = searchColumnState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
            sortedEntries.forEach { (providerName, searchList) ->
                // Skip provider if provider filter is active and this provider is not selected
                if (filterState.selectedProviders.isNotEmpty() && providerName !in filterState.selectedProviders) {
                    return@forEach
                }

                val filteredItems = searchList.list.applySearchFilters(filterState)

                if (filteredItems.isNotEmpty()) {
                    item(key = "provider_row_$providerName") {
                        val lazyRowState = rememberLazyListState()
                        val coroutineScope = rememberCoroutineScope()

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = TvDimensions.SectionHeaderBottomSpacing),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$providerName (${filteredItems.size})",
                                    style = TvTypography.Section.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                )

                                if (searchList.hasNext) {
                                    TextButton(onClick = { onExpandProvider(providerName) }) {
                                        Text(
                                            text = "Load More",
                                            color = TvColors.FocusElectricBlue,
                                            style = TvTypography.Button
                                        )
                                    }
                                }
                            }

                            LazyRow(
                                state = lazyRowState,
                                horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .desktopMouseSwipeable(lazyRowState, coroutineScope),
                            ) {
                                itemsIndexed(filteredItems, key = { _, item -> "${item.apiName}_${item.url}" }) { _, item ->
                                    SearchResultCard(
                                        item = item,
                                        onClick = { onCardClick(item) }
                                    )
                                }

                                if (searchList.hasNext) {
                                    item(key = "load_more_$providerName") {
                                        val interactionSource = remember { MutableInteractionSource() }
                                        Card(
                                        modifier = Modifier
                                            .width(TvDimensions.CardWidth)
                                            .height(TvDimensions.CardHeight)
                                            .clip(RoundedCornerShape(TvDimensions.CornerRadiusCard))
                                            .focusable(interactionSource = interactionSource)
                                            .clickable(interactionSource = interactionSource, indication = null) {
                                                onExpandProvider(providerName)
                                            },
                                        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                                        colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
                                    ) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Load More",
                                                    tint = TvColors.FocusElectricBlue,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                                Text(
                                                    text = "Load More",
                                                    style = TvTypography.Button.copy(color = TvColors.FocusElectricBlue)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Recent Search History section with chips for instant re-query, individual deletion, right-click menu, and clear all.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchHistorySection(
    history: List<SearchHistoryItem>,
    onSelect: (SearchHistoryItem) -> Unit,
    onRemove: (SearchHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalTvFocusManager.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = TvColors.FocusElectricBlue,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Recent Searches",
                    style = TvTypography.Headline.copy(fontSize = 18.sp, color = TvColors.TextPrimary)
                )
            }

            if (history.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.focusable()
                ) {
                    Text(
                        text = "Clear All History",
                        color = TvColors.ErrorRed,
                        style = TvTypography.Button.copy(fontSize = 13.sp)
                    )
                }
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No recent searches. Type above or press '/' to search across installed scrapers.",
                    style = TvTypography.Body.copy(color = TvColors.TextMuted)
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(history, key = { _, item -> item.key }) { index, item ->
                    val requester = remember { FocusRequester() }
                    var isFocused by remember { mutableStateOf(false) }
                    var isHovered by remember { mutableStateOf(false) }
                    var showContextMenu by remember { mutableStateOf(false) }

                    RegisterTvFocusItem(
                        row = 3,
                        col = index,
                        focusManager = focusManager,
                        requester = requester
                    )

                    val shape = RoundedCornerShape(TvDimensions.CornerRadiusPill)
                    val interaction = remember { MutableInteractionSource() }

                    Box {
                        Surface(
                            shape = shape,
                            color = when {
                                isFocused -> TvColors.FocusElectricBlue
                                isHovered -> TvColors.SurfaceElevated
                                else -> TvColors.SurfaceCard
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isFocused -> TvColors.BorderGlowActive
                                    isHovered -> TvColors.FocusElectricBlue.copy(alpha = 0.5f)
                                    else -> TvColors.BorderSubtle
                                }
                            ),
                            modifier = Modifier
                                .clip(shape)
                                .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                                .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    if (event.button == PointerButton.Secondary) {
                                        showContextMenu = true
                                    }
                                }
                                .focusRequester(requester)
                                .onFocusChanged { state ->
                                    isFocused = state.isFocused
                                    if (state.isFocused) {
                                        focusManager.notifyFocused(3, index)
                                    }
                                }
                                .focusable(interactionSource = interaction)
                                .clickable(interactionSource = interaction, indication = null) {
                                    onSelect(item)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Text(
                                    text = item.searchText,
                                    style = TvTypography.Body.copy(
                                        color = if (isFocused) TvColors.TextPrimary else TvColors.TextSecondary,
                                        fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
                                    )
                                )

                                IconButton(
                                    onClick = { onRemove(item) },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove query",
                                        tint = if (isFocused) TvColors.TextPrimary else TvColors.TextMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = showContextMenu,
                            onDismissRequest = { showContextMenu = false },
                            modifier = Modifier.background(TvColors.SurfaceElevated)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Search again", color = TvColors.TextPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = TvColors.FocusElectricBlue)
                                },
                                onClick = {
                                    showContextMenu = false
                                    onSelect(item)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remove from history", color = TvColors.ErrorRed) },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = TvColors.ErrorRed)
                                },
                                onClick = {
                                    showContextMenu = false
                                    onRemove(item)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
