package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.components.desktopMouseSwipeable
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@Composable
fun HomeSearchResults(
    searchResultsGrouped: List<Pair<MainAPI, List<SearchResponse>>>?,
    isLoadingSearch: Boolean,
    onViewAll: (MainAPI, String, List<SearchResponse>) -> Unit,
    onItemClick: (MainAPI, SearchResponse, String?) -> Unit,
) {
    if (isLoadingSearch && searchResultsGrouped.isNullOrEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = TvColors.FocusElectricBlue,
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Searching across providers...",
                    style = TvTypography.Section.copy(color = TvColors.TextSecondary),
                )
            }
        }
    } else if (searchResultsGrouped != null) {
        if (searchResultsGrouped.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 80.dp,
                    bottom = 32.dp,
                    start = TvDimensions.ScreenHorizontalPadding,
                    end = TvDimensions.ScreenHorizontalPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(TvDimensions.RowSpacing),
            ) {
                items(searchResultsGrouped.size) { index ->
                    val (provider, items) = searchResultsGrouped[index]

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = TvDimensions.SectionHeaderBottomSpacing),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${provider.name} (${items.size})",
                                style = TvTypography.Section,
                            )

                            val viewAllInteraction = remember { MutableInteractionSource() }
                            TextButton(
                                onClick = { onViewAll(provider, provider.name, items) },
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

                        val searchRowState = rememberLazyListState()
                        val coroutineScope = rememberCoroutineScope()

                        LazyRow(
                            state = searchRowState,
                            horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .desktopMouseSwipeable(searchRowState, coroutineScope),
                        ) {
                            itemsIndexed(items) { colIndex, item ->
                                val subtitleText = when (item) {
                                    is MovieSearchResponse -> item.year?.toString()
                                    is TvSeriesSearchResponse -> item.year?.toString()
                                    else -> null
                                }

                                TvCard(
                                    title = item.name,
                                    posterUrl = item.posterUrl,
                                    subtitle = subtitleText,
                                    modifier = Modifier.focusable(),
                                    onClick = {
                                        onItemClick(provider, item, null)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else if (!isLoadingSearch) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No results found.",
                    style = TvTypography.Headline.copy(color = TvColors.TextMuted),
                )
            }
        }
    }
}
