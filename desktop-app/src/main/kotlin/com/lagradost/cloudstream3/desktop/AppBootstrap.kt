// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.desktop

import android.content.DesktopContextProvider
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.DownloaderTestImpl
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.desktop.observability.CoilObservabilityListener
import com.lagradost.cloudstream3.desktop.observability.NetworkObservabilityInterceptor
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.metaproviders.TraktProvider
import com.lagradost.cloudstream3.services.BackupWorkManager
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.ui.player.Torrent
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.logging.DiagnosticOrgan
import com.lagradost.common.logging.SystemDiagnostics
import com.lagradost.common.network.DohDnsResolver
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import com.lagradost.player.impl.MpvProcessLauncher
import com.lagradost.runtime.loader.ExtensionLoader
import org.schabi.newpipe.extractor.NewPipe
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * Manages plugin lifecycle and discovery on desktop.
 */
object PluginManager {
    fun loadInstalledPlugins(): Int {
        return com.lagradost.cloudstream3.plugins.PluginManager.loadInstalledPlugins()
    }

    fun unloadPlugin(absolutePath: String) {
        com.lagradost.cloudstream3.plugins.PluginManager.unloadPlugin(absolutePath)
    }
}

/**
 * Deterministic Application Lifecycle Bootstrapper.
 *
 * Boot Order:
 * 1. PlatformPaths.init() -> Strict Freedesktop XDG directories and POSIX permissions.
 * 1.1 Configure Windows native DLL search paths and JNA library resolution.
 * 2. DesktopDataStore.init() -> Atomic JSON datastore and watch history repository.
 * 3. Crash diagnostic check & exit cleanup -> setLastError, cleanUpFilesToDelete, registerShutdownHook.
 * 4. Configure deep observability state and global hooks.
 * 5. Coil3 ImageLoader -> Strict 256 MB disk cache in PlatformPaths.imageCacheDir and 25% heap.
 * 6. NewPipeExtractor bootstrap -> Connects DownloaderTestImpl for YouTube streams/trailers.
 * 7. Register built-in meta-providers into APIHolder.allProviders.
 * 8. PluginManager.loadInstalledPlugins() -> DEX-to-JAR transpilation and runtime loading.
 * 9. Fire plugin & repository lifecycle events -> mainPluginsLoadedEvent, afterPluginsLoadedEvent, afterRepositoryLoadedEvent.
 * 10. Start background daemons -> DownloadQueueManager, SubscriptionWorkManager, BackupWorkManager.
 * 11. Verify MPV system binary.
 * 12. Process initial command-line arguments / deep link intents.
 */
object AppBootstrap {

    @Volatile
    private var isInitialized = false

    fun init(args: Array<String> = emptyArray()) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return

            AppLogger.i("AppBootstrap", "Starting deterministic application bootstrap sequence...")

            // Step 1: Freedesktop XDG Base Directory initialization
            PlatformPaths.init()

            // Step 1.1: Native library search path configuration (Windows 64-bit DLLs & JNA setup)
            if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) {
                configureWindowsNativePaths()
            }

            // Step 2: Crash-resilient Atomic DataStore initialization
            DesktopDataStore.init()

            // Step 3: Check crash diagnostic file from prior run and clean up filesToDelete
            val context = DesktopContextProvider.context
            CloudStreamApp().onCreate()
            MainActivity.setLastError(context)
            if (MainActivity.lastError != null) {
                AppLogger.w("AppBootstrap", "Detected prior crash: ${MainActivity.lastError?.take(200)}")
            }
            MainActivity.cleanUpFilesToDelete()
            MainActivity.registerShutdownHook()
            // Step 3.1: Prune leftover temporary torrent chunks from prior runs
            ioSafe {
                Torrent.deleteAllFiles()
            }
            // Step 3.2: Clean leftover plugin shadow copies from prior runs or crashes
            try {
                com.lagradost.cloudstream3.loader.PluginShadowManager.cleanRuntimeCache()
            } catch (t: Throwable) {
                AppLogger.w("AppBootstrap", "Failed to clean shadow plugin runtime cache: ${t.message}")
            }

            // Step 4: Configure deep observability state and global hooks
            SystemDiagnostics.isEnabled = DesktopDataStore.getKey<Boolean>("deep_observability_enabled") ?: true
            installObservabilityHooks()

            // Step 5: Coil3 ImageLoader configuration (Strict 256 MB disk cache & 25% JVM heap)
            configureCoil()

            // Step 6: NewPipeExtractor engine bootstrap (must happen before plugins/providers)
            initNewPipeExtractor()

            // Step 7: Load installed plugins (Upstream parity: only real plugins populate allProviders)
            val loadedCount = PluginManager.loadInstalledPlugins()

            // Step 9: Fire plugin and repository lifecycle events
            val homeApi = DataStoreHelper.currentHomePage
            val homeLoaded = if (homeApi != null) {
                APIHolder.getApiFromNameNull(homeApi) != null
            } else {
                loadedCount > 0
            }
            MainActivity.mainPluginsLoadedEvent.invoke(homeLoaded)
            MainActivity.afterPluginsLoadedEvent.invoke(false)
            MainActivity.afterRepositoryLoadedEvent.invoke(true)
            AppLogger.i("AppBootstrap", "Fired mainPluginsLoadedEvent($homeLoaded), afterPluginsLoadedEvent(false), and afterRepositoryLoadedEvent(true).")

            // Step 10: Start background daemons (DownloadQueueManager, SubscriptionWorkManager, BackupWorkManager)
            try {
                DownloadQueueManager.init(context)
                SubscriptionWorkManager.enqueuePeriodicWork(context)
                BackupWorkManager.enqueuePeriodicWork(context, 24L)
                AppLogger.i("AppBootstrap", "Background daemons initialized (DownloadQueue, Subscriptions, Backup).")
            } catch (t: Throwable) {
                AppLogger.w("AppBootstrap", "Failed to initialize background daemons: ${t.message}")
            }

            // Step 11: Verify MPV system binary
            checkMpvSystem()

            // Step 12: Route CLI arguments or URI deep links to MainActivity intent handler
            if (args.isNotEmpty()) {
                AppLogger.i("AppBootstrap", "Processing ${args.size} command-line argument(s)...")
                args.forEach { arg ->
                    try {
                        val handled = MainActivity.handleAppIntentUrl(null, arg, false, null)
                        AppLogger.i("AppBootstrap", "CLI argument '$arg' handled: $handled")
                    } catch (t: Throwable) {
                        AppLogger.w("AppBootstrap", "Failed to handle CLI argument '$arg': ${t.message}")
                    }
                }
            }

            isInitialized = true
            AppLogger.i("AppBootstrap", "Application bootstrap completed successfully.")
        }
    }

    private fun installObservabilityHooks() {
        // Global Uncaught Exception Handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            SystemDiagnostics.record(
                organ = DiagnosticOrgan.CRASH,
                level = AppLogger.Level.ERROR,
                tag = "UncaughtException",
                message = "FATAL exception on thread '${thread.name}'",
                error = "${throwable::class.qualifiedName}: ${throwable.message}\n" + throwable.stackTraceToString().take(1500),
            )
            AppLogger.e("FATAL", "Unhandled exception on thread '${thread.name}'", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Global OkHttp Interceptor and DoH DNS resolver on both default and insecure clients
        runCatching {
            val interceptor = NetworkObservabilityInterceptor()
            val ddosGuardKiller = com.lagradost.cloudstream3.network.DdosGuardKiller(alwaysBypass = false)
            app.baseClient = app.baseClient.newBuilder()
                .dns(DohDnsResolver.INSTANCE)
                .addInterceptor(interceptor)
                .addInterceptor(ddosGuardKiller)
                .build()
            insecureApp.baseClient = insecureApp.baseClient.newBuilder()
                .dns(DohDnsResolver.INSTANCE)
                .addInterceptor(interceptor)
                .addInterceptor(ddosGuardKiller)
                .build()
            AppLogger.i("AppBootstrap", "DohDnsResolver, NetworkObservabilityInterceptor and DdosGuardKiller attached to global HTTP clients.")
        }.onFailure {
            AppLogger.w("AppBootstrap", "Failed to attach network interceptor: ${it.message}")
        }
    }

    /**
     * Initializes the NewPipeExtractor engine using DownloaderTestImpl for YouTube streams and trailers.
     */
    fun initNewPipeExtractor() {
        try {
            val downloader = DownloaderTestImpl.getInstance()
            if (downloader != null) {
                NewPipe.init(downloader)
                AppLogger.i("AppBootstrap", "NewPipeExtractor initialized with DownloaderTestImpl.")
            } else {
                AppLogger.w("AppBootstrap", "DownloaderTestImpl instance was null; NewPipeExtractor skipped.")
            }
        } catch (t: Throwable) {
            AppLogger.e("AppBootstrap", "Failed to initialize NewPipeExtractor: ${t.message}", t)
        }
    }

    /**
     * Upstream Parity:
     * Built-in meta-providers (TMDB, Trakt, CrossTmdbProvider) are metadata enrichment modules,
     * NOT user-facing streaming/content providers. Upstream CloudStream never injects them into
     * APIHolder.allProviders or APIHolder.apis. TitleMetadataEnricher instantiates TmdbProvider
     * locally on demand.
     */
    fun registerMetaProviders() {
        // No-op for 1:1 upstream parity.
    }

    /**
     * Builds and registers the singleton Coil 3 ImageLoader strictly bounded to:
     * - 25% JVM Heap memory cache
     * - 256 MB disk cache in $XDG_CACHE_HOME/cloudstream/images
     */
    fun configureCoil(context: PlatformContext = PlatformContext.INSTANCE): ImageLoader {
        val cacheDirFile = PlatformPaths.imageCacheDir.toFile().apply { mkdirs() }

        val imageLoader = ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDirFile.toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024) // Strictly 256 MB
                    .build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { request ->
                            val finalRequest = if (request.header("User-Agent") == null) {
                                request.newBuilder()
                                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .build()
                            } else {
                                request
                            }
                            try {
                                app.baseClient.newCall(finalRequest)
                            } catch (t: Throwable) {
                                AppLogger.d("AppBootstrap", "Fallback to default OkHttpClient: ${t.message}")
                                okhttp3.OkHttpClient().newCall(finalRequest)
                            }
                        }
                    )
                )
            }
            .crossfade(true)
            .eventListenerFactory(CoilObservabilityListener.Factory())
            .build()

        SingletonImageLoader.setSafe { imageLoader }
        CloudStreamApp.imageLoaderProvider = { imageLoader }
        AppLogger.i("AppBootstrap", "Coil3 configured with 25% heap and 256 MB disk cache at ${cacheDirFile.absolutePath}")
        return imageLoader
    }

    /**
     * Checks if the MPV executable or native library is accessible on the host system.
     */
    fun checkMpvSystem(): Boolean {
        val mpvPath = MpvProcessLauncher.findMpvExecutable()
        val isWindows = PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS
        val dllFile = if (isWindows) findMpvDll() else null

        val hasExecutable = mpvPath != null
        if (hasExecutable) {
            AppLogger.i("AppBootstrap", "MPV executable located at: $mpvPath")
        } else {
            AppLogger.w("AppBootstrap", "Warning: MPV executable not found on system PATH. Playback may fail.")
        }

        if (isWindows) {
            if (dllFile != null) {
                AppLogger.i("AppBootstrap", "MPV native 64-bit DLL located at: ${dllFile.absolutePath}")
            } else {
                AppLogger.w("AppBootstrap", "Warning: mpv-2.dll not found in native search paths or jna.library.path. In-process playback may require bundled DLLs.")
            }
        }

        return hasExecutable || dllFile != null
    }

    /**
     * Top-level entry point to configure native library bindings based on host operating system.
     */
    fun configureNativeLibraries(
        os: PlatformPaths.OS = PlatformPaths.currentOS,
        env: Map<String, String> = System.getenv(),
        workingDir: File = File(".").absoluteFile
    ): List<String> {
        return if (os == PlatformPaths.OS.WINDOWS) {
            configureWindowsNativePaths(env, workingDir)
        } else {
            emptyList()
        }
    }

    /**
     * Configures the JNA native library search path (`jna.library.path`) on Windows 64-bit environments.
     *
     * Resolves and binds:
     * 1. Primary application working directory and code source paths (`native/win64`, resources).
     * 2. Platform data and runtime native directories.
     * 3. System installation and package manager locations (%LOCALAPPDATA%, %ProgramFiles%, Scoop, Chocolatey).
     * 4. System PATH directories containing MPV native binaries or DLLs.
     *
     * Ensures JNA can reliably discover and load `mpv-2.dll` and its required MinGW/MSYS2 dependencies.
     */
    fun configureWindowsNativePaths(
        env: Map<String, String> = System.getenv(),
        workingDir: File = File(".").absoluteFile
    ): List<String> {
        AppLogger.i("AppBootstrap", "Configuring Windows 64-bit MPV native library search paths...")

        val searchDirs = resolveWindowsNativeSearchDirs(env, workingDir)

        // Identify directories that explicitly contain mpv-2.dll or related variants
        val dllNames = listOf("mpv-2.dll", "libmpv-2.dll", "mpv-1.dll", "libmpv-1.dll")
        val dllDirs = searchDirs.filter { dir ->
            dllNames.any { dllName ->
                File(dir, dllName).let { it.isFile && it.canRead() }
            }
        }

        val existingJnaPath = System.getProperty("jna.library.path").orEmpty()
        val existingPaths = existingJnaPath.split(File.pathSeparator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Priority ordering:
        // 1. Directories containing verified MPV DLLs
        // 2. Application and platform native directories
        // 3. Discovered system installation paths
        // 4. Pre-existing jna.library.path entries
        val combinedPaths = (dllDirs.map { it.absolutePath } +
                searchDirs.map { it.absolutePath } +
                existingPaths).distinct()

        val finalJnaPath = combinedPaths.joinToString(File.pathSeparator)
        System.setProperty("jna.library.path", finalJnaPath)

        // Register search paths directly with JNA NativeLibrary runtime if available on classpath
        runCatching {
            val nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary")
            val addSearchPathMethod = nativeLibraryClass.getMethod("addSearchPath", String::class.java, String::class.java)
            for (path in combinedPaths) {
                addSearchPathMethod.invoke(null, "mpv-2", path)
                addSearchPathMethod.invoke(null, "mpv", path)
                addSearchPathMethod.invoke(null, "libmpv-2", path)
            }
            AppLogger.d("AppBootstrap", "Registered native search paths with JNA NativeLibrary registry.")
        }.onFailure {
            AppLogger.d("AppBootstrap", "JNA NativeLibrary reflection not active: ${it.message}")
        }

        // Register primary directory with Win32 SetDllDirectory for MinGW/MSYS2 dependency resolution
        val primaryDir = dllDirs.firstOrNull()?.absolutePath ?: File(workingDir, "native/win64").absolutePath
        runCatching {
            val kernel32Class = Class.forName("com.sun.jna.platform.win32.Kernel32")
            val instance = kernel32Class.getField("INSTANCE").get(null)
            val setDllDirectoryMethod = kernel32Class.getMethod("SetDllDirectory", String::class.java)
            setDllDirectoryMethod.invoke(instance, primaryDir)
            AppLogger.i("AppBootstrap", "Configured Win32 SetDllDirectory: $primaryDir")
        }.onFailure {
            AppLogger.d("AppBootstrap", "Win32 SetDllDirectory reflection not active: ${it.message}")
        }

        val locatedDll = findMpvDll(combinedPaths)
        if (locatedDll != null) {
            AppLogger.i("AppBootstrap", "Discovered 64-bit MPV native DLL at: ${locatedDll.absolutePath}")
        } else {
            AppLogger.i("AppBootstrap", "Configured Windows jna.library.path with ${combinedPaths.size} directories (mpv-2.dll will resolve when installed).")
        }

        return combinedPaths
    }

    /**
     * Resolves all prospective directories for Windows 64-bit native libraries and dependencies.
     */
    fun resolveWindowsNativeSearchDirs(
        env: Map<String, String> = System.getenv(),
        workingDir: File = File(".").absoluteFile
    ): List<File> {
        val candidates = mutableListOf<File>()

        // 1. Application working directory native/win64
        val localNativeDir = File(workingDir, "native/win64")
        if (!localNativeDir.exists()) {
            runCatching { localNativeDir.mkdirs() }
        }
        candidates.add(localNativeDir)

        // 2. Code source / JAR execution base directory
        runCatching {
            val codeSource = AppBootstrap::class.java.protectionDomain?.codeSource
            val location = codeSource?.location?.toURI()?.let { File(it) }
            val baseDir = if (location?.isFile == true) location.parentFile else location
            if (baseDir != null) {
                candidates.add(File(baseDir, "native/win64"))
                candidates.add(File(baseDir, "app/native/win64"))
                candidates.add(File(baseDir, "runtime/bin"))
                candidates.add(File(baseDir, "bin"))
                candidates.add(baseDir)
            }
        }.onFailure {
            AppLogger.d("AppBootstrap", "CodeSource native directory resolution skipped: ${it.message}")
        }

        // 3. Project and resources native paths
        candidates.add(File(workingDir, "desktop-app/src/main/resources/native/win64"))
        candidates.add(File(workingDir, "src/main/resources/native/win64"))
        candidates.add(File(workingDir, "resources/native/win64"))

        // 4. PlatformPaths native runtime and data directories
        runCatching {
            candidates.add(PlatformPaths.dataDir.resolve("native").resolve("win64").toFile())
            candidates.add(PlatformPaths.runtimeDir.resolve("native").resolve("win64").toFile())
        }.onFailure {
            AppLogger.d("AppBootstrap", "PlatformPaths native directory resolution skipped: ${it.message}")
        }

        // 5. LOCALAPPDATA standard directories
        val localAppData = env["LOCALAPPDATA"]?.trim()?.takeIf { it.isNotEmpty() }
        if (localAppData != null) {
            candidates.add(File(localAppData, "Programs/mpv"))
            candidates.add(File(localAppData, "Programs/mpv.net"))
            candidates.add(File(localAppData, "mpv"))
            candidates.add(File(localAppData, "mpv.net"))
        }

        // 6. ProgramFiles, ProgramW6432, and ProgramFiles(x86) standard directories
        val programFiles = env["ProgramFiles"]?.trim()?.takeIf { it.isNotEmpty() }
        if (programFiles != null) {
            candidates.add(File(programFiles, "mpv"))
            candidates.add(File(programFiles, "mpv.net"))
        }
        val programW6432 = env["ProgramW6432"]?.trim()?.takeIf { it.isNotEmpty() }
        if (programW6432 != null) {
            candidates.add(File(programW6432, "mpv"))
            candidates.add(File(programW6432, "mpv.net"))
        }
        val programFilesX86 = env["ProgramFiles(x86)"]?.trim()?.takeIf { it.isNotEmpty() }
        if (programFilesX86 != null) {
            candidates.add(File(programFilesX86, "mpv"))
            candidates.add(File(programFilesX86, "mpv.net"))
        }

        // 7. Scoop package manager standard install paths
        val userProfile = env["USERPROFILE"]?.trim()?.takeIf { it.isNotEmpty() }
        if (userProfile != null) {
            candidates.add(File(userProfile, "scoop/apps/mpv/current"))
            candidates.add(File(userProfile, "scoop/apps/mpv-git/current"))
            candidates.add(File(userProfile, "scoop/shims"))
        }
        val programData = env["ProgramData"]?.trim()?.takeIf { it.isNotEmpty() }
        if (programData != null) {
            candidates.add(File(programData, "scoop/apps/mpv/current"))
            candidates.add(File(programData, "scoop/apps/mpv-git/current"))
            candidates.add(File(programData, "scoop/shims"))
        }

        // 8. Chocolatey package manager standard install paths
        val chocoInstall = env["ChocolateyInstall"]?.trim()?.takeIf { it.isNotEmpty() }
        if (chocoInstall != null) {
            candidates.add(File(chocoInstall, "bin"))
            candidates.add(File(chocoInstall, "lib/mpv.install/tools"))
            candidates.add(File(chocoInstall, "lib/mpv/tools"))
        }
        candidates.add(File("C:\\ProgramData\\chocolatey\\bin"))
        candidates.add(File("C:\\ProgramData\\chocolatey\\lib\\mpv.install\\tools"))
        candidates.add(File("C:\\ProgramData\\chocolatey\\lib\\mpv\\tools"))

        // 9. Root and Tools standard fallback paths
        candidates.add(File("C:\\mpv"))
        candidates.add(File("C:\\tools\\mpv"))
        candidates.add(File("C:\\tools\\mpv.net"))

        // 10. System PATH directories containing MPV binaries or DLLs
        val pathEnv = env["PATH"]?.trim()?.takeIf { it.isNotEmpty() }
        if (pathEnv != null) {
            val pathDirs = pathEnv.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it.trim()) }
            for (dir in pathDirs) {
                if (dir.isDirectory) {
                    val hasMpv = File(dir, "mpv.exe").exists() ||
                            File(dir, "mpv-2.dll").exists() ||
                            File(dir, "libmpv-2.dll").exists()
                    if (hasMpv) {
                        candidates.add(dir)
                    }
                }
            }
        }

        return candidates
            .map { it.absoluteFile }
            .filter { it.isDirectory || it == localNativeDir.absoluteFile }
            .distinct()
    }

    /**
     * Scans configured directories or provided search paths for 64-bit MPV dynamic link library (DLL).
     * Recognizes standard Windows naming conventions: `mpv-2.dll`, `libmpv-2.dll`, `mpv-1.dll`, `libmpv-1.dll`.
     */
    fun findMpvDll(searchPaths: List<String> = emptyList()): File? {
        val dllNames = listOf("mpv-2.dll", "libmpv-2.dll", "mpv-1.dll", "libmpv-1.dll")

        val directoriesToSearch = if (searchPaths.isNotEmpty()) {
            searchPaths.map { File(it) }
        } else {
            val fromJnaProp = System.getProperty("jna.library.path").orEmpty()
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it.trim()) }
            val resolvedDirs = resolveWindowsNativeSearchDirs()
            (fromJnaProp + resolvedDirs).distinct()
        }

        for (dir in directoriesToSearch) {
            if (!dir.isDirectory) continue
            for (dllName in dllNames) {
                val dllFile = File(dir, dllName)
                if (dllFile.isFile && dllFile.canRead()) {
                    return dllFile
                }
            }
        }

        return null
    }
}
