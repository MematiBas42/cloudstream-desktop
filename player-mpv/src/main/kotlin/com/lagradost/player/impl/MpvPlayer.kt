// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt", upstreamCommit = "caeec18")
package com.lagradost.player.impl

import android.content.Context
import android.graphics.Bitmap
import android.util.Rational
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.media.DesktopMediaControls
import com.lagradost.common.media.MediaEventListener
import com.lagradost.common.media.MediaMetadata
import com.lagradost.common.media.MediaPlaybackState
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.platform.SleepInhibitor
import com.lagradost.common.storage.WatchHistoryRepository
import com.lagradost.player.api.MediaPlayer
import com.lagradost.player.api.PlayerState
import com.lagradost.player.history.WatchHistoryCoordinator
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.preview.IPreviewGenerator
import com.lagradost.player.preview.PreviewGenerator
import com.lagradost.player.subtitles.MpvSubtitleStyler
import com.lagradost.player.tracks.MpvTrackManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * High-performance Linux MPV Media Player engine.
 * Connects MpvProcessLauncher (Wayland/X11 independent window),
 * MpvIpcClient (native NIO AF_UNIX JSON-RPC),
 * WatchHistoryCoordinator (sub-second watch history synchronization), and
 * MirrorFallbackCoordinator (dynamic in-flight mirror failover & auto-recovery).
 * Implements [MediaPlayer] and [IPlayer] contracts.
 */
class MpvPlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MediaPlayer, IPlayer {

    val ipcClient: MpvIpcClient = MpvIpcClient(scope)
    val historyCoordinator: WatchHistoryCoordinator = WatchHistoryCoordinator(ipcClient, scope)
    val mirrorCoordinator: MirrorFallbackCoordinator = MirrorFallbackCoordinator(this)
    val fallbackCoordinator: MirrorFallbackCoordinator get() = mirrorCoordinator
    val trackManager: com.lagradost.player.tracks.MpvTrackManager = com.lagradost.player.tracks.MpvTrackManager(ipcClient, scope)
    val previewGenerator: IPreviewGenerator = IPreviewGenerator.new()
    var sleepInhibitor: SleepInhibitor = SleepInhibitor.createDefault()
    var mediaControls: DesktopMediaControls = DesktopMediaControls.createDefault()
    private val observedSubtitleCues = CopyOnWriteArrayList<SubtitleCue>()

    @Volatile
    private var isAudioOnlyMode: Boolean = false

    fun isAudioOnly(): Boolean = isAudioOnlyMode

    override val state: StateFlow<PlayerState> = ipcClient.state

    private val _playerError = MutableStateFlow<PlayerError?>(null)
    val playerError: StateFlow<PlayerError?> = _playerError.asStateFlow()
    var onPlayerError: ((PlayerError) -> Unit)? = null

    private val process = AtomicReference<Process?>(null)
    private var currentSocketFile: File? = null
    private val intentionallyStopped = AtomicBoolean(false)
    private val isFailingOver = AtomicBoolean(false)
    private var autoRecoveryAttempts = 0

    @Volatile
    var windowId: Long? = null
        private set

    @Volatile
    private var currentPlaybackSpeed: Float = 1.0f

    @Volatile
    private var currentSubDelayMs: Long = 0L

    @Volatile
    private var currentPreferredSubtitle: SubtitleData? = null

    private var playerEventHandler: ((PlayerEvent) -> Unit)? = null
    private var requestedPercentages: List<Int>? = null

    private val activeTimeStamps = CopyOnWriteArrayList<VideoSkipStamp>()
    @Volatile
    var currentActiveStamp: VideoSkipStamp? = null
        private set

    fun attachWindow(wid: Long) {
        if (wid > 0L) {
            this.windowId = wid
            AppLogger.i("MpvPlayer", "Attached native window surface (wid=$wid)")
        }
    }

    fun detachWindow() {
        this.windowId = null
        AppLogger.i("MpvPlayer", "Detached native window surface")
    }

    fun toggleMute() {
        ipcClient.cycle("mute")
    }

    @Volatile
    private var lastKnownPositionMs: Long = 0L

    data class PlayCall(
        val link: ExtractorLink,
        val title: String?,
        val subtitles: List<String>,
        val startPositionMs: Long,
    )

    var lastPlayCall: PlayCall? = null

    var socketFactory: (() -> File)? = null
    var processLauncher: (
        socketPath: String,
        mediaUrl: String,
        title: String?,
        startSec: Long,
        userAgent: String?,
        referrer: String?,
        headers: Map<String, String>,
        subtitles: List<String>,
        link: ExtractorLink?,
        extraArgs: List<String>,
        wid: Long?,
    ) -> Process = { socketPath, mediaUrl, title, startSec, userAgent, referrer, headers, subtitles, link, extraArgs, wid ->
        MpvProcessLauncher.launch(
            socketPath = socketPath,
            mediaUrl = mediaUrl,
            title = title,
            startSec = startSec,
            userAgent = userAgent,
            referrer = referrer,
            headers = headers,
            subtitles = subtitles,
            link = link,
            extraArgs = extraArgs,
            wid = wid,
        )
    }

    private data class PlaybackSession(
        val link: ExtractorLink,
        val title: String?,
        val subtitles: List<String>,
        val validatedUrl: String,
        val displayTitle: String,
        val userAgent: String?,
        val referrer: String?,
        val headers: Map<String, String>,
    )

    private var currentSession: PlaybackSession? = null
    val subtitleStyler: MpvSubtitleStyler = MpvSubtitleStyler(ipcClient)

    init {
        mediaControls.setListener(object : MediaEventListener {
            override fun onPlay() {
                resume()
            }
            override fun onPause() {
                pause()
            }
            override fun onToggle() {
                togglePause()
            }
            override fun onNext() {
                handleEvent(CSPlayerEvent.NextEpisode, PlayerEventSource.Player)
            }
            override fun onPrevious() {
                handleEvent(CSPlayerEvent.PrevEpisode, PlayerEventSource.Player)
            }
            override fun onSeek(positionMs: Long) {
                seek(positionMs)
            }
        })

        scope.launch(Dispatchers.IO) {
            var prevFinished = false
            var prevPlaying = false
            var prevLoadingStatus: CSPlayerLoading? = null
            var lastReportedPosMs = 0L
            var lastReportedDurMs = 0L
            var lastReportedMediaTitle: String? = null
            var lastReportedTracks: List<com.lagradost.player.api.TrackInfo> = emptyList()
            var lastReportedEmbeddedSubs: List<SubtitleData> = emptyList()
            var lastReportedAudioTrackId: Int? = null
            var lastReportedSubtitleTrackId: Int? = null

            ipcClient.state.collect { st ->
                if (st.position > 0L) {
                    lastKnownPositionMs = st.position
                }
                val isFinishedTransition = st.isFinished && !prevFinished
                val wasActive = prevPlaying || st.position > 0L || prevFinished
                if (!intentionallyStopped.get() && isFinishedTransition && !st.isCompleted && wasActive) {
                    AppLogger.w("MpvPlayer", "Intercepted MPV end-file error event (position=${st.position}ms, isCompleted=false)")
                    handleStreamFailure("MPV end-file error (isCompleted=false)")
                }

                val currentStatus = when {
                    st.isCompleted || st.isFinished -> CSPlayerLoading.IsEnded
                    st.isLoading -> CSPlayerLoading.IsBuffering
                    st.isPlaying -> CSPlayerLoading.IsPlaying
                    st.isPaused -> CSPlayerLoading.IsPaused
                    else -> CSPlayerLoading.IsPaused
                }

                if (prevLoadingStatus != currentStatus) {
                    val old = prevLoadingStatus ?: CSPlayerLoading.IsPaused
                    playerEventHandler?.invoke(
                        StatusEvent(
                            wasPlaying = old,
                            isPlaying = currentStatus,
                            source = PlayerEventSource.Player
                        )
                    )
                    when (currentStatus) {
                        CSPlayerLoading.IsPlaying -> {
                            sleepInhibitor.acquire()
                            playerEventHandler?.invoke(PlayEvent(PlayerEventSource.Player))
                        }
                        CSPlayerLoading.IsPaused -> {
                            sleepInhibitor.release()
                            playerEventHandler?.invoke(PauseEvent(PlayerEventSource.Player))
                        }
                        CSPlayerLoading.IsEnded -> {
                            sleepInhibitor.release()
                            playerEventHandler?.invoke(VideoEndedEvent(PlayerEventSource.Player))
                        }
                        else -> Unit
                    }
                    prevLoadingStatus = currentStatus
                }

                val durMs = if (st.duration > 0L) st.duration else (st.durationSec * 1000.0).toLong()
                val posMs = if (st.position > 0L) st.position else (st.positionSec * 1000.0).toLong()

                val effectiveTitle = currentSession?.displayTitle
                    ?: currentSession?.title
                    ?: historyCoordinator.currentItem?.title
                    ?: "CloudStream"

                if (durMs > 0L && (durMs != lastReportedDurMs || (effectiveTitle != lastReportedMediaTitle && effectiveTitle.isNotBlank()))) {
                    lastReportedDurMs = durMs
                    lastReportedMediaTitle = effectiveTitle
                    mediaControls.updateMetadata(
                        MediaMetadata(
                            title = effectiveTitle,
                            durationMs = durMs
                        )
                    )
                }

                mediaControls.updatePlaybackState(
                    MediaPlaybackState(
                        isPlaying = st.isPlaying,
                        positionMs = posMs,
                        speed = currentPlaybackSpeed
                    )
                )

                if (durMs > 0L && posMs >= 0L && (posMs != lastReportedPosMs || st.isPlaying)) {
                    playerEventHandler?.invoke(
                        PositionEvent(
                            source = PlayerEventSource.Player,
                            fromMs = lastReportedPosMs,
                            toMs = posMs,
                            durationMs = durMs
                        )
                    )
                    lastReportedPosMs = posMs

                    val stamp = activeTimeStamps.firstOrNull { posMs in it.timestamp.startMs..it.timestamp.endMs }
                    if (stamp != currentActiveStamp) {
                        currentActiveStamp = stamp
                        if (stamp != null) {
                            playerEventHandler?.invoke(TimestampInvokedEvent(stamp, PlayerEventSource.Player))
                        }
                    }
                }

                val subText = st.currentSubText
                if (!subText.isNullOrBlank()) {
                    val lastCue = observedSubtitleCues.lastOrNull()
                    if (lastCue == null || lastCue.text.firstOrNull() != subText) {
                        observedSubtitleCues.add(SubtitleCue(posMs, 3000L, listOf(subText)))
                        if (observedSubtitleCues.size > 500) {
                            observedSubtitleCues.removeAt(0)
                        }
                    }
                }

                val tracksChanged = st.tracks != lastReportedTracks
                val activeSelectionChanged = st.activeAudioTrackId != lastReportedAudioTrackId ||
                    st.activeSubtitleTrackId != lastReportedSubtitleTrackId

                if (tracksChanged || activeSelectionChanged) {
                    lastReportedTracks = st.tracks
                    lastReportedAudioTrackId = st.activeAudioTrackId
                    lastReportedSubtitleTrackId = st.activeSubtitleTrackId

                    if (st.tracks.isEmpty()) {
                        lastReportedEmbeddedSubs = emptyList()
                    } else {
                        val embeddedSubs = st.tracks
                            .filter {
                                it.type == "sub" &&
                                    MpvTrackManager.resolveSubtitleOrigin(it.external, it.externalFilename) ==
                                    com.lagradost.player.tracks.SubtitleOrigin.EMBEDDED_IN_VIDEO
                            }
                            .map { track ->
                                val langName = if (!track.lang.isNullOrBlank()) {
                                    MpvTrackManager.resolveLanguageName(track.lang)
                                } else if (!track.title.isNullOrBlank()) {
                                    track.title
                                } else {
                                    "Unknown"
                                }
                                val suffix = if (!track.lang.isNullOrBlank() &&
                                    !track.title.isNullOrBlank() &&
                                    !track.title.equals(langName, ignoreCase = true)
                                ) {
                                    track.title
                                } else {
                                    ""
                                }
                                SubtitleData(
                                    originalName = langName,
                                    nameSuffix = suffix,
                                    url = track.id.toString(),
                                    origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
                                    mimeType = "application/x-subrip",
                                    headers = emptyMap(),
                                    languageCode = track.lang
                                )
                            }

                        if (embeddedSubs.isNotEmpty() && embeddedSubs != lastReportedEmbeddedSubs) {
                            lastReportedEmbeddedSubs = embeddedSubs
                            playerEventHandler?.invoke(EmbeddedSubtitlesFetchedEvent(embeddedSubs))
                            playerEventHandler?.invoke(SubtitlesUpdatedEvent())
                        }

                        playerEventHandler?.invoke(TracksChangedEvent())
                    }
                }

                prevFinished = st.isFinished
                prevPlaying = st.isPlaying
            }
        }

        ipcClient.addOnDisconnectListener {
            if (!intentionallyStopped.get()) {
                AppLogger.w("MpvPlayer", "MPV Unix socket disconnected unexpectedly")
                handleStreamFailure("MPV Unix socket disconnected unexpectedly")
            }
        }
    }

    fun handleStreamFailure(reason: String, exception: Throwable? = null) {
        if (intentionallyStopped.get()) {
            return
        }
        if (isFailingOver.getAndSet(true)) {
            AppLogger.d("MpvPlayer", "Failover already in progress, ignoring duplicate trigger: $reason")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                executeFailover(reason, exception)
            } finally {
                isFailingOver.set(false)
            }
        }
    }

    private suspend fun executeFailover(reason: String, exception: Throwable?) {
        val failedLink = mirrorCoordinator.currentLink ?: currentSession?.link
        if (failedLink != null) {
            mirrorCoordinator.markErrored(failedLink)
        }

        if (mirrorCoordinator.hasNextMirror()) {
            val currentPositionMs = if (state.value.position > 0L) state.value.position else lastKnownPositionMs
            val nextLink = mirrorCoordinator.nextMirror()
            if (nextLink != null) {
                AppLogger.i("MpvPlayer", "In-flight mirror failover: switching to [${nextLink.name}] (${nextLink.url}) at ${currentPositionMs}ms (Reason: $reason)")
                val playResult = play(
                    link = nextLink,
                    title = currentSession?.title,
                    subtitles = currentSession?.subtitles ?: emptyList(),
                    startPositionMs = currentPositionMs,
                )
                if (playResult.isFailure) {
                    AppLogger.w("MpvPlayer", "Playback launch failed for next mirror [${nextLink.name}], cascading to subsequent mirror", playResult.exceptionOrNull())
                    executeFailover("Launch failed for ${nextLink.name}", playResult.exceptionOrNull())
                }
                return
            }
        }

        AppLogger.e("MpvPlayer", "All candidate mirrors exhausted ($reason). Surfacing PlayerError.NoMirrorsRemaining.")
        _playerError.value = PlayerError.NoMirrorsRemaining
        onPlayerError?.invoke(PlayerError.NoMirrorsRemaining)
        playerEventHandler?.invoke(ErrorEvent(PlayerError.NoMirrorsRemaining, PlayerEventSource.Player))
        stop()
    }

    suspend fun playWithMirrors(
        links: List<ExtractorLink>,
        title: String? = null,
        subtitles: List<String> = emptyList(),
        startPositionMs: Long = 0L,
    ): Result<Unit> {
        require(links.isNotEmpty()) { "Cannot start playback with empty links list" }
        mirrorCoordinator.reset(links)
        val firstLink = mirrorCoordinator.currentLink ?: return Result.failure(PlayerError.NoMirrorsRemaining)
        return play(firstLink, title, subtitles, startPositionMs)
    }

    override suspend fun play(
        link: ExtractorLink,
        title: String?,
        subtitles: List<String>,
        startPositionMs: Long,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lastPlayCall = PlayCall(link, title, subtitles, startPositionMs)

            if (mirrorCoordinator.candidateMirrors.none { it.url == link.url }) {
                mirrorCoordinator.addCandidateLinks(listOf(link))
            }
            val linkIdx = mirrorCoordinator.candidateMirrors.indexOfFirst { it.url == link.url }
            if (linkIdx >= 0) {
                mirrorCoordinator.currentLinkIndex = linkIdx
            }

            val support = PlayerLinkHandler.playbackSupport(link, PlayerLinkHandler.PlayerBackend.MPV)
            require(support.supported) { support.reason ?: "MPV does not support this stream." }

            val stream = PlayerLinkHandler.loadLink(link, title, startPositionMs).getOrThrow()

            val resumeFromDb = if (startPositionMs <= 0L) {
                val lastWatched = WatchHistoryRepository.getLastWatched(link.url, null)
                if (lastWatched != null && !lastWatched.isCompleted) {
                    PlayerLinkHandler.resumeStartSeconds(lastWatched.position, lastWatched.duration)
                } else {
                    0L
                }
            } else {
                0L
            }

            val startSec = if (startPositionMs > 0L) {
                startPositionMs / 1000L
            } else {
                resumeFromDb
            }

            lastKnownPositionMs = if (startPositionMs > 0L) startPositionMs else resumeFromDb * 1000L
            _playerError.value = null
            isAudioOnlyMode = false
            observedSubtitleCues.clear()
            (previewGenerator as? PreviewGenerator)?.let { gen ->
                if (!gen.hasPreview()) {
                    gen.load(link, keepCache = true)
                }
            }

            val userAgent = stream.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value
            val referrer = stream.headers.entries.firstOrNull {
                it.key.equals("referer", true) || it.key.equals("referrer", true)
            }?.value

            val session = PlaybackSession(
                link = link,
                title = title,
                subtitles = subtitles,
                validatedUrl = stream.url,
                displayTitle = stream.displayTitle,
                userAgent = userAgent,
                referrer = referrer,
                headers = stream.headers,
            )
            currentSession = session
            autoRecoveryAttempts = 0

            startPlaybackInternal(session, startSec)
        }
    }

    private val playbackMutex = Mutex()

    private suspend fun startPlaybackInternal(
        session: PlaybackSession,
        startSec: Long,
    ) {
        playbackMutex.withLock {
        stopProcessOnly()

        intentionallyStopped.set(false)
        historyCoordinator.start()
        sleepInhibitor.acquire()
        mediaControls.updateMetadata(
            MediaMetadata(
                title = session.displayTitle.ifBlank { session.title ?: "CloudStream" },
                durationMs = 0L
            )
        )
        mediaControls.updatePlaybackState(
            MediaPlaybackState(
                isPlaying = true,
                positionMs = startSec * 1000L,
                speed = currentPlaybackSpeed
            )
        )

        val socketDir = PlatformPaths.socketDir.toFile().apply { mkdirs() }
        val socketFile = socketFactory?.invoke() ?: File(socketDir, "mpv_${UUID.randomUUID()}.sock")
        currentSocketFile = socketFile

        if (historyCoordinator.currentItem == null || historyCoordinator.currentItem?.url.isNullOrBlank()) {
            historyCoordinator.currentItem = WatchHistoryCoordinator.WatchHistoryItem(
                url = session.link.url,
                title = session.displayTitle,
            )
        }

        ipcClient.resetState(session.validatedUrl)

        val launched = processLauncher(
            socketFile.absolutePath,
            session.validatedUrl,
            session.displayTitle,
            startSec,
            session.userAgent,
            session.referrer,
            session.headers,
            session.subtitles,
            session.link,
            emptyList(),
            windowId,
        )
        process.set(launched)

        val connected = ipcClient.connect(socketFile)
        if (!connected) {
            AppLogger.w("MpvPlayer", "Failed to establish IPC connection to MPV on ${socketFile.absolutePath}")
        } else {
            subtitleStyler.applyStyle(SubtitlesFragment.getCurrentSavedStyle())
        }

        scope.launch(Dispatchers.IO) {
            try {
                launched.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        AppLogger.d("MPV", line)
                    }
                }
            } catch (e: java.io.IOException) {
                AppLogger.d("MpvPlayer", "MPV stdout reader closed: ${e.message}")
            }

            val exitCode = runCatching { launched.waitFor() }.getOrDefault(-1)
            AppLogger.i("MPV process terminated with exit code $exitCode")

            if (process.compareAndSet(launched, null)) {
                val wasIntentional = intentionallyStopped.get()
                currentSocketFile?.let { runCatching { it.delete() } }

                if (!wasIntentional) {
                    if (exitCode != 0) {
                        AppLogger.w("MpvPlayer", "MPV process crashed or failed with exit code $exitCode. Triggering fallback.")
                        handleStreamFailure("MPV process exit code $exitCode")
                        return@launch
                    }
                }

                historyCoordinator.stop()
                sleepInhibitor.release()
                ipcClient.close()
            }
        }
        }
    }

    override fun playLocal(file: File) {
        if (!file.exists() || !file.isFile) {
            AppLogger.e("MpvPlayer", "Cannot play local file, file does not exist: ${file.absolutePath}")
            return
        }

        scope.launch(Dispatchers.IO) {
            stopProcessOnly()
            intentionallyStopped.set(false)
            isAudioOnlyMode = false
            observedSubtitleCues.clear()
            historyCoordinator.start()
            sleepInhibitor.acquire()
            mediaControls.updateMetadata(
                MediaMetadata(
                    title = file.nameWithoutExtension,
                    durationMs = 0L
                )
            )
            mediaControls.updatePlaybackState(
                MediaPlaybackState(
                    isPlaying = true,
                    positionMs = 0L,
                    speed = currentPlaybackSpeed
                )
            )

            val socketDir = PlatformPaths.socketDir.toFile().apply { mkdirs() }
            val socketFile = socketFactory?.invoke() ?: File(socketDir, "mpv_${UUID.randomUUID()}.sock")
            currentSocketFile = socketFile

            historyCoordinator.currentItem = WatchHistoryCoordinator.WatchHistoryItem(
                url = file.absolutePath,
                title = file.nameWithoutExtension,
            )

            ipcClient.resetState(file.absolutePath)

            val launched = processLauncher(
                socketFile.absolutePath,
                file.absolutePath,
                file.nameWithoutExtension,
                0L,
                null,
                null,
                emptyMap(),
                emptyList(),
                null,
                emptyList(),
                windowId,
            )
            process.set(launched)

            ipcClient.connect(socketFile)

            scope.launch(Dispatchers.IO) {
                try {
                    launched.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { AppLogger.d("MPV", it) }
                    }
                } catch (e: Exception) {
                    AppLogger.d("MpvPlayer", "Local playback reader closed: ${e.message}")
                }

                runCatching { launched.waitFor() }
                if (process.compareAndSet(launched, null)) {
                    socketFile.delete()
                    historyCoordinator.flush()
                    sleepInhibitor.release()
                    ipcClient.close()
                }
            }
        }
    }

    override fun pause() {
        ipcClient.pause()
        historyCoordinator.flush()
        sleepInhibitor.release()
    }

    override fun resume() {
        ipcClient.play()
        sleepInhibitor.acquire()
    }

    fun togglePause() {
        ipcClient.togglePause()
    }

    override fun seek(positionMs: Long) {
        val sec = (positionMs / 1000.0).coerceAtLeast(0.0)
        ipcClient.seek(sec, "absolute")
        historyCoordinator.flush()
    }

    fun seekSeconds(positionSec: Double) {
        ipcClient.seek(positionSec.coerceAtLeast(0.0))
        historyCoordinator.flush()
    }

    fun selectSubtitle(trackId: Int) {
        ipcClient.selectSubtitle(trackId)
    }

    fun selectAudio(trackId: Int) {
        ipcClient.selectAudio(trackId)
    }

    fun adjustVolume(delta: Double) {
        ipcClient.adjustVolume(delta)
    }

    fun setVolume(volume: Double) {
        ipcClient.setVolume(volume)
    }

    private fun stopProcessOnly() {
        eventLooperIndex++
        intentionallyStopped.set(true)
        historyCoordinator.stop()
        sleepInhibitor.release()
        mediaControls.updatePlaybackState(
            MediaPlaybackState(
                isPlaying = false,
                positionMs = lastKnownPositionMs,
                speed = currentPlaybackSpeed
            )
        )
        ipcClient.quit()
        process.getAndSet(null)?.let { proc ->
            runCatching {
                proc.destroy()
                proc.waitFor()
            }
        }
        ipcClient.close()
        currentSocketFile?.let { runCatching { it.delete() } }
        currentSocketFile = null
    }

    override fun stop() {
        stopProcessOnly()
    }

    override fun destroy() {
        stop()
        previewGenerator.release()
        mediaControls.close()
        scope.cancel()
    }

    private var eventLooperIndex = 0

    fun startTorrentEventLooper(hash: String) {
        val currentIndex = ++eventLooperIndex
        scope.launch(Dispatchers.IO) {
            while (eventLooperIndex == currentIndex && playerEventHandler != null) {
                try {
                    val status = TorrServerClient.getStats(hash)
                    playerEventHandler?.invoke(
                        DownloadEvent(
                            connections = status.activePeers ?: status.totalPeers,
                            downloadSpeed = status.downloadSpeed?.toLong() ?: 0L,
                            totalBytes = status.torrentSize ?: 0L,
                            downloadedBytes = status.bytesRead ?: status.loadedSize ?: 0L,
                            source = PlayerEventSource.Player
                        )
                    )
                } catch (e: Exception) {
                    AppLogger.d("MpvPlayer", "Failed to emit torrent DownloadEvent: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    // =========================================================================
    // IPlayer Contract Implementation
    // =========================================================================

    override fun getPlaybackSpeed(): Float = currentPlaybackSpeed

    override fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        ipcClient.sendCommand(listOf("set_property", "speed", speed.toDouble()))
    }

    override fun getIsPlaying(): Boolean = state.value.isPlaying

    override fun getDuration(): Long? =
        state.value.duration.takeIf { it > 0L }
            ?: (state.value.durationSec * 1000.0).toLong().takeIf { it > 0L }

    override fun getPosition(): Long? =
        if (state.value.position > 0L) state.value.position
        else (state.value.positionSec * 1000.0).toLong().takeIf { it >= 0L }
            ?: lastKnownPositionMs

    override fun seekTime(time: Long, source: PlayerEventSource) {
        val current = getPosition() ?: 0L
        seek((current + time).coerceAtLeast(0L))
    }

    override fun seekTo(time: Long, source: PlayerEventSource) {
        seek(time.coerceAtLeast(0L))
    }

    override fun getSubtitleOffset(): Long = currentSubDelayMs

    override fun setSubtitleOffset(offset: Long) {
        currentSubDelayMs = offset
        ipcClient.sendCommand(listOf("set_property", "sub-delay", offset / 1000.0))
    }

    @AnyThread
    override fun initCallbacks(
        @MainThread eventHandler: ((PlayerEvent) -> Unit),
        requestedListeningPercentages: List<Int>?,
    ) {
        this.playerEventHandler = eventHandler
        this.requestedPercentages = requestedListeningPercentages
    }

    override fun releaseCallbacks() {
        this.playerEventHandler = null
        this.requestedPercentages = null
    }

    override fun updateSubtitleStyle(style: SaveCaptionStyle) {
        subtitleStyler.applyStyle(style)
    }

    override fun saveData() {
        historyCoordinator.flush()
    }

    override fun addTimeStamps(timeStamps: List<VideoSkipStamp>) {
        activeTimeStamps.clear()
        activeTimeStamps.addAll(timeStamps)
        currentActiveStamp = null
    }

    override fun loadPlayer(
        context: Context,
        sameEpisode: Boolean,
        link: ExtractorLink?,
        data: ExtractorUri?,
        startPosition: Long?,
        subtitles: Set<SubtitleData>,
        subtitle: SubtitleData?,
        autoPlay: Boolean?,
        preview: Boolean,
    ) {
        val targetLink = link ?: data?.let {
            ExtractorLink(
                source = it.name,
                name = it.name,
                url = it.uri.toString(),
                referer = "",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.VIDEO
            )
        }

        (previewGenerator as? PreviewGenerator)?.let { gen ->
            if (preview) {
                if (link != null) {
                    gen.load(link, sameEpisode)
                } else if (data != null) {
                    gen.load(context, data, sameEpisode)
                }
            } else {
                gen.clear(sameEpisode)
            }
        }

        if (targetLink != null) {
            currentPreferredSubtitle = subtitle
            val subUrls = subtitles.mapNotNull { it.url }

            val torrentHash = when {
                targetLink.url.contains("link=") -> targetLink.url.substringAfter("link=").substringBefore("&")
                targetLink.type == ExtractorLinkType.TORRENT -> targetLink.url
                else -> null
            }
            if (!torrentHash.isNullOrBlank()) {
                startTorrentEventLooper(torrentHash)
            }

            scope.launch(Dispatchers.IO) {
                if (data != null && link == null) {
                    PlayerLinkHandler.loadLink(targetLink, data.name).onFailure { err ->
                        AppLogger.e("MpvPlayer", "Failed to validate offline media link via PlayerLinkHandler: ${err.message}")
                    }
                }
                val effectivePositionMs = PlayerLinkHandler.resolveFallbackPosition(
                    sameEpisode = sameEpisode,
                    lastKnownPositionMs = if (state.value.position > 0L) state.value.position else lastKnownPositionMs,
                    startPositionMs = startPosition,
                )
                play(
                    link = targetLink,
                    title = data?.name,
                    subtitles = subUrls,
                    startPositionMs = effectivePositionMs,
                )
            }
        }
    }

    override fun reloadPlayer(context: Context) {
        lastPlayCall?.let { call ->
            scope.launch(Dispatchers.IO) {
                play(call.link, call.title, call.subtitles, lastKnownPositionMs)
            }
        }
    }

    override fun getPreview(fraction: Float): Bitmap? {
        return previewGenerator.getPreviewImage(fraction)
    }

    override fun hasPreview(): Boolean {
        return previewGenerator.hasPreview()
    }

    override fun setActiveSubtitles(subtitles: Set<SubtitleData>) {
        for (sub in subtitles) {
            if (sub.origin != SubtitleOrigin.EMBEDDED_IN_VIDEO) {
                ipcClient.sendCommand(listOf("sub-add", sub.url))
            }
        }
    }

    override fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean {
        currentPreferredSubtitle = subtitle
        if (subtitle != null) {
            if (subtitle.origin == SubtitleOrigin.EMBEDDED_IN_VIDEO) {
                val trackId = subtitle.url.toIntOrNull()
                if (trackId != null) {
                    trackManager.selectSubtitleTrack(trackId)
                }
            } else {
                ipcClient.sendCommand(listOf("sub-add", subtitle.url, "select"))
            }
        } else {
            trackManager.selectSubtitleTrack(0)
        }
        return false
    }

    override fun getCurrentPreferredSubtitle(): SubtitleData? = currentPreferredSubtitle

    override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
        when (event) {
            CSPlayerEvent.Pause -> pause()
            CSPlayerEvent.Play -> resume()
            CSPlayerEvent.PlayPauseToggle -> togglePause()
            CSPlayerEvent.SeekForward -> {
                seekTime(10_000L, source)
            }
            CSPlayerEvent.SeekBack -> {
                seekTime(-10_000L, source)
            }
            CSPlayerEvent.ToggleMute -> toggleMute()
            CSPlayerEvent.Restart -> seekTo(0L, source)
            CSPlayerEvent.SkipCurrentChapter -> {
                val stamp = currentActiveStamp ?: activeTimeStamps.firstOrNull {
                    val pos = getPosition() ?: 0L
                    pos in it.timestamp.startMs..it.timestamp.endMs
                }
                if (stamp != null) {
                    val duration = getDuration()
                    val isAtEof = duration != null && duration > 0L && (stamp.timestamp.endMs + 1L) >= duration
                    if (stamp.skipToNextEpisode || isAtEof) {
                        handleEvent(CSPlayerEvent.NextEpisode, source)
                    } else {
                        seekTo(stamp.timestamp.endMs + 1L, source)
                    }
                    playerEventHandler?.invoke(TimestampSkippedEvent(stamp, source))
                }
            }
            CSPlayerEvent.NextEpisode -> {
                playerEventHandler?.invoke(EpisodeSeekEvent(1, source))
            }
            CSPlayerEvent.PrevEpisode -> {
                playerEventHandler?.invoke(EpisodeSeekEvent(-1, source))
            }
            CSPlayerEvent.PlayAsAudio -> {
                isAudioOnlyMode = true
                ipcClient.sendCommand(listOf("set_property", "vid", "no"))
                AppLogger.i("MpvPlayer", "Switched to audio-only playback mode (vid=no)")
            }
        }
    }

    override fun onStop() {
        saveData()
        sleepInhibitor.release()
        if (!isAudioOnlyMode) {
            handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Player)
        }
    }

    override fun onPause() {
        saveData()
        sleepInhibitor.release()
        if (!isAudioOnlyMode) {
            handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Player)
        }
    }
    override fun onResume(context: Context) = resume()
    override fun release() = destroy()

    override fun isActive(): Boolean =
        !intentionallyStopped.get() && (state.value.isPlaying || state.value.isLoading)

    override fun getVideoTracks(): CurrentTracks {
        val tracks = state.value.tracks
        val allVideo = tracks.filter { it.type == "video" }.map {
            VideoTrack(
                id = it.id.toString(),
                label = it.title,
                language = it.lang,
                width = null,
                height = null,
                sampleMimeType = null
            )
        }
        val allAudio = tracks.filter { it.type == "audio" }.map {
            AudioTrack(
                id = it.id.toString(),
                label = it.title,
                language = it.lang,
                sampleMimeType = null,
                channelCount = null,
                formatIndex = null
            )
        }
        val allText = tracks.filter { it.type == "sub" }.map {
            TextTrack(
                id = it.id.toString(),
                label = it.title,
                language = it.lang,
                sampleMimeType = null
            )
        }
        val currVideo = allVideo.firstOrNull { it.id == tracks.firstOrNull { t -> t.type == "video" && t.selected }?.id?.toString() }
        val currAudio = allAudio.firstOrNull { it.id == (state.value.activeAudioTrackId?.toString() ?: tracks.firstOrNull { t -> t.type == "audio" && t.selected }?.id?.toString()) }
        val currText = allText.filter { it.id == (state.value.activeSubtitleTrackId?.toString() ?: tracks.firstOrNull { t -> t.type == "sub" && t.selected }?.id?.toString()) }
        return CurrentTracks(currVideo, currAudio, currText, allVideo, allAudio, allText)
    }

    override fun getAspectRatio(): Rational? {
        val st = state.value
        val aspect = st.videoAspect
        if (aspect != null && aspect > 0.0) {
            return doubleToRational(aspect)
        }
        val dw = st.dwidth
        val dh = st.dheight
        if (dw != null && dh != null && dw > 0 && dh > 0) {
            return Rational(dw, dh)
        }
        return null
    }

    override fun setMaxVideoSize(width: Int, height: Int, id: String?) {
        if (id != null) {
            ipcClient.sendCommand(listOf("set_property", "vid", id))
        }
    }

    override fun setPreferredAudioTrack(trackLanguage: String?, id: String?, formatIndex: Int?) {
        if (id != null) {
            id.toIntOrNull()?.let { selectAudio(it) }
        } else if (trackLanguage != null) {
            val matching = state.value.audioTracks.firstOrNull {
                it.lang.equals(trackLanguage, true)
            }
            matching?.let { selectAudio(it.id) }
        }
    }

    @PlatformQuarantine(
        reason = "MPV IPC does not expose full pre-parsed static cue lists for embedded subtitles; runtime cues are collected from sub-text property and external subtitle files",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt:559",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    override fun getSubtitleCues(): List<SubtitleCue> {
        if (observedSubtitleCues.isNotEmpty()) {
            return observedSubtitleCues.toList()
        }
        val currentText = state.value.currentSubText
        if (!currentText.isNullOrBlank()) {
            val pos = getPosition() ?: 0L
            return listOf(SubtitleCue(pos, 3000L, listOf(currentText)))
        }
        return emptyList()
    }

    companion object {
        fun doubleToRational(value: Double): Rational {
            val tolerance = 1.0E-4
            var h1 = 1
            var h2 = 0
            var k1 = 0
            var k2 = 1
            var b = value
            do {
                val a = Math.floor(b).toInt()
                val auxH = h1
                h1 = a * h1 + h2
                h2 = auxH
                val auxK = k1
                k1 = a * k1 + k2
                k2 = auxK
                if (Math.abs(b - a) < 1.0E-9) break
                b = 1.0 / (b - a)
            } while (Math.abs(value - h1.toDouble() / k1.toDouble()) > value * tolerance && k1 < 10000)
            return if (k1 > 0) Rational(h1, k1) else Rational((value * 1000).toInt(), 1000)
        }
    }
}
