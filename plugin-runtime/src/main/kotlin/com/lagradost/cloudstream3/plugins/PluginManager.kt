// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI.Companion.settingsForProvider
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.loader.SafePluginClassLoader
import com.lagradost.cloudstream3.mvvm.debugPrint
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.downloadPluginToFile
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.plugins.RepositoryManager.sha256
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Different keys for local and not since local can be removed at any time without app knowing, hence the local are getting rebuilt on every app start
const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"

const val EXTENSIONS_CHANNEL_ID = "cloudstream3.extensions"
const val EXTENSIONS_CHANNEL_NAME = "Extensions"
const val EXTENSIONS_CHANNEL_DESCRIPT = "Extension notification channel"

// Data class for internal storage
@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("url") @SerialName("url") val url: String?,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String,
    @JsonProperty("version") @SerialName("version") val version: Int,
) {
    @WorkerThread
    fun toSitePlugin(): SitePlugin {
        return SitePlugin(
            this.filePath,
            PROVIDER_STATUS_OK,
            maxOf(1, version),
            1,
            internalName,
            internalName,
            emptyList(),
            File(this.filePath).name,
            null,
            null,
            null,
            null,
            File(this.filePath).length(),
            // No file hash for local plugins. Local plugins have no use for the hash, and it's expensive to compute.
            null
        )
    }
}

// This is used as a placeholder / not set version
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE

// This always updates
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

object PluginManager {
    // Prevent multiple writes at once
    val lock = Mutex()

    const val TAG = "PluginManager"

    private var hasCreatedNotChanel = false

    private val _pluginsLoaded = MutableStateFlow(false)
    val pluginsLoaded: StateFlow<Boolean> = _pluginsLoaded

    var loadedOnlinePlugins: Boolean = false
        set(value) {
            field = value
            _pluginsLoaded.value = value && loadedLocalPlugins
        }

    var loadedLocalPlugins: Boolean = false
        set(value) {
            field = value
            _pluginsLoaded.value = loadedOnlinePlugins && value
        }

    val CLOUD_STREAM_FOLDER: String =
        PlatformPaths.dataDir.resolve("Cloudstream3").toFile().absolutePath

    val LOCAL_PLUGINS_PATH: String =
        PlatformPaths.pluginsDir.toFile().absolutePath

    var currentlyLoading: String? = null

    // Maps filepath to plugin
    val plugins: MutableMap<String, BasePlugin>
        get() = ExtensionLoader.plugins

    // Maps urls to plugin
    val urlPlugins: MutableMap<String, BasePlugin> = ConcurrentHashMap()

    val classLoaders: MutableMap<ClassLoader, BasePlugin> = ConcurrentHashMap()

    /**
     * Store data about the plugin for fetching later
     */
    suspend fun setPluginData(data: PluginData) {
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY, newPlugins.toTypedArray())
            } else {
                val plugins = getPluginsLocal()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY_LOCAL, newPlugins.toTypedArray())
            }
        }
    }

    suspend fun deletePluginData(data: PluginData?) {
        if (data == null) return
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline().filter { it.url != data.url }
                setKey(PLUGINS_KEY, plugins.toTypedArray())
            } else {
                val plugins = getPluginsLocal().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY_LOCAL, plugins.toTypedArray())
            }
        }
    }

    suspend fun deleteRepositoryData(repositoryPath: String) {
        lock.withLock {
            val plugins = getPluginsOnline().filter {
                !it.filePath.contains(repositoryPath)
            }
            val file = File(repositoryPath)
            safe {
                if (file.exists()) file.deleteRecursively()
            }
            setKey(PLUGINS_KEY, plugins.toTypedArray())
        }
    }

    /**
     * Deletes all generated oat and transpiled cache files.
     */
    fun deleteAllOatFiles(context: Context) {
        val onlineFolder = File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}")
        if (onlineFolder.exists()) {
            onlineFolder.listFiles()?.forEach { repo ->
                repo.listFiles { file -> file.name == "oat" && file.isDirectory }?.forEach { file ->
                    val success = file.deleteRecursively()
                    Log.i(TAG, "Deleted oat directory: ${file.absolutePath} Success=$success")
                }
            }
        }
        safe {
            val cacheDir = PlatformPaths.transpiledCacheDir.toFile()
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
                Log.i(TAG, "Deleted transpiled DEX cache in: ${cacheDir.absolutePath}")
            }
        }
    }

    fun getPluginsOnline(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()
    }

    fun getPluginsLocal(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()
    }

    private suspend fun maybeLoadPlugin(context: Context, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3" || file.extension == "jar") {
            loadPlugin(
                context,
                file,
                PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
            )
        } else {
            Log.i(TAG, "Skipping invalid plugin file: $file")
        }
    }

    // Helper class for updateAllOnlinePluginsAndLoadThem
    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: PluginWrapper,
    ) {
        val isOutdated =
            onlineData.plugin.version > savedData.version || onlineData.plugin.version == PLUGIN_VERSION_ALWAYS_UPDATE
        val isDisabled = onlineData.plugin.status == PROVIDER_STATUS_DOWN

        fun validOnlineData(context: Context): Boolean {
            return getPluginPath(
                context,
                savedData.internalName,
                onlineData.repositoryData.url
            ).absolutePath == savedData.filePath
        }
    }

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean {
        return (getPluginsOnline().firstOrNull {
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        }
            ?: getPluginsLocal().firstOrNull {
                it.internalName.replace("provider", "", ignoreCase = true) == apiName
            })?.let { savedData ->
            loadPlugin(
                context,
                File(savedData.filePath),
                savedData
            )
        } ?: false
    }

    @Throws
    private fun assertNonRecursiveCallstack() {
        if (Thread.currentThread().stackTrace.any { it.methodName == "loadPlugin" }) {
            throw Error("You tried to call a function that will recursively call loadPlugin, this will cause crashes or memory leaks. Do not do this, there is better ways to implement the feature than reloading plugins. Are you sure you read the compile error or docs?")
        }
    }

    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(activity: Context) {
        assertNonRecursiveCallstack()

        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val outdatedPlugins = getPluginsOnline().map { savedData ->
            onlinePlugins
                .filter { onlineData -> savedData.internalName == onlineData.plugin.internalName }
                .map { onlineData ->
                    OnlinePluginData(savedData, onlineData)
                }.filter {
                    it.validOnlineData(activity)
                }
        }.flatten().distinctBy { it.onlineData.plugin.url }

        debugPrint {
            "Outdated plugins: ${outdatedPlugins.filter { it.isOutdated }}"
        }

        val updatedPlugins = mutableListOf<String>()

        outdatedPlugins.amap { pluginData ->
            if (pluginData.isDisabled) {
                unloadPlugin(pluginData.savedData.filePath)
            } else if (pluginData.isOutdated) {
                downloadPlugin(
                    activity,
                    pluginData.onlineData.plugin.url,
                    pluginData.onlineData.plugin.fileHash,
                    pluginData.savedData.internalName,
                    File(pluginData.savedData.filePath),
                    true
                ).let { success ->
                    if (success)
                        updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }

        main {
            val uitext = txt(R.string.plugins_updated, updatedPlugins.size)
            createNotification(activity, uitext, updatedPlugins)
        }

        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)

        Log.i(TAG, "Plugin update done!")
    }

    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
        activity: Context,
        mode: AutoDownloadMode
    ) {
        assertNonRecursiveCallstack()

        val newDownloadPlugins = mutableListOf<String>()
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val providerLang = activity.getApiProviderLangSettings()

        val notDownloadedPlugins = onlinePlugins.mapNotNull { onlineData ->
            val sitePlugin = onlineData.plugin
            val tvtypes = sitePlugin.tvTypes ?: listOf()

            if (sitePlugin.url.isBlank()) {
                return@mapNotNull null
            }
            if (sitePlugin.repositoryUrl.isNullOrBlank()) {
                return@mapNotNull null
            }

            if (getPluginPath(activity, sitePlugin.internalName, onlineData.repositoryData.url).exists()) {
                Log.i(TAG, "Skip > ${sitePlugin.internalName}")
                return@mapNotNull null
            }

            if (mode == AutoDownloadMode.NsfwOnly) {
                if (!tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }
            if (!settingsForProvider.enableAdult) {
                if (tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }

            if (mode == AutoDownloadMode.FilterByLang) {
                val lang = sitePlugin.language ?: return@mapNotNull null
                if (!providerLang.contains(AllLanguagesName) && !providerLang.contains(lang)) {
                    return@mapNotNull null
                }
            }

            val savedData = PluginData(
                url = sitePlugin.url,
                internalName = sitePlugin.internalName,
                isOnline = true,
                filePath = "",
                version = sitePlugin.version
            )
            OnlinePluginData(savedData, onlineData)
        }

        notDownloadedPlugins.amap { pluginData ->
            downloadPlugin(
                activity,
                pluginData.onlineData.plugin.url,
                pluginData.onlineData.plugin.fileHash,
                pluginData.savedData.internalName,
                pluginData.onlineData.repositoryData.url,
                !pluginData.isDisabled
            ).let { success ->
                if (success)
                    newDownloadPlugins.add(pluginData.onlineData.plugin.name)
            }
        }

        main {
            val uitext = txt(R.string.plugins_downloaded, newDownloadPlugins.size)
            createNotification(activity, uitext, newDownloadPlugins)
        }

        afterPluginsLoadedEvent.invoke(false)

        Log.i(TAG, "Plugin download done!")
    }

    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        getPluginsOnline().toList().amap { pluginData ->
            loadPlugin(
                context,
                File(pluginData.filePath),
                pluginData
            )
        }
        loadedOnlinePlugins = true
    }

    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(activity: Context?) {
        assertNonRecursiveCallstack()

        Log.d(TAG, "Reloading all local plugins!")
        if (activity == null) return
        getPluginsLocal().forEach {
            unloadPlugin(it.filePath)
        }
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(activity, true)
    }

    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context: Context, forceReload: Boolean = false) {
        assertNonRecursiveCallstack()

        val dir = File(LOCAL_PLUGINS_PATH)

        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                Log.w(TAG, "Failed to create local directories")
                loadedLocalPlugins = true
                return
            }
        }

        val sortedPlugins = dir.listFiles()
        Log.d(TAG, "Files in '$LOCAL_PLUGINS_PATH' folder: ${sortedPlugins?.size}")

        val pluginDirectory = File(context.filesDir ?: PlatformPaths.pluginsDir.toFile(), "plugins")
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs()
        }

        removeKey(PLUGINS_KEY_LOCAL)

        sortedPlugins?.sortedBy { it.name }?.amap { file ->
            try {
                val destinationFile = File(pluginDirectory, file.name)
                if (!destinationFile.exists() ||
                    destinationFile.length() != file.length() ||
                    destinationFile.lastModified() != file.lastModified()
                ) {
                    file.copyTo(destinationFile, overwrite = true)
                    destinationFile.setLastModified(file.lastModified())
                }

                maybeLoadPlugin(context, destinationFile)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to copy the file: ${file.name}")
                logError(t)
            }
        }

        loadedLocalPlugins = true
        afterPluginsLoadedEvent.invoke(forceReload)
    }

    /** @return true if safe mode is enabled in any possible way. */
    fun isSafeMode(): Boolean {
        return checkSafeModeFile() || MainActivity.lastError != null
    }

    /**
     * This can be used to override any extension loading to fix crashes!
     * Checks XDG configuration/data directories and legacy paths for safe mode indicator files.
     * @return true if safe mode file is present
     **/
    fun checkSafeModeFile(): Boolean {
        return safe {
            val dataSafeFile = File(PlatformPaths.dataDir.toFile(), "safe")
            val dataDotSafeMode = File(PlatformPaths.dataDir.toFile(), ".safe_mode")
            val configSafeFile = File(PlatformPaths.configDir.toFile(), "safe")
            val configDotSafeMode = File(PlatformPaths.configDir.toFile(), ".safe_mode")
            if (dataSafeFile.exists() || dataDotSafeMode.exists() || configSafeFile.exists() || configDotSafeMode.exists()) {
                return@safe true
            }

            val folder = File(CLOUD_STREAM_FOLDER)
            if (folder.exists()) {
                val files = folder.listFiles { _, name ->
                    name.equals("safe", ignoreCase = true) || name.equals(".safe_mode", ignoreCase = true)
                }
                if (files?.any() == true) return@safe true
            }
            false
        } ?: false
    }

    suspend fun loadPlugin(context: Context, file: File, data: PluginData): Boolean {
        val fileName = file.nameWithoutExtension
        val filePath = file.absolutePath
        currentlyLoading = fileName
        Log.i(TAG, "Loading plugin: $data")

        return try {
            if (!file.exists()) {
                Log.e(TAG, "Plugin file does not exist: $filePath")
                currentlyLoading = null
                return false
            }

            val pluginInstance = ExtensionLoader.loadJar(file)
            Log.d(TAG, "Plugin ${data.internalName} loaded via shadow copy mechanism; original file remains lock-free at: $filePath")

            var version = data.version
            try {
                java.util.zip.ZipFile(file).use { zip ->
                    val manifestEntry = zip.getEntry("manifest.json")
                    if (manifestEntry != null) {
                        zip.getInputStream(manifestEntry).use { input ->
                            val manifest = parseJson<BasePlugin.Manifest>(input.reader().readText())
                            if (manifest.version != null) {
                                version = manifest.version!!
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.d(TAG, "No manifest version for ${data.internalName}: ${t.message}")
            }

            setPluginData(data.copy(version = version))

            pluginInstance.filename = file.absolutePath
            synchronized(plugins) {
                plugins[filePath] = pluginInstance
            }
            synchronized(classLoaders) {
                classLoaders[pluginInstance.javaClass.classLoader] = pluginInstance
            }
            synchronized(urlPlugins) {
                urlPlugins[data.url ?: filePath] = pluginInstance
            }

            ExtensionLoader.initializePlugin(pluginInstance)

            Log.i(TAG, "Loaded plugin ${data.internalName} successfully")
            currentlyLoading = null
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load $file: ${Log.getStackTraceString(e)}")
            safe {
                showToast(
                    context.getString(R.string.plugin_load_fail).format(fileName),
                    Toast.LENGTH_LONG
                )
            }
            currentlyLoading = null
            false
        }
    }

    /**
     * 7-Step Metaspace Unloader:
     * 1. plugin.beforeUnload()
     * 2. APIHolder and provider mapping cleanup
     * 3. Static fields nulling (target class and inner classes)
     * 4. Clear ghost cache
     * 5. Flush Jackson TypeFactory & Introspector reflection caches
     * 6. Close URLClassLoader to release POSIX file locks
     * 7. Registry cleanup (classLoaders, plugins, urlPlugins)
     */
    fun unloadPlugin(absolutePath: String): Boolean {
        Log.i(TAG, "Unloading plugin: $absolutePath")
        val plugin = plugins[absolutePath]
            ?: ExtensionLoader.plugins[absolutePath]
            ?: urlPlugins[absolutePath]
            ?: classLoaders.values.firstOrNull { it.filename == absolutePath }
            ?: urlPlugins.values.firstOrNull { it.filename == absolutePath }

        // Step 1: plugin.beforeUnload()
        if (plugin != null) {
            try {
                plugin.beforeUnload()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to run beforeUnload $absolutePath: ${Log.getStackTraceString(e)}")
            }
            if (plugin is Plugin) {
                plugin.openSettings = null
            }
        } else {
            Log.w(TAG, "Couldn't find loaded plugin instance for $absolutePath, performing provider and registry purge")
        }

        val sourcePath = plugin?.filename?.ifEmpty { absolutePath } ?: absolutePath

        // Step 2: APIHolder and provider mapping cleanup
        try {
            APIHolder.apis.filter { api -> api.sourcePlugin == sourcePath || api.sourcePlugin == absolutePath }.forEach {
                removePluginMapping(it)
            }
            APIHolder.allProviders.withLock {
                APIHolder.allProviders.removeAll { provider -> provider.sourcePlugin == sourcePath || provider.sourcePlugin == absolutePath }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to remove API mappings for $absolutePath: ${t.message}")
        }

        try {
            extractorApis.withLock {
                extractorApis.removeAll { provider -> provider.sourcePlugin == sourcePath || provider.sourcePlugin == absolutePath }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to remove extractor APIs for $absolutePath: ${t.message}")
        }

        try {
            VideoClickActionHolder.allVideoClickActions.withLock {
                VideoClickActionHolder.allVideoClickActions.removeAll { action -> action.sourcePlugin == sourcePath || action.sourcePlugin == absolutePath }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to remove video click actions for $absolutePath: ${t.message}")
        }

        // Steps 3 - 6: Clean unload via SafePluginClassLoader (Wipes static fields, ghost cache, Jackson caches, closes URLClassLoader)
        val classLoader = classLoaders.entries.firstOrNull { it.value == plugin || it.value.filename == absolutePath || it.value.filename == sourcePath }?.key
            ?: plugin?.javaClass?.classLoader

        if (classLoader != null) {
            ExtensionLoader.classLoaders.remove(classLoader)
            SafePluginClassLoader.cleanUnload(classLoader, plugin?.javaClass)
        }

        // Step 7: Purge registries
        synchronized(classLoaders) {
            classLoaders.values.removeIf { v -> v == plugin || v.filename == absolutePath || v.filename == sourcePath }
            if (classLoader != null) {
                classLoaders.remove(classLoader)
            }
        }
        synchronized(urlPlugins) {
            urlPlugins.values.removeIf { v -> v == plugin || v.filename == absolutePath || v.filename == sourcePath }
            urlPlugins.remove(absolutePath)
        }
        synchronized(plugins) {
            plugins.remove(absolutePath)
            if (plugin != null) {
                plugins.values.removeIf { it == plugin }
            }
        }
        ExtensionLoader.plugins.remove(absolutePath)
        if (plugin != null) {
            ExtensionLoader.plugins.values.removeIf { it == plugin }
        }

        afterPluginsLoadedEvent.invoke(false)
        return true
    }

    /**
     * Spits out a unique and safe filename based on name.
     * Used for repo folders (using repo url) and plugin file names (using internalName)
     */
    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(
            name,
            true
        ) + "." + name.hashCode()
    }

    /**
     * This should not be changed as it is used to also detect if a plugin is installed!
     */
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl)
        val fileName = getPluginSanitizedFileName(internalName)
        val filesDir = context.filesDir ?: PlatformPaths.pluginsDir.toFile()
        val extensionsDir = File(filesDir, ONLINE_PLUGINS_FOLDER)
        val repoFolder = File(extensionsDir, folderName)
        return File(repoFolder, "$fileName.cs3")
    }

    suspend fun downloadPlugin(
        activity: Context,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean
    ): Boolean {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        return downloadPlugin(activity, pluginUrl, pluginHash, internalName, file, loadPlugin)
    }

    suspend fun downloadPlugin(
        activity: Context,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
    ): Boolean {
        try {
            Log.d(TAG, "Downloading plugin: $pluginUrl to ${file.absolutePath}")
            val newFile = downloadPluginToFile(activity, pluginUrl, file, pluginHash) ?: return false

            val data = PluginData(
                internalName,
                pluginUrl,
                true,
                newFile.absolutePath,
                PLUGIN_VERSION_NOT_SET
            )

            return if (loadPlugin) {
                unloadPlugin(file.absolutePath)
                com.lagradost.cloudstream3.loader.PluginShadowManager.deleteShadowCopies(file)
                val loaded = loadPlugin(
                    activity,
                    newFile,
                    data
                )
                if (loaded) {
                    afterPluginsLoadedEvent.invoke(false)
                }
                loaded
            } else {
                setPluginData(data)
                true
            }
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    suspend fun deletePlugin(file: File): Boolean {
        val list =
            (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }

        return try {
            // Unload first to close URLClassLoader and release open file handles (crucial on Windows & POSIX)
            unloadPlugin(file.absolutePath)
            // Clean up any shadow copies associated with this plugin
            com.lagradost.cloudstream3.loader.PluginShadowManager.deleteShadowCopies(file)
            // With Shadow Copy (Copy-on-Load), the original file in plugins/ is never locked by URLClassLoader
            if (File(file.absolutePath).delete() || !file.exists()) {
                list.forEach { deletePluginData(it) }
                afterPluginsLoadedEvent.invoke(false)
                return true
            }
            false
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(activity: Context) {
        assertNonRecursiveCallstack()

        showToast(activity.getString(R.string.starting_plugin_update_manually), Toast.LENGTH_LONG)

        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val allPlugins = getPluginsOnline().flatMap { savedData ->
            onlinePlugins
                .filter { it.plugin.internalName == savedData.internalName }
                .mapNotNull { onlineData ->
                    OnlinePluginData(savedData, onlineData).takeIf { it.validOnlineData(activity) }
                }
        }.distinctBy { it.onlineData.plugin.url }

        val updatedPlugins = mutableListOf<String>()

        allPlugins.amap { pluginData ->
            if (pluginData.isDisabled) {
                Log.e(
                    TAG,
                    "Unloading disabled plugin: ${pluginData.onlineData.plugin.name}"
                )
                unloadPlugin(pluginData.savedData.filePath)
            } else {
                val existingFile = File(pluginData.savedData.filePath)
                if (existingFile.exists()) {
                    unloadPlugin(existingFile.absolutePath)
                    com.lagradost.cloudstream3.loader.PluginShadowManager.deleteShadowCopies(existingFile)
                    existingFile.delete()
                }

                if (downloadPlugin(
                        activity,
                        pluginData.onlineData.plugin.url,
                        pluginData.onlineData.plugin.fileHash,
                        pluginData.savedData.internalName,
                        existingFile,
                        true
                    )
                ) {
                    updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }.also {
            main {
                val message = if (updatedPlugins.isNotEmpty()) {
                    activity.getString(R.string.plugins_updated_manually, updatedPlugins.size)
                } else {
                    activity.getString(R.string.no_plugins_updated_manually)
                }
                showToast(message, Toast.LENGTH_LONG)

                val notificationText = UiText.StringResource(
                    R.string.plugins_updated_manually,
                    listOf(updatedPlugins.size)
                )
                createNotification(activity, notificationText, updatedPlugins)
            }
        }

        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)

        Log.i(TAG, "Plugin update done!")
    }

    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // the NotificationChannel class is new and not in the support library
        val name = EXTENSIONS_CHANNEL_NAME
        val descriptionText = EXTENSIONS_CHANNEL_DESCRIPT
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(EXTENSIONS_CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager? =
            this.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.createNotificationChannel(channel)
    }

    private fun createNotification(
        context: Context,
        uitext: UiText,
        extensions: List<String>
    ): Notification? {
        try {
            if (extensions.isEmpty()) return null

            val content = extensions.joinToString(", ")
            val builder = NotificationCompat.Builder(context, EXTENSIONS_CHANNEL_ID)
                .setAutoCancel(false)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentTitle(uitext.asString(context))
                .setSmallIcon(R.drawable.ic_baseline_extension_24)
                .setContentText(content)

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            NotificationManagerCompat.from(context)
                .notify((System.currentTimeMillis() / 1000).toInt(), notification)
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    /**
     * Desktop consolidation entrypoint:
     * Discovers and loads all installed plugins in PlatformPaths.pluginsDir,
     * updates loaded flags and emits afterPluginsLoadedEvent.
     */
    fun loadInstalledPlugins(context: Context? = null): Int {
        val pluginsDir = PlatformPaths.pluginsDir.toFile()
        if (!pluginsDir.exists()) {
            pluginsDir.mkdirs()
            Log.i(TAG, "Created plugins directory at ${pluginsDir.absolutePath}")
            loadedLocalPlugins = true
            loadedOnlinePlugins = true
            afterPluginsLoadedEvent.invoke(false)
            return 0
        }

        return try {
            val count = ExtensionLoader.rescanAndLoadNewPlugins(pluginsDir)
            Log.i(TAG, "Loaded $count installed plugin(s) from ${pluginsDir.absolutePath}")
            loadedLocalPlugins = true
            loadedOnlinePlugins = true
            afterPluginsLoadedEvent.invoke(false)
            count
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load installed plugins: ${t.message}")
            logError(t)
            loadedLocalPlugins = true
            loadedOnlinePlugins = true
            afterPluginsLoadedEvent.invoke(false)
            0
        }
    }
}

typealias DesktopPluginManager = PluginManager
