package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.desktop.DesktopErrorReporter
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.rememberCloseableViewModel
import com.lagradost.cloudstream3.desktop.ui.components.KeyboardShortcutsDialog
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.components.desktopMouseSwipeable
import androidx.compose.ui.input.key.*
import com.lagradost.cloudstream3.desktop.ui.navigation.LocalVideoPlayer
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.navigation.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class HomeSavedTab(val id: Int, val label: String) {
    FAVORITES(-1, "Favorites"),
    WATCHING(0, "Watching"),
    COMPLETED(1, "Completed"),
    ON_HOLD(2, "On Hold"),
    DROPPED(3, "Dropped"),
    PLAN_TO_WATCH(4, "Plan to Watch"),
    SUBSCRIBED(-2, "Subscribed");

    companion object {
        fun fromId(id: Int?): HomeSavedTab = entries.find { it.id == id } ?: FAVORITES
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposeHomeScreen(
    navController: NavController,
    showErrorsDialog: Boolean = false,
    onDismissErrors: () -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel = rememberCloseableViewModel("canonical_home_view_model") { HomeViewModel() }

    // StateFlows directly consumed from canonical HomeViewModel
    val page by viewModel.page.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val resumeWatching by viewModel.resumeWatching.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val availableWatchStatusTypes by viewModel.availableWatchStatusTypes.collectAsState()
    val randomItems by viewModel.randomItems.collectAsState()
    val apiName by viewModel.apiName.collectAsState()

    val videoPlayer = LocalVideoPlayer.current
    val savedTabId = remember { DataStoreHelper.homeBookmarkedList.firstOrNull() ?: -1 }
    var activeSavedTab by remember { mutableStateOf(HomeSavedTab.fromId(savedTabId)) }
    var errorSnapshot by remember { mutableStateOf(DesktopErrorReporter.getSnapshot()) }
    var showShortcutsDialog by remember { mutableStateOf(false) }

    if (showShortcutsDialog) {
        KeyboardShortcutsDialog(onDismissRequest = { showShortcutsDialog = false })
    }

    val syncGen by DesktopRepositoryManager.syncGeneration.collectAsState()

    val providers = remember(syncGen) {
        APIHolder.allProviders.filter {
            (it.hasMainPage || it.supportedTypes.isNotEmpty()) && it.providerType != ProviderType.MetaProvider
        }
    }
    val currentProvider = remember(apiName, providers) {
        apiName?.let { APIHolder.getApiFromNameNull(it) }
            ?: DataStoreHelper.currentHomePage?.let { APIHolder.getApiFromNameNull(it) }
            ?: providers.firstOrNull { it.hasMainPage }
            ?: providers.firstOrNull()
    }

    val allLoadedItems: List<SearchResponse> = remember(page, randomItems) {
        val fromPage = (page as? Resource.Success)?.value?.values
            ?.flatMap { it.list.list }
            ?: emptyList()
        val fromRandom = randomItems ?: emptyList()
        (fromPage + fromRandom).distinctBy { it.url }
    }

    val handleRandomClick: () -> Unit = {
        val randomItem = allLoadedItems.randomOrNull()
        if (randomItem != null) {
            val prov = APIHolder.getApiFromNameNull(randomItem.apiName) ?: currentProvider
            if (prov != null) {
                navController.navigate(
                    Screen.Details(
                        provider = prov,
                        url = randomItem.url,
                        preloadedName = randomItem.name,
                        preloadedPoster = randomItem.posterUrl,
                        preloadedBg = null,
                    )
                )
            }
        }
    }

    val hasUnreadUpdates by DesktopDataStore.pluginUpdatesFlow
        .map { DesktopDataStore.hasUnreadUpdates() }
        .collectAsState(initial = DesktopDataStore.hasUnreadUpdates())

    val updatesHistory by DesktopDataStore.pluginUpdatesFlow
        .map { DesktopDataStore.getUpdatesHistory() }
        .collectAsState(initial = DesktopDataStore.getUpdatesHistory())

    val historyUpdates by DesktopDataStore.historyUpdates.collectAsState()

    val favoritesList = remember(historyUpdates, bookmarks) {
        DataStoreHelper.getAllFavorites()
    }
    val subscriptionsList = remember(historyUpdates, bookmarks) {
        DataStoreHelper.getAllSubscriptions()
    }
    val hasAnySaved = bookmarks.first || favoritesList.isNotEmpty() || subscriptionsList.isNotEmpty()

    val currentSavedItems: List<SearchResponse> = remember(activeSavedTab, bookmarks, favoritesList, subscriptionsList) {
        when (activeSavedTab) {
            HomeSavedTab.FAVORITES -> favoritesList
            HomeSavedTab.SUBSCRIBED -> subscriptionsList
            HomeSavedTab.WATCHING,
            HomeSavedTab.COMPLETED,
            HomeSavedTab.ON_HOLD,
            HomeSavedTab.DROPPED,
            HomeSavedTab.PLAN_TO_WATCH -> bookmarks.second
        }
    }

    // Upstream 1:1 Parity: Automatically reload resume watching & history in real-time on any database mutation
    LaunchedEffect(historyUpdates) {
        viewModel.reloadStored()
    }

    // Initial load: restore preferred home provider or fallback to first available, and load stored history
    LaunchedEffect(providers, activeSavedTab) {
        val targetApi = DataStoreHelper.currentHomePage
            ?.takeIf { name -> providers.any { it.name == name } }
            ?: providers.firstOrNull { it.hasMainPage }?.name
            ?: providers.firstOrNull()?.name

        if (targetApi != null && (page !is Resource.Success || apiName != targetApi)) {
            viewModel.loadAndCancel(
                targetApi,
                forceReload = false,
                fromUI = (DataStoreHelper.currentHomePage == null),
            )
        }

        if (activeSavedTab != HomeSavedTab.FAVORITES && activeSavedTab != HomeSavedTab.SUBSCRIBED) {
            viewModel.loadStoredData(setOf(WatchType.fromInternalId(activeSavedTab.id)))
        } else {
            viewModel.reloadStored()
        }
    }

    LaunchedEffect(showErrorsDialog) {
        if (showErrorsDialog) {
            errorSnapshot = DesktopErrorReporter.getSnapshot()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.F1 || (event.isShiftPressed && event.key == Key.Slash))) {
                    showShortcutsDialog = !showShortcutsDialog
                    true
                } else false
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Home Top Bar with Provider Switcher & Direct Search Routing
            HomeTopBar(
                searchQuery = "",
                onSearchQueryChange = { query ->
                    if (query.isNotBlank()) {
                        navController.navigate(Screen.Search(initialQuery = query))
                    }
                },
                onSearch = {
                    // Navigate to dedicated Search screen
                    navController.navigate(Screen.Search())
                },
                providers = providers,
                selectedProvider = currentProvider,
                onProviderSelected = { provName ->
                    viewModel.loadAndCancel(provName, forceReload = true, fromUI = true)
                },
                mergedPluginIcons = emptyMap(),
                hasUnreadUpdates = hasUnreadUpdates,
                updatesHistory = updatesHistory,
                onMarkUpdatesRead = { DesktopDataStore.setUnreadUpdates(false) },
                onRandomClick = if (allLoadedItems.isNotEmpty()) handleRandomClick else null,
                onShowShortcuts = { showShortcutsDialog = true },
            )

            // Main Content Area
            when {
                // Extensions not installed
                providers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Card(
                            modifier = Modifier
                                .width(540.dp)
                                .wrapContentHeight()
                                .padding(TvDimensions.SpacingLarge),
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
                            border = BorderStroke(TvDimensions.BorderNormal, TvColors.BorderSubtle),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(TvDimensions.SpacingMedium),
                            ) {
                                Text(
                                    text = "No Extensions Installed",
                                    style = TvTypography.Headline,
                                    color = TvColors.TextPrimary,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = "Add repositories and install plugins from the Extensions menu to start watching.",
                                    style = TvTypography.Body.copy(color = TvColors.TextSecondary),
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(TvDimensions.SpacingSmall))

                                var isBtnFocused by remember { mutableStateOf(false) }
                                val btnShape = RoundedCornerShape(TvDimensions.CornerRadiusPill)
                                val btnInteractionSource = remember { MutableInteractionSource() }

                                Surface(
                                    modifier = Modifier
                                        .clip(btnShape)
                                        .onFocusChanged { isBtnFocused = it.isFocused }
                                        .focusable(interactionSource = btnInteractionSource)
                                        .clickable(
                                            interactionSource = btnInteractionSource,
                                            indication = null,
                                            onClick = { navController.navigate(Screen.Extensions) },
                                        ),
                                    shape = btnShape,
                                    color = if (isBtnFocused) TvColors.FocusElectricBlue else TvColors.SurfaceElevated,
                                    border = if (isBtnFocused) {
                                        BorderStroke(TvDimensions.BorderGlow, TvColors.BorderGlowActive)
                                    } else {
                                        BorderStroke(TvDimensions.BorderNormal, TvColors.BorderSubtle)
                                    },
                                ) {
                                    Text(
                                        text = "[ Manage Extensions ]",
                                        style = TvTypography.Button.copy(
                                            color = if (isBtnFocused) Color.White else TvColors.FocusElectricBlue,
                                        ),
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Loading Homepage
                page is Resource.Loading && (preview !is Resource.Success) -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            CircularProgressIndicator(
                                color = TvColors.FocusElectricBlue,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                text = "Loading homepage from ${currentProvider?.name ?: "provider"}...",
                                style = TvTypography.Section.copy(color = TvColors.TextSecondary),
                            )
                        }
                    }
                }

                // Homepage Failure
                page is Resource.Failure && (preview !is Resource.Success) -> {
                    val failure = page as Resource.Failure
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = failure.errorString.ifBlank { "Failed to load homepage" },
                                style = TvTypography.Headline.copy(color = TvColors.ErrorRed),
                            )
                            OutlinedButton(
                                onClick = {
                                    viewModel.loadAndCancel(currentProvider?.name, forceReload = true)
                                },
                                modifier = Modifier.focusable(),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Retry", style = TvTypography.Button)
                            }
                        }
                    }
                }

                // Homepage Content
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(TvDimensions.RowSpacing),
                    ) {
                        // 1. Hero Carousel (rich LoadResponse from viewModel.preview)
                        if (preview is Resource.Success) {
                            val previewData = (preview as Resource.Success).value.second
                            if (previewData.isNotEmpty()) {
                                item(key = "hero_carousel") {
                                    HomeHeroCarousel(
                                        items = previewData,
                                        onItemClick = { item, backdrop ->
                                            val prov = APIHolder.getApiFromNameNull(item.apiName) ?: currentProvider
                                            if (prov != null) {
                                                navController.navigate(
                                                    Screen.Details(
                                                        provider = prov,
                                                        url = item.url,
                                                        preloadedName = item.name,
                                                        preloadedPoster = item.posterUrl,
                                                        preloadedBg = backdrop,
                                                    )
                                                )
                                            }
                                        },
                                        onLoadMore = {
                                            viewModel.loadMoreHomeScrollResponses()
                                        },
                                    )
                                }
                            }
                        }

                        // 2. Continue Watching Row (from viewModel.resumeWatching)
                        if (resumeWatching.isNotEmpty()) {
                            item(key = "continue_watching") {
                                HomeHistoryRow(
                                    resumeWatching = resumeWatching,
                                    onClearHistory = {
                                        viewModel.deleteResumeWatching()
                                    },
                                    onRemoveItem = { item ->
                                        DataStoreHelper.removeLastWatched(item.parentId)
                                        viewModel.reloadStored()
                                    },
                                    onMarkAsWatched = { item ->
                                        val dur = item.watchPos?.duration ?: 100_000L
                                        DataStoreHelper.setViewPos(item.id, dur, dur)
                                        DataStoreHelper.removeLastWatched(item.parentId)
                                        viewModel.reloadStored()
                                    },
                                    onItemClick = { item ->
                                        val prov = APIHolder.getApiFromNameNull(item.apiName) ?: currentProvider
                                        if (prov != null) {
                                            navController.navigate(
                                                Screen.Details(
                                                    provider = prov,
                                                    url = item.url,
                                                    preloadedName = item.name,
                                                    preloadedPoster = item.posterUrl,
                                                    preloadedBg = null,
                                                )
                                            )
                                        }
                                    },
                                    onPlayClick = { item ->
                                        val prov = APIHolder.getApiFromNameNull(item.apiName) ?: currentProvider
                                        if (prov != null) {
                                            coroutineScope.launch(Dispatchers.IO) {
                                                // 1. Check local watch history repository for accurate episode data URL & resume position
                                                val allHistory = WatchHistoryRepository.getAllWatchHistory()
                                                val savedRecord = allHistory.firstOrNull {
                                                    it.title.equals(item.name, ignoreCase = true) ||
                                                    it.url == item.url ||
                                                    (it.parentId != null && it.parentId == item.url) ||
                                                    (item.parentId != null && it.parentId == item.parentId.toString())
                                                }

                                                var targetDataUrl = savedRecord?.episodeId?.takeIf { it.isNotBlank() && !it.all { char -> char.isDigit() } }
                                                var episodeNumber = item.episode ?: savedRecord?.episode
                                                var seasonNumber = item.season ?: savedRecord?.season
                                                val startPos = savedRecord?.position ?: item.watchPos?.position ?: 0L

                                                // 2. If episode data URL is missing or is just a numeric hash, load show metadata from provider
                                                if (targetDataUrl.isNullOrBlank()) {
                                                    try {
                                                        val loadRes = prov.load(item.url)
                                                        if (loadRes is TvSeriesLoadResponse) {
                                                            val ep = loadRes.episodes.firstOrNull { it.data.hashCode() == item.id }
                                                                ?: loadRes.episodes.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }
                                                                ?: loadRes.episodes.firstOrNull()
                                                            if (ep != null) {
                                                                targetDataUrl = ep.data
                                                                episodeNumber = ep.episode
                                                                seasonNumber = ep.season
                                                            }
                                                        } else if (loadRes is AnimeLoadResponse) {
                                                            val allEps = loadRes.episodes.values.flatten()
                                                            val ep = allEps.firstOrNull { it.data.hashCode() == item.id }
                                                                ?: allEps.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }
                                                                ?: allEps.firstOrNull()
                                                            if (ep != null) {
                                                                targetDataUrl = ep.data
                                                                episodeNumber = ep.episode
                                                                seasonNumber = ep.season
                                                            }
                                                        } else if (loadRes != null) {
                                                            targetDataUrl = (loadRes as? com.lagradost.cloudstream3.MovieLoadResponse)?.dataUrl ?: loadRes.url
                                                        }
                                                    } catch (t: Throwable) {
                                                        AppLogger.w("HomeScreen", "Failed to resolve resume episode data: ${t.message}")
                                                    }
                                                }

                                                val finalDataUrl = targetDataUrl ?: item.url
                                                val hist = WatchHistory(
                                                    url = finalDataUrl,
                                                    parentId = item.url,
                                                    episodeId = finalDataUrl,
                                                    title = item.name,
                                                    apiName = item.apiName,
                                                    posterUrl = item.posterUrl,
                                                    season = seasonNumber,
                                                    episode = episodeNumber,
                                                    position = startPos,
                                                    duration = item.watchPos?.duration ?: 0L,
                                                    updateTime = System.currentTimeMillis()
                                                )

                                                withContext(Dispatchers.Main) {
                                                    videoPlayer.invoke(
                                                        VideoLaunchData(
                                                            links = emptyList(),
                                                            initialIndex = 0,
                                                            title = item.name,
                                                            subtitles = emptyList(),
                                                            startPositionMs = startPos,
                                                            history = hist,
                                                            provider = prov,
                                                            dataUrl = finalDataUrl,
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        // 3. Saved Bookmarks Row with 7 Filter Chips (Favorites, 5 Watch States, Subscribed)
                        if (hasAnySaved) {
                            item(key = "bookmarks_section") {
                                val bookmarkLazyListState = rememberLazyListState()

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = TvDimensions.SectionHeaderBottomSpacing),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = null,
                                                tint = TvColors.FocusElectricBlue,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Text(
                                                text = "Saved Bookmarks",
                                                style = TvTypography.Section,
                                            )
                                        }

                                        // 7 Filter Chips: Favorites, Watching, Completed, On Hold, Dropped, Plan to Watch, Subscribed
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            HomeSavedTab.entries.forEach { tab ->
                                                val isSelected = activeSavedTab == tab
                                                val chipInteraction = remember { MutableInteractionSource() }

                                                Surface(
                                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                                                    color = if (isSelected) TvColors.FocusElectricBlue else TvColors.SurfaceElevated,
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (isSelected) TvColors.BorderGlowActive else TvColors.BorderSubtle,
                                                    ),
                                                    modifier = Modifier
                                                        .clickable(interactionSource = chipInteraction, indication = null) {
                                                            activeSavedTab = tab
                                                            DataStoreHelper.homeBookmarkedList = intArrayOf(tab.id)
                                                            if (tab != HomeSavedTab.FAVORITES && tab != HomeSavedTab.SUBSCRIBED) {
                                                                viewModel.loadStoredData(setOf(WatchType.fromInternalId(tab.id)))
                                                            }
                                                        }
                                                ) {
                                                    Text(
                                                        text = tab.label,
                                                        style = TvTypography.Caption.copy(
                                                            color = if (isSelected) Color.White else TvColors.TextSecondary,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (currentSavedItems.isNotEmpty()) {
                                        LazyRow(
                                            state = bookmarkLazyListState,
                                            horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .desktopMouseSwipeable(bookmarkLazyListState, coroutineScope),
                                        ) {
                                            itemsIndexed(currentSavedItems, key = { _, item -> "${item.apiName}_${item.url}" }) { _, item ->
                                                TvCard(
                                                    title = item.name,
                                                    posterUrl = item.posterUrl,
                                                    subtitle = item.apiName,
                                                    onClick = {
                                                        val prov = APIHolder.getApiFromNameNull(item.apiName) ?: currentProvider
                                                        if (prov != null) {
                                                            navController.navigate(
                                                                Screen.Details(
                                                                    provider = prov,
                                                                    url = item.url,
                                                                    preloadedName = item.name,
                                                                    preloadedPoster = item.posterUrl,
                                                                    preloadedBg = null,
                                                                )
                                                            )
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(130.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "No saved titles in ${activeSavedTab.label}",
                                                style = TvTypography.Caption.copy(color = TvColors.TextMuted),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Category Rows from viewModel.page (Resource<Map<String, ExpandableHomepageList>>)
                        if (page is Resource.Success) {
                            val categories = (page as Resource.Success).value
                            categories.values.forEach { categoryList ->
                                item(key = "category_${categoryList.list.name}") {
                                    HomeCategorySection(
                                        category = categoryList,
                                        provider = currentProvider,
                                        onViewAll = { prov, title, items ->
                                            if (prov != null) {
                                                navController.navigate(Screen.CategoryGrid(prov, title, items))
                                            }
                                        },
                                        onItemClick = { prov, item, backdrop ->
                                            val targetProv = prov ?: currentProvider
                                            if (targetProv != null) {
                                                navController.navigate(
                                                    Screen.Details(
                                                        provider = targetProv,
                                                        url = item.url,
                                                        preloadedName = item.name,
                                                        preloadedPoster = item.posterUrl,
                                                        preloadedBg = backdrop,
                                                    )
                                                )
                                            }
                                        },
                                        onLoadMore = {
                                            viewModel.expand(categoryList.list.name)
                                        },
                                    )
                                }
                            }
                        }

                        // 5. Random Recommendations (from viewModel.randomItems)
                        if (!randomItems.isNullOrEmpty()) {
                            item(key = "random_recommendations") {
                                val randomLazyListState = rememberLazyListState()

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 12.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = TvDimensions.SectionHeaderBottomSpacing),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = TvColors.FocusElectricBlue,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = "Recommended For You",
                                            style = TvTypography.Section,
                                        )
                                    }

                                    LazyRow(
                                        state = randomLazyListState,
                                        horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .desktopMouseSwipeable(randomLazyListState, coroutineScope),
                                    ) {
                                        itemsIndexed(randomItems!!, key = { _, item -> "${item.apiName}_${item.url}" }) { _, item ->
                                            TvCard(
                                                title = item.name,
                                                posterUrl = item.posterUrl,
                                                subtitle = item.apiName,
                                                onClick = {
                                                    val prov = APIHolder.getApiFromNameNull(item.apiName) ?: currentProvider
                                                    if (prov != null) {
                                                        navController.navigate(
                                                            Screen.Details(
                                                                provider = prov,
                                                                url = item.url,
                                                                preloadedName = item.name,
                                                                preloadedPoster = item.posterUrl,
                                                                preloadedBg = null,
                                                            )
                                                        )
                                                    }
                                                },
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

        if (showErrorsDialog) {
            AlertDialog(
                onDismissRequest = onDismissErrors,
                title = { Text("Error Logs", style = TvTypography.Headline) },
                text = {
                    OutlinedTextField(
                        value = errorSnapshot,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(400.dp),
                    )
                },
                confirmButton = {
                    Button(onClick = onDismissErrors) {
                        Text("Close")
                    }
                },
            )
        }
    }
}
