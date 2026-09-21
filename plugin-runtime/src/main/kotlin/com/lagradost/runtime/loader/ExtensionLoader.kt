package com.lagradost.runtime.loader

import android.content.DesktopContextProvider
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.googlecode.dex2jar.tools.Dex2jarCmd
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

object ExtensionLoader {

    private val mapper = ObjectMapper().registerModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // Keep track of loaded plugins by absolute path
    val plugins: MutableMap<String, BasePlugin> = mutableMapOf()

    // Map class loader to plugin name
    val classLoaders: MutableMap<ClassLoader, String> = ConcurrentHashMap()

    fun getCallingPluginName(): String? {
        val stackTrace = Thread.currentThread().stackTrace
        for (element in stackTrace) {
            val className = element.className
            if (className.startsWith("com.lagradost.") || className.startsWith("java.") || className.startsWith("kotlin.")) continue

            for ((loader, name) in classLoaders) {
                try {
                    val clazz = Class.forName(className, false, loader)
                    if (clazz.classLoader == loader) return name
                } catch (e: ClassNotFoundException) {
                    AppLogger.d("ExtensionLoader", "Class $className not found in loader: ${e.message}")
                }
            }
        }
        return null
    }

    // Native plugin interceptors
    var nativePluginInterceptor: ((String) -> BasePlugin?)? = null

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun loadJar(jarFile: File, fallbackPluginClassName: String? = null, forceBypassSecurity: Boolean = false): BasePlugin {
        if (!jarFile.exists()) {
            throw IllegalArgumentException("Jar file does not exist: ${jarFile.absolutePath}")
        }

        // Some Android plugins capture CommonActivity.activity in their constructor.
        // It must exist before the plugin class is instantiated, not only before load().
        try {
            com.lagradost.cloudstream3.CommonActivity.activity =
                DesktopContextProvider.context as? android.app.Activity
        } catch (t: Throwable) {
            AppLogger.w("ExtensionLoader", "Could not initialize desktop activity bridge: ${t.message}")
        }

        var pluginClassName = fallbackPluginClassName
        var internalNameFromManifest: String? = null
        var nameFromManifest: String? = null
        var jarToLoad = jarFile

        ZipFile(jarFile).use { zip ->
            // Extract manifest to get actual class name and internal identifier
            val manifestEntry = zip.getEntry("manifest.json")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { input ->
                    val manifestData = mapper.readValue(input, Map::class.java)
                    val className = manifestData["pluginClassName"] as? String
                    if (className != null) {
                        pluginClassName = className
                    }
                    internalNameFromManifest = manifestData["internalName"] as? String
                    nameFromManifest = manifestData["name"] as? String
                }
            }

            // Check if it's Dalvik bytecode (classes.dex present)
            val dexEntry = zip.getEntry("classes.dex")
            if (dexEntry != null) {
                val dexBytes = zip.getInputStream(dexEntry).use { it.readBytes() }
                val dexSha256 = calculateSha256(dexBytes)

                val pluginIdent = (internalNameFromManifest ?: nameFromManifest ?: jarFile.nameWithoutExtension)
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    .removeSuffix("-jvm")

                val transpiledCacheDir = PlatformPaths.transpiledCacheDir.toFile().apply { mkdirs() }
                val cachedJar = File(transpiledCacheDir, "$pluginIdent-$dexSha256.jar")

                if (cachedJar.exists() && cachedJar.length() > 0) {
                    AppLogger.i("ExtensionLoader", "Found cached transpiled JVM jar for $pluginIdent ($dexSha256), loading in < 15ms")
                    jarToLoad = cachedJar
                } else {
                    AppLogger.i("ExtensionLoader", "Transpiling Dalvik classes.dex to JVM jar for $pluginIdent (SHA-256: $dexSha256)...")
                    val tempDex = File.createTempFile("cs3-dex-", ".dex", transpiledCacheDir)
                    val tempJar = File.createTempFile("cs3-transpiled-", ".jar", transpiledCacheDir)

                    try {
                        tempDex.writeBytes(dexBytes)

                        try {
                            Dex2jarCmd().doMain("-f", tempDex.absolutePath, "-o", tempJar.absolutePath)
                        } catch (_: Exception) {
                            Dex2jarCmd.main("-f", tempDex.absolutePath, "-o", tempJar.absolutePath)
                        }

                        // Structurally rewrite the JAR to fix Kotlin inline class methods and neutralize dead UI
                        PluginBytecodeTransformer.transform(tempJar)

                        // Atomically move to target cache file
                        try {
                            Files.move(
                                tempJar.toPath(),
                                cachedJar.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Exception) {
                            Files.move(
                                tempJar.toPath(),
                                cachedJar.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                        }

                        jarToLoad = cachedJar
                    } finally {
                        tempDex.delete()
                        if (tempJar.exists()) tempJar.delete()
                    }
                }
            }
        }

        if (pluginClassName == null) {
            throw IllegalArgumentException("Could not determine pluginClassName from manifest.json and no fallback provided.")
        }

        AppLogger.i("ExtensionLoader", "Loading plugin class: $pluginClassName from ${jarToLoad.absolutePath}")

        val isReflectionTrusted = forceBypassSecurity || isTrusted(jarToLoad)

        if (!isReflectionTrusted) {
            AppLogger.i("ExtensionLoader", "Running static bytecode security verification on ${jarToLoad.name}...")
            com.lagradost.runtime.security.PluginSecurityVerifier.verifyJar(jarToLoad)
        } else {
            if (forceBypassSecurity) {
                addTrusted(jarToLoad)
            }
            AppLogger.i("ExtensionLoader", "Bypassing static bytecode security verification for trusted plugin ${jarToLoad.name}!")
        }

        val nativeIntercept = nativePluginInterceptor?.invoke(pluginClassName!!)
        val pluginInstance: BasePlugin = if (nativeIntercept != null) {
            AppLogger.i("ExtensionLoader", "Intercepted plugin $pluginClassName! Injecting native JVM implementation.")
            nativeIntercept
        } else {
            val shadowJar = com.lagradost.cloudstream3.loader.PluginShadowManager.createShadowCopy(
                jarToLoad,
                internalNameFromManifest ?: nameFromManifest
            )
            val safeParentLoader = SafePluginClassLoader(this::class.java.classLoader, isReflectionTrusted)
            val classLoader = URLClassLoader(arrayOf(shadowJar.toURI().toURL()), safeParentLoader)
            val pluginClass = classLoader.loadClass(pluginClassName)

            // MegaPlugin VerifiedRepo MixIn injection
            if (pluginClassName == "com.mega.MegaPlugin") {
                try {
                    val verifiedRepoClass = classLoader.loadClass("com.mega.MegaPlugin\$getRepositories\$VerifiedRepo")
                    com.lagradost.cloudstream3.mapper.addMixIn(verifiedRepoClass, VerifiedRepoMixIn::class.java)
                } catch (e: Exception) {
                    AppLogger.i("ExtensionLoader", "Failed to inject VerifiedRepo MixIn for MegaPlugin (it might not be loaded yet)")
                }
            }

            val instance = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
            val finalInternalName = internalNameFromManifest ?: nameFromManifest ?: pluginClassName?.split(".")?.lastOrNull() ?: jarFile.nameWithoutExtension.removeSuffix("-jvm")
            classLoaders[classLoader] = finalInternalName
            instance
        }

        pluginInstance.filename = jarFile.absolutePath
        plugins[jarFile.absolutePath] = pluginInstance

        return pluginInstance
    }

    private fun getTrustedList(): MutableList<String> {
        return try {
            DesktopDataStore.getKey("trusted_plugins", Array<String>::class.java)?.toMutableList()
                ?: mutableListOf()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    private fun isTrusted(jarFile: File): Boolean {
        val name = jarFile.nameWithoutExtension.removeSuffix("-jvm")
        return getTrustedList().contains(name)
    }

    private fun addTrusted(jarFile: File) {
        val name = jarFile.nameWithoutExtension.removeSuffix("-jvm")
        val trusted = getTrustedList()
        if (!trusted.contains(name)) {
            trusted.add(name)
            try {
                DesktopDataStore.setKey("trusted_plugins", trusted)
            } catch (t: Throwable) {
                AppLogger.w("ExtensionLoader", "Failed to save trusted plugins: ${t.message}")
            }
        }
    }

    fun loadPlugin(jarFile: File): BasePlugin = loadAndInit(jarFile)

    fun loadAndInit(jarFile: File, fallbackPluginClassName: String? = null, forceBypassSecurity: Boolean = false): BasePlugin {
        val pluginInstance = loadJar(jarFile, fallbackPluginClassName, forceBypassSecurity)
        initializePlugin(pluginInstance)
        return pluginInstance
    }

    fun initializePlugin(pluginInstance: BasePlugin) {
        if (pluginInstance is Plugin) {
            pluginInstance.load(DesktopContextProvider.context)
        } else {
            pluginInstance.load()
        }
    }

    fun unloadPlugin(absolutePath: String): Boolean {
        // Consolidated through canonical PluginManager 7-step Metaspace unloader (purges urlPlugins & APIHolder)
        return com.lagradost.cloudstream3.plugins.PluginManager.unloadPlugin(absolutePath)
    }

    fun isPluginLoaded(absolutePath: String): Boolean = plugins.containsKey(absolutePath)

    fun getPlugin(absolutePath: String): BasePlugin? = plugins[absolutePath]

    /**
     * Loads any extension jars on disk that are not already in memory (e.g. after sync/install).
     */
    fun rescanAndLoadNewPlugins(extensionsDir: File = PlatformPaths.pluginsDir.toFile()): Int {
        if (!extensionsDir.exists()) return 0

        var loaded = 0
        extensionsDir.walkTopDown()
            .filter { it.isFile && (it.extension == "jar" || it.extension == "cs3") }
            .filter { !it.name.endsWith("-jvm.jar") }
            .forEach { jar ->
                if (!isPluginLoaded(jar.absolutePath)) {
                    try {
                        loadAndInit(jar)
                        loaded++
                        AppLogger.i("ExtensionLoader", "Rescan: loaded ${jar.name}")
                    } catch (e: Throwable) {
                        AppLogger.i("ExtensionLoader", "Rescan: failed ${jar.name}: ${e.message}")
                    }
                }
            }
        return loaded
    }
}

abstract class VerifiedRepoMixIn {
    @com.fasterxml.jackson.annotation.JsonCreator
    constructor(
        @com.fasterxml.jackson.annotation.JsonProperty("url") url: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("verified") verified: Boolean?
    )
}
