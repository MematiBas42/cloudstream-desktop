package com.lagradost.cloudstream3.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.desktop.ui.navigation.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.osd.PlayerHudMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.Subtitle
import org.openani.mediamp.source.UriMediaData
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * Convenience factory function to instantiate [MpvMediampPlayer] with desktop default contexts.
 */
fun MpvMediampPlayer(
    context: Any = Any(),
    parentCoroutineContext: CoroutineContext = Dispatchers.Default,
): MpvMediampPlayer = MpvMediampPlayer(
    context = context,
    parentCoroutineContext = parentCoroutineContext,
)

/**
 * Single-window in-process embedded player view for CloudStream Desktop.
 *
 * Implements CLAUDE.md Section 5.6 architectural requirements:
 * - Single-Window Embedded Surface (Zero external detached windows)
 * - Zero-Copy GPU Skia Compose surface rendering via [MpvMediampPlayerSurface] (open-ani/mediamp)
 * - 1:1 Netflix-style [TvPlayerHud] overlay with full desktop controls
 * - In-Player [InPlayerEpisodeDrawer] with audio, subtitle, tracks and episode switching
 * - Complete keyboard navigation & shortcuts:
 *     Space: Play/Pause toggle
 *     Left/Right Arrow: 10s Seek backward/forward
 *     Up/Down Arrow: Volume +/- 5%
 *     F / F11: Fullscreen toggle
 *     M: Mute toggle
 *     S / Enter / NumPadEnter: Skip intro (+85s)
 *     N: Next episode
 *     A: Audio track drawer
 *     C / V: Subtitle track drawer
 *     T: Combined tracks drawer
 *     E: Episode switcher drawer
 *     [, ]: Playback speed +/- 0.25x
 *     Backspace: Reset speed to 1.0x
 *     Esc: Close drawer / Stop playback and return
 * - Pointer-aware OSD auto-hide on inactivity
 */
@Composable
fun EmbeddedPlayerView(
    launchData: VideoLaunchData,
    onClose: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    player: MpvPlayer = remember { MpvPlayer() },
) {
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val hudController = remember { TvPlayerHudController() }
    val mediampPlayer = remember { MpvMediampPlayer() }
    val playerState by mediampPlayer.state.collectAsState()
    val drawerViewModel = remember(player) { PlayerDrawerViewModel(player) }
    val drawerState by drawerViewModel.drawerState.collectAsState()

    var previousVolume by remember { mutableStateOf(1f) }

    val episodeIntId = remember(launchData) {
        launchData.history.episodeId?.toIntOrNull()
            ?: launchData.history.episodeId?.hashCode()
            ?: launchData.history.url.toIntOrNull()
            ?: launchData.history.url.hashCode()
    }

    // 1:1 Upstream Parity: Prepare canonical ResultEpisode and nextEpisode for DataStoreHelper
    val currentEpisodeData = remember(launchData, episodeIntId) {
        val epId = launchData.history.episodeId ?: launchData.history.url
        launchData.episodes.firstOrNull { it.data == epId } ?: ResultEpisode(
            headerName = launchData.title ?: launchData.history.showName.orEmpty(),
            name = launchData.title ?: launchData.history.showName,
            poster = launchData.history.posterUrl,
            episode = launchData.history.episode ?: 1,
            season = launchData.history.season,
            data = epId,
            apiName = launchData.provider?.name ?: "",
            id = episodeIntId,
            parentId = (launchData.history.parentId ?: launchData.history.url).hashCode(),
        )
    }

    val nextEpisodeData = remember(launchData, currentEpisodeData) {
        val allEps = launchData.episodes
        val currentIdx = allEps.indexOfFirst { it.data == currentEpisodeData.data }
        if (currentIdx in 0 until allEps.lastIndex) {
            allEps[currentIdx + 1]
        } else null
    }

    // Upstream saveData equivalent (DataStoreHelper.kt:700 & GeneratorPlayer.kt:1732):
    // commits progress instantly to DataStoreHelper and DesktopDataStore only when valid
    val saveCurrentProgress: () -> Unit = remember(launchData, mediampPlayer, episodeIntId, currentEpisodeData, nextEpisodeData) {
        {
            val posMs = mediampPlayer.currentPositionMillis.value
            val durMs = mediampPlayer.mediaProperties.value?.durationMillis ?: 0L
            // Upstream DataStoreHelper.kt:700: duration must be >= 30_000L and position must be positive
            // Guard: Never overwrite saved progress with an opening/buffering position if video hasn't reached saved progress yet
            val isValidDuration = durMs >= 30_000L
            val isLegitimateProgress = if (launchData.startPositionMs > 1000L) {
                posMs >= (launchData.startPositionMs - 5000L) // Allow saving only if user played near/past saved point
            } else {
                posMs > 1000L
            }

            if (isValidDuration && isLegitimateProgress) {
                val pId = launchData.history.parentId ?: launchData.history.url
                val epId = currentEpisodeData.data

                // 1. Upstream DataStoreHelper.setViewPosAndResume (handles 90% next episode threshold & 95% finish)
                DataStoreHelper.setViewPosAndResume(
                    id = episodeIntId,
                    position = posMs,
                    duration = durMs,
                    currentEpisode = currentEpisodeData.copy(position = posMs, duration = durMs),
                    nextEpisode = nextEpisodeData,
                )

                // 2. DesktopDataStore watch_history persistence
                val updatedHistory = launchData.history.copy(
                    position = posMs,
                    duration = durMs,
                    parentId = pId,
                    episodeId = epId,
                    updateTime = System.currentTimeMillis()
                )
                DesktopDataStore.setLastWatched(updatedHistory)
                DataStoreHelper.setViewPos(episodeIntId, posMs, durMs)
                epId.hashCode().let { DataStoreHelper.setViewPos(it, posMs, durMs) }

                AppLogger.d("EmbeddedPlayerView", "Upstream saveData committed: $posMs / $durMs ms for $epId")
            }
        }
    }

    var hasEverPlayed by remember(launchData) { mutableStateOf(false) }

    // Zero-Polling Reactive Flow: React to playback state changes (pause/play)
    LaunchedEffect(mediampPlayer) {
        mediampPlayer.state.collect { state ->
            val isPaused = !state.isPlaying
            hudController.onPlaybackStateChanged(isPaused)
            if (state.isPlaying) {
                hasEverPlayed = true
            }
            if (isPaused && hasEverPlayed) {
                // Upstream behavior: save immediately whenever active playback pauses
                saveCurrentProgress()
            }
        }
    }

    // Zero-Polling Reactive Flow: React to position ticks (throttled to 5s delta without while loops)
    LaunchedEffect(mediampPlayer) {
        var lastSavedSec = 0L
        mediampPlayer.currentPositionMillis.collect { posMs ->
            val currentSec = posMs / 1000L
            if (currentSec > 0L && abs(currentSec - lastSavedSec) >= 5L) {
                lastSavedSec = currentSec
                saveCurrentProgress()
            }
        }
    }

    // Configure HUD metadata and initialize episode drawer from launch data
    LaunchedEffect(launchData) {
        val meta = PlayerHudMetadata(
            title = launchData.title ?: launchData.history.showName.orEmpty(),
            logoUrl = null,
            seasonNumber = launchData.history.season,
            episodeNumber = launchData.history.episode,
            isMovie = launchData.history.season == null && launchData.history.episode == null,
        )
        hudController.setMetadata(meta)
        hudController.showHud()

        val pId = launchData.history.parentId ?: launchData.history.url
        val eps = launchData.episodes.map { resEp ->
            @Suppress("DEPRECATION_ERROR")
            Episode(
                data = resEp.data,
                name = resEp.name,
                season = resEp.season,
                episode = resEp.episode,
                posterUrl = resEp.poster,
                description = resEp.description,
            )
        }
        drawerViewModel.initialize(
            parentId = pId,
            showTitle = launchData.history.showName ?: launchData.title.orEmpty(),
            episodes = eps,
            currentPlayingEpisodeData = launchData.history.episodeId,
            provider = launchData.provider,
        )
    }

    var lastLaunchedUrl by remember { mutableStateOf<String?>(null) }
    var hasAppliedStartPosition by remember(launchData) { mutableStateOf(false) }

    val resolvedLinks = remember(launchData) { mutableStateListOf<ExtractorLink>().apply { addAll(launchData.links) } }
    val resolvedSubtitles = remember(launchData) { mutableStateListOf<SubtitleFile>().apply { addAll(launchData.subtitles) } }
    val seenLinkUrls = remember(launchData) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(launchData.links.map { it.url }) } }
    val seenSubUrls = remember(launchData) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(launchData.subtitles.map { it.url }) } }
    val failedLinks = remember(launchData) { mutableSetOf<String>() }
    var isResolvingLinks by remember(launchData) { mutableStateOf(launchData.links.isEmpty() && launchData.provider != null && launchData.dataUrl != null) }
    var hasStartedPlayback by remember(launchData) { mutableStateOf(false) }
    var scrapeJob by remember(launchData) { mutableStateOf<Job?>(null) }
    val defaultProfile = remember { QualityDataHelper.getProfiles().firstOrNull()?.id ?: 0 }

    var nextMirrorAction: ((String?) -> Unit)? = null

    fun selectBestLink(links: List<ExtractorLink>): ExtractorLink? {
        if (links.isEmpty()) return null
        return links.maxByOrNull { QualityDataHelper.getLinkPriority(defaultProfile, it) } ?: links.first()
    }

    fun playSelectedLink(link: ExtractorLink, startPosMs: Long? = null) {
        if (hasStartedPlayback && link.url == lastLaunchedUrl) return
        hasStartedPlayback = true
        lastLaunchedUrl = link.url
        val effectiveStartMs = startPosMs ?: launchData.startPositionMs
        hasAppliedStartPosition = effectiveStartMs <= 0L
        AppLogger.i("EmbeddedPlayerView", "Starting embedded playback for '${launchData.title}': ${link.url} (startPos: ${effectiveStartMs}ms)")

        val headers = link.headers.toMutableMap()
        if (link.referer.isNotBlank() && !headers.containsKey("Referer") && !headers.containsKey("referer")) {
            headers["Referer"] = link.referer
        }
        val userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
        val referer = headers.entries.firstOrNull { it.key.equals("referer", true) || it.key.equals("referrer", true) }?.value
        val extraHeaders = headers.filterKeys { !it.equals("user-agent", true) && !it.equals("referer", true) && !it.equals("referrer", true) }

        // Use native MPV direct streaming with native header injection to support all HLS segments without loopback proxy issues
        val playUrl = if (launchData.useLocalProxy && headers.isNotEmpty()) {
            LocalStreamProxy.registerStream(link.url, headers)
        } else {
            link.url
        }
        AppLogger.i("EmbeddedPlayerView", "Resolved playback URI: $playUrl")

        val extraSubs = resolvedSubtitles.filter { it.url.isNotBlank() }.map { sub ->
            Subtitle(
                uri = sub.url,
                mimeType = null,
                language = sub.langTag ?: sub.lang,
                label = sub.lang.ifBlank { "Subtitle" },
            )
        }

        val startSec = (effectiveStartMs / 1000L).coerceAtLeast(0L)
        val mpvOptions = mutableListOf<String>()
        launchData.title?.let { mpvOptions.add("--force-media-title=$it") }
        if (startSec > 0L) mpvOptions.add("--start=$startSec")
        if (!userAgent.isNullOrBlank()) mpvOptions.add("--user-agent=$userAgent")
        if (!referer.isNullOrBlank()) mpvOptions.add("--referrer=$referer")
        if (extraHeaders.isNotEmpty()) {
            val fields = extraHeaders.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" }
            mpvOptions.add("--http-header-fields=$fields")
        }

        val uriData = UriMediaData(
            uri = playUrl,
            headers = headers,
            extraFiles = MediaExtraFiles(subtitles = extraSubs),
            options = mpvOptions
        )

        // Pre-configure MPVHandle properties before loading media
        (mediampPlayer.impl as? MPVHandle)?.let { handle ->
            if (!userAgent.isNullOrBlank()) handle.setPropertyString("user-agent", userAgent)
            if (!referer.isNullOrBlank()) handle.setPropertyString("referrer", referer)
            if (extraHeaders.isNotEmpty()) {
                val fields = extraHeaders.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" }
                handle.setPropertyString("http-header-fields", fields)
            }
        }

        coroutineScope.launch {
            try {
                mediampPlayer.setMediaData(
                    data = uriData,
                    playWhenReady = true,
                    startPositionMillis = effectiveStartMs,
                )
            } catch (t: Throwable) {
                AppLogger.e("EmbeddedPlayerView", "Failed to setMediaData: ${t.message}")
                nextMirrorAction?.invoke(t.message)
            }

            // Configure external subtitles via MPV handle
            for (sub in resolvedSubtitles) {
                if (sub.url.isNotBlank()) {
                    (mediampPlayer.impl as? MPVHandle)?.command("sub-add", sub.url, "select")
                    break
                }
            }
        }
    }

    // Upstream 1:1 In-Flight Mirror Fallback: automatically switch to next mirror on failure
    fun nextMirror(errorReason: String? = null) {
        val currentUrl = lastLaunchedUrl
        if (!currentUrl.isNullOrBlank()) {
            failedLinks.add(currentUrl)
        }
        AppLogger.w("EmbeddedPlayerView", "nextMirror triggered. Reason: $errorReason. Failed: ${failedLinks.size}, Available: ${resolvedLinks.size}")

        val remainingLinks = resolvedLinks.filter { it.url !in failedLinks }
        val nextLink = selectBestLink(remainingLinks) ?: remainingLinks.firstOrNull()

        if (nextLink != null) {
            AppLogger.i("EmbeddedPlayerView", "nextMirror: Switching automatically to alternative stream: '${nextLink.name}' (${nextLink.url})")
            // Upstream parity (GeneratorPlayer.kt:535 & CS3IPlayer.kt:284):
            // Retain original start position if playback hasn't advanced yet
            val curPos = mediampPlayer.currentPositionMillis.value
            val effectivePos = if (curPos > 1000L) curPos else launchData.startPositionMs
            hasStartedPlayback = false
            playSelectedLink(nextLink, startPosMs = effectivePos)
        } else {
            AppLogger.e("EmbeddedPlayerView", "nextMirror: All mirrors exhausted.")
            launchData.onError?.invoke("Tüm video kaynakları başarısız oldu.")
            saveCurrentProgress()
            mediampPlayer.close()
            onClose()
        }
    }
    nextMirrorAction = ::nextMirror

    // Synchronize available scraped sources with InPlayerEpisodeDrawer
    LaunchedEffect(resolvedLinks.size, lastLaunchedUrl) {
        drawerViewModel.setAvailableSources(resolvedLinks.toList(), lastLaunchedUrl)
    }

    // Launch playback directly or resolve streams asynchronously matching upstream GeneratorPlayer
    LaunchedEffect(launchData) {
        if (launchData.links.isNotEmpty()) {
            val chosen = selectBestLink(launchData.links) ?: launchData.links.first()
            playSelectedLink(chosen)
        } else if (launchData.provider != null && launchData.dataUrl != null) {
            val provider = launchData.provider
            val dataUrl = launchData.dataUrl
            isResolvingLinks = true
            scrapeJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    provider.loadLinks(
                        data = dataUrl,
                        isCasting = false,
                        subtitleCallback = { sub ->
                            if (seenSubUrls.add(sub.url)) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    resolvedSubtitles.add(sub)
                                    if (hasStartedPlayback && sub.url.isNotBlank()) {
                                        (mediampPlayer.impl as? MPVHandle)?.command("sub-add", sub.url)
                                    }
                                }
                            }
                        },
                        callback = { link ->
                            if (seenLinkUrls.add(link.url)) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    resolvedLinks.add(link)

                                    // Upstream GeneratorPlayer AUTO_SKIP_PRIORITY parity:
                                    // If high quality link (1080p or priority >= 10) arrives, auto-launch immediately!
                                    val priority = QualityDataHelper.getLinkPriority(defaultProfile, link)
                                    if (!hasStartedPlayback && priority >= QualityDataHelper.AUTO_SKIP_PRIORITY) {
                                        AppLogger.i("EmbeddedPlayerView", "AUTO_SKIP_PRIORITY ($priority >= 10) matched for '${link.name}'! Auto-starting...")
                                        playSelectedLink(link)
                                    }
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    AppLogger.w("EmbeddedPlayerView", "Error loading links: ${e.message}")
                } finally {
                    isResolvingLinks = false
                    withContext(Dispatchers.Main) {
                        if (!hasStartedPlayback && resolvedLinks.isNotEmpty()) {
                            val bestLink = selectBestLink(resolvedLinks)
                            if (bestLink != null) {
                                playSelectedLink(bestLink)
                            }
                        } else if (!hasStartedPlayback && resolvedLinks.isEmpty()) {
                            launchData.onError?.invoke("No streams found for this episode.")
                        }
                    }
                }
            }
        }
    }

    // Ensure startPositionMs is applied once duration is resolved
    LaunchedEffect(mediampPlayer, launchData.startPositionMs) {
        mediampPlayer.mediaProperties.collect { props ->
            val dur = props?.durationMillis ?: 0L
            if (!hasAppliedStartPosition && launchData.startPositionMs > 1000L && dur > 0L) {
                hasAppliedStartPosition = true
                val startSec = launchData.startPositionMs / 1000.0
                (mediampPlayer.impl as? MPVHandle)?.command("seek", "$startSec", "absolute")
            }
        }
    }

    // Handle playback errors matching upstream GeneratorPlayer: trigger in-flight mirror fallback
    val mediaStatus = playerState.mediaStatus
    LaunchedEffect(mediaStatus) {
        if (mediaStatus is MediaStatus.Error) {
            val msg = mediaStatus.error.message ?: "Playback error"
            AppLogger.e("EmbeddedPlayerView", "Embedded playback error: $msg")
            nextMirror(msg)
        }
    }

    val skipButtonFocusRequester = remember { FocusRequester() }
    val canSkipLoading = isResolvingLinks && resolvedLinks.isNotEmpty() && !hasStartedPlayback

    LaunchedEffect(canSkipLoading) {
        if (canSkipLoading) {
            runCatching { skipButtonFocusRequester.requestFocus() }
        }
    }

    val skipLoadingAction: () -> Unit = {
        AppLogger.i("EmbeddedPlayerView", "Skip loading triggered! Starting playback with best link...")
        scrapeJob?.cancel()
        isResolvingLinks = false
        val bestLink = selectBestLink(resolvedLinks) ?: resolvedLinks.firstOrNull()
        if (bestLink != null) {
            playSelectedLink(bestLink)
        }
    }

    // Loading & buffering status matching upstream GeneratorPlayer & PlayerView
    val isMediaBuffering = playerState.isBuffering || playerState.mediaStatus is MediaStatus.Opening || (playerState.mediaStatus is MediaStatus.Idle && hasStartedPlayback)
    val isPlayerLoading = isResolvingLinks || (isMediaBuffering && !playerState.isPlaying)

    // Request initial focus so keyboard shortcuts work immediately
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    DisposableEffect(mediampPlayer, launchData) {
        onDispose {
            saveCurrentProgress()
            mediampPlayer.close()
            hudController.dispose()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Press -> {
                                hudController.onUserInteraction(isDpadNavigation = false)
                            }
                            PointerEventType.Scroll -> {
                                val change = event.changes.firstOrNull()
                                val scrollDeltaY = change?.scrollDelta?.y ?: 0f
                                val scrollDeltaX = change?.scrollDelta?.x ?: 0f
                                val isShift = event.keyboardModifiers.isShiftPressed || abs(scrollDeltaX) > abs(scrollDeltaY)

                                if (isShift) {
                                    val effectiveDelta = if (abs(scrollDeltaX) > abs(scrollDeltaY)) scrollDeltaX else scrollDeltaY
                                    val curMs = mediampPlayer.currentPositionMillis.value
                                    val durMs = mediampPlayer.mediaProperties.value?.durationMillis ?: 0L
                                    val target = if (effectiveDelta < 0f) (curMs + 10000L).coerceAtMost(durMs) else (curMs - 10000L).coerceAtLeast(0L)
                                    mediampPlayer.seekTo(target)
                                    hudController.onUserInteraction(isDpadNavigation = false)
                                } else {
                                    val audio = mediampPlayer.features[AudioLevelController]
                                    val curVol = audio?.volume?.value ?: 1f
                                    if (scrollDeltaY < 0f) {
                                        audio?.setVolume((curVol + 0.05f).coerceIn(0f, 1f))
                                        hudController.onUserInteraction(isDpadNavigation = false)
                                    } else if (scrollDeltaY > 0f) {
                                        audio?.setVolume((curVol - 0.05f).coerceIn(0f, 1f))
                                        hudController.onUserInteraction(isDpadNavigation = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Spacebar -> {
                            if (canSkipLoading) {
                                skipLoadingAction()
                                true
                            } else {
                                if (mediampPlayer.state.value.isPlaying) mediampPlayer.pause() else mediampPlayer.play()
                                hudController.onUserInteraction(isDpadNavigation = true)
                                true
                            }
                        }
                        Key.DirectionLeft -> {
                            val curMs = mediampPlayer.currentPositionMillis.value
                            val target = (curMs - 10000L).coerceAtLeast(0L)
                            mediampPlayer.seekTo(target)
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.DirectionRight -> {
                            val curMs = mediampPlayer.currentPositionMillis.value
                            val durMs = mediampPlayer.mediaProperties.value?.durationMillis ?: 0L
                            val target = (curMs + 10000L).coerceAtMost(durMs)
                            mediampPlayer.seekTo(target)
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.DirectionUp -> {
                            val audio = mediampPlayer.features[AudioLevelController]
                            val curVol = audio?.volume?.value ?: 1f
                            audio?.setVolume((curVol + 0.05f).coerceIn(0f, 1f))
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.DirectionDown -> {
                            val audio = mediampPlayer.features[AudioLevelController]
                            val curVol = audio?.volume?.value ?: 1f
                            audio?.setVolume((curVol - 0.05f).coerceIn(0f, 1f))
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.M -> {
                            val audio = mediampPlayer.features[AudioLevelController]
                            if (audio != null) {
                                val curVol = audio.volume.value
                                if (curVol > 0f && !audio.isMute.value) {
                                    previousVolume = curVol
                                    audio.setVolume(0f)
                                    audio.setMute(true)
                                } else {
                                    val restoredVol = if (previousVolume > 0f) previousVolume else 1f
                                    audio.setVolume(restoredVol)
                                    audio.setMute(false)
                                }
                            }
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.F, Key.F11 -> {
                            onToggleFullscreen()
                            true
                        }
                        Key.S, Key.Enter, Key.NumPadEnter -> {
                            if (canSkipLoading) {
                                skipLoadingAction()
                                true
                            } else {
                                val curMs = mediampPlayer.currentPositionMillis.value
                                val durMs = mediampPlayer.mediaProperties.value?.durationMillis ?: 0L
                                val target = (curMs + 85000L).coerceAtMost(durMs)
                                mediampPlayer.seekTo(target)
                                hudController.onUserInteraction(isDpadNavigation = true)
                                true
                            }
                        }
                        Key.N -> {
                            saveCurrentProgress()
                            if (launchData.onNextEpisode != null) {
                                launchData.onNextEpisode.invoke()
                            }
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.A -> {
                            drawerViewModel.openDrawer(PlayerDrawerTab.AUDIO)
                            true
                        }
                        Key.C, Key.V -> {
                            drawerViewModel.openDrawer(PlayerDrawerTab.SUBTITLES)
                            true
                        }
                        Key.T -> {
                            drawerViewModel.openDrawer(PlayerDrawerTab.TRACKS)
                            true
                        }
                        Key.E -> {
                            drawerViewModel.openDrawer(PlayerDrawerTab.EPISODES)
                            true
                        }
                        Key.O, Key.L -> {
                            drawerViewModel.openDrawer(PlayerDrawerTab.SOURCES)
                            true
                        }
                        Key.LeftBracket -> {
                            val speedFeature = mediampPlayer.features[PlaybackSpeed]
                            val current = speedFeature?.value ?: 1.0f
                            val target = (current - 0.25f).coerceIn(0.25f, 2.0f)
                            speedFeature?.set(target)
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.RightBracket -> {
                            val speedFeature = mediampPlayer.features[PlaybackSpeed]
                            val current = speedFeature?.value ?: 1.0f
                            val target = (current + 0.25f).coerceIn(0.25f, 2.0f)
                            speedFeature?.set(target)
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.Backspace -> {
                            mediampPlayer.features[PlaybackSpeed]?.set(1.0f)
                            hudController.onUserInteraction(isDpadNavigation = true)
                            true
                        }
                        Key.Escape, Key.Back -> {
                            if (drawerState.isOpen) {
                                drawerViewModel.closeDrawer()
                                true
                            } else {
                                saveCurrentProgress()
                                mediampPlayer.close()
                                onClose()
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // 1. In-process Compose MPV Video Player Surface (Zero-Copy GPU Surface via Skiko)
        MpvMediampPlayerSurface(
            player = mediampPlayer,
            modifier = Modifier.fillMaxSize(),
        )

        // 2. Gesture Detector Layer (Double-tap for fullscreen / 3-zone seek, single-tap for play/pause, long-press for 2x speed)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val width = size.width.toFloat()
                            if (width > 0f) {
                                when {
                                    offset.x < width * 0.25f -> {
                                        val curMs = mediampPlayer.currentPositionMillis.value
                                        mediampPlayer.seekTo((curMs - 10000L).coerceAtLeast(0L))
                                        hudController.onUserInteraction(isDpadNavigation = false)
                                    }
                                    offset.x > width * 0.75f -> {
                                        val curMs = mediampPlayer.currentPositionMillis.value
                                        val durMs = mediampPlayer.mediaProperties.value?.durationMillis ?: 0L
                                        mediampPlayer.seekTo((curMs + 10000L).coerceAtMost(durMs))
                                        hudController.onUserInteraction(isDpadNavigation = false)
                                    }
                                    else -> onToggleFullscreen()
                                }
                            } else {
                                onToggleFullscreen()
                            }
                        },
                        onTap = {
                            if (hudController.uiState.value.isVisible) {
                                if (mediampPlayer.state.value.isPlaying) mediampPlayer.pause() else mediampPlayer.play()
                            }
                            hudController.onUserInteraction(isDpadNavigation = false)
                        },
                        onLongPress = {
                            val speedFeature = mediampPlayer.features[PlaybackSpeed]
                            speedFeature?.set(2.0f)
                            hudController.onUserInteraction(isDpadNavigation = false)
                        }
                    )
                }
        )

        // 3. Transparent TV OSD / HUD Overlay with full desktop controls
        TvPlayerHud(
            controller = hudController,
            player = mediampPlayer,
            onNextEpisode = launchData.onNextEpisode?.let { nextEp ->
                {
                    saveCurrentProgress()
                    nextEp.invoke()
                }
            },
            onOpenDrawer = { tab ->
                drawerViewModel.openDrawer(tab)
            },
            onToggleFullscreen = onToggleFullscreen,
            onClose = {
                saveCurrentProgress()
                mediampPlayer.close()
                onClose()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 4. Upstream 1:1 Loading Spinner and Dynamic "Yüklemeyi Atla (X)" Overlay
        if (isPlayerLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (playerState.isPlaying) 0.4f else 0.85f))
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = TvColors.FocusElectricBlue,
                        strokeWidth = 4.dp
                    )

                    // Upstream GeneratorPlayer overlay_loading_skip_button parity
                    if (canSkipLoading) {
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = skipLoadingAction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TvColors.FocusElectricBlue,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                            modifier = Modifier
                                .height(44.dp)
                                .focusRequester(skipButtonFocusRequester)
                                .focusable()
                        ) {
                            Text(
                                text = "Yüklemeyi Atla (${resolvedLinks.size})",
                                style = TvTypography.Button.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }

        // 5. In-Player Track, Source and Episode Drawer
        if (drawerState.isOpen) {
            InPlayerEpisodeDrawer(
                viewModel = drawerViewModel,
                modifier = Modifier.fillMaxSize(),
                onSelectSource = { link ->
                    drawerViewModel.closeDrawer()
                    val curPos = mediampPlayer.currentPositionMillis.value
                    playSelectedLink(link, startPosMs = curPos)
                },
                onLaunchEpisodeStream = { ep ->
                    drawerViewModel.closeDrawer()
                    if (launchData.provider != null) {
                        scrapeJob?.cancel()
                        hasStartedPlayback = false
                        isResolvingLinks = true
                        resolvedLinks.clear()
                        resolvedSubtitles.clear()
                        seenLinkUrls.clear()
                        seenSubUrls.clear()
                        scrapeJob = coroutineScope.launch(Dispatchers.IO) {
                            val p = launchData.provider
                            val d = ep.data
                            try {
                                p.loadLinks(
                                    data = d,
                                    isCasting = false,
                                    subtitleCallback = { sub ->
                                        if (seenSubUrls.add(sub.url)) {
                                            coroutineScope.launch(Dispatchers.Main) {
                                                resolvedSubtitles.add(sub)
                                                if (hasStartedPlayback && sub.url.isNotBlank()) {
                                                    (mediampPlayer.impl as? MPVHandle)?.command("sub-add", sub.url)
                                                }
                                            }
                                        }
                                    },
                                    callback = { link ->
                                        if (seenLinkUrls.add(link.url)) {
                                            coroutineScope.launch(Dispatchers.Main) {
                                                resolvedLinks.add(link)
                                            }
                                        }
                                    }
                                )
                            } catch (e: Throwable) {
                                AppLogger.e("EmbeddedPlayerView", "Failed to load episode stream: ${e.message}")
                            } finally {
                                isResolvingLinks = false
                                withContext(Dispatchers.Main) {
                                    if (!hasStartedPlayback && resolvedLinks.isNotEmpty()) {
                                        val best = selectBestLink(resolvedLinks) ?: resolvedLinks.first()
                                        playSelectedLink(best)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
