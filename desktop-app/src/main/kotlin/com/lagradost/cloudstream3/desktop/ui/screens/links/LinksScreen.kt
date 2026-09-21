package com.lagradost.cloudstream3.desktop.ui.screens.links

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.ui.navigation.LocalVideoPlayer
import com.lagradost.cloudstream3.desktop.ui.navigation.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.impl.PlayerLinkHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val mpvPlayer = MpvPlayer()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinksSidePanel(
    provider: MainAPI,
    dataUrl: String,
    history: WatchHistory,
    autoPlay: Boolean = false,
    episodes: List<ResultEpisode> = emptyList(),
    onClose: () -> Unit,
) {
    val links = remember { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember { mutableStateListOf<SubtitleFile>() }
    var statusText by remember { mutableStateOf("Finding streams for you...") }
    var isScraping by remember { mutableStateOf(true) }

    val coroutineScope = rememberCoroutineScope()
    var isLaunchingPlayer by remember { mutableStateOf(false) }
    var playerLaunchError by remember { mutableStateOf<String?>(null) }
    var selectedQuality by remember { mutableStateOf<String?>(null) }
    var currentPlayingUrl by remember { mutableStateOf<String?>(null) }

    val clipboardManager = LocalClipboardManager.current

    val availableQualities = remember(links.size) {
        links.map { it.quality.toString() }.distinct().sorted()
    }
    val filteredLinks = remember(links.size, selectedQuality) {
        if (selectedQuality == null) links else links.filter { it.quality.toString() == selectedQuality }
    }

    val displayTitle = remember(history) {
        buildString {
            append(history.showName)
            if (history.season != null && history.episode != null) {
                append(" - S${history.season}E${history.episode}")
            } else if (history.episode != null) {
                append(" - E${history.episode}")
            }
        }
    }

    val videoPlayer = LocalVideoPlayer.current

    // Embedded MPV Play Action
    val playLink: (ExtractorLink) -> Unit = { link ->
        if (!isLaunchingPlayer) {
            isLaunchingPlayer = true
            currentPlayingUrl = link.url
            statusText = "Launching player..."

            val pId = history.parentId ?: history.url
            val latestHistory = DesktopDataStore.getEpisodeWatched(pId, history.episodeId) ?: history
            val episodeIntId = history.episodeId?.toIntOrNull()
                ?: history.episodeId?.hashCode()
                ?: history.url.toIntOrNull()
                ?: history.url.hashCode()

            val savedPosDur = com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos(episodeIntId)
            val startMs = if (savedPosDur != null && savedPosDur.duration >= 30_000L) {
                val sec = PlayerLinkHandler.resumeStartSeconds(savedPosDur.position, savedPosDur.duration)
                sec * 1000L
            } else {
                val startSec = PlayerLinkHandler.resumeStartSeconds(latestHistory.position, latestHistory.duration)
                startSec * 1000L
            }

            coroutineScope.launch {
                try {
                    val resolvedLink = if (link.type == com.lagradost.cloudstream3.utils.ExtractorLinkType.TORRENT ||
                        link.url.startsWith("magnet:", ignoreCase = true) ||
                        link.url.contains(".torrent", ignoreCase = true)
                    ) {
                        try {
                            val (transformed, _) = com.lagradost.cloudstream3.ui.player.Torrent.transformLink(link)
                            transformed
                        } catch (e: Throwable) {
                            statusText = e.message ?: "Torrent transformation failed"
                            playerLaunchError = e.message
                            isLaunchingPlayer = false
                            currentPlayingUrl = null
                            return@launch
                        }
                    } else {
                        link
                    }

                    val v = PlayerLinkHandler.validate(resolvedLink, displayTitle).getOrElse {
                        statusText = it.message ?: "Invalid stream"
                        playerLaunchError = it.message
                        isLaunchingPlayer = false
                        currentPlayingUrl = null
                        return@launch
                    }

                    val preparedLink = com.lagradost.cloudstream3.utils.newExtractorLink(
                        source = resolvedLink.source,
                        name = resolvedLink.name,
                        url = v.url,
                        type = resolvedLink.type,
                    ).apply {
                        quality = resolvedLink.quality
                        referer = resolvedLink.referer
                        headers = v.headers
                    }

                    // Check default player preference from Settings
                    val playerPref = DesktopDataStore.getKey<String>("player_default_key")
                    val isExternalMpv = playerPref == com.lagradost.cloudstream3.actions.temp.MpvPackage().uniqueId() ||
                            (playerPref == "external" && com.lagradost.cloudstream3.actions.temp.VlcPackage.isBinaryInPath("mpv"))
                    val isExternalVlc = playerPref == com.lagradost.cloudstream3.actions.temp.VlcPackage().uniqueId() ||
                            (playerPref == "external" && !com.lagradost.cloudstream3.actions.temp.VlcPackage.isBinaryInPath("mpv") && com.lagradost.cloudstream3.actions.temp.VlcPackage.isBinaryInPath("vlc"))

                    if (isExternalMpv || isExternalVlc) {
                        val fakeEp = episodes.firstOrNull { it.data == dataUrl } ?: ResultEpisode(
                            headerName = displayTitle,
                            name = displayTitle,
                            poster = history.posterUrl,
                            episode = history.episode ?: 1,
                            season = history.season,
                            data = dataUrl,
                            apiName = provider.name,
                            id = episodeIntId,
                            position = startMs,
                        )
                        val linkResult = com.lagradost.cloudstream3.ui.result.LinkLoadingResult(
                            links = listOf(preparedLink),
                            subs = subtitles.map { sub ->
                                com.lagradost.cloudstream3.ui.player.SubtitleData(
                                    originalName = sub.lang,
                                    url = sub.url,
                                )
                            },
                            syncData = hashMapOf()
                        )
                        if (isExternalMpv) {
                            com.lagradost.cloudstream3.actions.temp.MpvPackage().runAction(null, fakeEp, linkResult, 0)
                        } else {
                            com.lagradost.cloudstream3.actions.temp.VlcPackage().runAction(null, fakeEp, linkResult, 0)
                        }
                        statusText = "Opened in external player"
                        playerLaunchError = null
                        isLaunchingPlayer = false
                        currentPlayingUrl = null
                        onClose()
                        return@launch
                    }

                    videoPlayer(
                        VideoLaunchData(
                            links = listOf(preparedLink),
                            initialIndex = 0,
                            title = displayTitle,
                            subtitles = subtitles.toList(),
                            startPositionMs = startMs,
                            history = latestHistory,
                            episodes = episodes,
                            provider = provider,
                            dataUrl = dataUrl,
                            onClosed = {
                                isLaunchingPlayer = false
                                currentPlayingUrl = null
                            },
                            onError = { err ->
                                playerLaunchError = err
                                statusText = "Player error: $err"
                                isLaunchingPlayer = false
                                currentPlayingUrl = null
                            }
                        )
                    )

                    statusText = "Playing: ${link.name}"
                    playerLaunchError = null
                    isLaunchingPlayer = false
                    currentPlayingUrl = null
                    onClose()
                } catch (e: Exception) {
                    playerLaunchError = e.message ?: "Failed to launch player"
                    statusText = "Could not start player: ${e.message}"
                    isLaunchingPlayer = false
                    currentPlayingUrl = null
                } finally {
                    isLaunchingPlayer = false
                    currentPlayingUrl = null
                }
            }
        }
    }

    // Scrape video links and subtitle tracks from provider
    LaunchedEffect(dataUrl) {
        links.clear()
        subtitles.clear()
        isScraping = true
        statusText = "Finding streams for you..."
        withContext(Dispatchers.IO) {
            try {
                provider.loadLinks(
                    data = dataUrl,
                    isCasting = false,
                    subtitleCallback = { sub ->
                        if (subtitles.none { it.url == sub.url }) {
                            subtitles.add(sub)
                        }
                    },
                    callback = { link ->
                        if (links.none { it.url == link.url }) {
                            val isFirst = links.isEmpty()
                            links.add(link)
                            if (autoPlay && isFirst && !isLaunchingPlayer && currentPlayingUrl == null) {
                                playLink(link)
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                statusText = "Error finding streams: ${e.message}"
            } finally {
                isScraping = false
                if (links.isEmpty()) {
                    statusText = "No streams found."
                } else {
                    statusText = "${links.size} stream${if (links.size == 1) "" else "s"} available."
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.SurfaceCard)
            .padding(20.dp),
    ) {
        // Drawer Header with Title and Close Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Streams",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Text(
                    text = displayTitle,
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.focusable(),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status or Error Banner
        if (playerLaunchError != null) {
            Surface(
                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                color = TvColors.ErrorRed.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, TvColors.ErrorRed),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    text = playerLaunchError!!,
                    style = TvTypography.Caption.copy(color = TvColors.ErrorRed),
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        // Status Text & Scraping Progress
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            if (isScraping) {
                CircularProgressIndicator(
                    color = TvColors.FocusElectricBlue,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = statusText,
                style = TvTypography.Caption.copy(color = TvColors.TextMuted),
            )
        }

        // Quality Filter Chips
        if (availableQualities.size > 1) {
            QualitySelector(
                availableQualities = availableQualities,
                selectedQuality = selectedQuality,
                onSelect = { selectedQuality = it },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Links List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(filteredLinks) { index, link ->
                val isCurrentlyPlaying = currentPlayingUrl == link.url
                var isRowFocused by remember { mutableStateOf(false) }
                val rowInteractionSource = remember { MutableInteractionSource() }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isRowFocused = it.isFocused }
                        .focusable(interactionSource = rowInteractionSource),
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isRowFocused -> TvColors.SurfaceElevated
                            isCurrentlyPlaying -> TvColors.SurfaceHigh
                            else -> TvColors.SurfaceContainer
                        },
                    ),
                    border = when {
                        isRowFocused -> BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
                        isCurrentlyPlaying -> BorderStroke(1.dp, TvColors.FocusEmerald)
                        else -> BorderStroke(1.dp, TvColors.BorderSubtle)
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = link.name,
                                    style = TvTypography.Card.copy(
                                        color = if (isRowFocused) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    color = TvColors.FocusElectricBlue.copy(alpha = 0.2f),
                                ) {
                                    Text(
                                        text = "${link.quality}p",
                                        style = TvTypography.Badge.copy(color = TvColors.FocusElectricBlue),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = link.source,
                                style = TvTypography.Caption.copy(color = TvColors.TextMuted),
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Copy Link
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(link.url))
                                    statusText = "Copied link to clipboard!"
                                },
                                modifier = Modifier.size(36.dp).focusable(),
                            ) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = "Copy",
                                    tint = TvColors.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Spacer(Modifier.width(6.dp))

                            // Play Button (Always re-enabled after launch via deadlock fix)
                            Button(
                                onClick = { playLink(link) },
                                enabled = !isLaunchingPlayer,
                                modifier = Modifier.focusable(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRowFocused) TvColors.FocusElectricBlue else TvColors.SurfaceElevated,
                                    contentColor = if (isRowFocused) Color.White else TvColors.FocusElectricBlue,
                                    disabledContainerColor = TvColors.SurfaceContainer,
                                    disabledContentColor = TvColors.TextMuted,
                                ),
                                border = if (isRowFocused) null else BorderStroke(1.dp, TvColors.BorderSubtle),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (isCurrentlyPlaying) "Active" else "Play",
                                    style = TvTypography.Button.copy(fontSize = 13.sp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
