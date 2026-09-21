package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.repo.SitePlugin
import com.lagradost.common.logging.AppLogger
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalPlugin(
    val file: File,
    val name: String,
    val internalName: String,
    val version: Int,
    val iconUrl: String?,
    val repoName: String,
    val language: String?,
    val tvTypes: List<String>?,
)

class ExtensionsViewModel(private val coroutineScope: CoroutineScope) {
    private val _isFetching = MutableStateFlow(false)
    val isFetching = _isFetching.asStateFlow()

    private val _statusText = MutableStateFlow("Press Sync or Fetch to load plugins from repositories.")
    val statusText = _statusText.asStateFlow()

    private val _plugins = MutableStateFlow<List<Pair<String, SitePlugin>>>(emptyList())
    val plugins = _plugins.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<LocalPlugin>>(emptyList())
    val installedPlugins = _installedPlugins.asStateFlow()

    private val _installingPlugins = MutableStateFlow<Set<String>>(emptySet())
    val installingPlugins = _installingPlugins.asStateFlow()

    private val _reloadingPlugins = MutableStateFlow<Set<String>>(emptySet())
    val reloadingPlugins = _reloadingPlugins.asStateFlow()

    private val _pluginRequiringBypass = MutableStateFlow<Pair<String, SitePlugin>?>(null)
    val pluginRequiringBypass = _pluginRequiringBypass.asStateFlow()

    private val _localPluginRequiringBypass = MutableStateFlow<File?>(null)
    val localPluginRequiringBypass = _localPluginRequiringBypass.asStateFlow()

    init {
        refreshInstalled()
    }

    fun fetchPlugins() {
        _isFetching.value = true
        _statusText.value = "Fetching plugins from repositories..."
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    DesktopRepositoryManager.syncAll()
                }
                _plugins.value = DesktopRepositoryManager.getAllPlugins()
                _statusText.value = "Fetched ${_plugins.value.size} plugins from ${DesktopRepositoryManager.getSavedRepositories().size} repositories."
            } catch (e: Throwable) {
                _statusText.value = "Error: ${e.message}"
            } finally {
                _isFetching.value = false
            }
        }
    }

    fun loadPluginsFromManager() {
        _plugins.value = DesktopRepositoryManager.getAllPlugins()
        _statusText.value = "Showing ${_plugins.value.size} plugins from ${DesktopRepositoryManager.getSavedRepositories().size} repositories."
    }

    fun refreshInstalled() {
        val list = mutableListOf<LocalPlugin>()
        val extensionsDir = DesktopRepositoryManager.getExtensionsDir()
        val allRemote = DesktopRepositoryManager.getAllPlugins()
        if (extensionsDir.exists()) {
            extensionsDir.walkTopDown()
                .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3") }
                .filter { !it.name.endsWith("-jvm.jar") }
                .forEach { jar ->
                    val manifest = runCatching {
                        java.util.zip.ZipFile(jar).use { zip ->
                            val entry = zip.getEntry("manifest.json") ?: return@use null
                            zip.getInputStream(entry).use { input ->
                                com.fasterxml.jackson.databind.ObjectMapper()
                                    .readValue(input, Map::class.java)
                            }
                        }
                    }.getOrNull()

                    val name = manifest?.get("name") as? String ?: jar.nameWithoutExtension
                    val internalName = manifest?.get("internalName") as? String ?: name
                    val version = manifest?.get("version")?.toString()?.toIntOrNull() ?: 0
                    val iconUrl = manifest?.get("iconUrl") as? String

                    val remoteMatch = allRemote.find { it.second.internalName == internalName }
                    val repoName = remoteMatch?.first ?: jar.parentFile?.name?.replace("_", " ") ?: "Local"

                    val rawTvTypes = manifest?.get("tvTypes")
                    val tvTypes = when (rawTvTypes) {
                        is List<*> -> rawTvTypes.filterIsInstance<String>()
                        is String -> listOf(rawTvTypes)
                        else -> remoteMatch?.second?.tvTypes ?: emptyList()
                    }
                    val language = manifest?.get("language") as? String ?: remoteMatch?.second?.language

                    list.add(LocalPlugin(jar, name, internalName, version, iconUrl, repoName, language, tvTypes))
                }
        }
        _installedPlugins.value = list
    }

    fun reloadPlugin(plugin: LocalPlugin, onResult: (Result<Unit>) -> Unit) {
        _reloadingPlugins.update { it + plugin.internalName }
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        ExtensionLoader.unloadPlugin(plugin.file.absolutePath)
                        ExtensionLoader.loadAndInit(plugin.file)
                    }.map { Unit }
                }
                result.exceptionOrNull()?.let {
                    AppLogger.e("ExtensionsViewModel", "Failed to reload ${plugin.name}: ${it.message}")
                }
                if (result.isSuccess) {
                    refreshInstalled()
                    DesktopRepositoryManager.syncGeneration.value += 1
                }
                onResult(result)
            } finally {
                _reloadingPlugins.update { it - plugin.internalName }
            }
        }
    }

    fun installPlugin(repoName: String, plugin: SitePlugin, onResult: (String) -> Unit) {
        _installingPlugins.update { it + plugin.internalName }
        coroutineScope.launch {
            try {
                val jarFile = withContext(Dispatchers.IO) {
                    DesktopRepositoryManager.downloadPlugin(repoName, plugin)
                }
                if (jarFile != null) {
                    withContext(Dispatchers.IO) {
                        ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                        ExtensionLoader.loadAndInit(jarFile)
                    }
                    onResult("Installed")
                    refreshInstalled()
                    DesktopRepositoryManager.syncGeneration.value += 1
                } else {
                    onResult("Failed")
                }
            } catch (e: SecurityException) {
                AppLogger.w("ExtensionsViewModel", "Security exception installing plugin: ${e.message}")
                _pluginRequiringBypass.value = Pair(repoName, plugin)
                onResult("Blocked (Security)")
            } catch (e: Throwable) {
                AppLogger.e("ExtensionsViewModel", "Error loading plugin: ${e.message}")
                onResult("Error")
            } finally {
                _installingPlugins.update { it - plugin.internalName }
            }
        }
    }

    fun bypassSecurityAndInstall(repoName: String, plugin: SitePlugin) {
        _pluginRequiringBypass.value = null
        _installingPlugins.update { it + plugin.internalName }
        coroutineScope.launch {
            try {
                val jarFile = withContext(Dispatchers.IO) {
                    DesktopRepositoryManager.downloadPlugin(repoName, plugin)
                }
                if (jarFile != null) {
                    withContext(Dispatchers.IO) {
                        ExtensionLoader.unloadPlugin(jarFile.absolutePath)
                        ExtensionLoader.loadAndInit(jarFile, forceBypassSecurity = true)
                    }
                    refreshInstalled()
                    DesktopRepositoryManager.syncGeneration.value += 1
                }
            } catch (e: Throwable) {
                AppLogger.e("ExtensionsViewModel", "Error bypassing security for plugin: ${e.message}")
            } finally {
                _installingPlugins.update { it - plugin.internalName }
            }
        }
    }

    fun clearBypass() {
        _pluginRequiringBypass.value = null
    }

    fun dismissLocalPluginBypass() {
        _localPluginRequiringBypass.value?.delete()
        _localPluginRequiringBypass.value = null
    }

    fun uninstallPlugins(plugins: List<LocalPlugin>) {
        coroutineScope.launch(Dispatchers.IO) {
            for (plugin in plugins) {
                try {
                    ExtensionLoader.unloadPlugin(plugin.file.absolutePath)
                    val parentDir = plugin.file.parentFile
                    plugin.file.delete()
                    File(parentDir, plugin.file.nameWithoutExtension + "-jvm.jar").delete()
                    if (parentDir != null && parentDir.listFiles()?.isEmpty() == true) {
                        parentDir.delete()
                    }
                } catch (e: Throwable) {
                    AppLogger.e("ExtensionsViewModel", "Error uninstalling plugin: ${e.message}")
                }
            }
            refreshInstalled()
        }
    }

    fun loadLocalPlugin(file: File) {
        coroutineScope.launch(Dispatchers.IO) {
            val targetDir = File(DesktopRepositoryManager.getExtensionsDir(), "Local_Sandbox")
            targetDir.mkdirs()
            val targetFile = File(targetDir, file.name)
            try {
                file.copyTo(targetFile, overwrite = true)
                ExtensionLoader.loadAndInit(targetFile)
            } catch (e: SecurityException) {
                AppLogger.w("ExtensionsViewModel", "Security sandbox blocked local plugin ${file.name}: ${e.message}")
                _localPluginRequiringBypass.value = targetFile
            } catch (e: Exception) {
                AppLogger.e("ExtensionsViewModel", "Error loading local plugin ${file.name}: ${e.message}")
                targetFile.delete()
            }
            refreshInstalled()
        }
    }

    fun bypassSecurityAndLoadLocalPlugin() {
        val pendingFile = _localPluginRequiringBypass.value ?: return
        _localPluginRequiringBypass.value = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                ExtensionLoader.loadAndInit(pendingFile, forceBypassSecurity = true)
            } catch (e: Throwable) {
                AppLogger.e("ExtensionsViewModel", "Failed to load local plugin with bypass: ${e.message}")
            }
            refreshInstalled()
        }
    }
}
