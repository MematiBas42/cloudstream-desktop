package com.lagradost.cloudstream3.desktop.ui.screens.search

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusState
import com.lagradost.cloudstream3.desktop.ui.focus.RegisterTvFocusItem
import com.lagradost.cloudstream3.desktop.ui.focus.TvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.trackTvFocusBounds
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Enhanced Desktop & TV Search Result Card.
 *
 * Capabilities:
 * - Fluid spring hover scaling (1.05f) and elevation shadow
 * - 2D Spatial focus engine integration with [TvFocusManager]
 * - Multi-badge layout: Provider (Top-Left), Dub/Sub or Type (Top-Right), Score/Rating (Bottom-Right), Quality (Bottom-Left)
 * - Desktop Right-Click Context Menu (Open, Copy Title, Copy URL, Search Similar)
 * - Horizontal text marquee on active hover/focus
 * - Standard Keyboard navigation (Enter / Space to select)
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchResultCard(
    item: SearchResponse,
    modifier: Modifier = Modifier,
    cardWidth: Dp = TvDimensions.CardWidth,
    cardHeight: Dp = TvDimensions.CardHeight,
    row: Int? = null,
    col: Int? = null,
    focusManager: TvFocusManager? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSearchSimilar: ((String) -> Unit)? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    val isActive = isFocused || isHovered
    val focusState = LocalTvFocusState.current
    val coroutineScope = rememberCoroutineScope()

    if (row != null && col != null && focusManager != null) {
        RegisterTvFocusItem(
            row = row,
            col = col,
            focusManager = focusManager,
            requester = focusRequester
        )
    }

    val animatedScale by animateFloatAsState(
        targetValue = when {
            isFocused -> TvDimensions.FocusScale
            isHovered -> 1.05f
            else -> TvDimensions.UnfocusedScale
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "SearchCardScale"
    )

    val shape = RoundedCornerShape(TvDimensions.CornerRadiusCard)
    val interactionSource = remember { MutableInteractionSource() }

    // Extracted metadata
    val year = remember(item) { item.extractYear() }
    val score = remember(item) { item.extractScore() }
    val dubSubBadge = remember(item) { item.extractDubSubBadge() }
    val qualityName = remember(item) { item.quality?.name }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(cardWidth)
                .scale(animatedScale)
                .zIndex(if (isActive) 10f else 1f)
                .onPointerEvent(PointerEventType.Enter) { isHovered = true }
                .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    isFocused = state.isFocused
                    if (state.isFocused && row != null && col != null && focusManager != null) {
                        focusManager.notifyFocused(row, col)
                    }
                }
                .trackTvFocusBounds(
                    isFocused = isFocused,
                    focusState = focusState,
                    cornerRadius = TvDimensions.CornerRadiusCard.value,
                    coroutineScope = coroutineScope
                )
                .focusable(interactionSource = interactionSource)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
                    ) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.button == PointerButton.Secondary) {
                        showContextMenu = true
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
        ) {
            // Poster Card Container
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .shadow(
                        elevation = when {
                            isFocused -> TvDimensions.ElevationFocused
                            isHovered -> 12.dp
                            else -> TvDimensions.ElevationNormal
                        },
                        shape = shape,
                        spotColor = if (isActive) TvColors.FocusElectricBlue else TvColors.CardShadow
                    ),
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
                border = when {
                    isFocused -> BorderStroke(3.dp, Color.White)
                    isHovered -> BorderStroke(2.dp, TvColors.FocusElectricBlue)
                    else -> BorderStroke(1.dp, TvColors.BorderSubtle)
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Poster Image
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

                    // Vignette Gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xEE050508))
                                )
                            )
                    )

                    // Top-Left: Provider Badge
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = Color(0xD90F172A), // Slate 900 semi-transparent
                        border = BorderStroke(0.5.dp, Color(0x40FFFFFF))
                    ) {
                        Text(
                            text = item.apiName,
                            style = TvTypography.Badge.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = TvColors.TextPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Top-Right: Dub/Sub or TvType Badge
                    val rightBadgeText = dubSubBadge ?: item.type?.name
                    if (!rightBadgeText.isNullOrBlank()) {
                        val badgeColor = when {
                            dubSubBadge != null -> TvColors.FocusEmerald
                            item.type?.name == "Movie" -> TvColors.FocusElectricBlue
                            item.type?.name == "Anime" -> TvColors.AccentAmber
                            else -> TvColors.SurfaceElevated
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp),
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = badgeColor.copy(alpha = 0.9f)
                        ) {
                            Text(
                                text = rightBadgeText,
                                style = TvTypography.Badge.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Bottom-Right: Score / Rating Badge
                    if (score != null && score > 0f) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp),
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = Color(0xE6050508)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "★",
                                    color = TvColors.RatingGold,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = String.format(java.util.Locale.US, "%.1f", score),
                                    style = TvTypography.Badge.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Bottom-Left: Quality Badge
                    if (!qualityName.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp),
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = Color(0xCC1E293B)
                        ) {
                            Text(
                                text = qualityName,
                                style = TvTypography.Badge.copy(fontSize = 9.sp),
                                color = TvColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(TvDimensions.SpacingSmall))

            // Title with Marquee
            Text(
                text = item.name,
                style = TvTypography.Card.copy(
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = if (isActive) TvColors.TextPrimary else TvColors.TextSecondary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isActive) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 600,
                                velocity = 40.dp
                            )
                        } else {
                            Modifier
                        }
                    )
            )

            // Subtitle: Year • Type
            val subtitleParts = buildList {
                year?.let { add(it.toString()) }
                item.type?.name?.let { add(it) }
            }
            if (subtitleParts.isNotEmpty()) {
                Text(
                    text = subtitleParts.joinToString(" • "),
                    style = TvTypography.Caption.copy(fontSize = 12.sp, color = TvColors.TextMuted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Desktop Right-Click Context Menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(TvColors.SurfaceElevated)
        ) {
            DropdownMenuItem(
                text = { Text("Open Details", color = TvColors.TextPrimary) },
                leadingIcon = {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = TvColors.FocusElectricBlue,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    showContextMenu = false
                    onClick()
                }
            )

            DropdownMenuItem(
                text = { Text("Copy Title", color = TvColors.TextPrimary) },
                leadingIcon = {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = TvColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    showContextMenu = false
                    try {
                        val selection = StringSelection(item.name)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                    } catch (e: Throwable) {
                        // ignore clipboard errors
                    }
                }
            )

            DropdownMenuItem(
                text = { Text("Copy URL", color = TvColors.TextPrimary) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = TvColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    showContextMenu = false
                    try {
                        val selection = StringSelection(item.url)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                    } catch (e: Throwable) {
                        // ignore clipboard errors
                    }
                }
            )

            if (onSearchSimilar != null) {
                DropdownMenuItem(
                    text = { Text("Search on Other Providers", color = TvColors.TextPrimary) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = TvColors.FocusEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onSearchSimilar(item.name)
                    }
                )
            }
        }
    }
}
