package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.components.desktopMouseSwipeable
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import kotlinx.coroutines.launch

/**
 * Desktop Continue Watching row rendering DataStoreHelper.ResumeWatchingResult items.
 * Features:
 * - Circular watch progress indicator matching upstream CloudStream Android
 * - S{season}E{episode} • {remaining} min left rich subtitle format
 * - Quick 1-click play button overlay on card
 * - Right-click context menu (Play, Show Details, Mark as Watched, Remove from Continue Watching)
 * - Clear all history hook connected to HomeViewModel.deleteResumeWatching()
 * - Smooth mouse wheel horizontal scrolling
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeHistoryRow(
    resumeWatching: List<DataStoreHelper.ResumeWatchingResult>,
    onClearHistory: () -> Unit,
    onRemoveItem: (DataStoreHelper.ResumeWatchingResult) -> Unit,
    onMarkAsWatched: (DataStoreHelper.ResumeWatchingResult) -> Unit,
    onItemClick: (DataStoreHelper.ResumeWatchingResult) -> Unit,
    onPlayClick: (DataStoreHelper.ResumeWatchingResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (resumeWatching.isEmpty()) return

    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
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
            Text(
                text = "Continue Watching",
                style = TvTypography.Section,
            )

            val clearInteractionSource = remember { MutableInteractionSource() }
            TextButton(
                onClick = onClearHistory,
                interactionSource = clearInteractionSource,
                modifier = Modifier.focusable(interactionSource = clearInteractionSource),
            ) {
                Text("Clear History", color = TvColors.TextMuted, style = TvTypography.Caption)
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
            itemsIndexed(resumeWatching, key = { _, item -> "${item.apiName}_${item.parentId}_${item.id}" }) { _, item ->
                var showContextMenu by remember { mutableStateOf(false) }

                val origPos = item.watchPos?.position ?: 0L
                val origDur = item.watchPos?.duration ?: 0L
                val progress = if (origDur > 0L && origPos > 0L) {
                    (origPos.toFloat() / origDur.toFloat()).coerceIn(0f, 1f)
                } else null

                val remainingMs = if (origDur > 0L && origPos > 0L) {
                    maxOf(0L, origDur - origPos)
                } else 0L
                val remainingMin = (remainingMs / 60_000L).toInt()

                val epText = when {
                    item.season != null && item.episode != null -> "S${item.season}E${item.episode}"
                    item.episode != null -> "Episode ${item.episode}"
                    else -> null
                }
                val remainingText = if (remainingMin > 0) "$remainingMin min left" else null
                val subtitleText = listOfNotNull(epText, remainingText).joinToString(" • ").ifBlank { null }

                Box {
                    TvCard(
                        title = item.name,
                        subtitle = subtitleText,
                        posterUrl = item.posterUrl,
                        progress = progress,
                        badge = if (item.isFromDownload) "DOWNLOADED" else null,
                        badgeColor = TvColors.FocusElectricBlue,
                        onPlayClick = { onPlayClick(item) },
                        onClick = { onItemClick(item) },
                        modifier = Modifier
                            .onClick(
                                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                                onClick = { showContextMenu = true }
                            )
                    )

                    // Right-Click Context Menu for Desktop Mouse Ergonomics
                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false },
                        modifier = Modifier.background(TvColors.SurfaceElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Play", style = TvTypography.Body) },
                            leadingIcon = {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TvColors.FocusElectricBlue)
                            },
                            onClick = {
                                showContextMenu = false
                                onPlayClick(item)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Show Details", style = TvTypography.Body) },
                            leadingIcon = {
                                Icon(Icons.Default.Info, contentDescription = null, tint = TvColors.TextSecondary)
                            },
                            onClick = {
                                showContextMenu = false
                                onItemClick(item)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mark as Watched", style = TvTypography.Body) },
                            leadingIcon = {
                                Icon(Icons.Default.Check, contentDescription = null, tint = TvColors.FocusEmerald)
                            },
                            onClick = {
                                showContextMenu = false
                                onMarkAsWatched(item)
                            }
                        )
                        HorizontalDivider(color = TvColors.BorderSubtle)
                        DropdownMenuItem(
                            text = { Text("Remove from Continue Watching", style = TvTypography.Body.copy(color = TvColors.ErrorRed)) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = TvColors.ErrorRed)
                            },
                            onClick = {
                                showContextMenu = false
                                onRemoveItem(item)
                            }
                        )
                    }
                }
            }
        }
    }
}
