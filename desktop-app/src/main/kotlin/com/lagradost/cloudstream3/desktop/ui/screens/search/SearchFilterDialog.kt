package com.lagradost.cloudstream3.desktop.ui.screens.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.SubtitleHelper

private val TV_TYPE_OPTIONS = listOf(
    "Movies" to TvType.Movie,
    "TV Series" to TvType.TvSeries,
    "Anime" to TvType.Anime,
    "Asian Drama" to TvType.AsianDrama,
    "Cartoons" to TvType.Cartoon,
    "Documentaries" to TvType.Documentary,
    "Live Streams" to TvType.Live,
    "Torrents" to TvType.Torrent
)

/**
 * Desktop Modal Dialog for Search Filters matching upstream CloudStream capabilities.
 */
@Composable
fun SearchFilterDialog(
    initialState: SearchFilterState,
    onDismiss: () -> Unit,
    onApply: (SearchFilterState) -> Unit
) {
    var filterState by remember { mutableStateOf(initialState) }
    var providerSearchQuery by remember { mutableStateOf("") }

    val allApis = remember {
        val baseList = if (APIHolder.allProviders.isNotEmpty()) {
            APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
        } else {
            APIHolder.apis.withLock { APIHolder.apis.toList() }
        }
        baseList
            .filter { it.providerType != ProviderType.MetaProvider }
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }

    val filteredApis = remember(allApis, providerSearchQuery) {
        if (providerSearchQuery.isBlank()) {
            allApis
        } else {
            allApis.filter {
                it.name.contains(providerSearchQuery, ignoreCase = true) ||
                    it.lang.contains(providerSearchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .width(720.dp)
                .heightIn(max = 680.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header: Title, Active Count Badge, Reset, Close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.FilterList,
                            contentDescription = null,
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Search Filters",
                            style = TvTypography.Section.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = TvColors.TextPrimary
                        )

                        if (filterState.hasActiveFilters) {
                            Surface(
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                                color = TvColors.FocusElectricBlue.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, TvColors.FocusElectricBlue)
                            ) {
                                Text(
                                    text = "${filterState.activeFilterCount} active",
                                    style = TvTypography.Caption.copy(
                                        color = TvColors.FocusElectricBlue,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (filterState.hasActiveFilters) {
                            TextButton(
                                onClick = {
                                    filterState = SearchFilterState()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = TvColors.TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Reset All",
                                    color = TvColors.TextSecondary,
                                    style = TvTypography.Button.copy(fontSize = 13.sp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TvColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Scrollable Content
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 1. Content Type (TvType)
                    item {
                        FilterSectionTitle("Content Type")
                        Spacer(Modifier.height(8.dp))
                        FlowChipsRow(
                            options = TV_TYPE_OPTIONS.map { (label, type) ->
                                ChipOption(
                                    id = type.name,
                                    label = label,
                                    isSelected = type in filterState.selectedTypes
                                )
                            },
                            onToggle = { id ->
                                val type = TvType.valueOf(id)
                                val newTypes = if (type in filterState.selectedTypes) {
                                    filterState.selectedTypes - type
                                } else {
                                    filterState.selectedTypes + type
                                }
                                filterState = filterState.copy(selectedTypes = newTypes)
                            }
                        )
                    }

                    // 2. Sort By
                    item {
                        FilterSectionTitle("Sort Order")
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(SearchSortBy.entries) { sortOption ->
                                val isSelected = filterState.sortBy == sortOption
                                SelectableChip(
                                    label = sortOption.label,
                                    isSelected = isSelected,
                                    onClick = {
                                        filterState = filterState.copy(sortBy = sortOption)
                                    }
                                )
                            }
                        }
                    }

                    // 3. Audio / Dubbing
                    item {
                        FilterSectionTitle("Audio / Dubbing")
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DubStatusFilter.entries) { dubOption ->
                                val isSelected = filterState.dubStatusFilter == dubOption
                                SelectableChip(
                                    label = dubOption.label,
                                    isSelected = isSelected,
                                    onClick = {
                                        filterState = filterState.copy(dubStatusFilter = dubOption)
                                    }
                                )
                            }
                        }
                    }

                    // 4. Release Year
                    item {
                        FilterSectionTitle("Release Year")
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(YEAR_OPTIONS) { year ->
                                val isSelected = (filterState.selectedYear == year) ||
                                    (filterState.selectedYear == null && year == "All")
                                SelectableChip(
                                    label = year,
                                    isSelected = isSelected,
                                    onClick = {
                                        filterState = filterState.copy(
                                            selectedYear = if (year == "All") null else year
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // 5. Genres
                    item {
                        FilterSectionTitle("Genres")
                        Spacer(Modifier.height(8.dp))
                        FlowChipsRow(
                            options = GENRE_OPTIONS.map { genre ->
                                ChipOption(
                                    id = genre,
                                    label = genre,
                                    isSelected = genre in filterState.selectedGenres
                                )
                            },
                            onToggle = { genre ->
                                val newGenres = if (genre in filterState.selectedGenres) {
                                    filterState.selectedGenres - genre
                                } else {
                                    filterState.selectedGenres + genre
                                }
                                filterState = filterState.copy(selectedGenres = newGenres)
                            }
                        )
                    }

                    // 6. Providers
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterSectionTitle("Providers (${allApis.size} installed)")

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        filterState = filterState.copy(
                                            selectedProviders = allApis.map { it.name }.toSet()
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Select All", style = TvTypography.Caption.copy(color = TvColors.FocusElectricBlue))
                                }

                                TextButton(
                                    onClick = {
                                        filterState = filterState.copy(selectedProviders = emptySet())
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Clear All", style = TvTypography.Caption.copy(color = TvColors.TextMuted))
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Search box for providers
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                            color = TvColors.SurfaceCard,
                            border = BorderStroke(1.dp, TvColors.BorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = TvColors.TextMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicTextField(
                                    value = providerSearchQuery,
                                    onValueChange = { providerSearchQuery = it },
                                    singleLine = true,
                                    textStyle = TvTypography.Caption.copy(color = TvColors.TextPrimary),
                                    cursorBrush = SolidColor(TvColors.FocusElectricBlue),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (providerSearchQuery.isEmpty()) {
                                                Text(
                                                    "Search installed providers...",
                                                    style = TvTypography.Caption.copy(color = TvColors.TextMuted)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                                if (providerSearchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { providerSearchQuery = "" },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear",
                                            tint = TvColors.TextMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Provider Chips
                        FlowChipsRow(
                            options = filteredApis.map { api ->
                                val flag = SubtitleHelper.getFlagFromIso(api.lang)?.let { "$it " } ?: ""
                                ChipOption(
                                    id = api.name,
                                    label = "$flag${api.name}",
                                    isSelected = if (filterState.selectedProviders.isEmpty()) {
                                        true // When none explicitly selected, all are active by default
                                    } else {
                                        api.name in filterState.selectedProviders
                                    }
                                )
                            },
                            onToggle = { apiName ->
                                val current = filterState.selectedProviders
                                val newProviders = if (current.isEmpty()) {
                                    // First deselect: start with all minus this one
                                    allApis.map { it.name }.toSet() - apiName
                                } else if (apiName in current) {
                                    current - apiName
                                } else {
                                    current + apiName
                                }
                                filterState = filterState.copy(selectedProviders = newProviders)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Footer: Cancel & Apply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            color = TvColors.TextSecondary,
                            style = TvTypography.Button
                        )
                    }

                    Button(
                        onClick = {
                            // Persist upstream preferences
                            DataStoreHelper.searchPreferenceTags = filterState.selectedTypes.toList()
                            DataStoreHelper.searchPreferenceProviders = filterState.selectedProviders.toList()
                            onApply(filterState)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Apply Filters",
                            style = TvTypography.Button.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(
        text = title,
        style = TvTypography.Section.copy(
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TvColors.TextSecondary
        )
    )
}

private data class ChipOption(
    val id: String,
    val label: String,
    val isSelected: Boolean
)

@Composable
private fun FlowChipsRow(
    options: List<ChipOption>,
    onToggle: (String) -> Unit
) {
    FlowChipsLayout(
        horizontalSpacing = 8.dp,
        verticalSpacing = 8.dp
    ) {
        options.forEach { opt ->
            SelectableChip(
                label = opt.label,
                isSelected = opt.isSelected,
                onClick = { onToggle(opt.id) }
            )
        }
    }
}

@Composable
private fun FlowChipsLayout(
    horizontalSpacing: androidx.compose.ui.unit.Dp,
    verticalSpacing: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier
    ) { measurables, constraints ->
        val hGap = horizontalSpacing.roundToPx()
        val vGap = verticalSpacing.roundToPx()

        var currentX = 0
        var currentY = 0
        var lineHeight = 0

        val placeables = measurables.map { measurable ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            if (currentX + placeable.width > constraints.maxWidth && currentX > 0) {
                currentX = 0
                currentY += lineHeight + vGap
                lineHeight = 0
            }
            lineHeight = maxOf(lineHeight, placeable.height)
            currentX += placeable.width + hGap
            placeable
        }

        var curX = 0
        var curY = 0
        var curLineH = 0
        placeables.forEach { p ->
            if (curX + p.width > constraints.maxWidth && curX > 0) {
                curX = 0
                curY += curLineH + vGap
                curLineH = 0
            }
            curLineH = maxOf(curLineH, p.height)
            curX += p.width + hGap
        }
        val totalHeight = curY + curLineH

        layout(constraints.maxWidth, totalHeight) {
            var x = 0
            var y = 0
            var rowH = 0
            placeables.forEach { p ->
                if (x + p.width > constraints.maxWidth && x > 0) {
                    x = 0
                    y += rowH + vGap
                    rowH = 0
                }
                p.placeRelative(x, y)
                rowH = maxOf(rowH, p.height)
                x += p.width + hGap
            }
        }
    }
}

@Composable
private fun SelectableChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(TvDimensions.CornerRadiusPill)

    val bgColor by animateColorAsState(
        targetValue = if (isSelected) TvColors.FocusElectricBlue else TvColors.SurfaceCard,
        label = "ChipBg"
    )

    Surface(
        shape = shape,
        color = bgColor,
        border = BorderStroke(
            1.dp,
            if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle
        ),
        modifier = modifier
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Text(
                text = label,
                style = TvTypography.Caption.copy(
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else TvColors.TextSecondary
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
