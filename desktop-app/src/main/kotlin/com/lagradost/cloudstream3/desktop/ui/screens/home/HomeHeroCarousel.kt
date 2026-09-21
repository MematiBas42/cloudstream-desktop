package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.desktop.ui.components.TvRatingColors
import com.lagradost.cloudstream3.desktop.ui.components.TvRatingBadge
import com.lagradost.cloudstream3.desktop.ui.components.desktopMouseSwipeable
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.TvNavAction
import com.lagradost.cloudstream3.desktop.ui.focus.interceptTvKeyNavigation
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure metadata container representing an enriched hero carousel item.
 */
data class HeroMeta(
    val backdropUrl: String?,
    val logoUrl: String?,
    val posterHeaders: Map<String, String>?,
    val tags: List<String>,
    val plot: String?,
    val score: Double?,
    val scoreText: String?,
    val year: Int?,
    val duration: Int?,
    val actors: List<ActorData>?,
    val title: String,
) {
    val chromaticRatingColor: Color?
        get() = TvRatingColors.forScore(score)
}

/**
 * Strips release tags, encoding metadata, and formatting brackets from hero titles.
 */
fun cleanHeroTitle(raw: String): String {
    val coreTitle = raw.split(Regex("[\\(\\[\\{\\|]")).firstOrNull() ?: raw
    val s = coreTitle
        .replace(Regex("\\b(WEB-DL|HDTC|HDRip|BluRay|CAM|DVDSCR)\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(?i)Anime Series$"), "")
        .trim()
    return s.ifBlank { raw }
}

/**
 * Pure helper for circular pager index arithmetic.
 */
fun getHeroRealIndex(page: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return ((page % itemCount) + itemCount) % itemCount
}

/**
 * Pure helper for boundary check when navigating Left on D-Pad.
 * At index 0, returns false indicating focus should exit to Navigation Rail / Sidebar.
 */
fun canNavigateLeftInCarousel(realIndex: Int): Boolean {
    return realIndex > 0
}

/**
 * Debounce state tracker for viewport auto-centering (upstream: HomeParentItemAdapterPreview.kt:604-616).
 */
class CarouselCenteringDebouncer(
    val debounceTimeoutMs: Long = 500L,
    private val onCenter: () -> Unit = {},
) {
    var lastFocusTimestamp: Long = 0L
        private set

    fun onFocused(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val elapsed = currentTimeMs - lastFocusTimestamp
        lastFocusTimestamp = currentTimeMs

        return if (elapsed > debounceTimeoutMs) {
            onCenter()
            true
        } else {
            false
        }
    }
}

/**
 * Representation of the Title Logo Waterfall state machine.
 */
sealed class LogoWaterfallState {
    data class ShowLogo(val url: String) : LogoWaterfallState()
    data class ShowTextTitle(val title: String) : LogoWaterfallState()
}

/**
 * Resolves the logo waterfall fallback state:
 * 1. If logoUrl is present, non-blank, and has no loading error -> ShowLogo.
 * 2. If logoUrl is null, blank, or has errored -> Fallback to ShowTextTitle.
 */
fun resolveLogoWaterfall(
    logoUrl: String?,
    title: String,
    isImageError: Boolean = false,
): LogoWaterfallState {
    return if (!logoUrl.isNullOrBlank() && !isImageError) {
        LogoWaterfallState.ShowLogo(logoUrl.trim())
    } else {
        LogoWaterfallState.ShowTextTitle(title)
    }
}

/**
 * High-Resolution Watermark Logo Composable with fallback to bold text title.
 */
@Composable
fun HeroTitleLogo(
    logoUrl: String?,
    title: String,
    headers: Map<String, String>? = null,
    modifier: Modifier = Modifier,
) {
    var isImageError by remember(logoUrl) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .heightIn(min = 40.dp, max = 72.dp)
            .widthIn(max = 380.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        val state = resolveLogoWaterfall(logoUrl, title, isImageError)
        when (state) {
            is LogoWaterfallState.ShowLogo -> {
                AsyncImage(
                    model = state.url,
                    contentDescription = "Title Logo",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onState = { imageState ->
                        when (imageState) {
                            is AsyncImagePainter.State.Error -> {
                                isImageError = true
                            }
                            is AsyncImagePainter.State.Success -> {
                                isImageError = false
                            }
                            else -> Unit
                        }
                    },
                    modifier = Modifier
                        .heightIn(max = 72.dp)
                        .widthIn(max = 380.dp),
                )
            }
            is LogoWaterfallState.ShowTextTitle -> {
                Text(
                    text = state.title,
                    style = TvTypography.Headline.copy(
                        fontSize = 32.sp,
                        shadow = Shadow(
                            color = Color(0xB0000000),
                            offset = Offset(0f, 2f),
                            blurRadius = 6f,
                        ),
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Desktop Hero Carousel consuming canonical LoadResponse directly from HomeViewModel.preview.
 * Features:
 * - Direct consumption of LoadResponse (backdrop, logo watermark, plot, score, year, duration, cast, tags)
 * - Zero manual network loops or scraping shims in the UI layer
 * - Hover pauses auto-scroll (mouse ergonomics)
 * - D-Pad spatial keyboard navigation
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeHeroCarousel(
    items: List<LoadResponse>,
    onItemClick: (LoadResponse, String?) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMore: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return

    val displayItems = remember(items) { items.take(10) }
    val maxPages = displayItems.size * 1000
    val initialPage = maxPages / 2
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { maxPages })
    val scope = rememberCoroutineScope()
    val focusManager = LocalTvFocusManager.current

    var isUserInteracting by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    var isCarouselFocused by remember { mutableStateOf(false) }
    var isWatchFocused by remember { mutableStateOf(false) }
    var isDetailsFocused by remember { mutableStateOf(false) }

    // Focus Requesters
    val prevFocusRequester = remember { FocusRequester() }
    val nextFocusRequester = remember { FocusRequester() }
    val heroCardFocusRequester = remember { FocusRequester() }
    val watchFocusRequester = remember { FocusRequester() }
    val detailsFocusRequester = remember { FocusRequester() }

    val centeringDebouncer = remember {
        CarouselCenteringDebouncer(debounceTimeoutMs = 500L) {
            AppLogger.d("HomeHeroCarousel", "Centering hero view after 500ms dwell")
        }
    }

    // Auto-Scroll Loop: Pauses on hover, focus, or user interaction; resumes when departed
    val isAnyPaused = isCarouselFocused || isWatchFocused || isDetailsFocused || isUserInteracting || isHovered
    LaunchedEffect(isAnyPaused, displayItems.size) {
        while (!isAnyPaused && displayItems.isNotEmpty()) {
            delay(6000)
            if (!pagerState.isScrollInProgress) {
                try {
                    pagerState.animateScrollToPage(
                        page = pagerState.currentPage + 1,
                        animationSpec = tween(durationMillis = 1000),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (!isActive) throw e
                }
            }
        }
    }

    // Trigger loading more hero responses if user browses near the end
    LaunchedEffect(pagerState.currentPage) {
        val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
        if (realIndex >= displayItems.size - 2) {
            onLoadMore?.invoke()
        }
    }

    val heroFade = TvColors.AmoledBackground

    DisposableEffect(focusManager) {
        focusManager.registerItem(0, 0, heroCardFocusRequester)
        onDispose {
            focusManager.unregisterItem(0, 0)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TvDimensions.HeroBannerHeight)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 0.1dp PREV FOCUS TRAP (Left Flank)
        Box(
            modifier = Modifier
                .size(0.1.dp)
                .focusRequester(prevFocusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        val currentRealIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                        if (!canNavigateLeftInCarousel(currentRealIndex)) {
                            focusManager.move(TvNavAction.LEFT)
                        } else {
                            isUserInteracting = true
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    page = pagerState.currentPage - 1,
                                    animationSpec = tween(350),
                                )
                                delay(1500)
                                isUserInteracting = false
                            }
                            heroCardFocusRequester.requestFocus()
                        }
                    }
                }
                .focusable(),
        )

        // Main Hero Preview Card
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .graphicsLayer { clip = false }
                .focusRequester(heroCardFocusRequester)
                .onFocusChanged { state ->
                    isCarouselFocused = state.isFocused
                    if (state.isFocused) {
                        centeringDebouncer.onFocused()
                        focusManager.notifyFocused(0, 0)
                    }
                }
                .focusProperties {
                    left = prevFocusRequester
                    right = nextFocusRequester
                }
                .interceptTvKeyNavigation { action ->
                    when (action) {
                        TvNavAction.RIGHT -> {
                            nextFocusRequester.requestFocus()
                            true
                        }
                        TvNavAction.LEFT -> {
                            prevFocusRequester.requestFocus()
                            true
                        }
                        TvNavAction.DOWN -> {
                            watchFocusRequester.requestFocus()
                            true
                        }
                        TvNavAction.SELECT -> {
                            val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                            val activeItem = displayItems[realIndex]
                            val backdrop = activeItem.backgroundPosterUrl ?: activeItem.posterUrl
                            onItemClick(activeItem, backdrop)
                            true
                        }
                        else -> false
                    }
                }
                .border(
                    border = if (isCarouselFocused) {
                        BorderStroke(2.dp, TvColors.FocusElectricBlue)
                    } else {
                        BorderStroke(0.dp, Color.Transparent)
                    },
                    shape = RoundedCornerShape(10.dp),
                )
                .clip(RoundedCornerShape(10.dp))
                .desktopMouseSwipeable(
                    state = pagerState,
                    coroutineScope = scope,
                    onDragStateChange = { isInteracting ->
                        isUserInteracting = isInteracting
                    },
                )
                .clickable {
                    val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                    val activeItem = displayItems[realIndex]
                    val backdrop = activeItem.backgroundPosterUrl ?: activeItem.posterUrl
                    onItemClick(activeItem, backdrop)
                }
                .focusable(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = true,
            ) { page ->
                val realIndex = getHeroRealIndex(page, displayItems.size)
                val item = displayItems[realIndex]
                val backdrop = item.backgroundPosterUrl ?: item.posterUrl

                Box(modifier = Modifier.fillMaxSize()) {
                    // Background Backdrop Poster
                    if (!backdrop.isNullOrBlank()) {
                        AsyncImage(
                            model = backdrop,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(TvColors.SurfaceCard),
                        )
                    }

                    // Horizontal and Vertical Vignette Gradients
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        heroFade.copy(alpha = 0.95f),
                                        heroFade.copy(alpha = 0.85f),
                                        heroFade.copy(alpha = 0.40f),
                                        Color.Transparent,
                                    ),
                                    startX = 0f,
                                    endX = 1400f,
                                ),
                            ),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        heroFade.copy(alpha = 0.4f),
                                        heroFade,
                                    ),
                                ),
                            ),
                    )

                    // Overlay Content with Rich Metadata
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 24.dp)
                            .widthIn(max = 680.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Type Badge
                        item.type.let { tvType ->
                            val typeLabel = when (tvType) {
                                com.lagradost.cloudstream3.TvType.Movie -> "MOVIE"
                                com.lagradost.cloudstream3.TvType.TvSeries -> "SERIES"
                                com.lagradost.cloudstream3.TvType.Anime -> "ANIME"
                                com.lagradost.cloudstream3.TvType.Live -> "LIVE"
                                else -> tvType.name.uppercase()
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                    .background(TvColors.SurfaceElevated.copy(alpha = 0.8f))
                                    .border(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = typeLabel,
                                    color = TvColors.TextPrimary,
                                    style = TvTypography.Badge,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        // Title Logo Waterfall
                        HeroTitleLogo(
                            logoUrl = item.logoUrl,
                            title = cleanHeroTitle(item.name),
                            headers = item.posterHeaders,
                        )

                        // Rating, Year & Duration
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val scoreVal = item.score?.let { s ->
                                s.toStringNull(0.1, 10, 1, false)?.toDoubleOrNull() ?: s.toDouble()
                            }
                            val scoreText = item.score?.toStringNull(0.1, 10, 1, false)

                            TvRatingBadge(
                                score = scoreVal,
                                scoreText = scoreText,
                                modifier = Modifier.padding(end = 12.dp),
                            )

                            if (item.year != null) {
                                Text(
                                    text = item.year.toString(),
                                    color = TvColors.TextSecondary,
                                    style = TvTypography.Caption,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }

                            if (item.duration != null && item.duration!! > 0) {
                                Text(
                                    text = "${item.duration}m",
                                    color = TvColors.TextSecondary,
                                    style = TvTypography.Caption,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                            }

                            // Tags / Genres preview
                            item.tags?.take(3)?.let { tags ->
                                if (tags.isNotEmpty()) {
                                    Text(
                                        text = tags.joinToString(" • "),
                                        color = TvColors.TextMuted,
                                        style = TvTypography.Caption,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        // Plot Synopsis
                        if (!item.plot.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = item.plot!!,
                                color = TvColors.TextSecondary,
                                style = TvTypography.Body,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Cast
                        item.actors?.take(4)?.map { it.actor.name }?.let { actors ->
                            if (actors.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Cast: ${actors.joinToString(", ")}",
                                    color = Color(0xFFAAAAAA),
                                    style = TvTypography.Caption,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))

                        // Action Buttons: "Watch Now" and "Details"
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val watchInteractionSource = remember { MutableInteractionSource() }

                            Button(
                                onClick = {
                                    val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                                    val activeItem = displayItems[realIndex]
                                    val backdropUrl = activeItem.backgroundPosterUrl ?: activeItem.posterUrl
                                    onItemClick(activeItem, backdropUrl)
                                },
                                interactionSource = watchInteractionSource,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isWatchFocused) TvColors.FocusElectricBlue else TvColors.FocusElectricBlue.copy(alpha = 0.85f),
                                    contentColor = Color.White,
                                ),
                                border = if (isWatchFocused) BorderStroke(3.dp, Color.White) else null,
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .focusRequester(watchFocusRequester)
                                    .onFocusChanged { isWatchFocused = it.isFocused }
                                    .scale(if (isWatchFocused) TvDimensions.FocusScale else 1.0f)
                                    .interceptTvKeyNavigation { action ->
                                        when (action) {
                                            TvNavAction.RIGHT -> {
                                                detailsFocusRequester.requestFocus()
                                                true
                                            }
                                            TvNavAction.LEFT -> {
                                                prevFocusRequester.requestFocus()
                                                true
                                            }
                                            TvNavAction.UP -> {
                                                heroCardFocusRequester.requestFocus()
                                                true
                                            }
                                            TvNavAction.DOWN -> {
                                                focusManager.move(TvNavAction.DOWN)
                                            }
                                            TvNavAction.SELECT -> {
                                                val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                                                val activeItem = displayItems[realIndex]
                                                val backdropUrl = activeItem.backgroundPosterUrl ?: activeItem.posterUrl
                                                onItemClick(activeItem, backdropUrl)
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .focusable(interactionSource = watchInteractionSource),
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Watch Now", style = TvTypography.Button)
                            }

                            val detailsInteractionSource = remember { MutableInteractionSource() }

                            OutlinedButton(
                                onClick = {
                                    val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                                    val activeItem = displayItems[realIndex]
                                    val backdropUrl = activeItem.backgroundPosterUrl ?: activeItem.posterUrl
                                    onItemClick(activeItem, backdropUrl)
                                },
                                interactionSource = detailsInteractionSource,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isDetailsFocused) TvColors.SurfaceElevated else Color.Transparent,
                                    contentColor = Color.White,
                                ),
                                border = BorderStroke(
                                    if (isDetailsFocused) 3.dp else 1.dp,
                                    if (isDetailsFocused) Color.White else Color.White.copy(alpha = 0.5f),
                                ),
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .focusRequester(detailsFocusRequester)
                                    .onFocusChanged { isDetailsFocused = it.isFocused }
                                    .scale(if (isDetailsFocused) TvDimensions.FocusScale else 1.0f)
                                    .interceptTvKeyNavigation { action ->
                                        when (action) {
                                            TvNavAction.LEFT -> {
                                                watchFocusRequester.requestFocus()
                                                true
                                            }
                                            TvNavAction.RIGHT -> {
                                                nextFocusRequester.requestFocus()
                                                true
                                            }
                                            TvNavAction.UP -> {
                                                heroCardFocusRequester.requestFocus()
                                                true
                                            }
                                            TvNavAction.DOWN -> {
                                                focusManager.move(TvNavAction.DOWN)
                                            }
                                            TvNavAction.SELECT -> {
                                                val realIndex = getHeroRealIndex(pagerState.currentPage, displayItems.size)
                                                val activeItem = displayItems[realIndex]
                                                val backdropUrl = activeItem.backgroundPosterUrl ?: activeItem.posterUrl
                                                onItemClick(activeItem, backdropUrl)
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .focusable(interactionSource = detailsInteractionSource),
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Details", style = TvTypography.Button)
                            }
                        }
                    }
                }
            }

            // Pager Position Indicators
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = TvDimensions.ScreenHorizontalPadding, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(displayItems.size) { index ->
                    val isSelected = getHeroRealIndex(pagerState.currentPage, displayItems.size) == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) TvColors.FocusElectricBlue else Color.White.copy(alpha = 0.3f),
                            ),
                    )
                }
            }
        }

        // 0.1dp NEXT FOCUS TRAP (Right Flank)
        Box(
            modifier = Modifier
                .size(0.1.dp)
                .focusRequester(nextFocusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        isUserInteracting = true
                        scope.launch {
                            pagerState.animateScrollToPage(
                                page = pagerState.currentPage + 1,
                                animationSpec = tween(350),
                            )
                            delay(1500)
                            isUserInteracting = false
                        }
                        heroCardFocusRequester.requestFocus()
                    }
                }
                .focusable(),
        )
    }
}
