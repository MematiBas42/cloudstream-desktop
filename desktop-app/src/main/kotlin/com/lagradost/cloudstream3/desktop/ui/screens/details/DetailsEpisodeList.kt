package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.impl.PlayerLinkHandler

// Custom Material Design Icons for Desktop Parity
val IconDownload: ImageVector by lazy {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(19f, 9f)
            horizontalLineToRelative(-4f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineToRelative(6f)
            horizontalLineTo(5f)
            lineToRelative(7f, 7f)
            lineToRelative(7f, -7f)
            close()
            moveTo(5f, 18f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            horizontalLineTo(5f)
            close()
        }
    }.build()
}

val IconOpenInNew: ImageVector by lazy {
    ImageVector.Builder(
        name = "OpenInNew",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(19f, 19f)
            horizontalLineTo(5f)
            verticalLineTo(5f)
            horizontalLineToRelative(7f)
            verticalLineTo(3f)
            horizontalLineTo(5f)
            curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineToRelative(-7f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(7f)
            close()
            moveTo(14f, 3f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(3.59f)
            lineToRelative(-9.83f, 9.83f)
            lineToRelative(1.41f, 1.41f)
            lineTo(19f, 6.41f)
            verticalLineTo(10f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(-7f)
            close()
        }
    }.build()
}

val IconContentCopy: ImageVector by lazy {
    ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }
    }.build()
}

/**
 * Wide-screen vertical episode list directly wired to [ResultViewModel2].
 *
 * Implements CLAUDE.md Section 5.4 requirements:
 * - Dub/Sub chips/tabs (dubSubSelections, selectedDubStatus)
 * - Season chips/tabs (seasonSelections, selectedSeason)
 * - 50-episode range filter chips (rangeSelections, selectedRange)
 * - Dikey alt alta sıralı EpisodeCard listesi (episodes)
 * - AnimeDB filler badges (ep.isFiller == true -> amber "FILLER" badge)
 * - Progress indicators (LinearProgressIndicator with 0xFFE50914, green checkmark for >=95% watched)
 * - In-row Download Button with download state indicator
 * - Batch Download Season button
 * - Desktop Right-Click Context Menu (Mark watched, reset progress, external player, copy link)
 * - Episode options trigger (ACTION_SHOW_OPTIONS via resultViewModel.handleAction(EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)))
 */
@Composable
fun DetailsEpisodeList(
    resultViewModel: ResultViewModel2,
    provider: MainAPI,
    dataUrl: String,
    showName: String,
    showPoster: String?,
    latestHistory: WatchHistory?,
    onPlay: (Triple<MainAPI, String, WatchHistory>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dubSubSelections by resultViewModel.dubSubSelections.collectAsState()
    val selectedDubStatus by resultViewModel.selectedDubStatus.collectAsState()

    val seasonSelections by resultViewModel.seasonSelections.collectAsState()
    val selectedSeason by resultViewModel.selectedSeason.collectAsState()

    val rangeSelections by resultViewModel.rangeSelections.collectAsState()
    val selectedRange by resultViewModel.selectedRange.collectAsState()

    val episodesResource by resultViewModel.episodes.collectAsState()
    val episodesCountText by resultViewModel.episodesCountText.collectAsState()

    // Real-time download status tracking
    var downloadStatuses by remember { mutableStateOf(VideoDownloadManager.downloadStatus.toMap()) }
    DisposableEffect(Unit) {
        val listener = { _: Pair<Int, VideoDownloadManager.DownloadType> ->
            downloadStatuses = VideoDownloadManager.downloadStatus.toMap()
        }
        VideoDownloadManager.downloadStatusEvent += listener
        onDispose {
            VideoDownloadManager.downloadStatusEvent -= listener
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        // Section Header: Title + Episode count + Batch Download Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Episodes",
                    style = TvTypography.Headline.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )

                episodesCountText?.asStringNull(CloudStreamApp.context)?.let { countText ->
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = countText,
                        style = TvTypography.Caption.copy(fontSize = 14.sp, color = TvColors.TextSecondary),
                    )
                }
            }

            // Batch Download Season Button
            val currentEpisodes = (episodesResource as? Resource.Success)?.value ?: emptyList()
            if (currentEpisodes.isNotEmpty()) {
                Surface(
                    onClick = {
                        currentEpisodes.forEach { ep ->
                            resultViewModel.handleAction(EpisodeClickEvent(ACTION_DOWNLOAD_EPISODE, ep))
                        }
                        CommonActivity.showToast(
                            "Batch download started (${currentEpisodes.size} episodes)",
                            android.widget.Toast.LENGTH_SHORT,
                        )
                    },
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                    color = TvColors.SurfaceCard,
                    border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f)),
                    modifier = Modifier.focusable(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            IconDownload,
                            contentDescription = "Download Season",
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download Season",
                            style = TvTypography.Button.copy(fontSize = 12.sp, color = TvColors.FocusElectricBlue),
                        )
                    }
                }
            }
        }

        // 1. Dub / Sub Selector Chips
        if (dubSubSelections.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                items(dubSubSelections) { (uiText, dubStatus) ->
                    val isSelected = selectedDubStatus == uiText
                    val label = uiText?.asStringNull(CloudStreamApp.context) ?: dubStatus.name.uppercase()

                    EpisodeFilterChip(
                        label = label,
                        isSelected = isSelected,
                        onClick = { resultViewModel.changeDubStatus(dubStatus) },
                    )
                }
            }
        }

        // 2. Season Selector Chips
        if (seasonSelections.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                items(seasonSelections) { (uiText, season) ->
                    val isSelected = selectedSeason == uiText
                    val label = uiText?.asStringNull(CloudStreamApp.context)
                        ?: if (season == 0) "Specials" else "Season $season"

                    EpisodeFilterChip(
                        label = label,
                        isSelected = isSelected,
                        onClick = { resultViewModel.changeSeason(season) },
                    )
                }
            }
        }

        // 3. 50-Episode Range Filter Chips
        if (rangeSelections.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
            ) {
                items(rangeSelections) { (uiText, range) ->
                    val isSelected = selectedRange == uiText
                    val label = uiText?.asStringNull(CloudStreamApp.context)
                        ?: "${range.startEpisode} - ${range.endEpisode}"

                    EpisodeFilterChip(
                        label = label,
                        isSelected = isSelected,
                        onClick = { resultViewModel.changeRange(range) },
                    )
                }
            }
        }

        // 4. Vertical List of Episodes (Alt Alta Sıralı Dikey Liste)
        when (val resource = episodesResource) {
            is Resource.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = TvColors.FocusElectricBlue,
                        strokeWidth = 3.dp,
                    )
                }
            }
            is Resource.Failure -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = resource.errorString ?: "Failed to load episodes",
                        color = TvColors.ErrorRed,
                        style = TvTypography.Headline,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { resultViewModel.reloadEpisodes() },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                    ) {
                        Text("Retry", style = TvTypography.Button)
                    }
                }
            }
            is Resource.Success -> {
                val episodes = resource.value
                if (episodes.isEmpty()) {
                    Text(
                        text = "No episodes found.",
                        style = TvTypography.Caption.copy(color = TvColors.TextSecondary),
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        episodes.forEach { ep ->
                            val isLatest = latestHistory?.episodeId == ep.data
                            val downloadStatus = downloadStatuses[ep.id]
                            EpisodeCard(
                                ep = ep,
                                isLatest = isLatest,
                                downloadStatus = downloadStatus,
                                provider = provider,
                                dataUrl = dataUrl,
                                showName = showName,
                                showPoster = showPoster,
                                resultViewModel = resultViewModel,
                                onPlay = onPlay,
                                onOptionsClick = {
                                    resultViewModel.handleAction(
                                        EpisodeClickEvent(ACTION_SHOW_OPTIONS, ep)
                                    )
                                },
                            )
                        }
                    }
                }
            }
            null -> {}
        }
    }
}

/**
 * Filter chip component for Dub, Season, and Range selection.
 */
@Composable
fun EpisodeFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
        color = when {
            isFocused -> TvColors.FocusElectricBlue
            isSelected -> TvColors.SurfaceHigh
            else -> TvColors.SurfaceCard
        },
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = when {
                isFocused -> Color.White
                isSelected -> TvColors.FocusElectricBlue
                else -> TvColors.BorderSubtle
            },
        ),
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
    ) {
        Text(
            text = label,
            style = TvTypography.Button.copy(
                fontSize = 13.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isFocused || isSelected) Color.White else TvColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * Modern wide-screen vertical episode card matching upstream ResultEpisodeLargeBinding.
 * Features in-row download button, real-time download status, and right-click desktop context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeCard(
    ep: ResultEpisode,
    isLatest: Boolean,
    downloadStatus: VideoDownloadManager.DownloadType?,
    provider: MainAPI,
    dataUrl: String,
    showName: String,
    showPoster: String?,
    resultViewModel: ResultViewModel2,
    onPlay: (Triple<MainAPI, String, WatchHistory>) -> Unit,
    onOptionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val pos = ep.getDisplayPosition()
    val dur = ep.duration
    val isWatched = ep.videoWatchState == VideoWatchState.Watched || (dur > 0L && (pos * 100L / dur) >= 95L)
    val hasProgress = dur > 0L && (pos * 100L / dur > 1L) && !isWatched
    val progressFrac = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .scale(if (isFocused) 1.015f else 1.0f)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .onClick(
                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                onClick = { showContextMenu = true },
            )
            .clickable(interactionSource = interactionSource, indication = null) {
                navigateToPlay(provider, dataUrl, showName, showPoster, ep, onPlay)
            },
        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isFocused -> TvColors.SurfaceElevated
                isLatest -> TvColors.SurfaceHigh
                else -> TvColors.SurfaceCard
            },
        ),
        border = when {
            isFocused -> BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
            isLatest -> BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f))
            else -> BorderStroke(1.dp, TvColors.BorderSubtle)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. 16:9 Thumbnail (160x90dp)
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(90.dp)
                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val thumbUrl = provider.fixUrlNull(ep.poster) ?: provider.fixUrlNull(showPoster)
                if (thumbUrl != null) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = ep.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Watched green checkmark badge / Play overlay
                if (isWatched) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Watched",
                            tint = Color(0xFF4CAF50), // Green checkmark
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else if (isFocused) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                // Progress indicator (0xFFE50914 Netflix Red)
                if (hasProgress) {
                    LinearProgressIndicator(
                        progress = { progressFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .align(Alignment.BottomCenter),
                        color = Color(0xFFE50914),
                        trackColor = Color.Black.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 2. Episode Metadata
            Column(modifier = Modifier.weight(1f)) {
                val epNumber = ep.episode.let { "$it. " }
                val title = ep.name ?: "Episode ${ep.episode}"

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "$epNumber$title",
                        style = TvTypography.Card.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (isFocused) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    // AnimeDB Filler Badge (Amber "FILLER")
                    if (ep.isFiller == true) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFFA000).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFFFFA000)),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                text = "FILLER",
                                style = TvTypography.Caption.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    color = Color(0xFFFFC107),
                                ),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Runtime / Rating / AirDate metadata
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ep.runTime?.let { rt ->
                        val runTimeStr = if (rt > 300) "${rt / 60}m" else "${rt}m"
                        Text(
                            text = runTimeStr,
                            style = TvTypography.Caption.copy(fontSize = 12.sp, color = TvColors.TextSecondary),
                        )
                    }

                    ep.score?.let { score ->
                        Text(
                            text = "★ $score",
                            style = TvTypography.Caption.copy(fontSize = 12.sp, color = TvColors.RatingGold),
                        )
                    }
                }

                // Synopsis
                ep.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = desc,
                        style = TvTypography.Caption.copy(fontSize = 12.sp, color = TvColors.TextMuted),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 3. In-Row Download Button
            IconButton(
                onClick = {
                    when (downloadStatus) {
                        VideoDownloadManager.DownloadType.IsDone -> {
                            CommonActivity.showToast("Episode already downloaded", android.widget.Toast.LENGTH_SHORT)
                        }
                        VideoDownloadManager.DownloadType.IsDownloading,
                        VideoDownloadManager.DownloadType.IsPending -> {
                            VideoDownloadManager.downloadEvent.invoke(
                                Pair(ep.id, VideoDownloadManager.DownloadActionType.Pause)
                            )
                        }
                        VideoDownloadManager.DownloadType.IsPaused,
                        VideoDownloadManager.DownloadType.IsStopped -> {
                            VideoDownloadManager.downloadEvent.invoke(
                                Pair(ep.id, VideoDownloadManager.DownloadActionType.Resume)
                            )
                        }
                        else -> {
                            resultViewModel.handleAction(
                                EpisodeClickEvent(ACTION_DOWNLOAD_EPISODE, ep)
                            )
                        }
                    }
                },
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(36.dp),
            ) {
                when (downloadStatus) {
                    VideoDownloadManager.DownloadType.IsDone -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = TvColors.FocusEmerald,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    VideoDownloadManager.DownloadType.IsDownloading,
                    VideoDownloadManager.DownloadType.IsPending -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    VideoDownloadManager.DownloadType.IsPaused,
                    VideoDownloadManager.DownloadType.IsStopped -> {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Resume Download",
                            tint = Color(0xFFFFA000),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    else -> {
                        Icon(
                            IconDownload,
                            contentDescription = "Download Episode",
                            tint = if (isFocused) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 4. Episode Options Trigger (ACTION_SHOW_OPTIONS)
            IconButton(
                onClick = onOptionsClick,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(36.dp),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Episode Options",
                    tint = TvColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 5. Desktop Right-Click Context Menu
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                modifier = Modifier.background(TvColors.SurfaceElevated),
            ) {
                DropdownMenuItem(
                    text = { Text("Play in App", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TvColors.FocusElectricBlue)
                    },
                    onClick = {
                        showContextMenu = false
                        navigateToPlay(provider, dataUrl, showName, showPoster, ep, onPlay)
                    },
                )
                HorizontalDivider(color = TvColors.BorderSubtle)
                DropdownMenuItem(
                    text = {
                        Text(
                            if (isWatched) "Mark as Unwatched" else "Mark as Watched",
                            style = TvTypography.Body,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (isWatched) Icons.Default.Close else Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isWatched) TvColors.TextSecondary else TvColors.FocusEmerald,
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        resultViewModel.handleAction(EpisodeClickEvent(ACTION_MARK_AS_WATCHED, ep))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Mark Watched Up to Here", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(Icons.Default.Done, contentDescription = null, tint = TvColors.FocusEmerald)
                    },
                    onClick = {
                        showContextMenu = false
                        resultViewModel.handleAction(EpisodeClickEvent(ACTION_MARK_WATCHED_UP_TO_THIS_EPISODE, ep))
                    },
                )
                if (hasProgress || isWatched) {
                    DropdownMenuItem(
                        text = { Text("Reset Watch Progress", style = TvTypography.Body) },
                        leadingIcon = {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = TvColors.TextSecondary)
                        },
                        onClick = {
                            showContextMenu = false
                            val parentId = DesktopDataStore.watchHistoryId(provider.name, dataUrl)
                            com.lagradost.cloudstream3.utils.DataStoreHelper.setViewPos(ep.id, 0L, ep.duration)
                            com.lagradost.cloudstream3.utils.DataStoreHelper.setVideoWatchState(ep.id, com.lagradost.cloudstream3.ui.result.VideoWatchState.None)
                            resultViewModel.reloadEpisodes()
                        },
                    )
                }
                HorizontalDivider(color = TvColors.BorderSubtle)
                DropdownMenuItem(
                    text = { Text("Download Episode", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(IconDownload, contentDescription = null, tint = TvColors.FocusElectricBlue)
                    },
                    onClick = {
                        showContextMenu = false
                        resultViewModel.handleAction(EpisodeClickEvent(ACTION_DOWNLOAD_EPISODE, ep))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Download (Select Source)", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = TvColors.TextSecondary)
                    },
                    onClick = {
                        showContextMenu = false
                        resultViewModel.handleAction(EpisodeClickEvent(ACTION_DOWNLOAD_MIRROR, ep))
                    },
                )
                HorizontalDivider(color = TvColors.BorderSubtle)
                DropdownMenuItem(
                    text = { Text("Open in MPV", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(IconOpenInNew, contentDescription = null, tint = TvColors.FocusElectricBlue)
                    },
                    onClick = {
                        showContextMenu = false
                        val mpvId = VideoClickActionHolder.uniqueIdToId(MpvPackage().uniqueId())
                        if (mpvId != null) {
                            resultViewModel.handleAction(EpisodeClickEvent(mpvId, ep))
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Open in VLC", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(IconOpenInNew, contentDescription = null, tint = Color(0xFFFF9800))
                    },
                    onClick = {
                        showContextMenu = false
                        val vlcId = VideoClickActionHolder.uniqueIdToId(VlcPackage().uniqueId())
                        if (vlcId != null) {
                            resultViewModel.handleAction(EpisodeClickEvent(vlcId, ep))
                        }
                    },
                )
                HorizontalDivider(color = TvColors.BorderSubtle)
                DropdownMenuItem(
                    text = { Text("Copy Episode Link", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(IconContentCopy, contentDescription = null, tint = TvColors.TextSecondary)
                    },
                    onClick = {
                        showContextMenu = false
                        try {
                            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(java.awt.datatransfer.StringSelection(ep.data), null)
                            CommonActivity.showToast("Link copied to clipboard", android.widget.Toast.LENGTH_SHORT)
                        } catch (t: Throwable) {
                            com.lagradost.cloudstream3.mvvm.logError(t)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Resolves saved position and creates [WatchHistory] before invoking [onPlay].
 */
fun navigateToPlay(
    provider: MainAPI,
    dataUrl: String,
    showName: String,
    showPoster: String?,
    ep: ResultEpisode,
    onPlay: (Triple<MainAPI, String, WatchHistory>) -> Unit,
) {
    val parentId = DesktopDataStore.watchHistoryId(
        apiName = provider.name,
        showUrl = dataUrl,
    )
    val saved = DesktopDataStore.getEpisodeWatched(parentId, ep.data)
    val epIntId = ep.id
    val savedPosDur = com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos(epIntId)

    val resumePos = if (savedPosDur != null && savedPosDur.duration >= 30_000L) {
        PlayerLinkHandler.resumeStartSeconds(savedPosDur.position, savedPosDur.duration)
    } else {
        PlayerLinkHandler.resumeStartSeconds(
            saved?.position ?: ep.position,
            saved?.duration ?: ep.duration,
        )
    }

    val history = WatchHistory(
        parentId = parentId,
        showName = showName,
        showUrl = dataUrl,
        apiName = provider.name,
        posterUrl = ep.poster ?: showPoster,
        episode = ep.episode,
        season = ep.season,
        episodeId = ep.data,
        position = resumePos * 1000L,
        duration = savedPosDur?.duration ?: saved?.duration ?: ep.duration,
    )

    var patchedData = ep.data
    if (patchedData.startsWith("{") && patchedData.endsWith("}")) {
        if (!patchedData.contains("\"title\"")) {
            val titleStr = showName.replace("\"", "\\\"")
            patchedData = patchedData.replaceFirst("{", "{\"title\":\"$titleStr\",")
        }
        if (!patchedData.contains("\"tvtype\"")) {
            patchedData = patchedData.replaceFirst("{", "{\"tvtype\":\"\",")
        }
    }

    onPlay(Triple(provider, patchedData, history))
}
