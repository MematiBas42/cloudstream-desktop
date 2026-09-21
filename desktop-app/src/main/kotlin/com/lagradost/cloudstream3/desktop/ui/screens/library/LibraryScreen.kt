@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.lagradost.cloudstream3.desktop.ui.screens.library

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.library.LIBRARY_FOLDER
import com.lagradost.cloudstream3.ui.library.LibraryOpener
import com.lagradost.cloudstream3.ui.library.LibraryOpenerType
import com.lagradost.cloudstream3.ui.library.LibraryViewModel
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.library.ProviderLibraryData
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Deterministic lifecycle-scoped ViewModel provider for Compose Desktop.
 */
@Composable
private fun <VM : DesktopViewModel> rememberCloseableViewModel(key: Any, factory: () -> VM): VM {
    val vm = remember(key) { factory() }
    DisposableEffect(key) {
        onDispose { vm.onCleared() }
    }
    return vm
}

/**
 * Converts upstream [UiText] into human-readable text on Desktop JVM.
 */
private fun UiText.toDisplayString(context: Context? = CloudStreamApp.context): String {
    return when (this) {
        is UiText.DynamicString -> value
        is UiText.StringResource -> {
            val resolved = context?.getString(resId)
            if (resolved != null && !resolved.startsWith("res_")) {
                if (args.isEmpty()) resolved
                else try {
                    String.format(
                        java.util.Locale.US,
                        resolved,
                        *args.map {
                            when (it) {
                                is UiText -> it.toDisplayString(context)
                                else -> it
                            }
                        }.toTypedArray()
                    )
                } catch (_: Throwable) {
                    resolved
                }
            } else {
                when (resId) {
                    R.string.type_watching -> "Watching"
                    R.string.type_completed -> "Completed"
                    R.string.type_on_hold -> "On Hold"
                    R.string.type_dropped -> "Dropped"
                    R.string.type_plan_to_watch -> "Plan to Watch"
                    R.string.type_none -> "None"
                    R.string.type_re_watching -> "Re-watching"
                    R.string.favorites_list_name -> "Favorites"
                    R.string.subscription_list_name -> "Subscriptions"
                    R.string.sort_alphabetical_a -> "Alphabetical (A-Z)"
                    R.string.sort_alphabetical_z -> "Alphabetical (Z-A)"
                    R.string.sort_updated_new -> "Updated (Newest)"
                    R.string.sort_updated_old -> "Updated (Oldest)"
                    R.string.sort_rating_desc -> "Rating (High to Low)"
                    R.string.sort_rating_asc -> "Rating (Low to High)"
                    R.string.sort_release_date_new -> "Release Date (Newest)"
                    R.string.sort_release_date_old -> "Release Date (Oldest)"
                    R.string.action_default -> "Default"
                    R.string.browser -> "Browser"
                    R.string.search -> "Search"
                    R.string.none -> "Default"
                    else -> "List"
                }
            }
        }
    }
}

/**
 * Converts [ListSorting] into human-readable display label.
 */
private fun ListSorting.toDisplayString(): String = when (this) {
    ListSorting.Query -> "Default"
    ListSorting.AlphabeticalA -> "Alphabetical (A-Z)"
    ListSorting.AlphabeticalZ -> "Alphabetical (Z-A)"
    ListSorting.UpdatedNew -> "Updated (Newest)"
    ListSorting.UpdatedOld -> "Updated (Oldest)"
    ListSorting.ReleaseDateNew -> "Release Date (Newest)"
    ListSorting.ReleaseDateOld -> "Release Date (Oldest)"
    ListSorting.RatingHigh -> "Rating (High to Low)"
    ListSorting.RatingLow -> "Rating (Low to High)"
}

/**
 * Returns matching icon for dynamic library tab types.
 */
private fun getTabIcon(title: String): ImageVector = when (title.lowercase()) {
    "watching" -> Icons.Default.PlayArrow
    "completed" -> Icons.Default.Check
    "plan to watch", "planning" -> Icons.Default.Star
    "on hold", "paused" -> Icons.AutoMirrored.Filled.List
    "dropped" -> Icons.Default.Close
    "favorites" -> Icons.Default.Favorite
    "subscriptions" -> Icons.Default.Notifications
    else -> Icons.AutoMirrored.Filled.List
}

/**
 * Industrial-grade Native Desktop Library Screen (Domain 2: Library Parity).
 *
 * Architecture:
 * 1. Connects directly to canonical [LibraryViewModel] from `:plugin-runtime`.
 * 2. Hybrid library: Switch between LocalList and external sync providers (AniList, MAL, Simkl, Kitsu).
 * 3. Dynamic tabs driven by [LibraryViewModel.pages]: Watching, Completed, On Hold, Dropped, Plan to Watch, Favorites, Subscriptions.
 * 4. 9 Sorting modes via [ListSorting] and [LibraryViewModel.sort].
 * 5. In-library live search: Debounced query calling [LibraryViewModel.sort] with fuzzy Levenshtein distance.
 * 6. Batch Selection & Bulk Deletion mode with Confirm (Red) and Cancel (Gray) action bar.
 * 7. Upstream [LibraryOpener] integration: open with linked provider, specific provider, browser or search.
 * 8. Persistent tab index: restores last active tab per account/provider.
 * 9. Mouse-first responsive card grid with hover scaling, glow borders, and right-click context menu.
 */
@Composable
fun ComposeLibraryScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val viewModel = rememberCloseableViewModel("LibraryViewModel") {
        LibraryViewModel()
    }

    val pagesResource by viewModel.pages.collectAsState()
    val currentPageIndex by viewModel.currentPage.collectAsState()
    val currentApiName by viewModel.currentApiName.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var showSortDropdown by remember { mutableStateOf(false) }

    // Batch Selection Mode State
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<SyncAPI.LibraryItem>()) }

    // Upstream Open-With Dialog States
    var openWithItem by remember { mutableStateOf<SyncAPI.LibraryItem?>(null) }
    var showListOpenerDialog by remember { mutableStateOf(false) }

    // Initial load: force reload to ensure latest favorites and watch states are shown
    LaunchedEffect(Unit) {
        viewModel.reloadPages(true)
    }

    // Restore last visited tab per account and provider
    val lastTabKey = remember(currentApiName) { "$currentAccount/library_last_tab/$currentApiName" }
    var hasRestoredTab by remember(currentApiName) { mutableStateOf(false) }
    LaunchedEffect(pagesResource, hasRestoredTab) {
        if (!hasRestoredTab && pagesResource is Resource.Success) {
            val pages = (pagesResource as Resource.Success).value
            val savedTab = getKey<Int>(lastTabKey, 0) ?: 0
            if (savedTab in pages.indices && savedTab != currentPageIndex) {
                viewModel.switchPage(savedTab)
            }
            hasRestoredTab = true
        }
    }

    // Debounced in-library search (fuzzy Levenshtein)
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            delay(250)
            viewModel.sort(ListSorting.Query, searchQuery)
        } else {
            val targetSort = viewModel.currentSortingMethod ?: ListSorting.Query
            viewModel.sort(targetSort, null)
        }
    }

    val availableApis = remember(currentApiName, pagesResource) {
        viewModel.availableApiNames
    }

    // Upstream loadLibraryItem opener resolution
    val handleCardClick: (SyncAPI.LibraryItem) -> Unit = { item ->
        val syncId = item.syncId.ifBlank { item.id?.toString() ?: item.name }
        val savedListSelection = getKey<LibraryOpener>("$currentAccount/$LIBRARY_FOLDER", currentApiName)
        val savedSelection = getKey<LibraryOpener>(
            "$currentAccount/$LIBRARY_FOLDER",
            syncId
        ).takeIf {
            it?.openType != LibraryOpenerType.Default
        } ?: savedListSelection

        when (savedSelection?.openType) {
            null, LibraryOpenerType.Default -> {
                val provider = APIHolder.getApiFromNameNull(item.apiName)
                if (provider != null) {
                    navController.navigate(
                        Screen.Details(
                            provider = provider,
                            url = item.url,
                            preloadedName = item.name,
                            preloadedPoster = item.posterUrl
                        )
                    )
                } else {
                    navController.navigate(Screen.Search(initialQuery = item.name))
                }
            }
            LibraryOpenerType.Provider -> {
                val targetApi = savedSelection.providerData?.apiName?.let { APIHolder.getApiFromNameNull(it) }
                if (targetApi != null) {
                    navController.navigate(
                        Screen.Details(
                            provider = targetApi,
                            url = item.url,
                            preloadedName = item.name,
                            preloadedPoster = item.posterUrl
                        )
                    )
                } else {
                    navController.navigate(Screen.Search(initialQuery = item.name))
                }
            }
            LibraryOpenerType.Browser -> {
                if (item.url.isNotBlank() && (item.url.startsWith("http://") || item.url.startsWith("https://"))) {
                    CloudStreamApp.openBrowser(item.url)
                }
            }
            LibraryOpenerType.Search -> {
                navController.navigate(Screen.Search(initialQuery = item.name))
            }
            LibraryOpenerType.None -> {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.AmoledBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 20.dp)
        ) {
            // =====================================================================
            // 1. Top Controls Bar: Title, Provider Selector, Live Search, Sort, Manage/Trash, Refresh
            // =====================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Screen Title
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "My Library",
                            style = TvTypography.Headline.copy(
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Active Provider Badge / Selector Button
                        Box {
                            Surface(
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                color = TvColors.SurfaceElevated,
                                border = BorderStroke(1.dp, TvColors.FocusElectricBlue),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                    .clickable { showProviderDropdown = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (currentApiName == "Local") Icons.Default.Home else Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = TvColors.FocusElectricBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (currentApiName.isNotBlank()) currentApiName else "Local",
                                        style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Switch Provider",
                                        tint = TvColors.TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Sync Providers Dropdown
                            DropdownMenu(
                                expanded = showProviderDropdown,
                                onDismissRequest = { showProviderDropdown = false },
                                modifier = Modifier.background(TvColors.SurfaceCard)
                            ) {
                                availableApis.forEach { apiName ->
                                    val isSelected = apiName == currentApiName
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = apiName,
                                                    color = if (isSelected) TvColors.FocusElectricBlue else Color.White,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (isSelected) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = TvColors.FocusElectricBlue,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            showProviderDropdown = false
                                            if (apiName != currentApiName) {
                                                isSelectionMode = false
                                                selectedItems = emptySet()
                                                hasRestoredTab = false
                                                viewModel.switchList(apiName)
                                            }
                                        }
                                    )
                                }

                                if (currentApiName != "Local" && currentApiName.isNotBlank()) {
                                    HorizontalDivider(color = TvColors.BorderSubtle)
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Extension,
                                                    contentDescription = null,
                                                    tint = TvColors.FocusElectricBlue,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Default Opener for $currentApiName...",
                                                    color = TvColors.FocusElectricBlue,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        onClick = {
                                            showProviderDropdown = false
                                            showListOpenerDialog = true
                                        }
                                    )
                                }

                                HorizontalDivider(color = TvColors.BorderSubtle)

                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = null,
                                                tint = TvColors.TextSecondary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Manage Accounts...",
                                                color = TvColors.TextSecondary,
                                                fontSize = 13.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        showProviderDropdown = false
                                        navController.navigate(Screen.Settings)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // In-Library Search Input Field
                Box(
                    modifier = Modifier
                        .width(280.dp)
                        .height(38.dp)
                        .background(TvColors.SurfaceCard, RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                        .border(1.dp, TvColors.BorderSubtle, RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TvColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search in library...",
                                    style = TvTypography.Body.copy(fontSize = 13.sp),
                                    color = TvColors.TextMuted
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TvTypography.Body.copy(
                                    color = Color.White,
                                    fontSize = 13.sp
                                ),
                                cursorBrush = SolidColor(TvColors.FocusElectricBlue),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = TvColors.TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Open Downloads Screen Button
                Surface(
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                    color = TvColors.SurfaceCard,
                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                    modifier = Modifier
                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                        .clickable { navController.navigate(Screen.Downloads) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Downloads",
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Downloads",
                            style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 9 Sorting Modes Dropdown Selector
                Box {
                    Surface(
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = TvColors.SurfaceCard,
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        modifier = Modifier
                            .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                            .clickable { showSortDropdown = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = "Sort",
                                tint = TvColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = viewModel.currentSortingMethod?.toDisplayString() ?: "Sort",
                                style = TvTypography.Caption.copy(fontSize = 12.sp),
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = TvColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showSortDropdown,
                        onDismissRequest = { showSortDropdown = false },
                        modifier = Modifier.background(TvColors.SurfaceCard)
                    ) {
                        ListSorting.entries.forEach { method ->
                            val isSelected = viewModel.currentSortingMethod == method
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = method.toDisplayString(),
                                            color = if (isSelected) TvColors.FocusElectricBlue else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = TvColors.FocusElectricBlue,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    showSortDropdown = false
                                    viewModel.sort(method, searchQuery.ifBlank { null })
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Toggle Selection / Trash Management Button
                IconButton(
                    onClick = {
                        isSelectionMode = !isSelectionMode
                        if (!isSelectionMode) {
                            selectedItems = emptySet()
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (isSelectionMode) TvColors.FocusElectricBlue else TvColors.SurfaceCard,
                            RoundedCornerShape(TvDimensions.CornerRadiusSmall)
                        )
                        .border(
                            1.dp,
                            if (isSelectionMode) TvColors.FocusElectricBlue else TvColors.BorderSubtle,
                            RoundedCornerShape(TvDimensions.CornerRadiusSmall)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = if (isSelectionMode) "Exit Selection" else "Manage / Delete Items",
                        tint = if (isSelectionMode) Color.White else TvColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Refresh Library Button
                IconButton(
                    onClick = { viewModel.reloadPages(true) },
                    modifier = Modifier
                        .size(36.dp)
                        .background(TvColors.SurfaceCard, RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                        .border(1.dp, TvColors.BorderSubtle, RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = TvColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // =====================================================================
            // 2. Dynamic Tabs Row & Main Content Area
            // =====================================================================
            when (val resource = pagesResource) {
                is Resource.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = TvColors.FocusElectricBlue,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading library...",
                                style = TvTypography.Body,
                                color = TvColors.TextSecondary
                            )
                        }
                    }
                }

                is Resource.Failure -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = TvColors.ErrorRed,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Failed to load library",
                                style = TvTypography.Section.copy(color = Color.White)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = resource.errorString.ifBlank { "Could not fetch library metadata." },
                                style = TvTypography.Caption.copy(color = TvColors.TextSecondary),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.reloadPages(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }

                is Resource.Success -> {
                    val pages = resource.value
                    val activePageIndex = currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    val currentPage = pages.getOrNull(activePageIndex)
                    val items = currentPage?.items.orEmpty()

                    // Selection Mode Banner Controls
                    if (isSelectionMode) {
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = TvColors.SurfaceElevated,
                            border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = TvColors.FocusElectricBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${selectedItems.size} items selected",
                                    style = TvTypography.Body.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                                    color = Color.White
                                )

                                Spacer(modifier = Modifier.weight(1f))

                                val allSelected = items.isNotEmpty() && selectedItems.containsAll(items)
                                TextButton(
                                    onClick = {
                                        selectedItems = if (allSelected) {
                                            selectedItems - items.toSet()
                                        } else {
                                            selectedItems + items.toSet()
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (allSelected) "Deselect All" else "Select All",
                                        color = TvColors.FocusElectricBlue,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = {
                                        isSelectionMode = false
                                        selectedItems = emptySet()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Exit Selection",
                                        tint = TvColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 2.1 Dynamic Category Tabs Row
                    if (pages.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            itemsIndexed(pages) { index, page ->
                                val title = page.title.toDisplayString()
                                val isSelected = index == activePageIndex
                                val itemCount = page.items.size
                                val tabInteractionSource = remember { MutableInteractionSource() }
                                val isHovered by tabInteractionSource.collectIsHoveredAsState()
                                var isFocused by remember { mutableStateOf(false) }

                                val bgColor by animateColorAsState(
                                    targetValue = when {
                                        isFocused -> TvColors.FocusElectricBlue
                                        isSelected -> TvColors.SurfaceHigh
                                        isHovered -> TvColors.SurfaceElevated
                                        else -> TvColors.SurfaceCard
                                    },
                                    animationSpec = tween(150),
                                    label = "LibraryTabBg"
                                )

                                Surface(
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    color = bgColor,
                                    border = if (isFocused) {
                                        BorderStroke(2.dp, Color.White)
                                    } else if (isSelected) {
                                        BorderStroke(1.dp, TvColors.FocusElectricBlue)
                                    } else {
                                        BorderStroke(1.dp, TvColors.BorderSubtle)
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                        .hoverable(tabInteractionSource)
                                        .clickable(
                                            interactionSource = tabInteractionSource,
                                            indication = null
                                        ) {
                                            viewModel.switchPage(index)
                                            setKey(lastTabKey, index)
                                            if (isSelectionMode) {
                                                selectedItems = emptySet()
                                            }
                                        }
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .focusable()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) {
                                        Icon(
                                            imageVector = getTabIcon(title),
                                            contentDescription = null,
                                            tint = if (isFocused || isSelected) Color.White else TvColors.TextSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = title,
                                            style = TvTypography.Button.copy(
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (isFocused || isSelected) Color.White else TvColors.TextSecondary
                                        )
                                        if (itemCount > 0) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = if (isSelected) TvColors.FocusElectricBlue else TvColors.SurfaceContainer,
                                                modifier = Modifier.padding(start = 2.dp)
                                            ) {
                                                Text(
                                                    text = itemCount.toString(),
                                                    style = TvTypography.Caption.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2.2 Content Grid or Empty State
                    if (items.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (searchQuery.isNotBlank()) Icons.Default.Search else Icons.AutoMirrored.Filled.List,
                                    contentDescription = null,
                                    tint = TvColors.TextSecondary,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No matching titles"
                                    else "No titles in ${currentPage?.title?.toDisplayString() ?: "this list"}",
                                    style = TvTypography.Section.copy(color = Color.White),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank())
                                        "No titles match '$searchQuery' in this category."
                                    else if (currentApiName == "Local")
                                        "Bookmark movies and TV series to organize your personal library."
                                    else
                                        "No items synchronized for this category from $currentApiName.",
                                    style = TvTypography.Caption.copy(color = TvColors.TextSecondary),
                                    textAlign = TextAlign.Center
                                )

                                if (searchQuery.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = { searchQuery = "" },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated)
                                    ) {
                                        Text("Clear Search", color = Color.White)
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 165.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(bottom = if (isSelectionMode) 80.dp else 32.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(items, key = { it.syncId.ifBlank { "${it.apiName}_${it.url}_${it.name}" } }) { item ->
                                val isSelected = selectedItems.contains(item)
                                LibraryPosterCard(
                                    item = item,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = isSelected,
                                    onToggleSelect = {
                                        selectedItems = if (isSelected) {
                                            selectedItems - item
                                        } else {
                                            selectedItems + item
                                        }
                                    },
                                    onClick = {
                                        handleCardClick(item)
                                    },
                                    onSearchAlternative = {
                                        navController.navigate(Screen.Search(initialQuery = item.name))
                                    },
                                    onRemoveItem = {
                                        val id = item.id ?: item.syncId.toIntOrNull()
                                        if (id != null) {
                                            DataStoreHelper.deleteBookmarkedData(id)
                                            DataStoreHelper.removeFavoritesData(id)
                                            DataStoreHelper.removeSubscribedData(id)
                                            viewModel.reloadPages(true)
                                        }
                                    },
                                    onStatusChanged = { targetWatchType ->
                                        val id = item.id ?: item.syncId.toIntOrNull()
                                        if (id != null) {
                                            if (DataStoreHelper.getBookmarkedData(id) == null) {
                                                val bData = DataStoreHelper.BookmarkedData(
                                                    bookmarkedTime = System.currentTimeMillis(),
                                                    id = id,
                                                    latestUpdatedTime = System.currentTimeMillis(),
                                                    name = item.name,
                                                    url = item.url,
                                                    apiName = item.apiName,
                                                    type = item.type,
                                                    posterUrl = item.posterUrl,
                                                    year = null,
                                                    syncData = null,
                                                    quality = item.quality,
                                                    posterHeaders = item.posterHeaders,
                                                    plot = item.plot,
                                                    score = item.score,
                                                    tags = item.tags
                                                )
                                                DataStoreHelper.setBookmarkedData(id, bData)
                                            }
                                            DataStoreHelper.setResultWatchState(id, targetWatchType.internalId)
                                            viewModel.reloadPages(true)
                                        }
                                    },
                                    onOpenWith = {
                                        openWithItem = item
                                    }
                                )
                            }
                        }
                    }
                }

                null -> {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }

        // =====================================================================
        // 3. Floating Bottom Action Bar for Bulk Selection / Deletion
        // =====================================================================
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn(tween(150)) + slideInVertically(tween(200)) { it },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .zIndex(200f)
        ) {
            Surface(
                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                color = TvColors.SurfaceElevated,
                border = BorderStroke(1.dp, TvColors.BorderSubtle),
                shadowElevation = 24.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "${selectedItems.size} selected",
                        style = TvTypography.Body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                        color = Color.White
                    )

                    // Red Delete Confirmation Button
                    Button(
                        onClick = {
                            selectedItems.forEach { item ->
                                val id = item.id ?: item.syncId.toIntOrNull()
                                if (id != null) {
                                    DataStoreHelper.deleteBookmarkedData(id)
                                    DataStoreHelper.removeFavoritesData(id)
                                    DataStoreHelper.removeSubscribedData(id)
                                }
                            }
                            selectedItems = emptySet()
                            isSelectionMode = false
                            viewModel.reloadPages(true)
                        },
                        enabled = selectedItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TvColors.ErrorRed,
                            disabledContainerColor = TvColors.ErrorRed.copy(alpha = 0.35f)
                        ),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Remove Selected (${selectedItems.size})",
                            style = TvTypography.Button.copy(color = Color.White, fontSize = 13.sp)
                        )
                    }

                    // Gray Cancel Button
                    Button(
                        onClick = {
                            isSelectionMode = false
                            selectedItems = emptySet()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TvColors.SurfaceCard
                        ),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = TvColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cancel",
                            style = TvTypography.Button.copy(color = TvColors.TextSecondary, fontSize = 13.sp)
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 4. Upstream Open-With Dialogs (Item & Provider-List Level)
        // =====================================================================
        if (openWithItem != null) {
            val targetItem = openWithItem!!
            val targetKey = targetItem.syncId.ifBlank { targetItem.id?.toString() ?: targetItem.name }
            val currentOpener = remember(targetKey) {
                getKey<LibraryOpener>("$currentAccount/$LIBRARY_FOLDER", targetKey)
            }

            LibraryOpenWithDialog(
                title = "Open With...",
                subtitle = "Choose default opener for '${targetItem.name}'",
                currentOpener = currentOpener,
                onDismiss = { openWithItem = null },
                onSelect = { newOpener ->
                    setKey("$currentAccount/$LIBRARY_FOLDER", targetKey, newOpener)
                    openWithItem = null
                }
            )
        }

        if (showListOpenerDialog && currentApiName.isNotBlank() && currentApiName != "Local") {
            val currentOpener = remember(currentApiName) {
                getKey<LibraryOpener>("$currentAccount/$LIBRARY_FOLDER", currentApiName)
            }

            LibraryOpenWithDialog(
                title = "Default Opener for $currentApiName",
                subtitle = "Choose default opener for all items in $currentApiName",
                currentOpener = currentOpener,
                onDismiss = { showListOpenerDialog = false },
                onSelect = { newOpener ->
                    setKey("$currentAccount/$LIBRARY_FOLDER", currentApiName, newOpener)
                    showListOpenerDialog = false
                }
            )
        }
    }
}

/**
 * Modal dialog for selecting default provider or opening strategy (Upstream LibraryOpener).
 */
@Composable
private fun LibraryOpenWithDialog(
    title: String,
    subtitle: String,
    currentOpener: LibraryOpener?,
    onDismiss: () -> Unit,
    onSelect: (LibraryOpener) -> Unit
) {
    val allProviders = remember {
        val baseList = if (APIHolder.allProviders.isNotEmpty()) {
            APIHolder.allProviders.toList()
        } else {
            APIHolder.apis.toList()
        }
        baseList
            .filter { it.providerType != ProviderType.MetaProvider }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceCard,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = title,
                    style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val baseOptions = listOf(
                        Triple(LibraryOpenerType.Default, "Default", "Use linked provider or search"),
                        Triple(LibraryOpenerType.Search, "Search Across Providers", "Always search all installed providers"),
                        Triple(LibraryOpenerType.Browser, "Open in Browser", "Open original URL in web browser"),
                        Triple(LibraryOpenerType.None, "None", "Do nothing on click")
                    )

                    baseOptions.forEach { (type, label, desc) ->
                        val isSelected = currentOpener?.openType == type
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = if (isSelected) TvColors.FocusElectricBlue.copy(alpha = 0.2f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                .clickable {
                                    onSelect(LibraryOpener(type, null))
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        style = TvTypography.Body.copy(
                                            color = if (isSelected) TvColors.FocusElectricBlue else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                    )
                                    Text(
                                        text = desc,
                                        style = TvTypography.Caption.copy(fontSize = 11.sp, color = TvColors.TextMuted)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = TvColors.FocusElectricBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (allProviders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "INSTALLED PROVIDERS",
                            style = TvTypography.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvColors.TextSecondary),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        allProviders.forEach { api ->
                            val isSelected = currentOpener?.openType == LibraryOpenerType.Provider &&
                                currentOpener.providerData?.apiName == api.name

                            Surface(
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                color = if (isSelected) TvColors.FocusElectricBlue.copy(alpha = 0.2f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                    .clickable {
                                        onSelect(LibraryOpener(LibraryOpenerType.Provider, ProviderLibraryData(api.name)))
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = api.name,
                                        style = TvTypography.Body.copy(
                                            color = if (isSelected) TvColors.FocusElectricBlue else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = TvColors.FocusElectricBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = TvColors.TextSecondary)
                    }
                }
            }
        }
    }
}

/**
 * Responsive Desktop Library Poster Card with mouse hover animations,
 * selection mode check overlay, episode progress indicator, and right-click context menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LibraryPosterCard(
    item: SyncAPI.LibraryItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onSearchAlternative: () -> Unit,
    onRemoveItem: () -> Unit,
    onStatusChanged: (WatchType) -> Unit,
    onOpenWith: () -> Unit,
    modifier: Modifier = Modifier,
    cardHeight: Dp = 240.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isFocused by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isHovered || isFocused) 1.05f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "LibraryCardScale"
    )

    val shape = RoundedCornerShape(TvDimensions.CornerRadiusCard)

    // Calculate episode progress if available
    val progress = remember(item.episodesCompleted, item.episodesTotal) {
        val completed = item.episodesCompleted
        val total = item.episodesTotal
        if (completed != null && total != null && total > 0) {
            (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else null
    }

    Box(
        modifier = modifier
            .scale(animatedScale)
            .zIndex(if (isHovered || isFocused) 10f else 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .hoverable(interactionSource)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable(interactionSource = interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (isSelectionMode) {
                            onToggleSelect()
                        } else {
                            onClick()
                        }
                    }
                )
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Secondary) {
                        showContextMenu = true
                    }
                }
        ) {
            // Poster Surface
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .shadow(
                        elevation = if (isHovered || isFocused) 12.dp else 2.dp,
                        shape = shape,
                        spotColor = if (isFocused) Color.White else TvColors.FocusElectricBlue
                    ),
                shape = shape,
                color = TvColors.SurfaceCard,
                border = when {
                    isSelected -> BorderStroke(2.5.dp, TvColors.FocusElectricBlue)
                    isFocused -> BorderStroke(2.5.dp, Color.White)
                    isHovered -> BorderStroke(2.dp, TvColors.FocusElectricBlue)
                    else -> BorderStroke(1.dp, TvColors.BorderSubtle)
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (!item.posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(shape)
                        )
                    } else {
                        // Monogram ground
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(TvColors.SurfaceHigh, TvColors.SurfaceCard)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.name.take(2).uppercase(),
                                style = TvTypography.Headline.copy(color = TvColors.TextMuted)
                            )
                        }
                    }

                    // Selection Mode Checkmark Badge (top-left)
                    if (isSelectionMode) {
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) TvColors.FocusElectricBlue else Color(0xAA000000))
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                                    shape = CircleShape
                                )
                                .align(Alignment.TopStart),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Rating Badge (top-right)
                    val rating = item.personalRating?.toInt(10)?.let { it.toFloat() / 10f }
                        ?: item.score?.toInt(10)?.let { it.toFloat() / 10f }
                    if (rating != null && rating > 0f) {
                        Surface(
                            shape = RoundedCornerShape(bottomStart = 8.dp),
                            color = Color(0xCC050508),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = TvColors.RatingGold,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = String.format(java.util.Locale.US, "%.1f", rating),
                                    style = TvTypography.Caption.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Watch Progress Bar at bottom of poster
                    if (progress != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(Color(0x80000000))
                                .align(Alignment.BottomCenter)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .fillMaxHeight()
                                    .background(TvColors.FocusElectricBlue)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = item.name,
                style = TvTypography.Body.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = if (isHovered || isFocused) Color.White else TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Subtitle / Episode Meta
            val subtitle = when {
                item.episodesCompleted != null && item.episodesTotal != null -> {
                    "Ep ${item.episodesCompleted} / ${item.episodesTotal}"
                }
                item.episodesCompleted != null -> {
                    "Ep ${item.episodesCompleted}"
                }
                item.type != null -> {
                    item.type?.name ?: ""
                }
                else -> ""
            }

            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = TvTypography.Caption.copy(fontSize = 11.sp),
                    color = TvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Right-Click Context Menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(TvColors.SurfaceCard)
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Details", color = Color.White, fontSize = 13.sp)
                    }
                },
                onClick = {
                    showContextMenu = false
                    onClick()
                }
            )

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open With...", color = Color.White, fontSize = 13.sp)
                    }
                },
                onClick = {
                    showContextMenu = false
                    onOpenWith()
                }
            )

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = TvColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Across Providers", color = Color.White, fontSize = 13.sp)
                    }
                },
                onClick = {
                    showContextMenu = false
                    onSearchAlternative()
                }
            )

            if (item.url.isNotBlank() && (item.url.startsWith("http://") || item.url.startsWith("https://"))) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = TvColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in Browser", color = Color.White, fontSize = 13.sp)
                        }
                    },
                    onClick = {
                        showContextMenu = false
                        CloudStreamApp.openBrowser(item.url)
                    }
                )
            }

            HorizontalDivider(color = TvColors.BorderSubtle)

            Text(
                text = "MOVE / CHANGE STATUS",
                style = TvTypography.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvColors.TextMuted),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            listOf(
                WatchType.WATCHING to "Watching",
                WatchType.COMPLETED to "Completed",
                WatchType.ONHOLD to "On Hold",
                WatchType.DROPPED to "Dropped",
                WatchType.PLANTOWATCH to "Plan to Watch"
            ).forEach { (type, label) ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = getTabIcon(label),
                                contentDescription = null,
                                tint = TvColors.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, color = Color.White, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        showContextMenu = false
                        onStatusChanged(type)
                    }
                )
            }

            HorizontalDivider(color = TvColors.BorderSubtle)

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = TvColors.ErrorRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Remove from Library", color = TvColors.ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                onClick = {
                    showContextMenu = false
                    onRemoveItem()
                }
            )

            HorizontalDivider(color = TvColors.BorderSubtle)

            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TvColors.TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Title", color = TvColors.TextSecondary, fontSize = 13.sp)
                    }
                },
                onClick = {
                    showContextMenu = false
                    try {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(item.name), null)
                    } catch (t: Throwable) {
                        AppLogger.w("LibraryScreen", "Failed to copy title to clipboard: ${t.message}")
                    }
                }
            )
        }
    }
}

/**
 * Backward compatibility alias for [ComposeLibraryScreen].
 */
@Composable
fun ComposeTvLibraryScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    ComposeLibraryScreen(navController = navController, modifier = modifier)
}
