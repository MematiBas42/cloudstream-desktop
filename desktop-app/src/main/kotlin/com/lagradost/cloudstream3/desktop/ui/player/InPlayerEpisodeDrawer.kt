package com.lagradost.cloudstream3.desktop.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.ui.focus.TvNavAction
import com.lagradost.cloudstream3.desktop.ui.focus.interceptTvKeyNavigation
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.common.storage.WatchHistory
import com.lagradost.common.storage.WatchHistoryRepository
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.tracks.AudioTrack
import com.lagradost.player.tracks.MpvTrackManager
import com.lagradost.player.tracks.SubtitleOrigin
import com.lagradost.player.tracks.SubtitleTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Dns
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Navigation tabs inside the unified in-player drawer.
 */
enum class PlayerDrawerTab(val title: String) {
    EPISODES("Episodes"),
    SOURCES("Sources"),
    AUDIO("Audio"),
    SUBTITLES("Subtitles"),
    TRACKS("Tracks"),
    SLEEP_TIMER("Sleep Timer"),
}

/**
 * Sleep timer preset duration and modes.
 */
enum class SleepTimerMode(val label: String, val minutes: Int?) {
    OFF("Off / Disabled", null),
    MINUTES_15("15 Minutes", 15),
    MINUTES_30("30 Minutes", 30),
    MINUTES_45("45 Minutes", 45),
    MINUTES_60("60 Minutes", 60),
    END_OF_EPISODE("End of Episode", null),
}

/**
 * Presentation item representing an episode in the TV episode switcher.
 */
data class InPlayerEpisodeItem(
    val episode: Episode,
    val isCurrentPlaying: Boolean,
    val watchHistory: WatchHistory?,
    val formattedDuration: String,
    val progressPercentage: Float,
)

/**
 * Immutable StateFlow UI state for the in-player TV drawer.
 */
data class InPlayerDrawerState(
    val isOpen: Boolean = false,
    val activeTab: PlayerDrawerTab = PlayerDrawerTab.EPISODES,
    val currentShowTitle: String = "",
    val activeSeasonName: String = "",
    val episodes: List<InPlayerEpisodeItem> = emptyList(),
    val currentEpisodeIndex: Int = 0,
    val availableSources: List<ExtractorLink> = emptyList(),
    val currentPlayingSourceUrl: String? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioTrackId: Int = 0,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleTrackId: Int = 0,
    val subtitleDelaySec: Double = 0.0,
    val audioDelaySec: Double = 0.0,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerRemainingSec: Long? = null,
    val isSwitchingEpisode: Boolean = false,
)

/**
 * Reactive ViewModel managing In-Player Drawer lifecycle, episode switching,
 * and audio/subtitle track selection.
 */
class PlayerDrawerViewModel(
    private val mpvPlayer: MpvPlayer,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val _drawerState = MutableStateFlow(InPlayerDrawerState())
    val drawerState: StateFlow<InPlayerDrawerState> = _drawerState.asStateFlow()

    private var currentEpisodesList: List<Episode> = emptyList()
    private var currentProvider: MainAPI? = null
    private var parentId: String? = null

    init {
        // Observe track manager and player state for continuous track & delay updates
        mpvPlayer.trackManager.audioTracks
            .onEach { audios ->
                _drawerState.update { current ->
                    current.copy(
                        audioTracks = audios,
                        selectedAudioTrackId = mpvPlayer.trackManager.selectedAudioTrackId.value
                    )
                }
            }
            .launchIn(scope)

        mpvPlayer.trackManager.subtitleTracks
            .onEach { subs ->
                _drawerState.update { current ->
                    current.copy(
                        subtitleTracks = subs,
                        selectedSubtitleTrackId = mpvPlayer.trackManager.selectedSubtitleTrackId.value
                    )
                }
            }
            .launchIn(scope)

        mpvPlayer.trackManager.subtitleDelaySec
            .onEach { delay ->
                _drawerState.update { it.copy(subtitleDelaySec = delay) }
            }
            .launchIn(scope)
    }

    /**
     * Initializes the drawer with episode data and active parent identifier.
     */
    fun initialize(
        parentId: String,
        showTitle: String,
        episodes: List<Episode>,
        currentPlayingEpisodeData: String?,
        provider: MainAPI? = null,
    ) {
        this.parentId = parentId
        this.currentProvider = provider
        this.currentEpisodesList = episodes

        val items = episodes.mapIndexed { index, ep ->
            val history = WatchHistoryRepository.getLastWatched(parentId, ep.data)
                ?: DesktopDataStore.getEpisodeWatched(parentId, ep.data)
            val dur = history?.duration ?: 0L
            val pos = history?.position ?: 0L
            val prog = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
            val isCurrent = ep.data == currentPlayingEpisodeData || (currentPlayingEpisodeData == null && index == 0)

            InPlayerEpisodeItem(
                episode = ep,
                isCurrentPlaying = isCurrent,
                watchHistory = history,
                formattedDuration = if (dur > 0L) "${dur / 60000} min" else "",
                progressPercentage = prog,
            )
        }

        val currentIndex = items.indexOfFirst { it.isCurrentPlaying }.coerceAtLeast(0)
        val initialSeason = episodes.getOrNull(currentIndex)?.season?.let { "Season $it" } ?: "Episodes"

        _drawerState.update { current ->
            current.copy(
                currentShowTitle = showTitle,
                activeSeasonName = initialSeason,
                episodes = items,
                currentEpisodeIndex = currentIndex,
            )
        }
    }

    fun setAvailableSources(sources: List<ExtractorLink>, currentUrl: String?) {
        _drawerState.update { it.copy(availableSources = sources, currentPlayingSourceUrl = currentUrl) }
    }

    fun openDrawer(initialTab: PlayerDrawerTab = PlayerDrawerTab.EPISODES) {
        _drawerState.update { it.copy(isOpen = true, activeTab = initialTab) }
    }

    fun closeDrawer() {
        _drawerState.update { it.copy(isOpen = false) }
    }

    fun toggleDrawer() {
        _drawerState.update { it.copy(isOpen = !it.isOpen) }
    }

    fun selectTab(tab: PlayerDrawerTab) {
        _drawerState.update { it.copy(activeTab = tab) }
    }

    fun updateActiveSeason(seasonName: String) {
        _drawerState.update { it.copy(activeSeasonName = seasonName) }
    }

    fun selectAudioTrack(trackId: Int) {
        mpvPlayer.trackManager.selectAudioTrack(trackId)
        _drawerState.update { it.copy(selectedAudioTrackId = trackId) }
    }

    fun selectSubtitleTrack(trackId: Int) {
        mpvPlayer.trackManager.selectSubtitleTrack(trackId)
        _drawerState.update { it.copy(selectedSubtitleTrackId = trackId) }
    }

    fun adjustSubtitleDelay(deltaSec: Double) {
        mpvPlayer.trackManager.adjustSubtitleDelay(deltaSec)
    }

    fun resetSubtitleDelay() {
        mpvPlayer.trackManager.resetSubtitleDelay()
    }

    private var sleepTimerJob: Job? = null

    fun adjustAudioDelay(deltaSec: Double) {
        val current = _drawerState.value.audioDelaySec
        val target = ((current + deltaSec) * 100.0).let { Math.round(it) / 100.0 }
        val clamped = target.coerceIn(-5.0, 5.0)
        _drawerState.update { it.copy(audioDelaySec = clamped) }
        mpvPlayer.ipcClient.sendCommand(listOf("set_property", "audio-delay", clamped))
    }

    fun resetAudioDelay() {
        _drawerState.update { it.copy(audioDelaySec = 0.0) }
        mpvPlayer.ipcClient.sendCommand(listOf("set_property", "audio-delay", 0.0))
    }

    fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimerJob?.cancel()
        _drawerState.update { it.copy(sleepTimerMode = mode, sleepTimerRemainingSec = null) }

        if (mode == SleepTimerMode.OFF) return

        if (mode == SleepTimerMode.END_OF_EPISODE) {
            sleepTimerJob = scope.launch {
                mpvPlayer.state.collect { st ->
                    if (st.isFinishedOrCompleted) {
                        mpvPlayer.pause()
                        _drawerState.update { it.copy(sleepTimerMode = SleepTimerMode.OFF, sleepTimerRemainingSec = null) }
                        this@launch.cancel()
                    }
                }
            }
        } else if (mode.minutes != null) {
            sleepTimerJob = scope.launch {
                var remaining = mode.minutes * 60L
                while (remaining > 0) {
                    _drawerState.update { it.copy(sleepTimerRemainingSec = remaining) }
                    delay(1000L)
                    remaining--
                }
                _drawerState.update { it.copy(sleepTimerRemainingSec = 0L, sleepTimerMode = SleepTimerMode.OFF) }
                mpvPlayer.pause()
            }
        }
    }

    fun cancelSleepTimer() {
        setSleepTimer(SleepTimerMode.OFF)
    }

    /**
     * Seamlessly loads a new episode without tearing down or closing the player.
     */
    fun playEpisode(
        item: InPlayerEpisodeItem,
        onLaunchStream: (Episode) -> Unit,
    ) {
        if (item.isCurrentPlaying || _drawerState.value.isSwitchingEpisode) return

        _drawerState.update { it.copy(isSwitchingEpisode = true) }

        scope.launch {
            try {
                // 1. Commit watch history of current episode
                val currentHistory = mpvPlayer.historyCoordinator.currentItem
                if (currentHistory != null) {
                    val pos = mpvPlayer.state.value.position
                    val dur = mpvPlayer.state.value.duration
                    if (dur > 0L) {
                        val isCompleted = pos.toDouble() / dur.toDouble() >= 0.90
                        WatchHistoryRepository.save(
                            currentHistory.toWatchHistory(pos, dur, isCompleted)
                        )
                    }
                }

                // 2. Trigger stream extraction without tearing down MPV window
                onLaunchStream(item.episode)

                // 3. Update current playing indicator and close drawer
                _drawerState.update { current ->
                    val updatedList = current.episodes.map { epItem ->
                        epItem.copy(isCurrentPlaying = epItem.episode.data == item.episode.data)
                    }
                    val newIndex = updatedList.indexOfFirst { it.isCurrentPlaying }
                    current.copy(
                        episodes = updatedList,
                        currentEpisodeIndex = newIndex.coerceAtLeast(0),
                        isSwitchingEpisode = false,
                        isOpen = false,
                    )
                }
            } catch (e: Exception) {
                AppLogger.e("InPlayerEpisodeDrawer: Failed to switch episode: ${e.message}")
                _drawerState.update { it.copy(isSwitchingEpisode = false) }
            }
        }
    }

    fun formatLanguageName(rawTag: String?): String {
        return MpvTrackManager.resolveLanguageName(rawTag)
    }
}

/**
 * In-Player Episode Switcher & Audio/Subtitle Track Drawer.
 * Right-anchored sliding drawer (400dp width) over running video with translucent AMOLED background (#CC050508).
 */
@Composable
fun InPlayerEpisodeDrawer(
    viewModel: PlayerDrawerViewModel,
    modifier: Modifier = Modifier,
    onLaunchEpisodeStream: (Episode) -> Unit,
    onSelectSource: ((ExtractorLink) -> Unit)? = null,
) {
    val state by viewModel.drawerState.collectAsState()
    val drawerFocusRequester = remember { FocusRequester() }

    AnimatedVisibility(
        visible = state.isOpen,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(200)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(200)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .interceptTvKeyNavigation { action ->
                    when (action) {
                        TvNavAction.BACK -> {
                            viewModel.closeDrawer()
                            true
                        }
                        else -> false
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.Back, Key.Escape -> {
                                viewModel.closeDrawer()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            // Scrim Vignette over video plane (click to dismiss, video continues playback)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to Color.Transparent,
                            0.5f to Color(0x66000000),
                            1.0f to Color(0xCC050508)
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        viewModel.closeDrawer()
                    }
            )

            // Right-anchored sliding drawer (400dp width) with translucent AMOLED background (#CC050508)
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(400.dp)
                    .align(Alignment.CenterEnd)
                    .focusRequester(drawerFocusRequester),
                color = Color(0xCC050508),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                border = BorderStroke(1.dp, TvColors.BorderSubtle),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    // Header: Show Title & Current Section
                    Text(
                        text = state.currentShowTitle.ifBlank { "CloudStream Player" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = TvColors.TextSecondary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = when (state.activeTab) {
                            PlayerDrawerTab.EPISODES -> state.activeSeasonName
                            PlayerDrawerTab.SOURCES -> "Sources & Mirrors"
                            PlayerDrawerTab.AUDIO -> "Audio & Sync"
                            PlayerDrawerTab.SUBTITLES -> "Subtitles & Offset"
                            PlayerDrawerTab.TRACKS -> "Audio & Subtitles"
                            PlayerDrawerTab.SLEEP_TIMER -> "Sleep Timer"
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = TvColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )

                    // Navigation Tabs
                    DrawerTabBar(
                        activeTab = state.activeTab,
                        onTabSelected = { viewModel.selectTab(it) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tab Content
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (state.activeTab) {
                            PlayerDrawerTab.EPISODES -> {
                                EpisodeSwitcherContent(
                                    state = state,
                                    onEpisodeSelected = { item ->
                                        viewModel.playEpisode(item, onLaunchEpisodeStream)
                                    },
                                    onSeasonVisible = { season ->
                                        viewModel.updateActiveSeason(season)
                                    }
                                )
                            }
                            PlayerDrawerTab.SOURCES -> {
                                SourcesContent(
                                    sources = state.availableSources,
                                    currentUrl = state.currentPlayingSourceUrl,
                                    onSelectSource = { link ->
                                        onSelectSource?.invoke(link)
                                    }
                                )
                            }
                            PlayerDrawerTab.AUDIO -> {
                                AudioTrackPickerContent(
                                    state = state,
                                    onTrackSelected = { viewModel.selectAudioTrack(it.id) },
                                    onAdjustDelay = { delta -> viewModel.adjustAudioDelay(delta) },
                                    onResetDelay = { viewModel.resetAudioDelay() },
                                    formatLanguage = { viewModel.formatLanguageName(it) }
                                )
                            }
                            PlayerDrawerTab.SUBTITLES -> {
                                SubtitlePickerAndDelayContent(
                                    state = state,
                                    onSelectSubtitle = { viewModel.selectSubtitleTrack(it) },
                                    onAdjustDelay = { delta -> viewModel.adjustSubtitleDelay(delta) },
                                    onResetDelay = { viewModel.resetSubtitleDelay() },
                                    formatLanguage = { viewModel.formatLanguageName(it) }
                                )
                            }
                            PlayerDrawerTab.TRACKS -> {
                                CombinedTracksContent(
                                    state = state,
                                    onSelectAudio = { viewModel.selectAudioTrack(it.id) },
                                    onSelectSubtitle = { viewModel.selectSubtitleTrack(it) },
                                    onAdjustDelay = { delta -> viewModel.adjustSubtitleDelay(delta) },
                                    onResetDelay = { viewModel.resetSubtitleDelay() },
                                    onAdjustAudioDelay = { delta -> viewModel.adjustAudioDelay(delta) },
                                    onResetAudioDelay = { viewModel.resetAudioDelay() },
                                    formatLanguage = { viewModel.formatLanguageName(it) }
                                )
                            }
                            PlayerDrawerTab.SLEEP_TIMER -> {
                                SleepTimerContent(
                                    state = state,
                                    onSelectMode = { viewModel.setSleepTimer(it) },
                                    onCancel = { viewModel.cancelSleepTimer() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerTabBar(
    activeTab: PlayerDrawerTab,
    onTabSelected: (PlayerDrawerTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
            .background(TvColors.SurfaceCard)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DrawerTabButton(
            title = "Episodes",
            icon = Icons.AutoMirrored.Filled.List,
            isSelected = activeTab == PlayerDrawerTab.EPISODES,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PlayerDrawerTab.EPISODES) }
        )
        DrawerTabButton(
            title = "Sources",
            icon = Icons.Default.Dns,
            isSelected = activeTab == PlayerDrawerTab.SOURCES,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PlayerDrawerTab.SOURCES) }
        )
        DrawerTabButton(
            title = "Audio",
            icon = Icons.Default.PlayArrow,
            isSelected = activeTab == PlayerDrawerTab.AUDIO,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PlayerDrawerTab.AUDIO) }
        )
        DrawerTabButton(
            title = "Subtitles",
            icon = Icons.Default.Settings,
            isSelected = activeTab == PlayerDrawerTab.SUBTITLES || activeTab == PlayerDrawerTab.TRACKS,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PlayerDrawerTab.SUBTITLES) }
        )
        DrawerTabButton(
            title = "Sleep",
            icon = Icons.Default.Notifications,
            isSelected = activeTab == PlayerDrawerTab.SLEEP_TIMER,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(PlayerDrawerTab.SLEEP_TIMER) }
        )
    }
}

@Composable
private fun DrawerTabButton(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
            .background(
                when {
                    isFocused -> TvColors.FocusElectricBlue
                    isSelected -> TvColors.SurfaceElevated
                    else -> Color.Transparent
                }
            )
            .focusable(interactionSource = interactionSource)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isFocused || isSelected) Color.White else TvColors.TextSecondary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isFocused || isSelected) Color.White else TvColors.TextSecondary,
                    fontSize = 11.sp
                )
            )
        }
    }
}

@Composable
fun EpisodeSwitcherContent(
    state: InPlayerDrawerState,
    onEpisodeSelected: (InPlayerEpisodeItem) -> Unit,
    onSeasonVisible: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

    LaunchedEffect(state.currentEpisodeIndex) {
        if (state.currentEpisodeIndex in state.episodes.indices) {
            listState.scrollToItem(state.currentEpisodeIndex)
            focusRequesters[state.currentEpisodeIndex]?.requestFocus()
        }
    }

    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex) {
        state.episodes.getOrNull(firstVisibleItemIndex)?.episode?.season?.let {
            onSeasonVisible("Season $it")
        }
    }

    if (state.episodes.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No episodes available",
                style = MaterialTheme.typography.bodyMedium.copy(color = TvColors.TextSecondary)
            )
        }
    } else {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = state.episodes,
                key = { _, item -> item.episode.data }
            ) { index, item ->
                val itemFocusRequester = focusRequesters.getOrPut(index) { FocusRequester() }
                InPlayerEpisodeRow(
                    item = item,
                    focusRequester = itemFocusRequester,
                    onClick = { onEpisodeSelected(item) }
                )
            }
        }
    }
}

@Composable
private fun InPlayerEpisodeRow(
    item: InPlayerEpisodeItem,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
        color = when {
            isFocused -> TvColors.SurfaceElevated
            item.isCurrentPlaying -> TvColors.SurfaceHigh
            else -> TvColors.SurfaceCard
        },
        border = if (isFocused) {
            BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
        } else if (item.isCurrentPlaying) {
            BorderStroke(1.dp, TvColors.FocusEmerald)
        } else {
            BorderStroke(1.dp, TvColors.BorderSubtle)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with progress indicator
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black)
            ) {
                if (!item.episode.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.episode.posterUrl,
                        contentDescription = item.episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (item.isCurrentPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Currently Playing",
                            tint = TvColors.FocusEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (item.progressPercentage > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progressPercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = TvColors.ProgressWatched,
                        trackColor = Color(0x66FFFFFF),
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                val epNumber = item.episode.episode?.let { "E$it • " } ?: ""
                val epTitle = item.episode.name?.takeIf { it.isNotBlank() } ?: "Episode ${item.episode.episode ?: ""}"

                Text(
                    text = "$epNumber$epTitle",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (item.isCurrentPlaying || isFocused) FontWeight.Bold else FontWeight.Medium,
                        color = if (isFocused) Color.White else TvColors.TextPrimary,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.formattedDuration.isNotBlank()) {
                        Text(
                            text = item.formattedDuration,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TvColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }

                    if (item.watchHistory?.isCompleted == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Watched",
                            tint = TvColors.FocusEmerald,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioTrackPickerContent(
    state: InPlayerDrawerState,
    onTrackSelected: (AudioTrack) -> Unit,
    onAdjustDelay: (Double) -> Unit,
    onResetDelay: () -> Unit,
    formatLanguage: (String?) -> String,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AudioDelayCalibrationBox(
            delaySec = state.audioDelaySec,
            onAdjustDelay = onAdjustDelay,
            onResetDelay = onResetDelay
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Audio Streams",
            style = MaterialTheme.typography.labelLarge.copy(
                color = TvColors.TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (state.audioTracks.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No secondary audio tracks available",
                style = MaterialTheme.typography.bodyMedium.copy(color = TvColors.TextSecondary)
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.audioTracks, key = { it.id }) { track ->
                AudioTrackRow(
                    track = track,
                    isSelected = track.id == state.selectedAudioTrackId,
                    formatLanguage = formatLanguage,
                    onClick = { onTrackSelected(track) }
                )
            }
        }
    }
    }
}

@Composable
private fun AudioTrackRow(
    track: AudioTrack,
    isSelected: Boolean,
    formatLanguage: (String?) -> String,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val langDisplay = formatLanguage(track.lang)
    val channelDisplay = track.channels.orEmpty()
    val codecDisplay = track.codec.orEmpty()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
        color = when {
            isFocused -> TvColors.SurfaceElevated
            isSelected -> TvColors.SurfaceHigh
            else -> TvColors.SurfaceCard
        },
        border = if (isFocused) {
            BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
        } else if (isSelected) {
            BorderStroke(1.dp, TvColors.FocusEmerald)
        } else {
            BorderStroke(1.dp, TvColors.BorderSubtle)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = TvColors.FocusEmerald,
                    unselectedColor = TvColors.TextSecondary
                )
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOfNotNull(
                        langDisplay,
                        track.title.takeIf { !it.isNullOrBlank() && it != langDisplay }
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isFocused) Color.White else TvColors.TextPrimary,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val details = listOfNotNull(
                    channelDisplay.takeIf { it.isNotBlank() },
                    codecDisplay.takeIf { it.isNotBlank() }
                ).joinToString(" • ")

                if (details.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TvColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = TvColors.FocusEmerald,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun SubtitlePickerAndDelayContent(
    state: InPlayerDrawerState,
    onSelectSubtitle: (Int) -> Unit,
    onAdjustDelay: (Double) -> Unit,
    onResetDelay: () -> Unit,
    formatLanguage: (String?) -> String,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Subtitle Synchronization Delay Slider (-5.0s to +5.0s)
        SubtitleDelayCalibrationBox(
            delaySec = state.subtitleDelaySec,
            onAdjustDelay = onAdjustDelay,
            onResetDelay = onResetDelay
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Subtitle Tracks",
            style = MaterialTheme.typography.labelLarge.copy(
                color = TvColors.TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            // Option to turn subtitles OFF (trackId = 0)
            item {
                SubtitleTrackRow(
                    title = "Off / No Subtitles",
                    subtitle = "Disable subtitle rendering",
                    isSelected = state.selectedSubtitleTrackId <= 0,
                    onClick = { onSelectSubtitle(0) }
                )
            }

            items(state.subtitleTracks, key = { it.id }) { track ->
                val lang = formatLanguage(track.lang)
                val badge = when (track.origin) {
                    SubtitleOrigin.EMBEDDED_IN_VIDEO -> "Embedded"
                    SubtitleOrigin.URL -> "Online"
                    SubtitleOrigin.DOWNLOADED_FILE -> "Downloaded"
                }

                SubtitleTrackRow(
                    title = track.title?.takeIf { it.isNotBlank() } ?: lang,
                    subtitle = badge,
                    isSelected = track.id == state.selectedSubtitleTrackId,
                    onClick = { onSelectSubtitle(track.id) }
                )
            }
        }
    }
}

@Composable
fun CombinedTracksContent(
    state: InPlayerDrawerState,
    onSelectAudio: (AudioTrack) -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onAdjustDelay: (Double) -> Unit,
    onResetDelay: () -> Unit,
    onAdjustAudioDelay: (Double) -> Unit,
    onResetAudioDelay: () -> Unit,
    formatLanguage: (String?) -> String,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            SubtitleDelayCalibrationBox(
                delaySec = state.subtitleDelaySec,
                onAdjustDelay = onAdjustDelay,
                onResetDelay = onResetDelay
            )
        }

        item {
            AudioDelayCalibrationBox(
                delaySec = state.audioDelaySec,
                onAdjustDelay = onAdjustAudioDelay,
                onResetDelay = onResetAudioDelay
            )
        }

        item {
            Text(
                text = "Audio Streams",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = TvColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        items(state.audioTracks, key = { "audio_${it.id}" }) { track ->
            AudioTrackRow(
                track = track,
                isSelected = track.id == state.selectedAudioTrackId,
                formatLanguage = formatLanguage,
                onClick = { onSelectAudio(track) }
            )
        }

        item {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.labelLarge.copy(
                    color = TvColors.TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            SubtitleTrackRow(
                title = "Off / No Subtitles",
                subtitle = "Disable subtitle rendering",
                isSelected = state.selectedSubtitleTrackId <= 0,
                onClick = { onSelectSubtitle(0) }
            )
        }

        items(state.subtitleTracks, key = { "sub_${it.id}" }) { track ->
            val lang = formatLanguage(track.lang)
            val badge = when (track.origin) {
                SubtitleOrigin.EMBEDDED_IN_VIDEO -> "Embedded"
                SubtitleOrigin.URL -> "Online"
                SubtitleOrigin.DOWNLOADED_FILE -> "Downloaded"
            }
            SubtitleTrackRow(
                title = track.title?.takeIf { it.isNotBlank() } ?: lang,
                subtitle = badge,
                isSelected = track.id == state.selectedSubtitleTrackId,
                onClick = { onSelectSubtitle(track.id) }
            )
        }
    }
}

@Composable
private fun SubtitleDelayCalibrationBox(
    delaySec: Double,
    onAdjustDelay: (Double) -> Unit,
    onResetDelay: () -> Unit,
) {
    var isSliderFocused by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall)),
        color = TvColors.SurfaceCard,
        border = BorderStroke(
            if (isSliderFocused) TvDimensions.BorderGlow else 1.dp,
            if (isSliderFocused) TvColors.FocusElectricBlue else TvColors.BorderSubtle
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Subtitle Timing Offset",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = TvColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                )

                val sign = if (delaySec > 0.0) "+" else ""
                Text(
                    text = String.format(Locale.US, "%s%.2fs", sign, delaySec),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = when {
                            delaySec > 0.0 -> TvColors.FocusElectricBlue
                            delaySec < 0.0 -> TvColors.AccentAmber
                            else -> TvColors.FocusEmerald
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // D-Pad controllable slider (-5.0s to +5.0s in 100ms steps)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .focusable()
                    .onFocusChanged { isSliderFocused = it.isFocused }
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionLeft -> {
                                    onAdjustDelay(-0.10)
                                    true
                                }
                                Key.DirectionRight -> {
                                    onAdjustDelay(0.10)
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = delaySec.toFloat().coerceIn(-5.0f, 5.0f),
                    onValueChange = { targetVal ->
                        onAdjustDelay(targetVal.toDouble() - delaySec)
                    },
                    valueRange = -5.0f..5.0f,
                    steps = 99,
                    colors = SliderDefaults.colors(
                        thumbColor = if (isSliderFocused) Color.White else TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.SurfaceElevated,
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick preset step buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QuickStepButton(text = "-1.0s", modifier = Modifier.weight(1f)) { onAdjustDelay(-1.0) }
                QuickStepButton(text = "-0.1s", modifier = Modifier.weight(1f)) { onAdjustDelay(-0.1) }
                QuickStepButton(text = "Reset", modifier = Modifier.weight(1f)) { onResetDelay() }
                QuickStepButton(text = "+0.1s", modifier = Modifier.weight(1f)) { onAdjustDelay(0.1) }
                QuickStepButton(text = "+1.0s", modifier = Modifier.weight(1f)) { onAdjustDelay(1.0) }
            }
        }
    }
}

@Composable
private fun QuickStepButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isFocused) TvColors.FocusElectricBlue else TvColors.SurfaceElevated)
            .focusable(interactionSource = interactionSource)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (isFocused) Color.White else TvColors.TextSecondary,
                fontSize = 11.sp
            )
        )
    }
}

@Composable
private fun SubtitleTrackRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
        color = when {
            isFocused -> TvColors.SurfaceElevated
            isSelected -> TvColors.SurfaceHigh
            else -> TvColors.SurfaceCard
        },
        border = if (isFocused) {
            BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
        } else if (isSelected) {
            BorderStroke(1.dp, TvColors.FocusEmerald)
        } else {
            BorderStroke(1.dp, TvColors.BorderSubtle)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = TvColors.FocusEmerald,
                    unselectedColor = TvColors.TextSecondary
                )
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isFocused) Color.White else TvColors.TextPrimary,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TvColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = TvColors.FocusEmerald,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}


@Composable
fun AudioDelayCalibrationBox(
    delaySec: Double,
    onAdjustDelay: (Double) -> Unit,
    onResetDelay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isSliderFocused by remember { mutableStateOf(false) }
    val delayMs = (delaySec * 1000.0).let { Math.round(it) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall)),
        color = TvColors.SurfaceCard,
        border = BorderStroke(
            if (isSliderFocused) TvDimensions.BorderGlow else 1.dp,
            if (isSliderFocused) TvColors.FocusElectricBlue else TvColors.BorderSubtle
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audio Timing Offset (Sync)",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = TvColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                )

                val sign = if (delayMs > 0) "+" else ""
                Text(
                    text = "ms",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = when {
                            delayMs > 0 -> TvColors.FocusElectricBlue
                            delayMs < 0 -> TvColors.AccentAmber
                            else -> TvColors.FocusEmerald
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .focusable()
                    .onFocusChanged { isSliderFocused = it.isFocused }
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.key) {
                                Key.DirectionLeft -> {
                                    onAdjustDelay(-0.05)
                                    true
                                }
                                Key.DirectionRight -> {
                                    onAdjustDelay(0.05)
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
                contentAlignment = Alignment.Center
            ) {
                Slider(
                    value = delaySec.toFloat().coerceIn(-5.0f, 5.0f),
                    onValueChange = { targetVal ->
                        onAdjustDelay(targetVal.toDouble() - delaySec)
                    },
                    valueRange = -5.0f..5.0f,
                    steps = 199,
                    colors = SliderDefaults.colors(
                        thumbColor = if (isSliderFocused) Color.White else TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.SurfaceElevated,
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QuickStepButton(text = "-500ms", modifier = Modifier.weight(1f)) { onAdjustDelay(-0.5) }
                QuickStepButton(text = "-50ms", modifier = Modifier.weight(1f)) { onAdjustDelay(-0.05) }
                QuickStepButton(text = "Reset", modifier = Modifier.weight(1f)) { onResetDelay() }
                QuickStepButton(text = "+50ms", modifier = Modifier.weight(1f)) { onAdjustDelay(0.05) }
                QuickStepButton(text = "+500ms", modifier = Modifier.weight(1f)) { onAdjustDelay(0.5) }
            }
        }
    }
}

@Composable
fun SleepTimerContent(
    state: InPlayerDrawerState,
    onSelectMode: (SleepTimerMode) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
            color = if (state.sleepTimerMode != SleepTimerMode.OFF) TvColors.SurfaceHigh else TvColors.SurfaceCard,
            border = BorderStroke(
                1.dp,
                if (state.sleepTimerMode != SleepTimerMode.OFF) TvColors.FocusEmerald else TvColors.BorderSubtle
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Sleep Timer",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    )
                    val statusText = when {
                        state.sleepTimerRemainingSec != null -> {
                            val mins = state.sleepTimerRemainingSec / 60
                            val secs = state.sleepTimerRemainingSec % 60
                            String.format(Locale.US, "Active: %02d:%02d remaining", mins, secs)
                        }
                        state.sleepTimerMode == SleepTimerMode.END_OF_EPISODE -> {
                            "Active: Pauses at end of episode"
                        }
                        else -> "Disabled / No timer active"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (state.sleepTimerMode != SleepTimerMode.OFF) TvColors.FocusEmerald else TvColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    )
                }

                if (state.sleepTimerMode != SleepTimerMode.OFF) {
                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Cancel", style = TvTypography.Button.copy(fontSize = 12.sp, color = TvColors.ErrorRed))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Timer Presets",
            style = MaterialTheme.typography.labelLarge.copy(
                color = TvColors.TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(SleepTimerMode.entries) { mode ->
                val isSelected = state.sleepTimerMode == mode
                var isFocused by remember { mutableStateOf(false) }
                val interactionSource = remember { MutableInteractionSource() }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable(interactionSource = interactionSource)
                        .clickable(interactionSource = interactionSource, indication = null) {
                            onSelectMode(mode)
                        },
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                    color = when {
                        isFocused -> TvColors.SurfaceElevated
                        isSelected -> TvColors.SurfaceHigh
                        else -> TvColors.SurfaceCard
                    },
                    border = if (isFocused) {
                        BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
                    } else if (isSelected) {
                        BorderStroke(1.dp, TvColors.FocusEmerald)
                    } else {
                        BorderStroke(1.dp, TvColors.BorderSubtle)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = TvColors.FocusEmerald,
                                unselectedColor = TvColors.TextSecondary
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = if (isFocused) Color.White else TvColors.TextPrimary,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active",
                                tint = TvColors.FocusEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sources & Mirrors content matching upstream GeneratorPlayer showMirrorsDialogue() parity.
 */
@Composable
fun SourcesContent(
    sources: List<ExtractorLink>,
    currentUrl: String?,
    onSelectSource: (ExtractorLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Henüz alternatif kaynak bulunamadı veya arama sürüyor...",
                color = TvColors.TextMuted,
                style = TvTypography.Body
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sources) { link ->
                val isSelected = link.url == currentUrl
                var isFocused by remember { mutableStateOf(false) }
                val interactionSource = remember { MutableInteractionSource() }

                Surface(
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                    color = when {
                        isFocused -> TvColors.SurfaceElevated
                        isSelected -> TvColors.SurfaceHigh
                        else -> TvColors.SurfaceCard
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isFocused) TvColors.FocusElectricBlue else if (isSelected) TvColors.FocusEmerald else TvColors.BorderSubtle
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable(interactionSource = interactionSource)
                        .clickable(interactionSource = interactionSource, indication = null) { onSelectSource(link) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = link.name.ifBlank { link.source },
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isFocused) Color.White else TvColors.TextPrimary,
                                    fontSize = 13.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = link.source,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TvColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Quality Badge
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = TvColors.SurfaceElevated,
                                border = BorderStroke(1.dp, TvColors.BorderSubtle)
                            ) {
                                Text(
                                    text = Qualities.getStringByInt(link.quality),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = TvColors.FocusElectricBlue,
                                        fontSize = 10.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Active Source",
                                    tint = TvColors.FocusEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
