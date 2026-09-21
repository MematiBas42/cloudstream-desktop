// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.syncproviders.providers.Kitsu
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.settings.Globals
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllResumeStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getLastWatched
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream4.AppSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.NetworkInterface
import java.net.URI

object AppContextUtils {
    @PlatformQuarantine(
        reason = "Android RecyclerView is not applicable to Compose Desktop",
        upstreamRef = "AppContextUtils.kt:98",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.isRecyclerScrollable(): Boolean {
        return false
    }

    fun View.isLtr(): Boolean = this.layoutDirection == View.LAYOUT_DIRECTION_LTR
    fun View.isRtl(): Boolean = this.layoutDirection == View.LAYOUT_DIRECTION_RTL

    @PlatformQuarantine(
        reason = "BottomSheetDialog is Android View component not used in Compose Desktop",
        upstreamRef = "AppContextUtils.kt:108",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.ownHide() {
    }

    @PlatformQuarantine(
        reason = "BottomSheetDialog animation override is Android View specific",
        upstreamRef = "AppContextUtils.kt:112",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.ownShow() {
    }

    @PlatformQuarantine(
        reason = "Android TV WatchNextProgram deletion is not applicable to Linux desktop",
        upstreamRef = "AppContextUtils.kt:121",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Context.deleteFavorite(data: SearchResponse) {
    }

    fun String?.html(): CharSequence {
        return getHtmlText(this ?: return "")
    }

    private fun getHtmlText(text: String): CharSequence {
        return try {
            com.fleeksoft.ksoup.Ksoup.parse(text).text()
        } catch (e: Exception) {
            logError(e)
            text
        }
    }

    @PlatformQuarantine(
        reason = "Android TV WatchNextProgram and TvContractCompat are not applicable to Linux desktop",
        upstreamRef = "AppContextUtils.kt:151",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun buildWatchNextProgramUri(
        context: Context,
        card: DataStoreHelper.ResumeWatchingResult,
        resumeWatching: DownloadObjects.ResumeWatching?
    ): Any? {
        val isSeries = card.type?.isMovieType() == false
        val title = if (isSeries) {
            context.getNameFull(card.name, card.episode, card.season)
        } else {
            card.name
        }
        val durationMillis = card.watchPos?.duration?.toInt() ?: 0
        val positionMillis = card.watchPos?.position?.toInt() ?: 0
        val engagementTime = resumeWatching?.updateTime ?: System.currentTimeMillis()
        val customId = "${card.id}|${card.apiName}|${card.url}"
        return title to customId
    }

    @PlatformQuarantine(
        reason = "ViewPager2 drag sensitivity reflection hack is Android-specific",
        upstreamRef = "AppContextUtils.kt:195",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.reduceDragSensitivity(f: Int = 4) {
    }

    @PlatformQuarantine(
        reason = "ContentLoadingProgressBar is Android View widget not applicable to Compose Desktop",
        upstreamRef = "AppContextUtils.kt:206",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.animateProgressTo(to: Int) {
    }

    fun Context.createNotificationChannel(
        channelId: String,
        channelName: String,
        description: String
    ) {
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            this.description = description
        }

        val notificationManager: NotificationManager? =
            this.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        notificationManager?.createNotificationChannel(channel)
    }

    @PlatformQuarantine(
        reason = "Android TV WatchNextProgram query is not applicable to Linux desktop",
        upstreamRef = "AppContextUtils.kt:241",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun getAllWatchNextPrograms(context: Context): Set<Long> {
        return emptySet()
    }

    @PlatformQuarantine(
        reason = "Android TV WatchNextProgram query is not applicable to Linux desktop",
        upstreamRef = "AppContextUtils.kt:267",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun findFirstWatchNextProgram(context: Context, predicate: (Any?) -> Boolean): Pair<Any?, Long?> {
        return null to null
    }

    @PlatformQuarantine(
        reason = "Android TV WatchNextProgram query by videoId is not applicable to Linux desktop",
        upstreamRef = "AppContextUtils.kt:300",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun getWatchNextProgramByVideoId(
        id: String,
        context: Context
    ): Pair<Any?, Long?> {
        return null to null
    }

    private val continueWatchingLock = Mutex()

    @PlatformQuarantine(
        reason = "Android TV continue watching channel is not applicable to Linux desktop; Linux QuickList used instead",
        upstreamRef = "AppContextUtils.kt:316",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    suspend fun Context.addProgramsToContinueWatching(data: List<DataStoreHelper.ResumeWatchingResult>) {
        val context = this
        continueWatchingLock.withLock {
            val timeStampHashMap = HashMap<Int, DownloadObjects.ResumeWatching>()
            getAllResumeStateIds()?.forEach { id ->
                val lastWatched = getLastWatched(id) ?: return@forEach
                timeStampHashMap[lastWatched.parentId] = lastWatched
            }

            data.forEach { episodeInfo ->
                try {
                    val customId = "${episodeInfo.id}|${episodeInfo.apiName}|${episodeInfo.url}"
                    val resumeData = timeStampHashMap[episodeInfo.id]
                    buildWatchNextProgramUri(
                        context = context,
                        card = episodeInfo,
                        resumeWatching = resumeData
                    )
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
    }

    /** Sort subtitles by names */
    fun sortSubs(subs: Set<SubtitleData>): List<SubtitleData> {
        return subs.sortedWith(
            compareBy<SubtitleData> { subtitle -> subtitle.originalName }
                .thenBy { subtitle -> subtitle.nameSuffix }
        )
    }

    fun Context.getApiSettings(): HashSet<String> {
        val hashSet = HashSet<String>()
        val activeLangs = getApiProviderLangSettings()
        val hasUniversal = activeLangs.contains(AllLanguagesName)
        val filtered = apis.filter { api ->
            hasUniversal || activeLangs.contains(api.lang)
        }.map { api ->
            api.name
        }
        hashSet.addAll(filtered)
        return hashSet
    }

    fun Context.getApiDubstatusSettings(): HashSet<DubStatus> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val hashSet = HashSet<DubStatus>()
        hashSet.addAll(DubStatus.entries)
        val list = settingsManager.getStringSet(
            this.getString(R.string.display_sub_key),
            hashSet.map { it.name }.toMutableSet()
        ) ?: return hashSet

        val names = DubStatus.entries.map { it.name }.toHashSet()
        return list.filter { names.contains(it) }.map { DubStatus.valueOf(it) }.toHashSet()
    }

    fun Context.getApiProviderLangSettings(): HashSet<String> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val hashSet = hashSetOf(AllLanguagesName)
        val list = settingsManager.getStringSet(
            this.getString(R.string.provider_lang_key),
            hashSet
        )

        if (list.isNullOrEmpty()) return hashSet
        return list.toHashSet()
    }

    fun Context.getApiTypeSettings(): HashSet<TvType> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val hashSet = HashSet<TvType>()
        hashSet.addAll(TvType.entries)
        val list = settingsManager.getStringSet(
            this.getString(R.string.search_types_list_key),
            hashSet.map { it.name }.toMutableSet()
        )

        if (list.isNullOrEmpty()) return hashSet

        val names = TvType.entries.map { it.name }.toHashSet()
        val realSet = list.filter { names.contains(it) }.map { TvType.valueOf(it) }.toHashSet()
        if (realSet.isEmpty()) return hashSet

        return realSet
    }

    fun Context.updateHasTrailers() {
        LoadResponse.isTrailersEnabled = getHasTrailers()
    }

    private fun Context.getHasTrailers(): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        return settingsManager.getBoolean(this.getString(R.string.show_trailers_key), true)
    }

    fun Context.shouldShowPlayerMetadata(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(
            getString(R.string.show_player_metadata_key),
            true
        )
    }

    fun Context.filterProviderByPreferredMedia(hasHomePageIsRequired: Boolean = true): List<MainAPI> {
        val oldLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = TvType::class.java.classLoader

        val default = TvType.entries
            .sorted()
            .filter { it != TvType.NSFW }
            .map { it.ordinal }

        Thread.currentThread().contextClassLoader = oldLoader

        val defaultSet = default.map { it.toString() }.toSet()
        val currentPrefMedia = try {
            PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet(this.getString(R.string.prefer_media_type_key), defaultSet)
                ?.mapNotNull { it.toIntOrNull() ?: return@mapNotNull null }
        } catch (e: Throwable) {
            null
        } ?: default
        val langs = this.getApiProviderLangSettings()
        val hasUniversal = langs.contains(AllLanguagesName)
        val allApis =
            apis.filter { api -> (hasUniversal || langs.contains(api.lang)) && (api.hasMainPage || !hasHomePageIsRequired) }
        return if (currentPrefMedia.isEmpty()) {
            allApis
        } else {
            allApis.filter { api -> api.supportedTypes.any { currentPrefMedia.contains(it.ordinal) } }
        }
    }

    fun Context.filterSearchResultByFilmQuality(data: List<SearchResponse>): List<SearchResponse> {
        if (data.isNotEmpty()) {
            val filteredSearchQuality = AppSettings(this).ui.filterQuality.get()
            if (filteredSearchQuality.isNotEmpty()) {
                return data.filter { item ->
                    val searchQualVal = item.quality
                    !filteredSearchQuality.contains(searchQualVal)
                }
            }
        }
        return data
    }

    fun Context.filterHomePageListByFilmQuality(data: HomePageList): HomePageList {
        if (data.list.isNotEmpty()) {
            val filteredSearchQuality = AppSettings(this).ui.filterQuality.get()
            if (filteredSearchQuality.isNotEmpty()) {
                val filteredList = data.list.filter { item ->
                    val searchQualVal = item.quality
                    !filteredSearchQuality.contains(searchQualVal)
                }
                return HomePageList(
                    name = data.name,
                    isHorizontalImages = data.isHorizontalImages,
                    list = filteredList
                )
            }
        }
        return data
    }

    fun Activity.loadRepository(url: String) {
        ioSafe {
            val repo = RepositoryManager.parseRepository(url) ?: return@ioSafe
            val data = RepositoryData(
                iconUrl = repo.iconUrl ?: "",
                name = repo.name,
                url = url
            )
            RepositoryManager.addRepository(data)
            main {
                showToast(
                    getString(R.string.player_loaded_subtitles, repo.name),
                    Toast.LENGTH_LONG
                )
            }
            MainActivity.afterRepositoryLoadedEvent.invoke(true)
            addRepositoryDialog(data)
        }
    }

    fun Activity.addRepositoryDialog(
        repositoryData: RepositoryData
    ) {
        fun openAddedRepo() {
            val repos = RepositoryManager.getRepositories()
            if (repos.isNotEmpty()) {
                this.navigate(
                    R.id.global_to_navigation_settings_plugins,
                    repositoryData
                )
            }
        }

        runOnUiThread {
            showToast(getString(R.string.player_loaded_subtitles, repositoryData.name), Toast.LENGTH_SHORT)
            openAddedRepo()
        }
    }

    @PlatformQuarantine(
        reason = "System WebView check is Android-specific",
        upstreamRef = "AppContextUtils.kt:574",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun Context.hasWebView(): Boolean {
        return false
    }

    @PlatformQuarantine(
        reason = "In-app WebviewFragment is Android-specific; desktop opens external browser",
        upstreamRef = "AppContextUtils.kt:578",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun openWebView(fragment: Any?, url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url))
            } else {
                val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
                if (isWindows) {
                    ProcessBuilder("cmd", "/c", "start", url).start()
                } else {
                    ProcessBuilder("xdg-open", url).start()
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun Context.openBrowser(
        url: String,
        fallbackWebview: Boolean = false,
        fragment: Any? = null,
    ) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url))
                return
            }
        } catch (e: Exception) {
            logError(e)
        }
        try {
            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
            if (isWindows) {
                ProcessBuilder("cmd", "/c", "start", url).start()
            } else {
                ProcessBuilder("xdg-open", url).start()
            }
        } catch (e: Exception) {
            logError(e)
            if (fallbackWebview) {
                openWebView(fragment, url)
            }
        }
    }

    fun Context.isNetworkAvailable(): Boolean {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            interfaces.asSequence().any { iface ->
                iface.isUp && !iface.isLoopback
            }
        } catch (e: Exception) {
            logError(e)
            true
        }
    }

    fun splitQuery(url: java.net.URL): Map<String, String> {
        return com.lagradost.cloudstream3.splitUrlParameters(url.toString())
    }

    fun Context.getNameFull(name: String?, episode: Int?, season: Int?): String {
        val rEpisode = if (episode == 0) null else episode
        val rSeason = if (season == 0) null else season

        val seasonName = getString(R.string.season)
        val episodeName = getString(R.string.episode)
        val seasonNameShort = getString(R.string.season_short)
        val episodeNameShort = getString(R.string.episode_short)

        if (name != null) {
            return if (rEpisode != null && rSeason != null) {
                "$seasonNameShort${rSeason}:$episodeNameShort${rEpisode} $name"
            } else if (rEpisode != null) {
                "$episodeName $rEpisode. $name"
            } else {
                name
            }
        } else {
            if (rEpisode != null && rSeason != null) {
                return "$seasonName $rSeason - $episodeName $rEpisode"
            } else if (rSeason == null) {
                return "$episodeName $rEpisode"
            }
        }
        return ""
    }

    fun Context.getShortSeasonText(episode: Int?, season: Int?): String? {
        val rEpisode = if (episode == 0) null else episode
        val rSeason = if (season == 0) null else season
        val seasonNameShort = getString(R.string.season_short)
        val episodeNameShort = getString(R.string.episode_short)
        return if (rEpisode != null && rSeason != null) {
            "$seasonNameShort${rSeason}:$episodeNameShort${rEpisode}"
        } else if (rEpisode != null) {
            "$episodeNameShort$rEpisode"
        } else null
    }

    @PlatformQuarantine(
        reason = "Android Dalvik class preloading is not applicable to JVM",
        upstreamRef = "AppContextUtils.kt:694",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity?.loadCache() {
        cacheClass(null)
    }

    private fun getResultsId(): Int {
        return if (Globals.isLayout(Globals.TV or Globals.EMULATOR)) {
            R.id.global_to_navigation_results_tv
        } else {
            R.id.global_to_navigation_results_phone
        }
    }

    data class LoadResultData(
        val url: String,
        val apiName: String,
        val name: String,
        val startAction: Int = 0,
        val startValue: Int = 0
    )
    val loadResultEvent = Event<LoadResultData>()

    fun loadResult(
        url: String,
        apiName: String,
        name: String,
        startAction: Int = 0,
        startValue: Int = 0
    ) {
        try {
            val ctx = CloudStreamApp.context ?: CommonActivity.activity
            if (ctx != null) {
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)
                Kitsu.isEnabled =
                    settingsManager.getBoolean(ctx.getString(R.string.show_kitsu_posters_key), true)
            }
        } catch (t: Throwable) {
            logError(t)
        }

        val data = LoadResultData(url, apiName, name, startAction, startValue)
        loadResultEvent.invoke(data)
        (CommonActivity.activity as? Activity)?.let { act ->
            act.runOnUiThread {
                act.navigate(
                    getResultsId(),
                    data
                )
            }
        } ?: run {
            UIHelper.navigate(
                getResultsId(),
                data
            )
        }
    }

    fun Activity.loadResult(
        url: String,
        apiName: String,
        name: String,
        startAction: Int = 0,
        startValue: Int = 0
    ) {
        AppContextUtils.loadResult(url, apiName, name, startAction, startValue)
    }

    data class LoadSearchResultData(
        val card: SearchResponse,
        val startAction: Int = 0,
        val startValue: Int? = null
    ) {
        val url: String get() = card.url
        val apiName: String get() = card.apiName
        val name: String get() = card.name

        fun toLoadResultData(): LoadResultData = LoadResultData(
            url = card.url,
            apiName = card.apiName,
            name = card.name,
            startAction = startAction,
            startValue = startValue ?: 0
        )
    }

    val loadSearchResultEvent = Event<LoadSearchResultData>()

    fun loadSearchResult(
        card: SearchResponse,
        startAction: Int = 0,
        startValue: Int? = null,
    ) {
        (CommonActivity.activity as? Activity).loadSearchResult(card, startAction, startValue)
    }

    fun Activity?.loadSearchResult(
        card: SearchResponse,
        startAction: Int = 0,
        startValue: Int? = null,
    ) {
        val data = LoadSearchResultData(card, startAction, startValue)
        loadSearchResultEvent.invoke(data)
        if (this != null) {
            this.runOnUiThread {
                this.navigate(
                    getResultsId(),
                    data
                )
            }
        } else {
            UIHelper.navigate(
                getResultsId(),
                data
            )
        }
    }

    @PlatformQuarantine(
        reason = "Android AudioManager audio focus is not applicable on Linux; PipeWire/PulseAudio handles audio mixing",
        upstreamRef = "AppContextUtils.kt:768",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Activity.requestLocalAudioFocus(focusRequest: Any?) {
    }

    private var currentAudioFocusRequest: Any? = null
    private var currentAudioFocusChangeListener: Any? = null
    var onAudioFocusEvent = Event<Boolean>()

    @PlatformQuarantine(
        reason = "Android AudioManager audio focus listener is not applicable on Linux",
        upstreamRef = "AppContextUtils.kt:793",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun getAudioListener(): Any? {
        return null
    }

    @PlatformQuarantine(
        reason = "Google Play Services Cast API is not applicable to Linux desktop; DLNA/mDNS CastSender to be used",
        upstreamRef = "AppContextUtils.kt:808",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun Context.isCastApiAvailable(): Boolean {
        return false
    }

    @PlatformQuarantine(
        reason = "Chromecast connection state is Android GMS Cast specific; desktop uses DLNA/mDNS",
        upstreamRef = "AppContextUtils.kt:827",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun Context.isConnectedToChromecast(): Boolean {
        return false
    }

    @PlatformQuarantine(
        reason = "Android AlertDialog focus adjustment is not applicable to Compose Desktop",
        upstreamRef = "AppContextUtils.kt:841",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Any?.setDefaultFocus(buttonFocus: Int = -2) {
    }

    @PlatformQuarantine(
        reason = "Cellular mobile data is not typically applicable to Linux desktop",
        upstreamRef = "AppContextUtils.kt:849",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun Context.isUsingMobileData(): Boolean {
        return false
    }

    @PlatformQuarantine(
        reason = "Android Dalvik class caching optimization is not applicable to JVM",
        upstreamRef = "AppContextUtils.kt:864",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun Activity?.cacheClass(clazz: String?) {
    }

    fun Context.isAppInstalled(uri: String): Boolean {
        val binName = when {
            uri.contains("vlc", ignoreCase = true) -> "vlc"
            uri.contains("mpv", ignoreCase = true) -> "mpv"
            uri.contains("celluloid", ignoreCase = true) -> "celluloid"
            uri.contains("totem", ignoreCase = true) -> "totem"
            uri.contains("smplayer", ignoreCase = true) -> "smplayer"
            uri.contains("kodi", ignoreCase = true) -> "kodi"
            uri.contains("firefox", ignoreCase = true) -> "firefox"
            uri.contains("chrome", ignoreCase = true) -> "google-chrome"
            uri.contains("chromium", ignoreCase = true) -> "chromium"
            uri.contains("brave", ignoreCase = true) -> "brave"
            else -> uri.substringAfterLast('.').lowercase()
        }
        val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
        val checkCmd = if (isWindows) listOf("where", binName) else listOf("which", binName)
        return try {
            val process = ProcessBuilder(checkCmd)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logError(e)
            if (isWindows) {
                val pathExt = listOf("", ".exe", ".cmd", ".bat")
                val paths = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
                paths.any { dir -> pathExt.any { ext -> File(dir, "$binName$ext").exists() } }
            } else {
                File("/usr/bin/$binName").exists() || File("/usr/local/bin/$binName").exists()
            }
        }
    }

    @PlatformQuarantine(
        reason = "Android AudioFocusRequest is not applicable on Linux",
        upstreamRef = "AppContextUtils.kt:886",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun getFocusRequest(): Any? {
        return null
    }

    val Context.isTv: Boolean
        get() {
            val layoutPref = try {
                val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
                val key = this.getString(R.string.app_layout_key)
                settingsManager.getInt(key, -1)
            } catch (e: Throwable) {
                -1
            }
            return when (layoutPref) {
                1 -> true
                0, 2 -> false
                else -> Globals.isLayout(Globals.TV)
            }
        }

    val isTv: Boolean
        get() {
            val ctx = CloudStreamApp.context ?: CommonActivity.activity
            return ctx?.isTv ?: Globals.isLayout(Globals.TV)
        }

    fun Context.isTvSettings(): Boolean = isTv
    fun isTvSettings(): Boolean = isTv
}
