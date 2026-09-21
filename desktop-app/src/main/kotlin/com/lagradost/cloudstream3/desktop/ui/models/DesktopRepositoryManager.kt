package com.lagradost.cloudstream3.desktop.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.runtime.loader.ExtensionLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Industrial-grade extension repository and CDN routing manager for CloudStream Linux.
 * Fully compatible with upstream RepositoryManager contracts, jsDelivr CDN proxying,
 * parallel repository synchronization, and leak-free Metaspace/ClassLoader teardown.
 */
object DesktopRepositoryManager {
    private const val TAG = "DesktopRepositoryManager"
    const val REPOSITORIES_KEY = "REPOSITORIES_KEY"
    const val JSDELIVR_PROXY_KEY = "jsdelivr_proxy_key"
    private const val GITHUB_CHECK_URL = "https://raw.githubusercontent.com/recloudstream/.github/master/connectivitycheck"
    private const val RESERVED_CHARS = "|\\?*<\":>+[]/'"

    private val GH_REGEX =
        Regex("^https://raw.githubusercontent.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)/(.*)$")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private val redirectClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    private val mapper = ObjectMapper().registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val iconsFile by lazy { File(PlatformPaths.configDir.toFile(), "plugin_icons.json") }
    private val repoLock = Mutex()
    private val repoCache = ConcurrentHashMap<String, Repository>()
    private val pluginsCache = ConcurrentHashMap<String, List<SitePlugin>>()

    private val _savedRepositories = MutableStateFlow<List<RepositoryData>>(emptyList())
    val savedRepositories: StateFlow<List<RepositoryData>> = _savedRepositories.asStateFlow()

    val remotePluginIcons = MutableStateFlow<Map<String, String>>(emptyMap())
    val syncGeneration = MutableStateFlow(0)

    init {
        migrateLegacyReposFile()
        refreshSavedRepositories()
        val persistedIcons = readIconsFromDisk()
        if (persistedIcons.isNotEmpty()) {
            remotePluginIcons.value = persistedIcons
            AppLogger.i(TAG, "Loaded ${persistedIcons.size} persisted plugin icon(s) from disk.")
        }
    }

    // ============================================================================
    // Sanitization and Hashing (1:1 with Upstream PluginManager)
    // ============================================================================

    fun sanitizeFilename(name: String, removeSpaces: Boolean = false): String {
        var tempName = name
        for (c in RESERVED_CHARS) {
            tempName = tempName.replace(c, ' ')
        }
        if (removeSpaces) tempName = tempName.replace(" ", "")
        return tempName.replace(Regex(" +"), " ").trim(' ')
    }

    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(name, true) + "." + name.hashCode()
    }

    fun getExtensionsDir(): File = PlatformPaths.pluginsDir.toFile().apply { mkdirs() }

    fun getRepositoryFolder(repositoryUrl: String): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl)
        return File(getExtensionsDir(), folderName)
    }

    fun getPluginPath(internalName: String, repositoryUrl: String): File {
        val repoFolder = getRepositoryFolder(repositoryUrl)
        val fileName = getPluginSanitizedFileName(internalName)
        return File(repoFolder, "$fileName.cs3")
    }

    // ============================================================================
    // jsDelivr CDN Proxy & Network Routing
    // ============================================================================

    fun isJsDelivrProxyEnabled(): Boolean {
        return DesktopDataStore.getKey<Boolean>(JSDELIVR_PROXY_KEY) ?: false
    }

    fun setJsDelivrProxyEnabled(enabled: Boolean) {
        DesktopDataStore.setKey(JSDELIVR_PROXY_KEY, enabled)
        AppLogger.i(TAG, "jsDelivr CDN proxy set to: $enabled")
    }

    /**
     * Converts raw.githubusercontent.com URLs to cdn.jsdelivr.net if proxy is enabled (or forced).
     * Delegated to canonical upstream-parity RepositoryManager.
     */
    fun convertRawGitUrl(url: String, force: Boolean = false): String {
        return com.lagradost.cloudstream3.plugins.RepositoryManager.convertRawGitUrl(url, force)
    }

    /**
     * Probes direct GitHub raw connectivity. Returns false if blocked, timed out, or DNS poisoned.
     */
    suspend fun checkGithubConnectivity(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val request = Request.Builder()
                .url(GITHUB_CHECK_URL)
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string()?.trim() == "ok"
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "GitHub connectivity probe failed: ${t.message}")
            false
        }
    }

    /**
     * Boot-time probe to automatically turn on jsDelivr if GitHub is unreachable.
     */
    suspend fun autoDetectAndConfigureProxy(): Boolean = withContext(Dispatchers.IO) {
        val configured = DesktopDataStore.getKey<Boolean>(JSDELIVR_PROXY_KEY)
        if (configured == null) {
            val isGithubAvailable = checkGithubConnectivity()
            val shouldEnable = !isGithubAvailable
            setJsDelivrProxyEnabled(shouldEnable)
            if (shouldEnable) {
                AppLogger.i(TAG, "Censorship or timeout detected for raw.githubusercontent.com. Auto-enabled jsDelivr CDN proxy.")
            }
            return@withContext shouldEnable
        }
        return@withContext configured
    }

    // ============================================================================
    // Storage & Repository Lifecycle (DataStore + Mutex)
    // ============================================================================

    private fun migrateLegacyReposFile() {
        val legacyFile = File(getExtensionsDir(), "repos.json")
        if (legacyFile.exists()) {
            try {
                val repos = mapper.readValue(legacyFile, object : TypeReference<List<RepositoryData>>() {})
                if (repos.isNotEmpty()) {
                    val existing = getSavedRepositories()
                    val merged = (existing + repos).distinctBy { it.url }
                    DesktopDataStore.setKey(REPOSITORIES_KEY, merged.toTypedArray())
                    AppLogger.i(TAG, "Successfully migrated ${repos.size} repository record(s) from legacy repos.json into DesktopDataStore.")
                }
                legacyFile.delete()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to migrate legacy repos.json: ${e.message}")
            }
        }
    }

    private fun refreshSavedRepositories() {
        val stored = DesktopDataStore.getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()
        _savedRepositories.value = stored.toList()
    }

    fun getSavedRepositories(): List<RepositoryData> = _savedRepositories.value

    private fun saveDefaultRepositories() {
        val defaults = arrayOf(
            RepositoryData(name = "CloudStream 3 Repo", url = "https://cs3-repo.vercel.app/repo.json"),
            RepositoryData(name = "CloudStream 3 Repo (Mirror)", url = "https://cs3-repo.onrender.com/repo.json"),
        )
        DesktopDataStore.setKey(REPOSITORIES_KEY, defaults)
        _savedRepositories.value = defaults.toList()
    }

    suspend fun saveRepository(repository: RepositoryData) = repoLock.withLock {
        saveRepositoryInternal(repository)
    }

    private fun saveRepositoryInternal(repository: RepositoryData) {
        val incoming = normalizeRepositoryData(repository)
        val current = (DesktopDataStore.getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()).toMutableList()
        val index = current.indexOfFirst { it.url == incoming.url }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                name = if (incoming.name.isNotBlank() && incoming.name != incoming.url) incoming.name else existing.name,
                iconUrl = incoming.iconUrl ?: existing.iconUrl,
            )
        } else {
            current.add(incoming)
        }
        val distinct = current.distinctBy { it.url }.toTypedArray()
        DesktopDataStore.setKey(REPOSITORIES_KEY, distinct)
        _savedRepositories.value = distinct.toList()
    }

    private fun normalizeRepositoryData(data: RepositoryData): RepositoryData {
        val icon = data.iconUrl?.trim()?.takeIf { it.isNotEmpty() }
        val name = data.name.trim().ifEmpty { data.url }
        return data.copy(iconUrl = icon, name = name)
    }

    /**
     * Complete Production-Grade Repository Teardown:
     * 1. DataStore record removal.
     * 2. In-memory plugin unregistration and dynamic ClassLoader release (Metaspace free).
     * 3. Recursive directory purging on disk.
     * 4. Transpiled DEX JAR cache invalidation.
     * 5. In-memory manifest cache purge.
     */
    suspend fun removeRepository(repository: RepositoryData): Unit = withContext(Dispatchers.IO) {
        removeRepositoryInternal(repository.url)
    }

    fun removeRepository(url: String) {
        runBlocking(Dispatchers.IO) {
            removeRepositoryInternal(url)
        }
    }

    private suspend fun removeRepositoryInternal(repoUrl: String) = repoLock.withLock {
        // 1. Remove from Persistent Storage
        val current = DesktopDataStore.getKey<Array<RepositoryData>>(REPOSITORIES_KEY) ?: emptyArray()
        val updated = current.filter { it.url != repoUrl }.toTypedArray()
        DesktopDataStore.setKey(REPOSITORIES_KEY, updated)
        _savedRepositories.value = updated.toList()

        // 2. Unload running plugins and release ClassLoaders
        val repoFolder = getRepositoryFolder(repoUrl)
        if (repoFolder.exists() && repoFolder.isDirectory) {
            repoFolder.walkTopDown()
                .filter { it.isFile && (it.extension == "cs3" || it.extension == "jar") }
                .forEach { pluginFile ->
                    try {
                        AppLogger.i(TAG, "Unloading repository plugin: ${pluginFile.name}")
                        ExtensionLoader.unloadPlugin(pluginFile.absolutePath)

                        // Purge corresponding transpiled JVM cache
                        val transpiledDir = PlatformPaths.transpiledCacheDir.toFile()
                        if (transpiledDir.exists()) {
                            val prefix = pluginFile.nameWithoutExtension.substringBefore(".")
                            transpiledDir.listFiles { _, name -> name.startsWith(prefix) }?.forEach { cached ->
                                cached.delete()
                                AppLogger.i(TAG, "Deleted transpiled cache: ${cached.name}")
                            }
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error during plugin teardown (${pluginFile.name}): ${t.message}")
                    }
                }

            // 3. Delete directory tree on disk
            val deleted = repoFolder.deleteRecursively()
            AppLogger.i(TAG, "Purged repository directory '${repoFolder.absolutePath}': success=$deleted")
        }

        // 4. Invalidate memory caches
        val cachedRepo = repoCache.remove(repoUrl)
        cachedRepo?.pluginLists?.forEach { listUrl ->
            pluginsCache.remove(listUrl)
        }

        // 5. Signal reactive UI
        syncGeneration.value += 1
    }

    // ============================================================================
    // URL Resolution & Discovery
    // ============================================================================

    suspend fun parseRepoUrl(url: String): String? = withContext(Dispatchers.IO) {
        com.lagradost.cloudstream3.plugins.RepositoryManager.parseRepoUrl(url)
    }

    suspend fun fetchRepository(url: String): Repository? = withContext(Dispatchers.IO) {
        val resolved = parseRepoUrl(url) ?: url.trim().takeIf { it.startsWith("http") } ?: return@withContext null
        val proxyUrl = convertRawGitUrl(resolved)
        val request = Request.Builder().url(proxyUrl).build()

        return@withContext try {
            redirectClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                if (body.trimStart().startsWith("<")) return@withContext null
                mapper.readValue(body, Repository::class.java)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to fetch repository manifest from $proxyUrl: ${e.message}")
            null
        }
    }

    suspend fun fetchPlugins(pluginListUrl: String): List<SitePlugin> = withContext(Dispatchers.IO) {
        val proxyUrl = convertRawGitUrl(pluginListUrl)
        return@withContext try {
            val request = Request.Builder().url(proxyUrl).build()
            redirectClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                if (body.trimStart().startsWith("<")) return@withContext emptyList()

                mapper.readValue(body, object : TypeReference<List<SitePlugin>>() {})
                    .filter { it.status != 0 } // Exclude Down providers
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to fetch plugins from $proxyUrl: ${e.message}")
            emptyList()
        }
    }

    suspend fun getCachedRepository(url: String): Repository? {
        if (repoCache.containsKey(url)) return repoCache[url]
        val repo = fetchRepository(url)
        if (repo != null) repoCache[url] = repo
        return repo
    }

    suspend fun getCachedPlugins(listUrl: String): List<SitePlugin> {
        pluginsCache[listUrl]?.let { return it }
        val plugins = fetchPlugins(listUrl)
        pluginsCache[listUrl] = plugins
        updatePluginIcons(plugins)
        return plugins
    }

    private fun updatePluginIcons(plugins: List<SitePlugin>) {
        val newIcons = remotePluginIcons.value.toMutableMap()
        var hasNew = false
        plugins.forEach { remotePlugin ->
            val remoteIcon = remotePlugin.iconUrl
            if (!remoteIcon.isNullOrEmpty()) {
                if (newIcons[remotePlugin.internalName] != remoteIcon || newIcons[remotePlugin.name] != remoteIcon) {
                    newIcons[remotePlugin.internalName] = remoteIcon
                    newIcons[remotePlugin.name] = remoteIcon
                    hasNew = true
                }
            }
        }
        if (hasNew) {
            remotePluginIcons.value = newIcons
            persistIconsToDisk(newIcons)
        }
    }

    private fun readIconsFromDisk(): Map<String, String> {
        if (!iconsFile.exists()) return emptyMap()
        return try {
            mapper.readValue(iconsFile, object : TypeReference<Map<String, String>>() {})
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read plugin_icons.json: ${e.message}")
            emptyMap()
        }
    }

    private fun persistIconsToDisk(icons: Map<String, String>) {
        try {
            iconsFile.parentFile?.mkdirs()
            mapper.writeValue(iconsFile, icons)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to write plugin_icons.json: ${e.message}")
        }
    }

    suspend fun addRepositoryFromInput(inputUrl: String): List<Repository>? = withContext(Dispatchers.IO) {
        val trimmed = inputUrl.trim()
        if (trimmed.isEmpty()) return@withContext null
        val resolvedUrl = parseRepoUrl(trimmed) ?: return@withContext null

        val proxyUrl = convertRawGitUrl(resolvedUrl)
        val request = Request.Builder().url(proxyUrl).build()
        var body: String? = null
        try {
            redirectClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    body = response.body?.string()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to inspect repository at $proxyUrl: ${e.message}")
        }

        // MegaRepo handling
        if (body != null && body!!.trimStart().startsWith("[")) {
            try {
                val nodes = mapper.readTree(body!!)
                val urls = nodes.mapNotNull { it.get("url")?.asText() }
                val addedRepos = mutableListOf<Repository>()
                for (url in urls) {
                    val repo = addSingleRepository(url)
                    if (repo != null) addedRepos.add(repo)
                }
                return@withContext addedRepos.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to parse compound MegaRepo: ${e.message}")
            }
        }

        val repo = addSingleRepository(resolvedUrl)
        return@withContext if (repo != null) listOf(repo) else null
    }

    private suspend fun addSingleRepository(url: String): Repository? {
        val resolved = parseRepoUrl(url) ?: url
        val manifest = fetchRepository(resolved) ?: return null

        repoCache[resolved] = manifest
        saveRepository(
            RepositoryData(
                iconUrl = manifest.iconUrl,
                name = manifest.name,
                url = resolved,
            )
        )

        manifest.pluginLists.forEach { listUrl ->
            try {
                getCachedPlugins(listUrl)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to pre-fetch plugins for $listUrl: ${e.message}")
            }
        }
        return manifest
    }

    // ============================================================================
    // Cryptographic Integrity & Binary Download
    // ============================================================================

    fun sha256(file: File): String {
        return com.lagradost.cloudstream3.plugins.RepositoryManager.sha256(file)
    }

    /**
     * Downloads an extension package, enforces SHA-256 integrity against SitePlugin.fileHash,
     * writes atomically, and invokes ExtensionLoader.
     * Supports passing either repositoryUrl or repositoryName as the first argument.
     */
    suspend fun downloadPlugin(repositoryUrl: String, plugin: SitePlugin): File? = withContext(Dispatchers.IO) {
        val resolvedRepoUrl = getSavedRepositories().find { it.url == repositoryUrl || it.name == repositoryUrl }?.url
            ?: plugin.repositoryUrl
            ?: repositoryUrl

        val destFile = getPluginPath(plugin.internalName, resolvedRepoUrl)
        val parentDir = destFile.parentFile ?: return@withContext null
        parentDir.mkdirs()

        val tempFile = File.createTempFile(destFile.name, ".tmp", getExtensionsDir())
        val proxyUrl = convertRawGitUrl(plugin.url)

        try {
            val request = Request.Builder().url(proxyUrl).build()
            redirectClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} downloading plugin from $proxyUrl")
                val bodyStream = response.body?.byteStream() ?: throw IllegalStateException("Empty body from $proxyUrl")
                FileOutputStream(tempFile).use { out ->
                    bodyStream.copyTo(out)
                }
            }

            // Cryptographic Integrity Check
            if (!plugin.fileHash.isNullOrBlank()) {
                val computedHash = sha256(tempFile)
                if (!computedHash.equals(plugin.fileHash, ignoreCase = true)) {
                    tempFile.delete()
                    throw IllegalStateException("Hash mismatch for '${destFile.name}'! Expected: '${plugin.fileHash}', computed: '$computedHash'")
                }
            }

            // Unload prior version if already present in memory
            if (destFile.exists() && ExtensionLoader.isPluginLoaded(destFile.absolutePath)) {
                ExtensionLoader.unloadPlugin(destFile.absolutePath)
            }

            // Atomic Move
            try {
                Files.move(
                    tempFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile.toPath(),
                    destFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

            // Runtime Injection
            ExtensionLoader.loadPlugin(destFile)
            syncGeneration.value += 1
            return@withContext destFile
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to download and verify plugin '${plugin.name}': ${e.message}")
            if (tempFile.exists()) tempFile.delete()
            return@withContext null
        }
    }

    /**
     * Parallel repository synchronization with error isolation.
     * Each repository manifest and plugin list is fetched concurrently;
     * transient failures in one repository do not impede or cancel other repositories.
     */
    suspend fun syncAll(): Int = withContext(Dispatchers.IO) {
        val repos = getSavedRepositories()
        var updatedCount = 0

        coroutineScope {
            val deferreds = repos.map { repoData ->
                async {
                    try {
                        val manifest = fetchRepository(repoData.url)
                        if (manifest != null) {
                            repoCache[repoData.url] = manifest
                            val pluginListsCounts = coroutineScope {
                                manifest.pluginLists.map { listUrl ->
                                    async {
                                        try {
                                            val plugins = fetchPlugins(listUrl)
                                            pluginsCache[listUrl] = plugins
                                            updatePluginIcons(plugins)
                                            plugins.size
                                        } catch (e: Exception) {
                                            AppLogger.w(TAG, "Failed to fetch plugins from $listUrl for ${repoData.name}: ${e.message}")
                                            0
                                        }
                                    }
                                }.awaitAll()
                            }
                            pluginListsCounts.sum()
                        } else {
                            0
                        }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Error synchronizing ${repoData.name}: ${e.message}")
                        0
                    }
                }
            }
            updatedCount = deferreds.awaitAll().sum()
        }

        syncGeneration.value += 1
        return@withContext updatedCount
    }

    fun getAllPlugins(): List<Pair<String, SitePlugin>> {
        val list = mutableListOf<Pair<String, SitePlugin>>()
        val repos = getSavedRepositories()
        repos.forEach { repoData ->
            val manifest = repoCache[repoData.url]
            manifest?.pluginLists?.forEach { listUrl ->
                val plugins = pluginsCache[listUrl] ?: emptyList()
                plugins.forEach { p ->
                    list.add(Pair(repoData.name, p))
                }
            }
        }
        return list
    }
}
