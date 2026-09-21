// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsViewModel.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Context
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.plugins.DesktopPluginManager
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class RepositoryData(
    @JsonProperty("iconUrl") @SerialName("iconUrl") val iconUrl: String?,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("url") @SerialName("url") val url: String,
) {
    constructor(name: String, url: String) : this(null, name, url)
}

const val REPOSITORIES_KEY = "REPOSITORIES_KEY"

class ExtensionsViewModel(
    dispatcher: CoroutineDispatcher? = null
) : DesktopViewModel(dispatcher) {
    data class PluginStats(
        val total: Int,

        val downloaded: Int,
        val disabled: Int,
        val notDownloaded: Int,

        val downloadedText: UiText,
        val disabledText: UiText,
        val notDownloadedText: UiText,
        val outdated: Int = 0,
        val outdatedText: UiText? = null,
    ) {
        constructor(
            total: Int,
            downloaded: Int,
            disabled: Int,
            notDownloaded: Int,
            downloadedText: UiText,
            disabledText: UiText,
            notDownloadedText: UiText,
        ) : this(
            total,
            downloaded,
            disabled,
            notDownloaded,
            downloadedText,
            disabledText,
            notDownloadedText,
            0,
            null
        )
    }

    private val _repositories = MutableStateFlow<Array<RepositoryData>>(emptyArray())
    val repositories: StateFlow<Array<RepositoryData>> = _repositories.asStateFlow()

    private val _pluginStats = MutableStateFlow<PluginStats?>(null)
    val pluginStats: StateFlow<PluginStats?> = _pluginStats.asStateFlow()

    private val pluginLoadedCallback: (Boolean) -> Unit = {
        loadStats()
        loadRepositories()
    }

    init {
        MainActivity.afterPluginsLoadedEvent += pluginLoadedCallback
    }

    override fun onCleared() {
        MainActivity.afterPluginsLoadedEvent -= pluginLoadedCallback
        super.onCleared()
    }

    private fun <T> MutableStateFlow<T>.postValue(value: T) {
        this.value = value
    }

    // Cache get requests handled via ioSafe execution to prevent UI blocking
    // DO not use viewModelScope.launchSafe, it will ANR on slow internet
    fun loadStats() = ioSafe {
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().amap {
            RepositoryManager.getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        // Iterates over all offline plugins, compares to remote repo and returns the plugins which are outdated
        val outdatedPlugins = getPluginsOnline().flatMap { savedData ->
            onlinePlugins.filter { onlineData -> savedData.internalName == onlineData.plugin.internalName }
                .map { onlineData ->
                    PluginManager.OnlinePluginData(savedData, onlineData)
                }
        }.distinctBy { it.onlineData.plugin.url }

        val total = onlinePlugins.count()
        val disabled = outdatedPlugins.count { it.isDisabled }
        val outdated = outdatedPlugins.count { it.isOutdated }
        val downloadedTotal = outdatedPlugins.count()
        val downloaded = downloadedTotal - disabled
        val notDownloaded = total - downloadedTotal
        val stats = PluginStats(
            total,
            downloaded,
            disabled,
            notDownloaded,
            txt(R.string.plugins_downloaded, downloaded),
            txt(R.string.plugins_disabled, disabled),
            txt(R.string.plugins_not_downloaded, notDownloaded),
            outdated,
            txt(R.string.plugins_updated, outdated)
        )
        debugAssert({ stats.downloaded + stats.notDownloaded + stats.disabled != stats.total }) {
            "downloaded(${stats.downloaded}) + notDownloaded(${stats.notDownloaded}) + disabled(${stats.disabled}) != total(${stats.total})"
        }
        _pluginStats.postValue(stats)
    }

    private fun repos() = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
        ?: emptyArray()) + PREBUILT_REPOSITORIES

    fun loadRepositories() {
        val urls = repos()
        _repositories.postValue(urls)
    }

    // =========================================================================
    // Desktop Plugin Update Channels & Repository Synchronization Parity
    // =========================================================================

    /**
     * Synchronizes repositories and plugin statistics with DesktopPluginManager update channels.
     */
    fun syncRepositories(context: Context? = null) = ioSafe {
        loadRepositories()
        loadStats()
        if (context != null) {
            DesktopPluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(context)
        }
    }

    /**
     * Triggers online plugin updates across all configured repository channels via DesktopPluginManager.
     */
    fun updateAllPlugins(context: Context) = ioSafe {
        DesktopPluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(context)
        loadStats()
    }

    /**
     * Auto-downloads missing plugins from active channels respecting language and content filters.
     */
    fun downloadMissingPlugins(context: Context, mode: AutoDownloadMode = AutoDownloadMode.FilterByLang) = ioSafe {
        DesktopPluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(context, mode)
        loadStats()
    }

    /**
     * Manually checks for updates across all configured repository channels and replaces outdated plugin binaries on disk.
     */
    fun checkAndUpdatePluginsManually(context: Context) = ioSafe {
        DesktopPluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(context)
        loadStats()
    }
}
