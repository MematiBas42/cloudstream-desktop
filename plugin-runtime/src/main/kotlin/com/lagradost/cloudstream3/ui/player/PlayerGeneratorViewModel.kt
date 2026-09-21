// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/PlayerGeneratorViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.ui.player.source_priority.ProfileSettings
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getLinkPriority
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.videoskip.SkipAPI
import com.lagradost.cloudstream3.utils.videoskip.VideoSkipStamp
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

typealias VideoLink = Pair<ExtractorLink?, ExtractorUri?>

data class GeneratorState(
    val meta: Any?,
    val nextMeta: Any?,
    val allMeta: List<*>?,
    val response: LoadResponse?,
    val index: Int,
    val id: Int?,
)

data class DisplayLink(
    val link: VideoLink,
    val shouldUseLink: Boolean,
    val priority: Int
)

data class VideoState(
    val subtitles: PersistentSet<SubtitleData> = persistentSetOf(),
    val links: PersistentSet<VideoLink> = persistentSetOf(),
    val erroredLinks: PersistentSet<VideoLink> = persistentSetOf(),
    val stamps: PersistentList<VideoSkipStamp> = persistentListOf(),
    val loading: Resource<Unit> = Resource.Loading(),
    val generatorState: GeneratorState? = null,
    val instance: Int,
) {
    private val sortedLinks: ConcurrentHashMap<Int, List<DisplayLink>> = ConcurrentHashMap()

    fun clearSortedLinksCache() = sortedLinks.clear()

    private fun hasLinkErrored(link: VideoLink): Boolean {
        return erroredLinks.any { it == link }
    }

    private fun VideoLink.toDisplayLink(
        qualityProfile: Int,
        hideNegativeSources: Boolean,
        hideErrorSources: Boolean
    ): DisplayLink {
        val priority = getLinkPriority(qualityProfile, this.first)
        val shouldHideLink =
            (hideNegativeSources && priority < 0) || (hideErrorSources && hasLinkErrored(this))
        return DisplayLink(this, !shouldHideLink, priority)
    }

    fun sortLinks(qualityProfile: Int): List<DisplayLink> {
        sortedLinks[qualityProfile]?.let {
            return it
        }

        val hideNegativeSources =
            QualityDataHelper.getProfileSetting(qualityProfile, ProfileSettings.HideNegativeSources)
        val hideErrorSources =
            QualityDataHelper.getProfileSetting(qualityProfile, ProfileSettings.HideErrorSources)

        return links.map { link ->
            link.toDisplayLink(qualityProfile, hideNegativeSources, hideErrorSources)
        }.sortedBy {
            -it.priority
        }.also { value -> sortedLinks[qualityProfile] = value }
    }

    fun add(item: SubtitleData): VideoState = copy(subtitles = subtitles.add(item))
    fun add(item: VideoLink): VideoState = copy(links = links.add(item))
    fun add(item: VideoSkipStamp): VideoState = copy(stamps = stamps.add(item))

    @JvmName("addSubtitleData")
    fun add(items: Collection<SubtitleData>): VideoState = copy(subtitles = subtitles.addAll(items))

    @JvmName("addVideoLink")
    fun add(items: Collection<VideoLink>): VideoState = copy(links = links.addAll(items))

    @JvmName("addVideoSkipStamp")
    fun add(items: Collection<VideoSkipStamp>): VideoState = copy(stamps = stamps.addAll(items))

    fun set(item: SubtitleData): VideoState = copy(subtitles = persistentSetOf(item))
    fun set(item: VideoLink): VideoState = copy(links = persistentSetOf(item))
    fun set(item: VideoSkipStamp): VideoState = copy(stamps = persistentListOf(item))

    @JvmName("setSubtitleData")
    fun set(items: Collection<SubtitleData>): VideoState = copy(subtitles = items.toPersistentSet())

    @JvmName("setVideoLink")
    fun set(items: Collection<VideoLink>): VideoState = copy(links = items.toPersistentSet())

    @JvmName("setVideoSkipStamp")
    fun set(items: Collection<VideoSkipStamp>): VideoState = copy(stamps = items.toPersistentList())

    fun addError(item: VideoLink): VideoState = copy(erroredLinks = erroredLinks.add(item))
    fun setError(items: Collection<VideoLink>): VideoState = copy(erroredLinks = items.toPersistentSet())
}

data class VideoLive<T>(
    val value: T,
    val instance: Int,
)

class PlayerGeneratorViewModel : DesktopViewModel() {
    companion object {
        const val TAG = "PlayViewGen"
    }

    @Volatile
    var generator: VideoGenerator<*>? = null

    @Volatile
    var episodeIndex: Int = 0

    @Volatile
    var state = VideoState(instance = 0)
        private set

    private val _currentLinks = MutableStateFlow<VideoLive<Set<VideoLink>>?>(null)
    val currentLinks: StateFlow<VideoLive<Set<VideoLink>>?> = _currentLinks.asStateFlow()

    private val _currentSubtitles = MutableStateFlow<VideoLive<Set<SubtitleData>>?>(null)
    val currentSubtitles: StateFlow<VideoLive<Set<SubtitleData>>?> = _currentSubtitles.asStateFlow()

    private val _loadingLinks = MutableStateFlow<VideoLive<Resource<Unit>>>(VideoLive(Resource.Loading(), 0))
    val loadingLinks: StateFlow<VideoLive<Resource<Unit>>> = _loadingLinks.asStateFlow()

    private val _currentStamps = MutableStateFlow<VideoLive<List<VideoSkipStamp>>?>(null)
    val currentStamps: StateFlow<VideoLive<List<VideoSkipStamp>>?> = _currentStamps.asStateFlow()

    @Synchronized
    fun modifyState(op: VideoState.() -> VideoState) {
        val oldState = state
        state = op.invoke(oldState)

        if (state.instance != oldState.instance) {
            _currentSubtitles.value = VideoLive(state.subtitles, state.instance)
            _currentStamps.value = VideoLive(state.stamps, state.instance)
            _currentLinks.value = VideoLive(state.links, state.instance)
            _loadingLinks.value = VideoLive(state.loading, state.instance)
            return
        }

        if (state.links !== oldState.links)
            _currentLinks.value = VideoLive(state.links, state.instance)
        if (state.stamps !== oldState.stamps)
            _currentStamps.value = VideoLive(state.stamps, state.instance)
        if (state.subtitles !== oldState.subtitles)
            _currentSubtitles.value = VideoLive(state.subtitles, state.instance)

        if (state.loading != oldState.loading)
            _loadingLinks.value = VideoLive(state.loading, state.instance)
    }

    private val _currentSubtitleYear = MutableStateFlow<Int?>(null)
    val currentSubtitleYear: StateFlow<Int?> = _currentSubtitleYear.asStateFlow()

    private var currentLoadingEpisodeId: Int? = null
    var forceClearCache = false

    fun setSubtitleYear(year: Int?) {
        _currentSubtitleYear.value = year
    }

    fun loadLinksPrev() {
        Log.i(TAG, "loadLinksPrev")
        if (generator?.hasPrev(episodeIndex) == true) {
            episodeIndex -= 1
            loadLinks()
        }
    }

    fun loadLinksNext() {
        Log.i(TAG, "loadLinksNext")
        if (generator?.hasNext(episodeIndex) == true) {
            episodeIndex += 1
            loadLinks()
        }
    }

    fun hasNextEpisode(): Boolean? {
        return generator?.hasNext(episodeIndex)
    }

    fun hasPrevEpisode(): Boolean? {
        return generator?.hasPrev(episodeIndex)
    }

    fun preLoadNextLinks() {
        val id = generator?.getId(episodeIndex)
        if (id == currentLoadingEpisodeId) return

        Log.i(TAG, "preLoadNextLinks")
        currentJob?.cancel()
        currentLoadingEpisodeId = id

        currentJob = viewModelScope.launchSafe {
            try {
                if (generator?.hasCache == true && generator?.hasNext(episodeIndex) == true) {
                    safeApiCall {
                        generator?.generateLinks(
                            sourceTypes = LOADTYPE_INAPP,
                            clearCache = false,
                            isCasting = false,
                            callback = {},
                            subtitleCallback = {},
                            offset = episodeIndex + 1
                        )
                    }
                }
            } catch (t: Throwable) {
                logError(t)
            } finally {
                if (currentLoadingEpisodeId == id) {
                    currentLoadingEpisodeId = null
                }
            }
        }
    }

    fun loadThisEpisode(index: Int) {
        episodeIndex = index
        loadLinks()
    }

    fun attachGenerator(newGenerator: VideoGenerator<*>, index: Int) {
        Log.i(TAG, "attachGenerator with generator=$newGenerator and index=$index")
        generator = newGenerator
        episodeIndex = index
    }

    fun addSubtitles(file: Set<SubtitleData>) {
        val validFile = file.filter(::isValidSubtitle)
        if (validFile.isNotEmpty())
            modifyState {
                add(validFile)
            }
    }

    private var currentJob: Job? = null
    private var currentStampJob: Job? = null

    fun loadStamps(duration: Long) {
        currentStampJob = ioSafe {
            val genState = state.generatorState ?: return@ioSafe
            val meta = genState.meta
            val page = genState.response
            val id = genState.id
            if (page == null || meta !is ResultEpisode) {
                return@ioSafe
            }
            val stamps = SkipAPI.videoStamps(
                page,
                meta,
                duration,
                hasNextEpisode() ?: false
            )

            modifyState {
                if (id != this.generatorState?.id) {
                    this
                } else {
                    set(stamps)
                }
            }
        }
    }

    var langFilterList = listOf<String>()
    var filterSubByLang = false

    fun isValidSubtitle(subtitle: SubtitleData): Boolean {
        if (langFilterList.isEmpty() || !filterSubByLang) {
            return true
        }

        if (subtitle.origin != SubtitleOrigin.URL) {
            return true
        }

        return langFilterList.any { lang ->
            subtitle.originalName.contains(lang, ignoreCase = true)
        }
    }

    fun loadLinks(sourceTypes: Set<ExtractorLinkType> = LOADTYPE_INAPP) {
        Log.i(TAG, "loadLinks with generator=$generator and index=$episodeIndex")
        currentJob?.cancel()
        val index = episodeIndex

        modifyState {
            VideoState(
                loading = Resource.Loading(),
                generatorState = generator?.let { gen ->
                    GeneratorState(
                        meta = gen.videos.getOrNull(index),
                        nextMeta = gen.videos.getOrNull(index + 1),
                        id = gen.getId(index),
                        response = (gen as? RepoLinkGenerator)?.page,
                        index = index,
                        allMeta = gen.videos
                    )
                },
                instance = instance + 1
            )
        }

        currentJob = viewModelScope.launchSafe {
            val loadingState = safeApiCall {
                generator?.generateLinks(
                    sourceTypes = sourceTypes,
                    clearCache = forceClearCache,
                    callback = { link ->
                        if (isActive)
                            modifyState {
                                add(link)
                            }
                    },
                    isCasting = false,
                    offset = index,
                    subtitleCallback = { link ->
                        if (isActive && isValidSubtitle(link))
                            modifyState {
                                add(link)
                            }
                    })
                Unit
            }

            if (!isActive) {
                return@launchSafe
            }

            modifyState {
                if (!isActive) {
                    this
                } else {
                    when (loading) {
                        is Resource.Loading -> copy(loading = loadingState)
                        else -> this
                    }
                }
            }
        }
    }
}
