package com.lagradost.cloudstream3.desktop.ui.player

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.api.PlayerState
import com.lagradost.player.osd.PlayerHudMetadata
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Aspect ratio presentation modes for MPV IPC scaling.
 */
enum class AspectRatioMode(val label: String) {
    FIT("Fit (1:1)"),
    FILL("Fill (Crop)"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    STRETCH("Stretch"),
}

/**
 * Formats milliseconds to HH:MM:SS or MM:SS presentation.
 */
fun formatTimeMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Configures MPV aspect ratio via IPC commands.
 */
fun applyAspectRatio(player: MpvPlayer, mode: AspectRatioMode) {
    when (mode) {
        AspectRatioMode.FIT -> {
            player.ipcClient.sendCommand(listOf("set_property", "video-aspect-override", "-1"))
            player.ipcClient.sendCommand(listOf("set_property", "panscan", 0.0))
        }
        AspectRatioMode.FILL -> {
            player.ipcClient.sendCommand(listOf("set_property", "video-aspect-override", "-1"))
            player.ipcClient.sendCommand(listOf("set_property", "panscan", 1.0))
        }
        AspectRatioMode.RATIO_16_9 -> {
            player.ipcClient.sendCommand(listOf("set_property", "video-aspect-override", "16:9"))
            player.ipcClient.sendCommand(listOf("set_property", "panscan", 0.0))
        }
        AspectRatioMode.RATIO_4_3 -> {
            player.ipcClient.sendCommand(listOf("set_property", "video-aspect-override", "4:3"))
            player.ipcClient.sendCommand(listOf("set_property", "panscan", 0.0))
        }
        AspectRatioMode.STRETCH -> {
            player.ipcClient.sendCommand(listOf("set_property", "video-aspect-override", "16:9"))
            player.ipcClient.sendCommand(listOf("set_property", "panscan", 0.0))
        }
    }
}

/**
 * Configures Mediamp player aspect ratio and MPV IPC commands.
 */
fun applyAspectRatio(player: MediampPlayer, mode: AspectRatioMode) {
    val aspect = player.features[VideoAspectRatio]
    when (mode) {
        AspectRatioMode.FIT -> aspect?.setMode(org.openani.mediamp.features.AspectRatioMode.FIT)
        AspectRatioMode.FILL -> aspect?.setMode(org.openani.mediamp.features.AspectRatioMode.CROP)
        AspectRatioMode.STRETCH -> aspect?.setMode(org.openani.mediamp.features.AspectRatioMode.STRETCH)
        else -> {}
    }
    (player.impl as? MPVHandle)?.let { handle ->
        when (mode) {
            AspectRatioMode.FIT -> {
                handle.command("set_property", "video-aspect-override", "-1")
                handle.command("set_property", "panscan", "0.0")
            }
            AspectRatioMode.FILL -> {
                handle.command("set_property", "video-aspect-override", "-1")
                handle.command("set_property", "panscan", "1.0")
            }
            AspectRatioMode.RATIO_16_9 -> {
                handle.command("set_property", "video-aspect-override", "16:9")
                handle.command("set_property", "panscan", "0.0")
            }
            AspectRatioMode.RATIO_4_3 -> {
                handle.command("set_property", "video-aspect-override", "4:3")
                handle.command("set_property", "panscan", "0.0")
            }
            AspectRatioMode.STRETCH -> {
                handle.command("set_property", "video-aspect-override", "16:9")
                handle.command("set_property", "panscan", "0.0")
            }
        }
    }
}

@Composable
fun PauseIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Row(
        modifier = modifier.size(18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(14.dp)
                .background(tint, RoundedCornerShape(1.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(14.dp)
                .background(tint, RoundedCornerShape(1.dp))
        )
    }
}

@Composable
fun FullscreenIcon(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Text(
        text = "⛶",
        color = tint,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}

@Composable
fun VolumeIcon(isMuted: Boolean, volume: Double, modifier: Modifier = Modifier, tint: Color = Color.White) {
    val volRatio = if (isMuted) 0.0 else (volume / 100.0).coerceIn(0.0, 1.0)
    val icon = when {
        isMuted || volRatio <= 0.0 -> Icons.AutoMirrored.Filled.VolumeOff
        volRatio < 0.5 -> Icons.AutoMirrored.Filled.VolumeDown
        else -> Icons.AutoMirrored.Filled.VolumeUp
    }
    Icon(
        imageVector = icon,
        contentDescription = if (isMuted) "Mute" else "Volume",
        tint = if (isMuted) TvColors.ErrorRed else tint,
        modifier = modifier
    )
}

/**
 * Mathematical model and colorimetry constants for the Netflix-style 680dp left scrim gradient.
 * 1:1 Parity with upstream's bg_player_metadata_scrim_netflix.xml.
 */
object NetflixScrimMath {
    val ScrimWidth: Dp = 680.dp
    const val SCRIM_WIDTH_DP: Float = 680f

    const val STOP_0_RATIO: Float = 0.0f
    const val STOP_1_RATIO: Float = 0.5f
    const val STOP_2_RATIO: Float = 1.0f

    const val STOP_0_POS_DP: Float = 0f
    const val STOP_1_POS_DP: Float = 340f
    const val STOP_2_POS_DP: Float = 680f

    // #E6000000 (90.2% black alpha: 230/255)
    val ColorStart: Color = Color(0xE6000000)
    // #99000000 (60.0% black alpha: 153/255)
    val ColorCenter: Color = Color(0x99000000)
    // #00000000 (0.0% black alpha: 0/255)
    val ColorEnd: Color = Color(0x00000000)

    val ColorStops: Array<Pair<Float, Color>> = arrayOf(
        STOP_0_RATIO to ColorStart,
        STOP_1_RATIO to ColorCenter,
        STOP_2_RATIO to ColorEnd
    )

    /**
     * Calculates the exact linear alpha value at any horizontal offset [xDp] from the screen left edge.
     */
    fun calculateAlphaAt(xDp: Float): Float {
        val clamped = xDp.coerceIn(0f, SCRIM_WIDTH_DP)
        val ratio = clamped / SCRIM_WIDTH_DP
        return if (ratio <= 0.5f) {
            val localRatio = ratio / 0.5f
            ColorStart.alpha + localRatio * (ColorCenter.alpha - ColorStart.alpha)
        } else {
            val localRatio = (ratio - 0.5f) / 0.5f
            ColorCenter.alpha + localRatio * (ColorEnd.alpha - ColorCenter.alpha)
        }
    }

    /**
     * Calculates the horizontal viewport coverage percentage of the 680dp scrim.
     * On standard 1080p display (1920x1080), 680dp = 35.4166%.
     */
    fun horizontalCoverageRatio(viewportWidthDp: Float = 1920f): Float {
        return SCRIM_WIDTH_DP / viewportWidthDp
    }
}

/**
 * Representation of the Title Logo Waterfall / Fallback resolution state.
 * Implements upstream ResultFragment.kt:bindLogo logic.
 */
sealed class TvLogoResolutionState {
    data class ShowLogo(val url: String) : TvLogoResolutionState()
    data class ShowTitleFallback(val title: String) : TvLogoResolutionState()
}

/**
 * Resolves whether to display the high-res logo artwork or fall back to 30sp drop-shadowed typography.
 */
fun resolveLogoFallback(
    url: String?,
    title: String,
    isImageError: Boolean = false
): TvLogoResolutionState {
    return if (!url.isNullOrBlank() && !isImageError) {
        TvLogoResolutionState.ShowLogo(url.trim())
    } else {
        TvLogoResolutionState.ShowTitleFallback(title)
    }
}

/**
 * Controller managing the reactive visibility and lifecycle of the TV Player HUD.
 * Coordinates 3-second auto-hide countdown, D-Pad reset tokens, and pause persistence.
 */
class TvPlayerHudController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    val autoHideDelayMs: Long = 3000L,
    val pausePersistenceDelayMs: Long = 8000L,
) {
    data class HudUiState(
        val isVisible: Boolean = false,
        val isPaused: Boolean = false,
        val metadata: PlayerHudMetadata? = null,
        val isLogoLoaded: Boolean = false,
        val isLogoError: Boolean = false,
        val alpha: Float = 0f,
    )

    private val _uiState = MutableStateFlow(HudUiState())
    val uiState: StateFlow<HudUiState> = _uiState.asStateFlow()

    private val visibilityToken = AtomicLong(0L)
    private var autoHideJob: Job? = null

    /**
     * Updates the underlying media metadata.
     */
    fun setMetadata(metadata: PlayerHudMetadata?) {
        _uiState.update { it.copy(metadata = metadata, isLogoLoaded = false, isLogoError = false) }
    }

    /**
     * Synchronizes state with playback events from MpvPlayer or MpvMediampPlayer.
     */
    fun onPlaybackStateChanged(isPaused: Boolean) {
        val wasPaused = _uiState.value.isPaused

        _uiState.update { it.copy(isPaused = isPaused) }

        if (!wasPaused && isPaused) {
            // Transitioned to paused: Show HUD persistently
            showHud(persistent = true)
        } else if (wasPaused && !isPaused) {
            // Playback resumed: Re-arm 3-second countdown
            if (_uiState.value.isVisible) {
                scheduleAutoHide(autoHideDelayMs)
            }
        }
    }

    /**
     * Synchronizes state with playback events from MpvPlayer.
     */
    fun onPlayerStateChanged(playerState: PlayerState) {
        onPlaybackStateChanged(playerState.isPaused)
    }

    /**
     * Intercepts D-Pad navigation and key interactions.
     * Resets the 3-second auto-hide countdown.
     * @return true if the event was consumed by the HUD to reveal itself.
     */
    fun onUserInteraction(isDpadNavigation: Boolean = true): Boolean {
        val currentlyVisible = _uiState.value.isVisible

        if (!currentlyVisible) {
            // Reveal HUD immediately on first interaction
            showHud(persistent = _uiState.value.isPaused)
            return isDpadNavigation // Consume initial wake-up key
        }

        // If already visible, reset the 3-second countdown timer
        resetAutoHideTimer()
        return false // Allow D-Pad to move focus between controls
    }

    /**
     * Explicitly displays the HUD.
     */
    fun showHud(persistent: Boolean = false) {
        val token = visibilityToken.incrementAndGet()
        autoHideJob?.cancel()

        _uiState.update { it.copy(isVisible = true, alpha = 1f) }

        if (!persistent && !_uiState.value.isPaused) {
            scheduleAutoHide(autoHideDelayMs, token)
        }
    }

    /**
     * Explicitly hides the HUD with an animated transition.
     */
    fun hideHud() {
        visibilityToken.incrementAndGet()
        autoHideJob?.cancel()
        _uiState.update { it.copy(isVisible = false, alpha = 0f) }
    }

    /**
     * Resets the auto-hide timer to a fresh 3-second window.
     */
    fun resetAutoHideTimer() {
        val token = visibilityToken.incrementAndGet()
        autoHideJob?.cancel()

        if (!_uiState.value.isPaused) {
            scheduleAutoHide(autoHideDelayMs, token)
        }
    }

    private fun scheduleAutoHide(delayMs: Long, token: Long = visibilityToken.incrementAndGet()) {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(delayMs)
            // Monotonic guard: Ensure no interaction occurred during the countdown
            if (token == visibilityToken.get() && !_uiState.value.isPaused) {
                hideHud()
            }
        }
    }

    fun onLogoLoadSuccess() {
        _uiState.update { it.copy(isLogoLoaded = true, isLogoError = false) }
    }

    fun onLogoLoadError() {
        _uiState.update { it.copy(isLogoLoaded = false, isLogoError = true) }
    }

    fun dispose() {
        autoHideJob?.cancel()
        scope.coroutineContext[Job]?.cancelChildren()
    }
}

/**
 * 10-Foot TV Leanback Player HUD featuring the 1:1 Netflix Scrim, Title Watermark,
 * VideoSkip Floating Button, and Complete Desktop Bottom Controls Bar.
 * Overload for modern industry-standard [MediampPlayer].
 */
@Composable
fun TvPlayerHud(
    controller: TvPlayerHudController,
    player: MediampPlayer,
    modifier: Modifier = Modifier,
    onNextEpisode: (() -> Unit)? = null,
    onOpenDrawer: (PlayerDrawerTab) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val state by controller.uiState.collectAsState()
    val playerState by player.state.collectAsState()
    val curPosMs by player.currentPositionMillis.collectAsState()
    val mediaProps by player.mediaProperties.collectAsState()
    val durMs = mediaProps?.durationMillis ?: 0L

    val audioLevel = remember(player) { player.features[AudioLevelController] }
    val volFraction by (audioLevel?.volume ?: remember { MutableStateFlow(1f) }).collectAsState()
    val isMute by (audioLevel?.isMute ?: remember { MutableStateFlow(false) }).collectAsState()
    val speedFeature = remember(player) { player.features[PlaybackSpeed] }

    LaunchedEffect(playerState.isPlaying) {
        controller.onPlaybackStateChanged(!playerState.isPlaying)
    }

    TvPlayerHudContent(
        controller = controller,
        state = state,
        isPlaying = playerState.isPlaying,
        currentSpeed = speedFeature?.value ?: 1.0f,
        posMs = curPosMs,
        durMs = durMs,
        volume = (volFraction * 100f).toDouble(),
        isMuted = isMute || volFraction <= 0f,
        isSkipIntroVisible = false,
        skipIntroLabel = "İntroyu Atla",
        onSkipIntro = {
            val targetMs = (curPosMs + 85_000L).coerceAtMost(durMs)
            player.seekTo(targetMs)
        },
        onTogglePlayPause = {
            if (playerState.isPlaying) player.pause() else player.play()
        },
        onSeekTo = { targetMs ->
            player.seekTo(targetMs)
        },
        onToggleMute = {
            audioLevel?.let { it.setMute(!it.isMute.value) }
        },
        onSetVolume = { newVol ->
            audioLevel?.setVolume(newVol.toFloat().coerceIn(0f, 1f))
            if (audioLevel?.isMute?.value == true && newVol > 0.0) {
                audioLevel.setMute(false)
            }
        },
        onSetPlaybackSpeed = { spd ->
            speedFeature?.set(spd)
        },
        onApplyAspectRatio = { mode ->
            applyAspectRatio(player, mode)
        },
        modifier = modifier,
        onNextEpisode = onNextEpisode,
        onOpenDrawer = onOpenDrawer,
        onToggleFullscreen = onToggleFullscreen,
        onClose = onClose,
        content = content,
    )
}

/**
 * Overload specifically accepting [MpvMediampPlayer] for zero-copy Skiko Compose rendering.
 */
@Composable
fun TvPlayerHud(
    controller: TvPlayerHudController,
    player: MpvMediampPlayer,
    modifier: Modifier = Modifier,
    onNextEpisode: (() -> Unit)? = null,
    onOpenDrawer: (PlayerDrawerTab) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    TvPlayerHud(
        controller = controller,
        player = player as MediampPlayer,
        modifier = modifier,
        onNextEpisode = onNextEpisode,
        onOpenDrawer = onOpenDrawer,
        onToggleFullscreen = onToggleFullscreen,
        onClose = onClose,
        content = content,
    )
}

/**
 * 10-Foot TV Leanback Player HUD featuring the 1:1 Netflix Scrim, Title Watermark,
 * VideoSkip Floating Button, and Complete Desktop Bottom Controls Bar.
 * Overload for legacy [MpvPlayer].
 */
@Composable
fun TvPlayerHud(
    controller: TvPlayerHudController,
    player: MpvPlayer,
    modifier: Modifier = Modifier,
    onNextEpisode: (() -> Unit)? = null,
    onOpenDrawer: (PlayerDrawerTab) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val state by controller.uiState.collectAsState()
    val playerState by player.state.collectAsState()

    val curMs = if (playerState.position > 0L) playerState.position else (playerState.positionSec * 1000.0).toLong()
    val durMs = if (playerState.duration > 0L) playerState.duration else (playerState.durationSec * 1000.0).toLong()
    val activeStamp = player.currentActiveStamp
    var currentVolume by remember { mutableStateOf(100.0) }
    var isMuted by remember { mutableStateOf(false) }

    TvPlayerHudContent(
        controller = controller,
        state = state,
        isPlaying = playerState.isPlaying,
        currentSpeed = player.getPlaybackSpeed(),
        posMs = curMs,
        durMs = durMs,
        volume = currentVolume,
        isMuted = isMuted,
        isSkipIntroVisible = activeStamp != null,
        skipIntroLabel = activeStamp?.timestamp?.label ?: "İntroyu Atla",
        onSkipIntro = {
            player.handleEvent(CSPlayerEvent.SkipCurrentChapter)
        },
        onTogglePlayPause = {
            player.togglePause()
        },
        onSeekTo = { targetMs ->
            player.seek(targetMs)
        },
        onToggleMute = {
            player.toggleMute()
        },
        onSetVolume = { newVol ->
            player.setVolume(newVol * 100.0)
        },
        onSetPlaybackSpeed = { spd ->
            player.setPlaybackSpeed(spd)
        },
        onApplyAspectRatio = { mode ->
            applyAspectRatio(player, mode)
        },
        modifier = modifier,
        onNextEpisode = onNextEpisode,
        onOpenDrawer = onOpenDrawer,
        onToggleFullscreen = onToggleFullscreen,
        onClose = onClose,
        content = content,
    )
}

@Composable
private fun TvPlayerHudContent(
    controller: TvPlayerHudController,
    state: TvPlayerHudController.HudUiState,
    isPlaying: Boolean,
    currentSpeed: Float,
    posMs: Long,
    durMs: Long,
    volume: Double,
    isMuted: Boolean,
    isSkipIntroVisible: Boolean,
    skipIntroLabel: String,
    onSkipIntro: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onSetVolume: (Double) -> Unit = {},
    onSetPlaybackSpeed: (Float) -> Unit,
    onApplyAspectRatio: (AspectRatioMode) -> Unit,
    modifier: Modifier = Modifier,
    onNextEpisode: (() -> Unit)? = null,
    onOpenDrawer: (PlayerDrawerTab) -> Unit = {},
    onToggleFullscreen: () -> Unit = {},
    onClose: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    var isSpeedMenuOpen by remember { mutableStateOf(false) }
    var isAspectMenuOpen by remember { mutableStateOf(false) }
    var currentAspectMode by remember { mutableStateOf(AspectRatioMode.FIT) }

    var isSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }

    val displayPosMs = if (isSeeking) seekPositionMs else posMs
    val progressFraction = if (durMs > 0L) (displayPosMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f

    // Smooth Skiko alpha transition (300ms)
    val animatedAlpha by animateFloatAsState(
        targetValue = if (state.isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "PlayerHudAlphaAnimation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionDown,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.Enter,
                        Key.DirectionCenter,
                        Key.Spacebar -> {
                            controller.onUserInteraction(isDpadNavigation = true)
                        }
                        Key.Escape,
                        Key.Back -> {
                            if (state.isVisible) {
                                controller.hideHud()
                                true
                            } else {
                                false
                            }
                        }
                        else -> {
                            controller.resetAutoHideTimer()
                            false
                        }
                    }
                } else false
            }
    ) {
        // Underlying video view container
        content()

        if (animatedAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(animatedAlpha)
            ) {
                // 1. Fullscreen Vertical Shadow Overlay (Top and Bottom falloff)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color(0x66000000), // 40% black at top
                                0.25f to Color.Transparent,
                                0.65f to Color.Transparent,
                                1.0f to Color(0xFF000000)  // 100% black at bottom
                            )
                        )
                )

                // 2. Clean Desktop Top Title Header with Back Button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 28.dp, top = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onClose != null) {
                            IconButton(
                                onClick = onClose,
                                modifier = Modifier
                                    .size(40.dp)
                                    .focusable()
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.6f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Geri Dön",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                        }

                        state.metadata?.let { meta ->
                            Column {
                                Text(
                                    text = meta.title,
                                    style = TextStyle(
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        shadow = Shadow(
                                            color = Color.Black,
                                            offset = Offset(2f, 2f),
                                            blurRadius = 4f
                                        )
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val epSub = buildString {
                                    if (meta.seasonNumber != null && meta.episodeNumber != null) {
                                        append("Sezon ${meta.seasonNumber} • Bölüm ${meta.episodeNumber}")
                                    } else if (meta.episodeNumber != null) {
                                        append("Bölüm ${meta.episodeNumber}")
                                    }
                                }
                                if (epSub.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = epSub,
                                        style = TextStyle(
                                            color = TvColors.TextSecondary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            shadow = Shadow(
                                                color = Color.Black,
                                                offset = Offset(1f, 1f),
                                                blurRadius = 3f
                                            )
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Floating "İntroyu / Jeneriği Atla" (VideoSkip) Button
                AnimatedVisibility(
                    visible = isSkipIntroVisible,
                    enter = fadeIn(animationSpec = tween(200)) + slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                    ),
                    exit = fadeOut(animationSpec = tween(200)) + slideOutVertically(
                        targetOffsetY = { it / 2 },
                        animationSpec = tween(250, easing = FastOutSlowInEasing)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 40.dp, bottom = 120.dp)
                ) {
                    Button(
                        onClick = {
                            onSkipIntro()
                            controller.resetAutoHideTimer()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        border = BorderStroke(1.dp, TvColors.FocusEmerald),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Skip Intro",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$skipIntroLabel [S]",
                            style = TvTypography.Button.copy(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }

                // 5. Desktop Player Bottom Control Bar
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    color = Color.Transparent
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // A. Timeline Scrubber & Timestamp Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = formatTimeMs(displayPosMs),
                                style = TvTypography.Caption.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            )

                            // Scrubber Slider
                            Slider(
                                value = progressFraction,
                                onValueChange = { frac ->
                                    isSeeking = true
                                    seekPositionMs = (frac * durMs).toLong()
                                    controller.resetAutoHideTimer()
                                },
                                onValueChangeFinished = {
                                    onSeekTo(seekPositionMs)
                                    isSeeking = false
                                    controller.resetAutoHideTimer()
                                },
                                modifier = Modifier.weight(1f).height(24.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = TvColors.FocusElectricBlue,
                                    inactiveTrackColor = Color(0x66FFFFFF),
                                )
                            )

                            Text(
                                text = formatTimeMs(durMs),
                                style = TvTypography.Caption.copy(
                                    color = Color(0xB3FFFFFF),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            )
                        }

                        // B. Control Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left Controls: Play/Pause, -10s, +10s, Next Episode, Volume
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Play / Pause
                                IconButton(
                                    onClick = {
                                        onTogglePlayPause()
                                        controller.resetAutoHideTimer()
                                    }
                                ) {
                                    if (isPlaying) {
                                        PauseIcon(tint = Color.White)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                // Seek -10s
                                TextButton(
                                    onClick = {
                                        onSeekTo((displayPosMs - 10000L).coerceAtLeast(0L))
                                        controller.resetAutoHideTimer()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("-10s", style = TvTypography.Button.copy(color = Color.White, fontSize = 13.sp))
                                }

                                // Seek +10s
                                TextButton(
                                    onClick = {
                                        onSeekTo((displayPosMs + 10000L).coerceAtMost(durMs))
                                        controller.resetAutoHideTimer()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("+10s", style = TvTypography.Button.copy(color = Color.White, fontSize = 13.sp))
                                }

                                // Next Episode
                                if (onNextEpisode != null) {
                                    Button(
                                        onClick = {
                                            onNextEpisode.invoke()
                                            controller.resetAutoHideTimer()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceHigh),
                                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Sonraki Bölüm",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Sonraki [N]", style = TvTypography.Button.copy(color = Color.White, fontSize = 12.sp))
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Integrated Volume Capsule with Dynamic Vector Icon, Slider and Percent
                                Surface(
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    color = TvColors.SurfaceCard,
                                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    val volRatio = if (isMuted) 0f else (volume / 100.0).toFloat().coerceIn(0f, 1f)

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                onToggleMute()
                                                controller.resetAutoHideTimer()
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            VolumeIcon(isMuted = isMuted, volume = volume, modifier = Modifier.size(18.dp))
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        // Smooth Draggable Volume Slider with Correct 0f..1f Scale
                                        Slider(
                                            value = volRatio,
                                            onValueChange = { newVolRatio ->
                                                onSetVolume(newVolRatio.toDouble())
                                                controller.resetAutoHideTimer()
                                            },
                                            valueRange = 0f..1f,
                                            modifier = Modifier
                                                .width(84.dp)
                                                .height(20.dp),
                                            colors = SliderDefaults.colors(
                                                thumbColor = Color.White,
                                                activeTrackColor = TvColors.FocusElectricBlue,
                                                inactiveTrackColor = Color(0x33FFFFFF),
                                            )
                                        )

                                        Spacer(modifier = Modifier.width(6.dp))

                                        // Numeric Volume Percentage Indicator
                                        Text(
                                            text = if (isMuted) "Mute" else "${(volRatio * 100).toInt()}%",
                                            style = TvTypography.Caption.copy(
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isMuted) TvColors.ErrorRed else TvColors.TextSecondary
                                            ),
                                            modifier = Modifier.width(36.dp)
                                        )
                                    }
                                }
                            }

                            // Right Controls: Speed, Aspect Ratio, Tracks, Episodes, Fullscreen
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Playback Speed Menu Button
                                Box {
                                    Button(
                                        onClick = { isSpeedMenuOpen = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "%.2fx", currentSpeed).replace(".00x", ".0x"),
                                            style = TvTypography.Button.copy(fontSize = 12.sp, color = Color.White)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = isSpeedMenuOpen,
                                        onDismissRequest = { isSpeedMenuOpen = false }
                                    ) {
                                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text("${spd}x", color = Color.White, fontSize = 13.sp)
                                                        if (abs(currentSpeed - spd) < 0.05f) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = TvColors.FocusEmerald,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    onSetPlaybackSpeed(spd)
                                                    isSpeedMenuOpen = false
                                                    controller.resetAutoHideTimer()
                                                }
                                            )
                                        }
                                    }
                                }

                                // Aspect Ratio Menu Button
                                Box {
                                    Button(
                                        onClick = { isAspectMenuOpen = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = currentAspectMode.label,
                                            style = TvTypography.Button.copy(fontSize = 12.sp, color = Color.White)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = isAspectMenuOpen,
                                        onDismissRequest = { isAspectMenuOpen = false }
                                    ) {
                                        AspectRatioMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(mode.label, color = Color.White, fontSize = 13.sp)
                                                        if (currentAspectMode == mode) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = TvColors.FocusEmerald,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    currentAspectMode = mode
                                                    onApplyAspectRatio(mode)
                                                    isAspectMenuOpen = false
                                                    controller.resetAutoHideTimer()
                                                }
                                            )
                                        }
                                    }
                                }

                                // Sources & Mirrors Button (Upstream player_sources_btt parity)
                                Button(
                                    onClick = {
                                        onOpenDrawer(PlayerDrawerTab.SOURCES)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = "Sources",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Kaynaklar", style = TvTypography.Button.copy(fontSize = 12.sp, color = Color.White))
                                }

                                // Audio & Subtitles (Tracks) Button
                                Button(
                                    onClick = {
                                        onOpenDrawer(PlayerDrawerTab.TRACKS)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Tracks",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Altyazı & Ses", style = TvTypography.Button.copy(fontSize = 12.sp, color = Color.White))
                                }

                                // Episodes Button
                                Button(
                                    onClick = {
                                        onOpenDrawer(PlayerDrawerTab.EPISODES)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = "Episodes",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Bölümler", style = TvTypography.Button.copy(fontSize = 12.sp, color = Color.White))
                                }

                                // Fullscreen Toggle
                                IconButton(
                                    onClick = {
                                        onToggleFullscreen()
                                        controller.resetAutoHideTimer()
                                    }
                                ) {
                                    FullscreenIcon(tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
