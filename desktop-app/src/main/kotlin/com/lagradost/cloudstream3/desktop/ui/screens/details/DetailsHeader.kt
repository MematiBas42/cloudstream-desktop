package com.lagradost.cloudstream3.desktop.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.result.CurrentSynced
import com.lagradost.cloudstream3.ui.result.ExtractedTrailerData
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.common.storage.DesktopBookmark
import com.lagradost.common.storage.DesktopDataStore

@Composable
fun DetailsBackdrop(provider: MainAPI, data: LoadResponse, scrollState: LazyListState) {
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(constraints.maxWidth, 0) {
                    placeable.placeRelative(0, 0)
                }
            }
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
    ) {
        val bgUrl = provider.fixUrlNull(data.backgroundPosterUrl) ?: provider.fixUrlNull(data.posterUrl)
        if (bgUrl != null) {
            AsyncImage(
                model = bgUrl,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .run { if (data.backgroundPosterUrl == null) this.blur(40.dp) else this },
            )
        }

        val bgColor = TvColors.AmoledBackground
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.25f to bgColor.copy(alpha = 0.25f),
                            0.50f to bgColor.copy(alpha = 0.80f),
                            0.75f to bgColor.copy(alpha = 0.98f),
                            1.00f to bgColor,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to bgColor.copy(alpha = 0.65f),
                            0.60f to Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsMetadata(
    provider: MainAPI,
    data: LoadResponse,
    syncViewModel: SyncViewModel? = null,
    onOpenSyncDialog: (() -> Unit)? = null,
) {
    val syncedList = syncViewModel?.synced?.collectAsState()?.value ?: emptyList()
    val syncUserData = syncViewModel?.userData?.collectAsState()?.value

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 1100.dp)
                .padding(
                    horizontal = TvDimensions.ScreenHorizontalPadding,
                    vertical = 16.dp,
                )
                .clip(RoundedCornerShape(TvDimensions.CornerRadiusLarge))
                .background(TvColors.SurfaceElevated.copy(alpha = 0.85f))
                .border(1.dp, TvColors.BorderSubtle, RoundedCornerShape(TvDimensions.CornerRadiusLarge))
                .padding(28.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Main Poster
                AsyncImage(
                    model = provider.fixUrlNull(data.posterUrl),
                    contentDescription = data.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(TvDimensions.CardWidth)
                        .height(TvDimensions.CardHeight)
                        .shadow(TvDimensions.ElevationFocused, RoundedCornerShape(TvDimensions.CornerRadiusCard))
                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusCard))
                        .border(1.dp, TvColors.BorderSubtle, RoundedCornerShape(TvDimensions.CornerRadiusCard)),
                )
                Spacer(modifier = Modifier.width(28.dp))

                // Metadata Column
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = data.name,
                                style = TvTypography.Headline,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                data.year?.let {
                                    Text(it.toString(), style = TvTypography.Button, color = TvColors.TextSecondary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                data.duration?.let {
                                    Text("${it}m", style = TvTypography.Button, color = TvColors.TextSecondary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                data.score?.let {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = TvColors.RatingGold,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "★ ${it.toString()}",
                                        color = TvColors.RatingGold,
                                        style = TvTypography.Button,
                                    )
                                }
                            }
                        }

                        // Bookmark button
                        val bookmarkId = "${provider.name}_${data.url.hashCode()}"
                        var isBookmarked by remember { mutableStateOf(DesktopDataStore.isBookmarked(bookmarkId)) }
                        IconButton(
                            onClick = {
                                if (isBookmarked) {
                                    DesktopDataStore.removeBookmark(bookmarkId)
                                } else {
                                    DesktopDataStore.addBookmark(
                                        DesktopBookmark(
                                            id = bookmarkId,
                                            name = data.name,
                                            url = data.url,
                                            apiName = provider.name,
                                            posterUrl = data.posterUrl,
                                        ),
                                    )
                                }
                                isBookmarked = !isBookmarked
                            },
                            modifier = Modifier.focusable(),
                        ) {
                            Icon(
                                if (isBookmarked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Bookmark",
                                tint = if (isBookmarked) TvColors.ProgressWatched else TvColors.TextSecondary,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    // Sync Badges (AniList, MAL, Simkl, Kitsu)
                    if (syncedList.any { it.isSynced }) {
                        Spacer(modifier = Modifier.height(10.dp))
                        DetailsSyncBadges(
                            syncedList = syncedList,
                            syncUserData = syncUserData,
                            onClick = { onOpenSyncDialog?.invoke() },
                        )
                    }

                    // Synopsis / Plot
                    data.plot?.let { plot ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = plot,
                            style = TvTypography.Body.copy(color = TvColors.TextSecondary),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 22.sp,
                        )
                    }

                    // Tags / Genres
                    if (!data.tags.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(data.tags!!.take(8)) { tag ->
                                Surface(
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                                    color = TvColors.SurfaceCard,
                                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                ) {
                                    Text(
                                        text = tag,
                                        style = TvTypography.Badge.copy(color = TvColors.TextSecondary),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
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

/**
 * Interactive Sync Badges showing external tracking status (AniList, MAL, Simkl, Kitsu).
 */
@Composable
fun DetailsSyncBadges(
    syncedList: List<CurrentSynced>,
    syncUserData: Resource<com.lagradost.cloudstream3.syncproviders.SyncAPI.AbstractSyncStatus>?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSynced = syncedList.filter { it.isSynced }
    if (activeSynced.isEmpty()) return

    val statusVal = (syncUserData as? Resource.Success)?.value

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { onClick() },
    ) {
        activeSynced.forEach { sync ->
            val providerColor = when (sync.name.lowercase()) {
                "anilist" -> Color(0xFF02A9FF)
                "mal", "myanimelist" -> Color(0xFF2E51A2)
                "simkl" -> Color(0xFF000000)
                "kitsu" -> Color(0xFFFD755C)
                else -> TvColors.FocusElectricBlue
            }

            Surface(
                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                color = providerColor.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, providerColor.copy(alpha = 0.6f)),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = sync.name,
                        style = TvTypography.Caption.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color.White,
                        ),
                    )

                    statusVal?.let { user ->
                        user.status?.let { st ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• ${st.name}",
                                style = TvTypography.Caption.copy(
                                    fontSize = 10.sp,
                                    color = TvColors.FocusEmerald,
                                ),
                            )
                        }
                        user.score?.let { sc ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "★ ${sc.toInt()}/10",
                                style = TvTypography.Caption.copy(
                                    fontSize = 10.sp,
                                    color = TvColors.RatingGold,
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
 * Desktop Sync Status Dialog allowing full control over watch status, episode progress, and rating.
 */
@Composable
fun TvSyncStatusDialog(
    syncViewModel: SyncViewModel,
    onDismiss: () -> Unit,
) {
    val syncedList by syncViewModel.synced.collectAsState()
    val syncUserData by syncViewModel.userData.collectAsState()
    val syncMetadata by syncViewModel.metadata.collectAsState()

    val userStatus = (syncUserData as? Resource.Success)?.value
    val meta = (syncMetadata as? Resource.Success)?.value

    var selectedStatusIndex by remember(userStatus) {
        mutableStateOf(userStatus?.status?.internalId ?: 0)
    }
    var episodesWatched by remember(userStatus) {
        mutableStateOf(userStatus?.watchedEpisodes ?: 0)
    }
    var scoreValue by remember(userStatus) {
        mutableStateOf(userStatus?.score?.toInt() ?: 0)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusLarge),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.FocusElectricBlue),
            modifier = Modifier
                .width(480.dp)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Sync & Tracking",
                        style = TvTypography.Headline.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connected Services Chips
                Text(
                    text = "Connected Providers:",
                    style = TvTypography.Caption.copy(color = TvColors.TextSecondary),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    syncedList.forEach { sync ->
                        val isConnected = sync.isSynced && sync.hasAccount
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = if (isConnected) TvColors.FocusElectricBlue.copy(alpha = 0.2f) else TvColors.SurfaceCard,
                            border = BorderStroke(1.dp, if (isConnected) TvColors.FocusElectricBlue else TvColors.BorderSubtle),
                        ) {
                            Text(
                                text = sync.name,
                                style = TvTypography.Caption.copy(
                                    fontSize = 11.sp,
                                    color = if (isConnected) Color.White else TvColors.TextMuted,
                                    fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Watch Status Selector
                Text(
                    text = "Watch Status",
                    style = TvTypography.Body.copy(fontWeight = FontWeight.SemiBold, color = Color.White),
                )
                Spacer(modifier = Modifier.height(8.dp))
                val statusOptions = listOf(
                    SyncWatchType.WATCHING,
                    SyncWatchType.COMPLETED,
                    SyncWatchType.ONHOLD,
                    SyncWatchType.DROPPED,
                    SyncWatchType.PLANTOWATCH,
                    SyncWatchType.REWATCHING,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(statusOptions) { st ->
                        val isSelected = selectedStatusIndex == st.internalId
                        Surface(
                            onClick = {
                                selectedStatusIndex = st.internalId
                                syncViewModel.setStatus(st.internalId)
                            },
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = if (isSelected) TvColors.FocusElectricBlue else TvColors.SurfaceCard,
                            border = BorderStroke(1.dp, if (isSelected) Color.White else TvColors.BorderSubtle),
                        ) {
                            Text(
                                text = st.name,
                                style = TvTypography.Caption.copy(
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color.White else TvColors.TextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Watched Episodes Counter
                val maxEps = meta?.totalEpisodes
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Episodes Watched",
                            style = TvTypography.Body.copy(fontWeight = FontWeight.SemiBold, color = Color.White),
                        )
                        maxEps?.let {
                            Text(
                                text = "Total: $it episodes",
                                style = TvTypography.Caption.copy(color = TvColors.TextSecondary, fontSize = 11.sp),
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (episodesWatched > 0) {
                                    episodesWatched--
                                    syncViewModel.setEpisodes(episodesWatched)
                                }
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .background(TvColors.SurfaceCard, CircleShape),
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        Text(
                            text = "$episodesWatched",
                            style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )

                        IconButton(
                            onClick = {
                                if (maxEps == null || episodesWatched < maxEps) {
                                    episodesWatched++
                                    syncViewModel.setEpisodes(episodesWatched)
                                }
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .background(TvColors.SurfaceCard, CircleShape),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Score / Rating Selector (0 to 10)
                Text(
                    text = "Score: ${if (scoreValue > 0) "$scoreValue/10 ★" else "Not Rated"}",
                    style = TvTypography.Body.copy(fontWeight = FontWeight.SemiBold, color = Color.White),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items((0..10).toList()) { sc ->
                        val isSelected = scoreValue == sc
                        Surface(
                            onClick = {
                                scoreValue = sc
                                syncViewModel.setScore(if (sc > 0) Score.from(sc, 10) else null)
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = when {
                                isSelected -> TvColors.RatingGold
                                sc == 0 -> TvColors.SurfaceCard
                                else -> TvColors.SurfaceCard
                            },
                            border = BorderStroke(1.dp, if (isSelected) Color.White else TvColors.BorderSubtle),
                        ) {
                            Text(
                                text = if (sc == 0) "-" else "$sc",
                                style = TvTypography.Caption.copy(
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.Black else TvColors.TextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Save & Sync Button
                Button(
                    onClick = {
                        syncViewModel.publishUserData()
                        CommonActivity.showToast("Sync status updated", android.widget.Toast.LENGTH_SHORT)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save & Sync", style = TvTypography.Button.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

/**
 * Trailer Selection Dialog for titles with multiple trailers or mirrors.
 */
@Composable
fun TvTrailerDialog(
    trailers: List<ExtractedTrailerData>,
    onSelectTrailer: (Pair<com.lagradost.cloudstream3.utils.ExtractorLink, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allMirrors = remember(trailers) {
        trailers.flatMap { it.mirros }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.FocusElectricBlue),
            modifier = Modifier
                .width(440.dp)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Trailer",
                        style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                allMirrors.forEachIndexed { index, mirror ->
                    var isFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = {
                            onSelectTrailer(mirror)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = if (isFocused) TvColors.FocusElectricBlue else TvColors.SurfaceCard,
                        border = BorderStroke(1.dp, if (isFocused) Color.White else TvColors.BorderSubtle),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (isFocused) Color.White else TvColors.FocusElectricBlue,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = mirror.first.name.ifBlank { "Trailer ${index + 1}" },
                                    style = TvTypography.Button.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
                                )
                                Text(
                                    text = mirror.first.source,
                                    style = TvTypography.Caption.copy(color = TvColors.TextSecondary, fontSize = 11.sp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
