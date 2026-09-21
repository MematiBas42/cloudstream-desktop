// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import android.util.Rational
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.media3.common.MimeTypes
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.player.live.LiveHelper
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.delay

const val TAG = "CS3ExoPlayer"
const val PREFERRED_AUDIO_LANGUAGE_KEY = "preferred_audio_language"

/** toleranceBeforeUs – The maximum time that the actual position seeked to may precede the
 * requested seek position, in microseconds. Must be non-negative. */
const val toleranceBeforeUs = 300_000L

/**
 * toleranceAfterUs – The maximum time that the actual position seeked to may exceed the requested
 * seek position, in microseconds. Must be non-negative.
 */
const val toleranceAfterUs = 300_000L

/**
 * Interface contract for querying TorrServer statistics.
 * Allows pure dependency injection during unit testing and modular TorrServer communication.
 */
interface TorrServerClient {
    suspend fun getStats(hash: String): Torrent.TorrentStatus

    companion object : TorrServerClient {
        @Volatile
        private var clientInstance: TorrServerClient = object : TorrServerClient {
            override suspend fun getStats(hash: String): Torrent.TorrentStatus {
                return Torrent.get(hash)
            }
        }

        fun setClient(client: TorrServerClient) {
            clientInstance = client
        }

        fun resetClient() {
            clientInstance = object : TorrServerClient {
                override suspend fun getStats(hash: String): Torrent.TorrentStatus {
                    return Torrent.get(hash)
                }
            }
        }

        override suspend fun getStats(hash: String): Torrent.TorrentStatus {
            return clientInstance.getStats(hash)
        }
    }
}

/**
 * Lightweight preview generator interface for scrubbing and thumbnail extraction.
 */
interface IPreviewGenerator {
    fun hasPreview(): Boolean
    fun getPreviewImage(fraction: Float): Bitmap?
    fun release()

    companion object {
        fun new(): IPreviewGenerator = empty()
        fun empty(): IPreviewGenerator = object : IPreviewGenerator {
            override fun hasPreview(): Boolean = false
            override fun getPreviewImage(fraction: Float): Bitmap? = null
            override fun release() {}
        }
    }
}

/**
 * Raw track descriptor used to parse incoming track-list events from media playback backends.
 */
data class TrackDescriptor(
    val id: String,
    val type: String, // "video", "audio", "sub", "text"
    val label: String? = null,
    val language: String? = null,
    val isSelected: Boolean = false,
    val sampleMimeType: String? = null,
    val channelCount: Int? = null,
    val formatIndex: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isExternal: Boolean = false
)

/**
 * Desktop Canonical CS3IPlayer implementation adhering 1:1 to upstream CS3IPlayer architecture.
 * Manages playback state, track-list events, embedded subtitles, and background torrent telemetry.
 */
class CS3IPlayer(
    private var delegate: IPlayer? = null
) : IPlayer {

    var cacheSize = 0L
    var simpleCacheSize = 0L
    var videoBufferMs = 0L

    val imageGenerator: IPreviewGenerator = IPreviewGenerator.new()

    private val seekActionTime = 30000L

    private var isPlaying = false
    private var playBackSpeed: Float = 1.0f
    private var durationMs: Long? = null
    private var positionMs: Long? = null
    private var currentSubtitleOffset: Long = 0L

    private var lastMuteVolume: Float = 1.0f
    private var currentLink: ExtractorLink? = null
    private var currentDownloadedFile: ExtractorUri? = null

    private var currentSubtitles: SubtitleData? = null
    private var activeSubtitles: Set<SubtitleData> = emptySet()
    private val subtitleHelper = PlayerSubtitleHelper()

    private var currentTracks: CurrentTracks = CurrentTracks(
        currentVideoTrack = null,
        currentAudioTrack = null,
        currentTextTracks = emptyList(),
        allVideoTracks = emptyList(),
        allAudioTracks = emptyList(),
        allTextTracks = emptyList()
    )

    var playerSelectedSubtitleTracks: List<Pair<String, Boolean>> = emptyList()
        private set

    private var requestedListeningPercentages: List<Int>? = null
    private var eventHandler: ((PlayerEvent) -> Unit)? = null

    @Volatile
    var isPlayerActive: Boolean = false
        private set

    private var isAudioOnlyBackground = false
    private var currentAspectRatio: Rational? = Rational(16, 9)

    private var activeTimeStamps: List<VideoSkipStamp> = emptyList()
    private var currentActiveStamp: VideoSkipStamp? = null

    var maxVideoWidth: Int = Int.MAX_VALUE
        private set
    var maxVideoHeight: Int = Int.MAX_VALUE
        private set
    var maxVideoId: String? = null
        private set

    var selectedAudioTrackId: String? = null
        private set
    var selectedAudioTrackLanguage: String? = null
        private set

    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var captionStyle: SaveCaptionStyle? = null

    fun setDelegate(engine: IPlayer?) {
        this.delegate = engine
    }

    fun String.stripTrackId(): String {
        return this.replace(Regex("""^\d+:"""), "")
    }

    @AnyThread
    fun event(event: PlayerEvent) {
        val handler = eventHandler ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handler.invoke(event)
        } else {
            runOnMainThread {
                eventHandler?.invoke(event)
            }
        }
    }

    override fun initCallbacks(
        @MainThread eventHandler: (PlayerEvent) -> Unit,
        requestedListeningPercentages: List<Int>?
    ) {
        delegate?.initCallbacks(eventHandler, requestedListeningPercentages)
        this.eventHandler = eventHandler
        this.requestedListeningPercentages = requestedListeningPercentages
        if (!isPlayerActive) {
            isPlayerActive = true
            activePlayers += 1
        }
    }

    override fun releaseCallbacks() {
        delegate?.releaseCallbacks()
        eventHandler = null
        if (isPlayerActive) {
            isPlayerActive = false
            activePlayers = (activePlayers - 1).coerceAtLeast(0)
            releaseCronetEngine()
        }
    }

    // =========================================================================
    // Torrent Event Looper & Telemetry Wiring
    // =========================================================================

    private var eventLooperIndex = 0

    fun getEventLooperIndex(): Int = eventLooperIndex

    /**
     * Loops indefinitely pushing torrent download statistics until eventLooperIndex changes
     * or eventHandler becomes null.
     */
    fun torrentEventLooper(hash: String) = ioSafe {
        eventLooperIndex += 2
        val currentIndex = eventLooperIndex + 1
        while (eventLooperIndex <= currentIndex && eventHandler != null) {
            try {
                val status = TorrServerClient.getStats(hash)
                val connections = status.activePeers ?: status.totalPeers
                val downloadSpeed = status.downloadSpeed?.toLong() ?: 0L
                val totalBytes = status.torrentSize ?: 0L
                val downloadedBytes = status.bytesRead ?: status.loadedSize ?: 0L

                event(
                    DownloadEvent(
                        connections = connections,
                        downloadSpeed = downloadSpeed,
                        totalBytes = totalBytes,
                        downloadedBytes = downloadedBytes,
                        source = PlayerEventSource.Player
                    )
                )
            } catch (_: NullPointerException) {
            } catch (t: Throwable) {
                logError(t)
            }
            delay(1000)
        }
    }

    @MainThread
    fun loadTorrent(context: Context, link: ExtractorLink) {
        ioSafe {
            try {
                val (newLink, status) = Torrent.transformLink(link)
                val hash = status.hash
                runOnMainThread {
                    releasePlayer()
                    if (hash != null) {
                        torrentEventLooper(hash)
                    }
                    loadOnlinePlayer(context, newLink)
                }
            } catch (t: Throwable) {
                event(ErrorEvent(t))
            }
        }
    }

    @MainThread
    fun loadOnlinePlayer(context: Context, link: ExtractorLink, retry: Boolean = false) {
        Log.i(TAG, "loadOnlinePlayer $link")
        currentLink = link
        isPlaying = true
        LiveHelper.registerPlayer(this)
        event(StatusEvent(wasPlaying = CSPlayerLoading.IsPaused, isPlaying = CSPlayerLoading.IsPlaying))
        event(PlayEvent())
    }

    // =========================================================================
    // Track-List & Embedded Subtitles / Audio Event Wiring
    // =========================================================================

    /**
     * Core track change processor. Updates internal CurrentTracks, parses embedded subtitles
     * into SubtitleData with SubtitleOrigin.EMBEDDED_IN_VIDEO, and fires the three canonical events:
     * EmbeddedSubtitlesFetchedEvent, SubtitlesUpdatedEvent, and TracksChangedEvent.
     */
    fun onTracksChanged(
        videoTracks: List<VideoTrack> = emptyList(),
        audioTracks: List<AudioTrack> = emptyList(),
        textTracks: List<TextTrack> = emptyList(),
        selectedVideoTrack: VideoTrack? = null,
        selectedAudioTrack: AudioTrack? = null,
        selectedTextTracks: List<TextTrack> = emptyList()
    ) {
        safe {
            val resolvedCurrentAudio = selectedAudioTrack ?: audioTracks.firstOrNull { audio ->
                (selectedAudioTrackId != null && audio.id == selectedAudioTrackId) ||
                (preferredAudioTrackLanguage != null && audio.language.equals(preferredAudioTrackLanguage, ignoreCase = true))
            } ?: audioTracks.firstOrNull()

            currentTracks = CurrentTracks(
                currentVideoTrack = selectedVideoTrack ?: videoTracks.firstOrNull(),
                currentAudioTrack = resolvedCurrentAudio,
                currentTextTracks = selectedTextTracks,
                allVideoTracks = videoTracks,
                allAudioTracks = audioTracks,
                allTextTracks = textTracks
            )

            playerSelectedSubtitleTracks = textTracks.mapNotNull { track ->
                val trackId = track.id?.stripTrackId() ?: return@mapNotNull null
                val isSelected = selectedTextTracks.any { it.id == track.id }
                trackId to isSelected
            }

            val reportedTracks = textTracks.mapNotNull { track ->
                val trackId = track.id ?: return@mapNotNull null
                val lang = track.language
                if (lang == null || lang.startsWith("-")) return@mapNotNull null

                SubtitleData(
                    originalName = fromTagToLanguageName(lang) ?: lang,
                    nameSuffix = track.label ?: "",
                    url = trackId.stripTrackId(),
                    origin = SubtitleOrigin.EMBEDDED_IN_VIDEO,
                    mimeType = track.sampleMimeType ?: MimeTypes.APPLICATION_SUBRIP,
                    headers = emptyMap(),
                    languageCode = lang
                )
            }

            // Upstream fires EmbeddedSubtitlesFetchedEvent unconditionally (even with empty list),
            // then TracksChangedEvent, then SubtitlesUpdatedEvent — exact upstream order preserved.
            event(EmbeddedSubtitlesFetchedEvent(tracks = reportedTracks))
            event(TracksChangedEvent())
            event(SubtitlesUpdatedEvent())
        }
    }

    /**
     * Ingests a list of [TrackDescriptor] items from MPV or other media backends, converts them to
     * canonical VideoTrack, AudioTrack, and TextTrack instances, and invokes [onTracksChanged].
     */
    fun onTrackListReceived(tracks: List<TrackDescriptor>) {
        safe {
            val videoTracks = tracks.filter { it.type.equals("video", ignoreCase = true) }.map {
                VideoTrack(
                    id = it.id.stripTrackId(),
                    label = it.label,
                    language = it.language,
                    width = it.width,
                    height = it.height,
                    sampleMimeType = it.sampleMimeType
                )
            }
            val audioTracks = tracks.filter { it.type.equals("audio", ignoreCase = true) }.mapIndexed { idx, it ->
                AudioTrack(
                    id = it.id.stripTrackId(),
                    label = it.label,
                    language = it.language,
                    sampleMimeType = it.sampleMimeType,
                    channelCount = it.channelCount,
                    formatIndex = it.formatIndex ?: idx
                )
            }
            val textTracks = tracks.filter {
                it.type.equals("sub", ignoreCase = true) || it.type.equals("text", ignoreCase = true)
            }.map {
                TextTrack(
                    id = it.id.stripTrackId(),
                    label = it.label,
                    language = it.language,
                    sampleMimeType = it.sampleMimeType
                )
            }

            val selectedVideo = videoTracks.firstOrNull { v ->
                tracks.any { it.type.equals("video", ignoreCase = true) && it.id.stripTrackId() == v.id && it.isSelected }
            }
            val selectedAudio = audioTracks.firstOrNull { a ->
                tracks.any { it.type.equals("audio", ignoreCase = true) && it.id.stripTrackId() == a.id && it.isSelected }
            }
            val selectedText = textTracks.filter { t ->
                tracks.any { (it.type.equals("sub", ignoreCase = true) || it.type.equals("text", ignoreCase = true)) &&
                    it.id.stripTrackId() == t.id && it.isSelected }
            }

            onTracksChanged(
                videoTracks = videoTracks,
                audioTracks = audioTracks,
                textTracks = textTracks,
                selectedVideoTrack = selectedVideo,
                selectedAudioTrack = selectedAudio,
                selectedTextTracks = selectedText
            )
        }
    }

    override fun getVideoTracks(): CurrentTracks {
        return delegate?.getVideoTracks() ?: currentTracks
    }

    override fun setPreferredAudioTrack(trackLanguage: String?, id: String?, formatIndex: Int?) {
        delegate?.setPreferredAudioTrack(trackLanguage, id, formatIndex)
        preferredAudioTrackLanguage = trackLanguage
        selectedAudioTrackLanguage = trackLanguage
        selectedAudioTrackId = id

        val matchingAudio = currentTracks.allAudioTracks.firstOrNull { audio ->
            (id != null && audio.id == id) ||
            (trackLanguage != null && audio.language.equals(trackLanguage, ignoreCase = true))
        }

        if (matchingAudio != null) {
            currentTracks = currentTracks.copy(currentAudioTrack = matchingAudio)
            event(TracksChangedEvent())
        }
    }

    override fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean {
        delegate?.setPreferredSubtitles(subtitle)
        currentSubtitles = subtitle
        val trackId = subtitle?.url?.stripTrackId()
        val matchingText = if (trackId != null) {
            currentTracks.allTextTracks.filter { it.id == trackId }
        } else {
            emptyList()
        }
        currentTracks = currentTracks.copy(currentTextTracks = matchingText)
        playerSelectedSubtitleTracks = currentTracks.allTextTracks.map { track ->
            (track.id ?: "") to (track.id == trackId)
        }
        event(SubtitlesUpdatedEvent())
        event(TracksChangedEvent())
        return false
    }

    override fun getCurrentPreferredSubtitle(): SubtitleData? {
        return delegate?.getCurrentPreferredSubtitle() ?: currentSubtitles
    }

    override fun setActiveSubtitles(subtitles: Set<SubtitleData>) {
        delegate?.setActiveSubtitles(subtitles)
        activeSubtitles = subtitles
        subtitleHelper.setActiveSubtitles(subtitles)
    }

    override fun setSubtitleOffset(offset: Long) {
        delegate?.setSubtitleOffset(offset)
        currentSubtitleOffset = offset
    }

    override fun getSubtitleOffset(): Long {
        return delegate?.getSubtitleOffset() ?: currentSubtitleOffset
    }

    override fun getSubtitleCues(): List<SubtitleCue> {
        return delegate?.getSubtitleCues() ?: subtitleCues
    }

    fun setSubtitleCues(cues: List<SubtitleCue>) {
        this.subtitleCues = cues
    }

    override fun updateSubtitleStyle(style: SaveCaptionStyle) {
        delegate?.updateSubtitleStyle(style)
        this.captionStyle = style
    }

    // =========================================================================
    // Playback Navigation & Events
    // =========================================================================

    override fun getDuration(): Long? = delegate?.getDuration() ?: durationMs
    override fun getPosition(): Long? = delegate?.getPosition() ?: positionMs
    override fun getIsPlaying(): Boolean = delegate?.getIsPlaying() ?: isPlaying
    override fun getPlaybackSpeed(): Float = delegate?.getPlaybackSpeed() ?: playBackSpeed

    override fun setPlaybackSpeed(speed: Float) {
        delegate?.setPlaybackSpeed(speed)
        playBackSpeed = speed
    }

    fun updatePosition(posMs: Long, durationMs: Long, source: PlayerEventSource = PlayerEventSource.Player) {
        val from = positionMs ?: 0L
        this.positionMs = posMs
        this.durationMs = durationMs
        event(PositionEvent(source = source, fromMs = from, toMs = posMs, durationMs = durationMs))
        checkTimestamps(posMs)
    }

    private fun checkTimestamps(posMs: Long) {
        val currentStamp = activeTimeStamps.firstOrNull { it.timestamp.startMs <= posMs && posMs <= it.timestamp.endMs }
        if (currentStamp != currentActiveStamp) {
            currentActiveStamp = currentStamp
            if (currentStamp != null) {
                event(TimestampInvokedEvent(currentStamp))
            }
        }
    }

    override fun seekTime(time: Long, source: PlayerEventSource) {
        delegate?.seekTime(time, source) ?: run {
            val currentPos = positionMs ?: 0L
            val dur = durationMs ?: 0L
            val target = (currentPos + time).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
            seekTo(target, source)
        }
    }

    override fun seekTo(time: Long, source: PlayerEventSource) {
        delegate?.seekTo(time, source) ?: run {
            val from = positionMs ?: 0L
            positionMs = time
            event(PositionEvent(fromMs = from, toMs = time, durationMs = durationMs ?: 0L, source = source))
            checkTimestamps(time)
        }
    }

    override fun addTimeStamps(timeStamps: List<VideoSkipStamp>) {
        delegate?.addTimeStamps(timeStamps)
        activeTimeStamps = timeStamps
        currentActiveStamp = null
    }

    override fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
        try {
            delegate?.handleEvent(event, source)
            when (event) {
                CSPlayerEvent.Pause -> {
                    isPlaying = false
                    event(PauseEvent(source = source))
                    event(StatusEvent(wasPlaying = CSPlayerLoading.IsPlaying, isPlaying = CSPlayerLoading.IsPaused, source = source))
                }
                CSPlayerEvent.Play -> {
                    isPlaying = true
                    event(PlayEvent(source = source))
                    event(StatusEvent(wasPlaying = CSPlayerLoading.IsPaused, isPlaying = CSPlayerLoading.IsPlaying, source = source))
                }
                CSPlayerEvent.PlayPauseToggle -> {
                    if (isPlaying) {
                        handleEvent(CSPlayerEvent.Pause, source)
                    } else {
                        handleEvent(CSPlayerEvent.Play, source)
                    }
                }
                CSPlayerEvent.SeekForward -> {
                    seekTime(seekActionTime, source)
                }
                CSPlayerEvent.SeekBack -> {
                    seekTime(-seekActionTime, source)
                }
                CSPlayerEvent.NextEpisode -> {
                    event(EpisodeSeekEvent(1, source = source))
                }
                CSPlayerEvent.PrevEpisode -> {
                    event(EpisodeSeekEvent(-1, source = source))
                }
                CSPlayerEvent.SkipCurrentChapter -> {
                    currentActiveStamp?.let { stamp ->
                        seekTo(stamp.timestamp.endMs + 1L, source)
                        event(TimestampSkippedEvent(stamp, source = source))
                    }
                }
                CSPlayerEvent.Restart -> {
                    seekTo(0L, source)
                }
                CSPlayerEvent.ToggleMute -> {
                    lastMuteVolume = if (lastMuteVolume > 0f) 0f else 1.0f
                }
                CSPlayerEvent.PlayAsAudio -> {
                    isAudioOnlyBackground = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "handleEvent error", t)
            event(ErrorEvent(t, source = source))
        }
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
        preview: Boolean
    ) {
        currentLink = link
        currentDownloadedFile = data
        currentSubtitles = subtitle
        activeSubtitles = subtitles
        startPosition?.let { positionMs = it }

        if (link?.type == ExtractorLinkType.TORRENT || link?.type == ExtractorLinkType.MAGNET) {
            loadTorrent(context, link)
            return
        }

        delegate?.loadPlayer(
            context, sameEpisode, link, data, startPosition, subtitles, subtitle, autoPlay, preview
        ) ?: run {
            link?.let { loadOnlinePlayer(context, it) }
        }
    }

    override fun reloadPlayer(context: Context) {
        delegate?.reloadPlayer(context) ?: run {
            currentLink?.let { loadOnlinePlayer(context, it, retry = true) }
        }
    }

    override fun getPreview(fraction: Float): Bitmap? = delegate?.getPreview(fraction) ?: imageGenerator.getPreviewImage(fraction)
    override fun hasPreview(): Boolean = delegate?.hasPreview() ?: imageGenerator.hasPreview()

    override fun onStop() {
        delegate?.onStop()
        isPlaying = false
        event(PauseEvent())
    }

    override fun onPause() {
        delegate?.onPause()
        isPlaying = false
        event(PauseEvent())
    }

    override fun onResume(context: Context) {
        delegate?.onResume(context)
        isPlaying = true
        event(PlayEvent())
    }

    override fun release() {
        eventLooperIndex++
        releasePlayer()
        LiveHelper.unregisterPlayer(this)
        delegate?.release()
    }

    fun releasePlayer() {
        eventLooperIndex++
        isPlaying = false
        currentLink = null
        currentDownloadedFile = null
    }

    override fun isActive(): Boolean = delegate?.isActive() ?: isPlayerActive

    override fun getAspectRatio(): Rational? = delegate?.getAspectRatio() ?: currentAspectRatio

    override fun setMaxVideoSize(width: Int, height: Int, id: String?) {
        delegate?.setMaxVideoSize(width, height, id)
        maxVideoWidth = width
        maxVideoHeight = height
        maxVideoId = id
    }

    override fun saveData() {
        delegate?.saveData()
    }

    companion object {
        private const val CRONET_TIMEOUT_MS = 15_000

        @Volatile
        private var activePlayers = 0

        fun releaseCronetEngine() {
            // Chromium Cronet is not utilized on Linux desktop; network transport delegates to OkHttp / Conscrypt.
        }

        var preferredAudioTrackLanguage: String? = null
            get() {
                return field ?: getKey<String>(
                    "$currentAccount/$PREFERRED_AUDIO_LANGUAGE_KEY",
                    field
                )?.also {
                    field = it
                }
            }
            set(value) {
                setKey("$currentAccount/$PREFERRED_AUDIO_LANGUAGE_KEY", value)
                field = value
            }
    }
}
