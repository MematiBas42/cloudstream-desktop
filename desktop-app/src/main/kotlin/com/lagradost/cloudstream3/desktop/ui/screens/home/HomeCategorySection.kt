package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.components.desktopMouseSwipeable
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import kotlinx.coroutines.launch

/**
 * Desktop category row rendering HomeViewModel.ExpandableHomepageList.
 * Features:
 * - Direct consumption of ExpandableHomepageList from HomeViewModel.page
 * - Zero static caches or manual network calls in the presentation layer
 * - Mouse wheel horizontal scrolling via pointer input
 * - Pagination support via onLoadMore callback triggering viewModel.expand(name)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeCategorySection(
    category: HomeViewModel.ExpandableHomepageList,
    provider: MainAPI?,
    onViewAll: (MainAPI?, String, List<SearchResponse>) -> Unit,
    onItemClick: (MainAPI?, SearchResponse, String?) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: (() -> Unit)? = null,
) {
    val items = category.list.list
    if (items.isEmpty()) return

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Trigger pagination when user scrolls near the end of the category row
    LaunchedEffect(lazyListState.firstVisibleItemIndex, category.hasNext) {
        if (category.hasNext && items.size > 5) {
            val lastVisibleIndex = lazyListState.firstVisibleItemIndex + lazyListState.layoutInfo.visibleItemsInfo.size
            if (lastVisibleIndex >= items.size - 2) {
                onLoadMore?.invoke()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = TvDimensions.ScreenHorizontalPadding,
                vertical = 12.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = TvDimensions.SectionHeaderBottomSpacing),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = category.list.name,
                style = TvTypography.Section,
            )

            val viewAllInteraction = remember { MutableInteractionSource() }
            TextButton(
                onClick = { onViewAll(provider, category.list.name, items) },
                interactionSource = viewAllInteraction,
                modifier = Modifier.focusable(interactionSource = viewAllInteraction),
            ) {
                Text(
                    text = "View All",
                    color = TvColors.FocusElectricBlue,
                    style = TvTypography.Button,
                )
            }
        }

        LazyRow(
            state = lazyListState,
            horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
            contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .desktopMouseSwipeable(lazyListState, coroutineScope),
        ) {
            itemsIndexed(items, key = { _, item -> "${item.apiName}_${item.url}" }) { _, posterItem ->
                val subtitleText = when (posterItem) {
                    is MovieSearchResponse -> posterItem.year?.toString()
                    is TvSeriesSearchResponse -> posterItem.year?.toString()
                    else -> null
                }

                val badgeText: String? = when (posterItem) {
                    is AnimeSearchResponse -> {
                        if (posterItem.dubStatus?.contains(DubStatus.Dubbed) == true || posterItem.episodes[DubStatus.Dubbed] != null) "DUB" else null
                    }
                    else -> null
                }

                TvCard(
                    title = posterItem.name,
                    posterUrl = posterItem.posterUrl,
                    subtitle = subtitleText,
                    badge = badgeText,
                    badgeColor = TvColors.FocusEmerald,
                    onClick = { onItemClick(provider, posterItem, null) },
                )
            }

            // Load More card at the end of the row if more items are available
            if (category.hasNext) {
                item(key = "load_more_${category.list.name}") {
                    val interactionSource = remember { MutableInteractionSource() }
                    var isHovered by remember { mutableStateOf(false) }
                    var isLoading by remember { mutableStateOf(false) }

                    // Reset loading indicator when list items increase or hasNext changes
                    LaunchedEffect(category.list.list.size, category.hasNext) {
                        isLoading = false
                    }

                    Card(
                        modifier = Modifier
                            .width(TvDimensions.CardWidth)
                            .height(TvDimensions.CardHeight)
                            .clip(RoundedCornerShape(TvDimensions.CornerRadiusCard))
                            .focusable(interactionSource = interactionSource)
                            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                            .clickable(interactionSource = interactionSource, indication = null) {
                                if (!isLoading) {
                                    isLoading = true
                                    onLoadMore?.invoke()
                                }
                            }
                            .border(
                                width = if (isHovered) 2.dp else 1.dp,
                                color = if (isHovered) TvColors.FocusElectricBlue else TvColors.BorderSubtle,
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                            ),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isHovered) TvColors.SurfaceElevated else TvColors.SurfaceCard,
                        ),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = TvColors.FocusElectricBlue,
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp,
                                )
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Load More",
                                        tint = if (isHovered) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Text(
                                        text = "Load More",
                                        style = TvTypography.Button.copy(
                                            color = if (isHovered) androidx.compose.ui.graphics.Color.White else TvColors.FocusElectricBlue,
                                            fontWeight = if (isHovered) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                                        ),
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
