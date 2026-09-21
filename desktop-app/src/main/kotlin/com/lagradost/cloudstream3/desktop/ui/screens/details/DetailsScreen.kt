package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.focus.TvNavAction
import com.lagradost.cloudstream3.desktop.ui.focus.interceptTvKeyNavigation
import com.lagradost.cloudstream3.desktop.ui.navigation.LocalVideoPlayer
import android.content.DesktopContextProvider
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.navigation.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.screens.links.LinksSidePanel
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.common.storage.*
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Pure calculation engine for 20% Y-axis offset crop matching upstream PercentageCropImageView.kt lines 82-111.
 */
object PercentageCropMath {
    fun computeScale(srcWidth: Float, srcHeight: Float, vWidth: Float, vHeight: Float): Float {
        if (srcWidth <= 0f || srcHeight <= 0f || vWidth <= 0f || vHeight <= 0f) return 1f
        val widthScale = vWidth / srcWidth
        val heightScale = vHeight / srcHeight
        return max(widthScale, heightScale)
    }

    fun computeDy(srcHeight: Float, vHeight: Float, scale: Float, cropYCenterOffsetPct: Float = 0.20f): Float {
        val scaledHeight = srcHeight * scale
        val verticalOverflow = vHeight - scaledHeight
        return verticalOverflow * cropYCenterOffsetPct
    }

    fun computeDx(srcWidth: Float, vWidth: Float, scale: Float, cropXCenterOffsetPct: Float = 0.50f): Float {
        val scaledWidth = srcWidth * scale
        val horizontalOverflow = vWidth - scaledWidth
        return horizontalOverflow * cropXCenterOffsetPct
    }
}

/**
 * Custom ContentScale implementing the exact 20% Y-axis offset crop
 * defined in upstream PercentageCropImageView.kt lines 82-111.
 */
class PercentageCropContentScale(
    val cropYCenterOffsetPct: Float = 0.20f,
    val cropXCenterOffsetPct: Float = 0.50f,
) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): androidx.compose.ui.layout.ScaleFactor {
        val scale = PercentageCropMath.computeScale(srcSize.width, srcSize.height, dstSize.width, dstSize.height)
        return androidx.compose.ui.layout.ScaleFactor(scale, scale)
    }
}

/**
 * Alignment companion for PercentageCropContentScale that translates the scaled image
 * to match the 20% vertical crop offset.
 */
fun percentageCropAlignment(
    cropYCenterOffsetPct: Float = 0.20f,
    cropXCenterOffsetPct: Float = 0.50f,
): Alignment {
    return Alignment { size, space, _ ->
        val scale = PercentageCropMath.computeScale(
            size.width.toFloat(), size.height.toFloat(),
            space.width.toFloat(), space.height.toFloat(),
        )
        val dx = PercentageCropMath.computeDx(size.width.toFloat(), space.width.toFloat(), scale, cropXCenterOffsetPct)
        val dy = PercentageCropMath.computeDy(size.height.toFloat(), space.height.toFloat(), scale, cropYCenterOffsetPct)
        IntOffset(dx.toInt(), dy.toInt())
    }
}

@Composable
fun ComposeDetailsScreen(
    navController: NavController,
    provider: MainAPI,
    url: String,
    preloadedName: String? = null,
    preloadedPoster: String? = null,
    preloadedBg: String? = null,
    modifier: Modifier = Modifier,
) {
    // 1. ViewModels
    val resultViewModel = remember(url) {
        ResultViewModel2().apply {
            load(
                activity = null,
                url = url,
                apiName = provider.name,
                showFillers = true,
                dubStatus = DubStatus.None,
                autostart = null,
            )
        }
    }

    val syncViewModel = remember(url) {
        SyncViewModel().apply {
            addFromUrl(url)
        }
    }

    DisposableEffect(syncViewModel) {
        onDispose {
            syncViewModel.onCleared()
        }
    }

    // 2. States from ResultViewModel2
    val pageState by resultViewModel.page.collectAsState()
    val trailers by resultViewModel.trailers.collectAsState()
    val recommendations by resultViewModel.recommendations.collectAsState()
    val episodesResource by resultViewModel.episodes.collectAsState()
    val movieResource by resultViewModel.movie.collectAsState()

    // Slide-in Links Side Panel State
    var activeLinkData by remember { mutableStateOf<Triple<MainAPI, String, WatchHistory>?>(null) }
    var isPanelOpen by remember { mutableStateOf(false) }

    // Dialog Event Pattern: observe SharedFlow<DetailsDialogEvent>
    var currentDialogEvent by remember { mutableStateOf<DetailsDialogEvent?>(null) }
    LaunchedEffect(resultViewModel) {
        merge(resultViewModel.dialogEvent, DetailsDialogEvent.dialogEvents).collect { event ->
            currentDialogEvent = event
        }
    }

    // Sync Data sync whenever page successfully loads
    LaunchedEffect(pageState) {
        val data = (pageState as? Resource.Success)?.value
        if (data != null && data.syncData.isNotEmpty()) {
            syncViewModel.addSyncs(data.syncData)
            syncViewModel.updateSynced()
            syncViewModel.updateMetaAndUser()
        }
    }

    val ctx = CloudStreamApp.context ?: android.content.Context()
    val videoPlayer = LocalVideoPlayer.current

    // Upstream 1:1 Parity: Direct player launch on click with in-player stream resolution
    val handleDirectPlay: (Triple<MainAPI, String, WatchHistory>) -> Unit = { (playProvider, playData, playHist) ->
        val currentEpisodes = (episodesResource as? com.lagradost.cloudstream3.mvvm.Resource.Success)?.value ?: emptyList()
        val epIndex = currentEpisodes.indexOfFirst { it.data == playData }.coerceAtLeast(0)
        val showTitle = (pageState as? Resource.Success)?.value?.title ?: preloadedName

        val launch = VideoLaunchData(
            links = emptyList(), // Resolved inside EmbeddedPlayerView matching upstream GeneratorPlayer
            initialIndex = 0,
            title = playHist.title ?: showTitle,
            subtitles = emptyList(),
            startPositionMs = playHist.position,
            history = playHist,
            episodes = currentEpisodes,
            currentEpisodeIndex = epIndex,
            provider = playProvider,
            dataUrl = playData,
            onNextEpisode = {
                val nextIdx = epIndex + 1
                if (nextIdx < currentEpisodes.size) {
                    val nextEp = currentEpisodes[nextIdx]
                    val nextHist = playHist.copy(
                        episodeId = nextEp.data,
                        episode = nextEp.episode,
                        season = nextEp.season,
                        title = nextEp.name ?: showTitle,
                        position = 0L
                    )
                    videoPlayer.invoke(
                        VideoLaunchData(
                            links = emptyList(),
                            history = nextHist,
                            episodes = currentEpisodes,
                            currentEpisodeIndex = nextIdx,
                            provider = playProvider,
                            dataUrl = nextEp.data,
                            title = nextEp.name ?: showTitle
                        )
                    )
                }
            }
        )
        videoPlayer.invoke(launch)
    }

    Box(modifier = modifier.fillMaxSize().background(TvColors.AmoledBackground)) {
        when (val page = pageState) {
            is Resource.Loading -> {
                // Skeleton Screen / Preloaded Header state while fetching
                DetailsMainContent(
                    navController = navController,
                    provider = provider,
                    url = url,
                    resultData = null,
                    preloadedName = preloadedName,
                    preloadedPoster = preloadedPoster,
                    preloadedBg = preloadedBg,
                    resultViewModel = resultViewModel,
                    syncViewModel = syncViewModel,
                    trailers = emptyList(),
                    recommendations = emptyList(),
                    episodesResource = null,
                    movieResource = null,
                    onPlay = handleDirectPlay,
                )

                // Indeterminate Loading Bar
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = TvColors.FocusElectricBlue,
                        strokeWidth = 3.dp,
                    )
                }
            }
            is Resource.Failure -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = page.errorString ?: "Failed to load details.",
                            color = TvColors.ErrorRed,
                            style = TvTypography.Headline,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { navController.goBack() },
                            modifier = Modifier.focusable(),
                            colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        ) {
                            Text("Go Back", style = TvTypography.Button)
                        }
                    }
                }
            }
            is Resource.Success -> {
                val resultData = page.value
                DetailsMainContent(
                    navController = navController,
                    provider = provider,
                    url = url,
                    resultData = resultData,
                    preloadedName = preloadedName,
                    preloadedPoster = preloadedPoster,
                    preloadedBg = preloadedBg,
                    resultViewModel = resultViewModel,
                    syncViewModel = syncViewModel,
                    trailers = trailers,
                    recommendations = recommendations,
                    episodesResource = episodesResource,
                    movieResource = movieResource,
                    onPlay = handleDirectPlay,
                )
            }
            null -> {}
        }

        // Dim Overlay when Links Panel is Open
        AnimatedVisibility(
            visible = isPanelOpen,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isPanelOpen = false },
            )
        }

        // Slide-in Side Drawer for Links
        if (activeLinkData != null) {
            val offsetX by animateDpAsState(
                targetValue = if (isPanelOpen) 0.dp else 480.dp,
                animationSpec = tween(300),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(480.dp)
                    .offset { IntOffset(offsetX.roundToPx(), 0) },
            ) {
                val (playProvider, playData, playHist) = activeLinkData!!
                val currentEpisodes = (episodesResource as? com.lagradost.cloudstream3.mvvm.Resource.Success)?.value ?: emptyList()
                LinksSidePanel(
                    provider = playProvider,
                    dataUrl = playData,
                    history = playHist,
                    episodes = currentEpisodes,
                    onClose = { isPanelOpen = false },
                )
            }
        }

        // Dialog Event Pattern Modal
        currentDialogEvent?.let { event ->
            when (event) {
                is DetailsDialogEvent.SelectPopupDialog -> {
                    val popup = event.popup
                    Dialog(onDismissRequest = {
                        popup.callback(null)
                        currentDialogEvent = null
                    }) {
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                            color = TvColors.SurfaceCard,
                            border = BorderStroke(1.dp, TvColors.BorderSubtle),
                            modifier = Modifier
                                .width(400.dp)
                                .padding(16.dp),
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                val headerText = when (popup) {
                                    is SelectPopup.SelectText -> popup.text.asString(ctx)
                                    is SelectPopup.SelectArray -> popup.text.asString(ctx)
                                }
                                Text(
                                    text = headerText,
                                    style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 14.dp),
                                )
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.heightIn(max = 320.dp),
                                ) {
                                    val options = when (popup) {
                                        is SelectPopup.SelectText -> popup.options.map { it.asString(ctx) }
                                        is SelectPopup.SelectArray -> popup.options.map { it.first.asString(ctx) }
                                    }
                                    itemsIndexed(options) { index, optionName ->
                                        Surface(
                                            onClick = {
                                                popup.callback(index)
                                                currentDialogEvent = null
                                            },
                                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                            color = TvColors.SurfaceHigh,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = optionName,
                                                style = TvTypography.Button.copy(fontSize = 14.sp),
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        popup.callback(null)
                                        currentDialogEvent = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceHigh),
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text("Cancel", style = TvTypography.Button)
                                }
                            }
                        }
                    }
                }
                is DetailsDialogEvent.DuplicateWarning -> {
                    Dialog(onDismissRequest = {
                        event.callback(false, emptyList())
                        currentDialogEvent = null
                    }) {
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                            color = TvColors.SurfaceCard,
                            border = BorderStroke(1.dp, TvColors.BorderSubtle),
                            modifier = Modifier
                                .width(420.dp)
                                .padding(20.dp),
                        ) {
                            Column {
                                Text(
                                    text = ctx.getString(event.titleRes),
                                    style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                Text(
                                    text = event.message,
                                    style = TvTypography.Caption.copy(fontSize = 13.sp, color = TvColors.TextSecondary),
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    Button(
                                        onClick = {
                                            event.callback(false, emptyList())
                                            currentDialogEvent = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceHigh),
                                    ) {
                                        Text("Cancel", style = TvTypography.Button)
                                    }
                                    Button(
                                        onClick = {
                                            event.callback(true, event.duplicateIds)
                                            currentDialogEvent = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed),
                                    ) {
                                        Text("Replace", style = TvTypography.Button)
                                    }
                                    Button(
                                        onClick = {
                                            event.callback(true, emptyList())
                                            currentDialogEvent = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                                    ) {
                                        Text("Add", style = TvTypography.Button)
                                    }
                                }
                            }
                        }
                    }
                }
                is DetailsDialogEvent.SelectFcastDevice -> {
                    Dialog(onDismissRequest = {
                        event.callback(null)
                        currentDialogEvent = null
                    }) {
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                            color = TvColors.SurfaceCard,
                            border = BorderStroke(1.dp, TvColors.BorderSubtle),
                            modifier = Modifier
                                .width(420.dp)
                                .padding(16.dp),
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    text = event.title,
                                    style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(bottom = 14.dp),
                                )
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.heightIn(max = 320.dp),
                                ) {
                                    itemsIndexed(event.devices) { _, device ->
                                        Surface(
                                            onClick = {
                                                event.callback(device)
                                                currentDialogEvent = null
                                            },
                                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                            color = TvColors.SurfaceHigh,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                text = device.name,
                                                style = TvTypography.Button.copy(fontSize = 14.sp),
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        event.callback(null)
                                        currentDialogEvent = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceHigh),
                                    modifier = Modifier.align(Alignment.End),
                                ) {
                                    Text("Cancel", style = TvTypography.Button)
                                }
                            }
                        }
                    }
                }
                null -> {}
            }
        }
    }
}

@Composable
fun DetailsMainContent(
    navController: NavController,
    provider: MainAPI,
    url: String,
    resultData: ResultData?,
    preloadedName: String?,
    preloadedPoster: String?,
    preloadedBg: String?,
    resultViewModel: ResultViewModel2,
    syncViewModel: SyncViewModel,
    trailers: List<ExtractedTrailerData>,
    recommendations: List<SearchResponse>,
    episodesResource: Resource<List<ResultEpisode>>?,
    movieResource: Resource<Pair<com.lagradost.cloudstream3.utils.UiText, ResultEpisode>>?,
    onPlay: (Triple<MainAPI, String, WatchHistory>) -> Unit,
) {
    val historyUpdatesVal = DesktopDataStore.historyUpdates.collectAsState().value
    val latestHistory = remember(url, historyUpdatesVal) {
        DesktopDataStore.getLatestWatchHistoryForShow(url)
    }

    // Library Watch State & Favorites / Subscriptions (Upstream 1:1 ResultViewModel2 Parity)
    val vmFavoriteStatus by resultViewModel.favoriteStatus.collectAsState()
    val vmWatchStatus by resultViewModel.watchStatus.collectAsState()
    val vmSubscribeStatus by resultViewModel.subscribeStatus.collectAsState()

    val isFavorite = vmFavoriteStatus == true
    val watchType = vmWatchStatus
    val isSubscribed = vmSubscribeStatus == true

    // Sync State
    val syncedList by syncViewModel.synced.collectAsState()
    val syncUserData by syncViewModel.userData.collectAsState()

    var showWatchTypeDialog by remember { mutableStateOf(false) }
    var showSynopsisDialog by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var showTrailerDialog by remember { mutableStateOf(false) }

    val loadResponse = resultViewModel.currentResponse
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .interceptTvKeyNavigation { action ->
                if (action == TvNavAction.BACK) {
                    when {
                        showWatchTypeDialog -> {
                            showWatchTypeDialog = false
                            true
                        }
                        showSynopsisDialog -> {
                            showSynopsisDialog = false
                            true
                        }
                        showSyncDialog -> {
                            showSyncDialog = false
                            true
                        }
                        showTrailerDialog -> {
                            showTrailerDialog = false
                            true
                        }
                        else -> {
                            navController.goBack()
                            true
                        }
                    }
                } else {
                    false
                }
            },
    ) {
        // Layer 0: Backdrop
        val bgPoster = resultData?.backgroundPosterUrl ?: preloadedBg ?: resultData?.posterBackgroundImage ?: preloadedPoster
        if (bgPoster != null) {
            AsyncImage(
                model = bgPoster,
                contentDescription = null,
                contentScale = PercentageCropContentScale(cropYCenterOffsetPct = 0.20f),
                alignment = percentageCropAlignment(cropYCenterOffsetPct = 0.20f),
                modifier = Modifier
                    .fillMaxSize()
                    .run {
                        if (resultData?.backgroundPosterUrl == null) this.blur(32.dp) else this
                    },
            )
        }

        // Layer 0b: Scrims
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to TvColors.AmoledBackground.copy(alpha = 0.95f),
                            0.35f to TvColors.AmoledBackground.copy(alpha = 0.85f),
                            0.60f to TvColors.AmoledBackground.copy(alpha = 0.40f),
                            1.00f to Color.Transparent,
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            1.00f to TvColors.AmoledBackground,
                        ),
                    ),
                ),
        )

        // Layer 1: Single-Column Vertical Scroll Flow (Laptop/Desktop Ergonomic Layout)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp),
        ) {
            // 1. Top Section: Info, Actions, Synopsis, Cast
            val title = resultData?.title ?: preloadedName ?: "Loading..."
            val logo = resultData?.logoUrl
            val plot = resultData?.plotText?.asStringNull(CloudStreamApp.context)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 28.dp),
            ) {
                // Watermark Logo or Title
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .heightIn(max = 76.dp)
                            .padding(bottom = 12.dp),
                    )
                } else {
                    Text(
                        text = title,
                        style = TvTypography.Headline.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                // Next Episode countdown if available
                val nextAiring = resultData?.nextAiringDate?.asStringNull(CloudStreamApp.context)
                if (nextAiring != null) {
                    Surface(
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = TvColors.FocusElectricBlue.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f)),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                tint = TvColors.FocusElectricBlue,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Next: $nextAiring",
                                style = TvTypography.Caption.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = TvColors.FocusElectricBlue,
                                ),
                            )
                        }
                    }
                }

                // Sync Badges (AniList, MAL, Simkl, Kitsu)
                if (syncedList.any { it.isSynced }) {
                    DetailsSyncBadges(
                        syncedList = syncedList,
                        syncUserData = syncUserData,
                        onClick = { showSyncDialog = true },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                // Circular Resume Card
                if (latestHistory != null && latestHistory.duration > 0) {
                    val progressFraction = (latestHistory.position.toFloat() / latestHistory.duration.toFloat()).coerceIn(0f, 1f)
                    val remainingMins = ((latestHistory.duration - latestHistory.position) / 60_000L).coerceAtLeast(1)

                    Surface(
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                        color = TvColors.SurfaceCard.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .clickable {
                                val resumeEp = provider.newEpisode(latestHistory.episodeId ?: latestHistory.url) {
                                    name = latestHistory.showName
                                    season = latestHistory.season
                                    episode = latestHistory.episode
                                    posterUrl = latestHistory.posterUrl
                                }
                                onPlay(Triple(provider, resumeEp.data, latestHistory))
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { progressFraction },
                                    color = Color(0xFFE50914),
                                    trackColor = TvColors.SurfaceContainer,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp),
                                )
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = latestHistory.episode?.let { "S${latestHistory.season ?: 1}E$it" } ?: "Resume",
                                    style = TvTypography.Button.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                )
                                Text(
                                    text = "${remainingMins}m remaining",
                                    style = TvTypography.Caption.copy(fontSize = 11.sp, color = TvColors.TextSecondary),
                                )
                            }
                        }
                    }
                }

                // Action Buttons Row (Play, Resume, Trailer, Bookmark, Favorite, Subscribe, Sync, Search)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                ) {
                    // Play Button - Supports all media types!
                    TvActionButton(
                        icon = Icons.Default.PlayArrow,
                        label = "Play",
                        onClick = {
                            when (loadResponse) {
                                is MovieLoadResponse -> {
                                    val parentId = DesktopDataStore.watchHistoryId(provider.name, url)
                                    val hist = WatchHistory(
                                        parentId = parentId,
                                        showName = title,
                                        showUrl = url,
                                        apiName = provider.name,
                                        posterUrl = resultData?.posterImage ?: preloadedPoster,
                                        episode = null,
                                        season = null,
                                        episodeId = loadResponse.dataUrl,
                                        position = 0L,
                                        duration = 0L,
                                    )
                                    onPlay(Triple(provider, loadResponse.dataUrl, hist))
                                }
                                is TorrentLoadResponse -> {
                                    val torrentUrl = loadResponse.torrent ?: loadResponse.magnet ?: url
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val parentId = DesktopDataStore.watchHistoryId(provider.name, url)
                                        val streamUrl = try {
                                            val (transformed, _) = com.lagradost.cloudstream3.ui.player.Torrent.transformLink(
                                                com.lagradost.cloudstream3.utils.newExtractorLink(
                                                    source = provider.name,
                                                    name = title,
                                                    url = torrentUrl,
                                                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.TORRENT
                                                )
                                            )
                                            transformed.url
                                        } catch (t: Throwable) {
                                            com.lagradost.cloudstream3.mvvm.logError(t)
                                            torrentUrl
                                        }
                                        val hist = WatchHistory(
                                            parentId = parentId,
                                            showName = title,
                                            showUrl = url,
                                            apiName = provider.name,
                                            posterUrl = resultData?.posterImage ?: preloadedPoster,
                                            episode = null,
                                            season = null,
                                            episodeId = streamUrl,
                                            position = 0L,
                                            duration = 0L,
                                        )
                                        withContext(Dispatchers.Main) {
                                            onPlay(Triple(provider, streamUrl, hist))
                                        }
                                    }
                                }
                                is LiveStreamLoadResponse -> {
                                    val streamUrl = loadResponse.dataUrl
                                    val parentId = DesktopDataStore.watchHistoryId(provider.name, url)
                                    val hist = WatchHistory(
                                        parentId = parentId,
                                        showName = title,
                                        showUrl = url,
                                        apiName = provider.name,
                                        posterUrl = resultData?.posterImage ?: preloadedPoster,
                                        episode = null,
                                        season = null,
                                        episodeId = streamUrl,
                                        position = 0L,
                                        duration = 0L,
                                    )
                                    onPlay(Triple(provider, streamUrl, hist))
                                }
                                else -> {
                                    // TV series or Anime
                                    val eps = (episodesResource as? Resource.Success)?.value
                                    val targetEp = if (latestHistory?.episodeId != null) {
                                        eps?.find { it.data == latestHistory.episodeId } ?: eps?.firstOrNull()
                                    } else {
                                        eps?.firstOrNull()
                                    }
                                    if (targetEp != null) {
                                        navigateToPlay(
                                            provider = provider,
                                            dataUrl = url,
                                            showName = title,
                                            showPoster = resultData?.posterImage ?: preloadedPoster,
                                            ep = targetEp,
                                            onPlay = onPlay,
                                        )
                                    } else {
                                        // Movie fallback via movieResource
                                        val movieEp = (movieResource as? Resource.Success)?.value?.second
                                        if (movieEp != null) {
                                            navigateToPlay(
                                                provider = provider,
                                                dataUrl = url,
                                                showName = title,
                                                showPoster = resultData?.posterImage ?: preloadedPoster,
                                                ep = movieEp,
                                                onPlay = onPlay,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        isPrimary = true,
                    )

                    // Resume Button
                    if (latestHistory != null && latestHistory.position > 0) {
                        TvActionButton(
                            icon = Icons.Default.PlayArrow,
                            label = "Resume",
                            onClick = {
                                val resumeEp = provider.newEpisode(latestHistory.episodeId ?: latestHistory.url) {
                                    name = latestHistory.showName
                                    season = latestHistory.season
                                    episode = latestHistory.episode
                                    posterUrl = latestHistory.posterUrl
                                }
                                onPlay(Triple(provider, resumeEp.data, latestHistory))
                            },
                            isActive = true,
                        )
                    }

                    // Trailer Button
                    if (trailers.isNotEmpty()) {
                        val allMirrors = trailers.flatMap { it.mirros }
                        TvActionButton(
                            icon = Icons.Default.PlayArrow,
                            label = "Trailer",
                            onClick = {
                                if (allMirrors.size > 1) {
                                    showTrailerDialog = true
                                } else {
                                    val firstLink = allMirrors.firstOrNull()?.first
                                    if (firstLink != null) {
                                        val parentId = DesktopDataStore.watchHistoryId(provider.name, url)
                                        val hist = WatchHistory(
                                            parentId = parentId,
                                            showName = "$title - Trailer",
                                            showUrl = url,
                                            apiName = provider.name,
                                            posterUrl = resultData?.posterImage ?: preloadedPoster,
                                            episode = null,
                                            season = null,
                                            episodeId = firstLink.url,
                                            position = 0L,
                                            duration = 0L,
                                        )
                                        onPlay(Triple(provider, firstLink.url, hist))
                                    }
                                }
                            },
                        )
                    }

                    // Bookmark Button
                    TvActionButton(
                        icon = if (watchType != WatchType.NONE) Icons.Default.Check else Icons.Default.FavoriteBorder,
                        label = if (watchType != WatchType.NONE) watchType.toDisplayName() else "Bookmark",
                        onClick = { showWatchTypeDialog = true },
                        isActive = watchType != WatchType.NONE,
                    )

                    // Favorite Button
                    TvActionButton(
                        icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        label = if (isFavorite) "Favorited" else "Favorite",
                        onClick = {
                            resultViewModel.toggleFavoriteStatus(DesktopContextProvider.context)
                        },
                        isActive = isFavorite,
                    )

                    // Subscribe Button
                    TvActionButton(
                        icon = Icons.Default.Notifications,
                        label = if (isSubscribed) "Subscribed" else "Subscribe",
                        onClick = {
                            resultViewModel.toggleSubscriptionStatus(DesktopContextProvider.context)
                        },
                        isActive = isSubscribed,
                    )

                    // Sync Button
                    TvActionButton(
                        icon = Icons.Default.Refresh,
                        label = if (syncedList.any { it.isSynced }) "Synced" else "Sync",
                        onClick = { showSyncDialog = true },
                        isActive = syncedList.any { it.isSynced },
                    )

                    // Search Button
                    TvActionButton(
                        icon = Icons.Default.Search,
                        label = "Search",
                        onClick = {
                            navController.navigate(Screen.Search(initialQuery = title))
                        },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Metadata Chips (Year, Rating, Duration, Provider)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    resultData?.yearText?.asStringNull(CloudStreamApp.context)?.let { yr ->
                        Text(yr, style = TvTypography.Caption.copy(color = TvColors.TextSecondary))
                    }
                    resultData?.ratingText?.asStringNull(CloudStreamApp.context)?.let { rt ->
                        Text("★ $rt", style = TvTypography.Caption.copy(color = TvColors.RatingGold))
                    }
                    resultData?.durationText?.asStringNull(CloudStreamApp.context)?.let { dur ->
                        Text(dur, style = TvTypography.Caption.copy(color = TvColors.TextSecondary))
                    }
                    Text(provider.name, style = TvTypography.Caption.copy(color = TvColors.FocusElectricBlue))
                }

                // Fading Synopsis
                if (plot != null) {
                    TvFadingSynopsis(plot = plot, onExpandClick = { showSynopsisDialog = true })
                }

                // Cast & Crew
                val actors = resultData?.actors ?: (loadResponse as? LoadResponse)?.actors
                if (!actors.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Cast & Crew",
                        style = TvTypography.Section.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(actors) { actorData ->
                            TvActorCard(provider = provider, actorData = actorData)
                        }
                    }
                }
            }

            // 2. Middle Section: Vertical Episode List (Alt alta sıralı dikey liste)
            // Rendered whenever episodes exist or content is series/anime
            val hasEpisodes = (episodesResource as? Resource.Success)?.value?.isNotEmpty() == true ||
                loadResponse is TvSeriesLoadResponse ||
                loadResponse is AnimeLoadResponse

            if (hasEpisodes) {
                DetailsEpisodeList(
                    resultViewModel = resultViewModel,
                    provider = provider,
                    dataUrl = url,
                    showName = title,
                    showPoster = resultData?.posterImage ?: preloadedPoster,
                    latestHistory = latestHistory,
                    onPlay = { triple ->
                        // Synchronize sync scrobbling with SyncViewModel if episode number is present
                        triple.third.episode?.let { epNum ->
                            syncViewModel.modifyMaxEpisode(epNum)
                        }
                        onPlay(triple)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TvDimensions.ScreenHorizontalPadding),
                )
            }

            // 3. Bottom Section: Recommendations
            if (recommendations.isNotEmpty()) {
                TvRecommendationsSection(
                    recommendations = recommendations,
                    onRecommendationClick = { rec ->
                        navController.navigate(
                            Screen.Details(
                                provider = provider,
                                url = rec.url,
                                preloadedName = rec.name,
                                preloadedPoster = rec.posterUrl,
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 20.dp),
                )
            }
        }

        // Layer 2: Modal Dialogs (Watch Type / Synopsis / Sync / Trailer)
        if (showWatchTypeDialog) {
            TvWatchTypeDialog(
                currentType = watchType,
                onSelect = { selectedType ->
                    resultViewModel.updateWatchStatus(selectedType, DesktopContextProvider.context)
                    showWatchTypeDialog = false
                },
                onDismiss = { showWatchTypeDialog = false },
            )
        }

        if (showSynopsisDialog) {
            TvSynopsisDialog(
                title = resultData?.title ?: preloadedName ?: "",
                plot = resultData?.plotText?.asStringNull(CloudStreamApp.context) ?: "No synopsis available.",
                onDismiss = { showSynopsisDialog = false },
            )
        }

        if (showSyncDialog) {
            TvSyncStatusDialog(
                syncViewModel = syncViewModel,
                onDismiss = { showSyncDialog = false },
            )
        }

        if (showTrailerDialog) {
            val showTitle = resultData?.title ?: preloadedName ?: "Trailer"
            TvTrailerDialog(
                trailers = trailers,
                onSelectTrailer = { mirror ->
                    val parentId = DesktopDataStore.watchHistoryId(provider.name, url)
                    val hist = WatchHistory(
                        parentId = parentId,
                        title = "$showTitle - Trailer",
                        url = url,
                        apiName = provider.name,
                        posterUrl = resultData?.posterImage ?: preloadedPoster,
                        episode = null,
                        season = null,
                        episodeId = mirror.first.url,
                        position = 0L,
                        duration = 0L,
                    )
                    onPlay(Triple(provider, mirror.first.url, hist))
                },
                onDismiss = { showTrailerDialog = false },
            )
        }
    }
}

/**
 * 10-Foot Action Button with 1.08x focus scaling, glowing stroke, and synchronized marquee label.
 */
@Composable
fun TvActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    isPrimary: Boolean = false,
    isActive: Boolean = false,
    onFocusGained: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) TvDimensions.FocusScale else TvDimensions.UnfocusedScale,
        animationSpec = tween(150),
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(64.dp)
            .padding(horizontal = 2.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = when {
                isFocused -> TvColors.FocusElectricBlue
                isPrimary -> TvColors.SurfaceHigh
                isActive -> TvColors.FocusEmerald.copy(alpha = 0.2f)
                else -> TvColors.SurfaceCard
            },
            border = BorderStroke(
                width = if (isFocused) TvDimensions.BorderGlow else TvDimensions.BorderNormal,
                color = when {
                    isFocused -> Color.White
                    isActive -> TvColors.FocusEmerald
                    isPrimary -> TvColors.FocusElectricBlue
                    else -> TvColors.BorderSubtle
                },
            ),
            modifier = Modifier
                .size(48.dp)
                .scale(scale)
                .run { if (focusRequester != null) focusRequester(focusRequester) else this }
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocusGained()
                }
                .focusable(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = when {
                        isFocused -> Color.White
                        isActive -> TvColors.FocusEmerald
                        isPrimary -> TvColors.FocusElectricBlue
                        else -> TvColors.TextPrimary
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            style = TvTypography.Caption.copy(
                fontSize = 11.sp,
                color = if (isFocused) Color.White else TvColors.TextSecondary,
            ),
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.basicMarquee(),
        )
    }
}

/**
 * 7-Line Clamped Synopsis with a 30dp Vertical Fading Edge Shader.
 * Replicates upstream android:requiresFadingEdge="vertical" and android:fadingEdgeLength="30dp".
 */
@Composable
fun TvFadingSynopsis(
    plot: String?,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (plot.isNullOrBlank()) return

    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpandClick() }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
    ) {
        Text(
            text = plot,
            style = TvTypography.Body.copy(
                color = if (isFocused) Color.White else TvColors.TextSecondary,
                lineHeight = 22.sp,
                fontSize = 14.sp,
            ),
            maxLines = 7,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val fadeHeight = 30.dp.toPx()
                    val canvasHeight = this.size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Black,
                                ((canvasHeight - fadeHeight) / canvasHeight).coerceIn(0f, 1f) to Color.Black,
                                1.0f to Color.Transparent,
                            ),
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}

/**
 * Cast member thumbnail and role card with robust URL fixing and voice actor fallback.
 */
@Composable
fun TvActorCard(
    provider: MainAPI,
    actorData: ActorData,
    modifier: Modifier = Modifier,
) {
    val rawImage = actorData.actor.image?.takeIf { it.isNotBlank() }
        ?: actorData.voiceActor?.image?.takeIf { it.isNotBlank() }
    val imageUrl = provider.fixUrlNull(rawImage)
    var isFocused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(96.dp)
            .scale(if (isFocused) 1.05f else 1.0f)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = actorData.actor.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(TvColors.SurfaceCard)
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) TvColors.FocusElectricBlue else TvColors.BorderSubtle,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = actorData.actor.name,
            style = TvTypography.Caption.copy(
                fontSize = 11.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isFocused) TvColors.FocusElectricBlue else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        actorData.roleString?.let { role ->
            Text(
                text = role,
                style = TvTypography.Caption.copy(fontSize = 10.sp, color = TvColors.TextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        actorData.voiceActor?.name?.let { vaName ->
            Text(
                text = "VA: $vaName",
                style = TvTypography.Caption.copy(fontSize = 9.sp, color = TvColors.TextMuted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Recommendations Section displayed at the bottom of details.
 */
@Composable
fun TvRecommendationsSection(
    recommendations: List<SearchResponse>,
    onRecommendationClick: (SearchResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (recommendations.isEmpty()) return

    Column(modifier = modifier) {
        Text(
            text = "Recommended Titles",
            style = TvTypography.Headline.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(recommendations) { rec ->
                TvCard(
                    title = rec.name,
                    posterUrl = rec.posterUrl,
                    cardWidth = 160.dp,
                    cardHeight = 240.dp,
                    onClick = { onRecommendationClick(rec) },
                )
            }
        }
    }
}

private fun WatchType.toDisplayName(): String = when (this) {
    WatchType.WATCHING -> "Watching"
    WatchType.COMPLETED -> "Completed"
    WatchType.ONHOLD -> "On Hold"
    WatchType.DROPPED -> "Dropped"
    WatchType.PLANTOWATCH -> "Plan to Watch"
    WatchType.NONE -> "None"
}

/**
 * WatchType Selector Modal Dialog: Watching, Completed, On Hold, Dropped, Plan to Watch, None.
 */
@Composable
fun TvWatchTypeDialog(
    currentType: WatchType,
    onSelect: (WatchType) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.FocusElectricBlue),
            modifier = Modifier
                .width(320.dp)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Watch Status",
                    style = TvTypography.Headline.copy(fontSize = 18.sp),
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                val types = listOf(
                    WatchType.WATCHING,
                    WatchType.COMPLETED,
                    WatchType.ONHOLD,
                    WatchType.DROPPED,
                    WatchType.PLANTOWATCH,
                    WatchType.NONE,
                )

                types.forEach { type ->
                    val isSelected = type == currentType
                    var isFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { onSelect(type) },
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = when {
                            isFocused -> TvColors.FocusElectricBlue
                            isSelected -> TvColors.SurfaceHigh
                            else -> Color.Transparent
                        },
                        border = if (isFocused) BorderStroke(1.dp, Color.White) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = if (isFocused) Color.White else TvColors.FocusEmerald,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Spacer(modifier = Modifier.width(26.dp))
                            }
                            Text(
                                text = type.toDisplayName(),
                                style = TvTypography.Button.copy(
                                    color = if (isFocused) Color.White else TvColors.TextPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-Screen / Modal Synopsis Dialog with scrollable text.
 */
@Composable
fun TvSynopsisDialog(
    title: String,
    plot: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusLarge),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .width(600.dp)
                .heightIn(max = 500.dp)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = TvTypography.Headline.copy(fontSize = 20.sp),
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusable(),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState),
                ) {
                    Text(
                        text = plot,
                        style = TvTypography.Body.copy(
                            color = TvColors.TextPrimary,
                            lineHeight = 24.sp,
                            fontSize = 15.sp,
                        ),
                    )
                }
            }
        }
    }
}
