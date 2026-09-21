// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.MainThread
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.isAnimeOp
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.subtitleProviders
import com.lagradost.cloudstream3.ui.player.PlayerSubtitleHelper.Companion.toSubtitleMimeType
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getLinkPriority
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.getAutoSelectLanguageTagIETF
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

const val SKIP_OP_VIDEO_PERCENTAGE = 50
const val PRELOAD_NEXT_EPISODE_PERCENTAGE = 80
const val UPDATE_SYNC_PROGRESS_PERCENTAGE = 80

class GeneratorPlayer {
    companion object {
        const val TAG = "GeneratorPlayer"
        const val NOTIFICATION_ID = 2326
        const val CHANNEL_ID = 7340
        const val STOP_ACTION = "stopcs3"

        const val SKIP_OP_VIDEO_PERCENTAGE = com.lagradost.cloudstream3.ui.player.SKIP_OP_VIDEO_PERCENTAGE
        const val PRELOAD_NEXT_EPISODE_PERCENTAGE = com.lagradost.cloudstream3.ui.player.PRELOAD_NEXT_EPISODE_PERCENTAGE
        const val NEXT_WATCH_EPISODE_PERCENTAGE = com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
        const val UPDATE_SYNC_PROGRESS_PERCENTAGE = com.lagradost.cloudstream3.ui.player.UPDATE_SYNC_PROGRESS_PERCENTAGE

        private val generators = ConcurrentHashMap<String, VideoGenerator<*>>()

        fun newInstance(
            generator: VideoGenerator<*>,
            index: Int,
            syncData: HashMap<String, String>? = null
        ): Bundle {
            Log.i(TAG, "newInstance = $syncData")
            val uuid = UUID.randomUUID().toString()
            generators[uuid] = generator
            return Bundle().apply {
                putString("uuid", uuid)
                putInt("index", index)
                if (syncData != null) putSerializable("syncData", syncData)
            }
        }

        fun getGenerator(uuid: String): VideoGenerator<*>? = generators[uuid]

        val subsProviders = subtitleProviders
        val subsProvidersIsActive: Boolean get() = subsProviders.isNotEmpty()
    }

    val viewModel = PlayerGeneratorViewModel()
    var player: IPlayer? = null
    var context: Context = Context()

    var currentSelectedLink: VideoLink? = null
    var currentSelectedSubtitles: SubtitleData? = null
    var currentQualityProfile: Int = 1

    val currentMeta: Any? get() = viewModel.state.generatorState?.meta
    val nextMeta: Any? get() = viewModel.state.generatorState?.nextMeta

    val allMeta: List<ResultEpisode>?
        get() = viewModel.state.generatorState?.allMeta?.filterIsInstance<ResultEpisode>()
            ?.map { episode ->
                getViewPos(episode.id)?.let { data ->
                    episode.copy(position = data.position, duration = data.duration)
                } ?: episode
            }

    val isPlayerActive = AtomicBoolean(false)
    var isNextEpisode: Boolean = false

    var maxEpisodeSet: Int? = null
    var hasRequestedStamps: Boolean = false

    var preferredAutoSelectSubtitles: String? = getAutoSelectLanguageTagIETF()

    private val _isOpVisible = MutableStateFlow(false)
    val isOpVisible: StateFlow<Boolean> = _isOpVisible.asStateFlow()

    private val _currentActiveStamp = MutableStateFlow<VideoSkipStamp?>(null)
    val currentActiveStamp: StateFlow<VideoSkipStamp?> = _currentActiveStamp.asStateFlow()

    var onExitPlayer: (() -> Unit)? = null
    var onSyncProgress: ((episodeIndex: Int) -> Unit)? = null
    var onNoLinksFound: (() -> Unit)? = null

    fun attachPlayer(targetPlayer: IPlayer, ctx: Context = Context()) {
        this.player = targetPlayer
        this.context = ctx

        targetPlayer.initCallbacks(
            eventHandler = ::onPlayerEvent,
            requestedListeningPercentages = listOf(
                SKIP_OP_VIDEO_PERCENTAGE,
                PRELOAD_NEXT_EPISODE_PERCENTAGE,
                NEXT_WATCH_EPISODE_PERCENTAGE,
                UPDATE_SYNC_PROGRESS_PERCENTAGE,
            )
        )

        // Observe loading links state - matches upstream observe(viewModel.loadingLinks)
        viewModel.viewModelScope.launch {
            viewModel.loadingLinks.collect { live ->
                if (live == null) return@collect
                if (live.instance != viewModel.state.instance) return@collect

                when (val loading = live.value) {
                    is Resource.Loading -> {
                        // Keep or reset player if needed
                    }
                    is Resource.Success -> {
                        startPlayer()
                    }
                    is Resource.Failure -> {
                        showToast(loading.errorString, Toast.LENGTH_LONG)
                        startPlayer()
                    }
                }
            }
        }

        // Observe current links - matches upstream observe(viewModel.currentLinks)
        viewModel.viewModelScope.launch {
            viewModel.currentLinks.collect { live ->
                if (live == null) return@collect
                if (live.instance != viewModel.state.instance) return@collect

                safe {
                    if (!isPlayerActive.get() && (viewModel.state.links.any { link ->
                            getLinkPriority(currentQualityProfile, link.first) >=
                                    QualityDataHelper.AUTO_SKIP_PRIORITY
                        } || !viewModel.state.links.isNullOrEmpty())
                    ) {
                        startPlayer()
                    }
                }
            }
        }

        // Observe current subtitles - matches upstream observe(viewModel.currentSubtitles)
        viewModel.viewModelScope.launch {
            viewModel.currentSubtitles.collect { live ->
                if (live == null) return@collect
                if (live.instance != viewModel.state.instance) return@collect
                val subs = live.value
                if (!subs.isNullOrEmpty()) {
                    targetPlayer.setActiveSubtitles(subs)

                    // If the file is downloaded then do not select auto select the subtitles
                    // Downloaded subtitles cannot be selected immediately after loading since
                    // player.getCurrentPreferredSubtitle() cannot fetch data from non-loaded subtitles
                    // Resulting in unselecting the downloaded subtitle
                    if (subs.lastOrNull()?.origin != SubtitleOrigin.DOWNLOADED_FILE) {
                        autoSelectSubtitles()
                    }
                }
            }
        }

        // Observe skip timestamps - matches upstream observe(viewModel.currentStamps)
        viewModel.viewModelScope.launch {
            viewModel.currentStamps.collect { live ->
                if (live == null) return@collect
                if (live.instance != viewModel.state.instance) return@collect
                val stamps = live.value
                if (stamps != null) {
                    targetPlayer.addTimeStamps(stamps)
                }
            }
        }
    }

    fun initFromBundle(bundle: Bundle, targetPlayer: IPlayer? = null) {
        preferredAutoSelectSubtitles = getAutoSelectLanguageTagIETF()
        val uuid = bundle.getString("uuid")
        val index = bundle.getInt("index", 0)
        val generator = uuid?.let { getGenerator(it) }
        if (generator != null) {
            viewModel.attachGenerator(generator, index)
            viewModel.loadLinks()
        }
        if (targetPlayer != null) {
            attachPlayer(targetPlayer)
        }
    }

    fun getPos(): Long {
        val durPos = getViewPos(viewModel.state.generatorState?.id) ?: return 0L
        if (durPos.duration == 0L) return 0L
        // 95% Rule: Reset position to 0L when completed/near-end
        if (durPos.position * 100L / durPos.duration > 95L) {
            return 0L
        }
        return durPos.position
    }

    var currentVerifyLink: Job? = null

    fun loadExtractorJob(extractorLink: ExtractorLink?) {
        currentVerifyLink?.cancel()

        extractorLink?.let { link ->
            currentVerifyLink = ioSafe {
                if (link.extractorData != null) {
                    getApiFromNameNull(link.source)?.extractorVerifierJob(link.extractorData)
                }
            }
        }
    }

    fun playerPositionChanged(position: Long, duration: Long) {
        if ((currentMeta as? ResultEpisode)?.tvType?.isLiveStream() == true) return
        if ((currentMeta as? ResultEpisode)?.tvType == TvType.NSFW) return
        if (duration <= 0L) return

        if (!hasRequestedStamps) {
            hasRequestedStamps = true
            viewModel.loadStamps(duration)
        }

        val percentage = position * 100L / duration

        // Save position and advance watch progress (includes 90% Rule internally)
        DataStoreHelper.setViewPosAndResume(
            viewModel.state.generatorState?.id,
            position,
            duration,
            currentMeta,
            nextMeta
        )

        var opVisible = false
        when (val meta = currentMeta) {
            is ResultEpisode -> {
                // 80% Rule (Scrobbler)
                if (percentage >= UPDATE_SYNC_PROGRESS_PERCENTAGE && (maxEpisodeSet ?: -1) < meta.episode) {
                    maxEpisodeSet = meta.episode
                    onSyncProgress?.invoke(meta.totalEpisodeIndex ?: meta.episode)
                }

                // 50% Rule (Anime OP skip button visibility)
                if (meta.tvType.isAnimeOp()) {
                    opVisible = percentage < SKIP_OP_VIDEO_PERCENTAGE
                }
            }
        }
        _isOpVisible.value = opVisible

        // 80% Rule (Preload Next Episode)
        if (percentage >= PRELOAD_NEXT_EPISODE_PERCENTAGE) {
            viewModel.preLoadNextLinks()
        }

        // Active Chapter / Stamp verification
        val stamp = viewModel.state.stamps.firstOrNull {
            position in it.timestamp.startMs..it.timestamp.endMs
        }
        if (stamp != _currentActiveStamp.value) {
            _currentActiveStamp.value = stamp
            if (stamp != null) {
                player?.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Player)
            }
        }
    }

    fun skipCurrentChapter() {
        val active = _currentActiveStamp.value ?: return
        if (active.skipToNextEpisode) {
            loadNextEpisode()
        } else {
            player?.seekTo(active.timestamp.endMs + 1L)
        }
    }

    fun loadNextEpisode() {
        if (viewModel.hasNextEpisode() == true) {
            isNextEpisode = true
            hasRequestedStamps = false
            _currentActiveStamp.value = null
            _isOpVisible.value = false
            viewModel.loadLinksNext()
        }
    }

    fun loadPrevEpisode() {
        if (viewModel.hasPrevEpisode() == true) {
            isNextEpisode = true
            hasRequestedStamps = false
            _currentActiveStamp.value = null
            _isOpVisible.value = false
            viewModel.loadLinksPrev()
        }
    }

    fun nextEpisode() {
        loadNextEpisode()
    }

    fun prevEpisode() {
        loadPrevEpisode()
    }

    fun startPlayer() {
        if (isPlayerActive.get()) {
            return
        }

        val links = viewModel.state.sortLinks(currentQualityProfile)
        val firstAvailableLink = links.firstOrNull { it.shouldUseLink }?.link
        if (firstAvailableLink == null) {
            noLinksFound()
            return
        }

        if (!isPlayerActive.compareAndSet(false, true)) {
            return
        }

        loadLink(firstAvailableLink, false)
    }

    fun loadLink(link: VideoLink?, sameEpisode: Boolean) {
        if (link == null) return
        isPlayerActive.set(true)
        currentSelectedLink = link

        if (!sameEpisode) {
            hasRequestedStamps = false
            _currentActiveStamp.value = null
            _isOpVisible.value = false
        }

        loadExtractorJob(link.first)

        val (url, uri) = link
        val subtitles = viewModel.state.subtitles

        val startPos = if (sameEpisode) {
            null
        } else {
            if (isNextEpisode) {
                isNextEpisode = false
                0L
            } else {
                getPos()
            }
        }

        val selectedSub = (if (sameEpisode) currentSelectedSubtitles else null)
            ?: getAutoSelectSubtitle(subtitles, settings = true, downloads = true)

        player?.loadPlayer(
            context = context,
            sameEpisode = sameEpisode,
            link = url,
            data = uri,
            startPosition = startPos,
            subtitles = subtitles,
            subtitle = selectedSub,
            preview = true
        )

        currentSelectedSubtitles = selectedSub

        if (!sameEpisode) {
            player?.addTimeStamps(emptyList())
            player?.setSubtitleOffset(0L)
        }
    }

    fun getNextLink(): DisplayLink? {
        val links = viewModel.state.sortLinks(currentQualityProfile)
        val currentIndex = links.indexOfFirst { it.link == currentSelectedLink }
        val nextPotentialLink =
            links.withIndex().firstOrNull { it.index > currentIndex && it.value.shouldUseLink }
        return nextPotentialLink?.value
    }

    fun hasNextMirror(): Boolean = getNextLink() != null

    fun nextMirror() {
        val nextLink = getNextLink()
        if (nextLink == null) {
            noLinksFound()
            return
        }
        loadLink(nextLink.link, true)
    }

    fun playWithMirrors(links: List<ExtractorLink>) {
        viewModel.modifyState {
            var s = this
            for (l in links) {
                s = s.add(Pair(l, null))
            }
            s
        }
        startPlayer()
    }

    fun playerError(exception: Throwable) {
        currentSelectedLink?.let { link ->
            viewModel.modifyState { addError(link) }
        }

        val currentUrl =
            currentSelectedLink?.let { it.first?.url ?: it.second?.uri?.toString() } ?: "unknown"
        val headers = currentSelectedLink?.first?.headers?.toString() ?: "none"
        val referer = currentSelectedLink?.first?.referer ?: "none"
        Log.e(
            TAG,
            "playerError: $currentSelectedLink, " +
                    "type=${exception::class.qualifiedName}, " +
                    "message=${exception.message}, url=$currentUrl, headers=$headers, " +
                    "referer=$referer, position=${player?.getPosition() ?: "unknown"}, " +
                    "duration=${player?.getDuration() ?: "unknown"}, " +
                    "isPlaying=${player?.getIsPlaying()}", exception
        )

        if (!hasNextMirror()) {
            viewModel.forceClearCache = true
        }

        if (hasNextMirror()) {
            val msg = "${context.getString(R.string.source_error)}\n${exception.message ?: ""}".trim()
            showToast(msg, Toast.LENGTH_SHORT)
            nextMirror()
        } else {
            val msg = "${context.getString(R.string.no_links_found_toast)}\n${exception.message ?: ""}".trim()
            showToast(msg, Toast.LENGTH_LONG)
            noLinksFound()
        }
    }

    fun noLinksFound() {
        viewModel.forceClearCache = true
        val hiddenLinks = viewModel.state.sortLinks(currentQualityProfile).count { !it.shouldUseLink }

        // Display that there are hidden links to the user matching upstream 1:1
        if (hiddenLinks > 0) {
            val noLinksString = context.getString(R.string.no_links_found_toast)
            val hiddenString = "$hiddenLinks links hidden"
            val toastText = "$noLinksString\n($hiddenString)"
            showToast(toastText, Toast.LENGTH_SHORT)
        } else {
            showToast(R.string.no_links_found_toast, Toast.LENGTH_SHORT)
        }

        onNoLinksFound?.invoke()
    }

    fun setSubtitles(subtitle: SubtitleData?, userInitiated: Boolean): Boolean {
        if (subtitle != currentSelectedSubtitles && userInitiated) {
            val tag = if (subtitle == null) "" else subtitle.getIETF_tag()
            if (tag != null) {
                setKey(SUBTITLE_AUTO_SELECT_KEY, tag)
                preferredAutoSelectSubtitles = tag
            }
        }
        currentSelectedSubtitles = subtitle
        return player?.setPreferredSubtitles(subtitle) ?: false
    }

    fun noSubtitles(): Boolean {
        return setSubtitles(null, true)
    }

    fun getAutoSelectSubtitle(
        subtitles: Set<SubtitleData>,
        settings: Boolean,
        downloads: Boolean
    ): SubtitleData? {
        val langCode = preferredAutoSelectSubtitles ?: return null
        if (downloads) {
            sortSubs(subtitles).firstOrNull {
                it.origin == SubtitleOrigin.DOWNLOADED_FILE && it.matchesLanguageCode(langCode)
            }?.let { return it }
        }
        if (!settings) return null
        return sortSubs(subtitles).firstOrNull { it.matchesLanguageCode(langCode) }
    }

    private fun autoSelectFromSettings(): Boolean {
        val langCode = preferredAutoSelectSubtitles
        val current = player?.getCurrentPreferredSubtitle()
        Log.i(TAG, "autoSelectFromSettings = $current")
        val ctx = context
        if (current != null && (langCode == null || current.matchesLanguageCode(langCode))) {
            if (setSubtitles(current, false)) {
                player?.saveData()
                player?.reloadPlayer(ctx)
                player?.handleEvent(CSPlayerEvent.Play)
                return true
            }
        } else if (!langCode.isNullOrEmpty()) {
            getAutoSelectSubtitle(
                viewModel.state.subtitles, settings = true, downloads = false
            )?.let { sub ->
                if (setSubtitles(sub, false)) {
                    player?.saveData()
                    player?.reloadPlayer(ctx)
                    player?.handleEvent(CSPlayerEvent.Play)
                    return true
                }
            }
        }
        return false
    }

    private fun autoSelectFromDownloads() {
        if (player?.getCurrentPreferredSubtitle() != null) {
            return
        }
        val sub =
            getAutoSelectSubtitle(viewModel.state.subtitles, settings = false, downloads = true)
                ?: return
        val ctx = context
        if (!setSubtitles(sub, false)) {
            return
        }
        player?.saveData()
        player?.reloadPlayer(ctx)
        player?.handleEvent(CSPlayerEvent.Play)
    }

    fun autoSelectSubtitles() {
        safe {
            if (!autoSelectFromSettings()) {
                autoSelectFromDownloads()
            }
        }
    }

    /**
     * Attaches subtitle files or URLs directly to the player and view model,
     * matching upstream GeneratorPlayer.addAndSelectSubtitles 1:1.
     */
    @MainThread
    fun addAndSelectSubtitles(vararg subtitleData: SubtitleData) {
        if (subtitleData.isEmpty()) return
        val ctx = context
        val selectedSubtitle = subtitleData.first()
        viewModel.addSubtitles(subtitleData.toSet())

        player?.setActiveSubtitles(viewModel.state.subtitles)
        player?.saveData()
        player?.reloadPlayer(ctx)

        setSubtitles(selectedSubtitle, false)

        showToast(
            String.format(
                ctx.getString(R.string.player_loaded_subtitles),
                selectedSubtitle.name
            ),
            Toast.LENGTH_LONG
        )
    }

    fun loadSubtitleFile(name: String, url: String) {
        val subtitleData = SubtitleData(
            originalName = name,
            nameSuffix = "",
            url = url,
            origin = SubtitleOrigin.DOWNLOADED_FILE,
            mimeType = name.toSubtitleMimeType(),
            headers = emptyMap(),
            languageCode = null
        )
        addAndSelectSubtitles(subtitleData)
    }

    fun loadSubtitleUrl(
        url: String,
        name: String? = null,
        languageCode: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        val subName = name ?: url.substringAfterLast('/').substringBefore('?')
        val subtitleData = SubtitleData(
            originalName = subName,
            nameSuffix = "",
            url = url,
            origin = SubtitleOrigin.URL,
            mimeType = url.substringBefore('?').toSubtitleMimeType(),
            headers = headers,
            languageCode = languageCode
        )
        addAndSelectSubtitles(subtitleData)
    }

    fun getName(entry: AbstractSubtitleEntities.SubtitleEntity, withLanguage: Boolean): String {
        if (entry.lang.isBlank() || !withLanguage) {
            return entry.name
        }
        val language = fromTagToLanguageName(entry.lang.trim()) ?: entry.lang
        return "$language ${entry.name}"
    }

    fun addFirstSub(query: SubtitleSearch) =
        viewModel.viewModelScope.launch {
            var hasSelectASubtitle = false

            subsProviders.toList().amap { provider ->
                val success = when (val result = Resource.fromResult(
                    provider.search(
                        query = query
                    )
                )) {
                    is Resource.Failure -> {
                        if (this.isActive) {
                            showToast("${provider.idPrefix}${result.errorString}")
                        }
                        return@amap
                    }
                    is Resource.Loading -> return@amap
                    is Resource.Success -> result.value
                }

                for (subtitleEntry in success) {
                    if (hasSelectASubtitle || !this.isActive) {
                        break
                    }

                    val subtitleResources = provider.resource(subtitleEntry).getOrNull() ?: continue

                    val subtitles = subtitleResources.getSubtitles().map { resource ->
                        SubtitleData(
                            originalName = resource.name ?: getName(subtitleEntry, true),
                            nameSuffix = "",
                            url = resource.url,
                            origin = resource.origin,
                            mimeType = resource.url.toSubtitleMimeType(),
                            headers = subtitleEntry.headers,
                            languageCode = subtitleEntry.lang,
                        )
                    }

                    if (this.isActive && !viewModel.state.subtitles.containsAll(subtitles) && !hasSelectASubtitle) {
                        hasSelectASubtitle = true
                        runOnMainThread {
                            addAndSelectSubtitles(*subtitles.toTypedArray())
                        }
                        break
                    }
                }
            }
            if (!hasSelectASubtitle && this.isActive) {
                showToast(R.string.no_subtitles)
            }
        }

    fun embeddedSubtitlesFetched(subtitles: List<SubtitleData>) {
        viewModel.addSubtitles(subtitles.toSet())
    }

    fun onPlayerEvent(event: PlayerEvent) {
        when (event) {
            is PositionEvent -> playerPositionChanged(event.toMs, event.durationMs)
            is StatusEvent -> {
                if (event.isPlaying == CSPlayerLoading.IsPlaying) {
                    viewModel.forceClearCache = false
                }
            }
            is VideoEndedEvent -> {
                if (viewModel.hasNextEpisode() == true) {
                    loadNextEpisode()
                } else {
                    onExitPlayer?.invoke()
                }
            }
            is EpisodeSeekEvent -> {
                if (event.offset > 0) {
                    loadNextEpisode()
                } else if (event.offset < 0) {
                    loadPrevEpisode()
                }
            }
            is ErrorEvent -> playerError(event.error)
            is TimestampSkippedEvent -> {
                player?.seekTo(event.timestamp.timestamp.endMs + 1L)
            }
            is TimestampInvokedEvent -> {
                _currentActiveStamp.value = event.timestamp
            }
            is EmbeddedSubtitlesFetchedEvent -> {
                embeddedSubtitlesFetched(event.tracks)
            }
            else -> Unit
        }
    }

    fun releasePlayer() {
        currentVerifyLink?.cancel()
        currentVerifyLink = null
        player?.release()
        currentSelectedSubtitles = null
        currentSelectedLink = null
        isPlayerActive.set(false)
        viewModel.modifyState { setError(emptyList()) }
        viewModel.onCleared()
    }

    fun release() {
        releasePlayer()
    }

    fun exitPlayer() {
        releasePlayer()
        onExitPlayer?.invoke()
    }
}
