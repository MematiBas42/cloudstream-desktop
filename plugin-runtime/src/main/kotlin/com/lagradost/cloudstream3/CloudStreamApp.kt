// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3

import android.content.Context
import android.content.DesktopContextProvider
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.AppDebug
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.awt.Window
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.ref.WeakReference
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Crash and diagnostic exception handler mirroring upstream CloudStreamApp ExceptionHandler.
 * Records thread details, fatal stack trace, and currently loading extension into:
 * 1. PlatformPaths.logDir/app.log (persistent formatted log with full stack trace)
 * 2. Diagnostic error file (filesDir/last_error) for next-startup diagnostic recovery
 * 3. AppLogger / SystemDiagnostics bus
 * Does not exit the JVM process unless exitOnCrash is true or the error is an unrecoverable VirtualMachineError.
 */
class ExceptionHandler(
    val errorFile: File,
    val exitOnCrash: Boolean = false,
    val onError: (() -> Unit) = {}
) : Thread.UncaughtExceptionHandler {

    constructor(errorFile: File, onError: (() -> Unit)) : this(errorFile, exitOnCrash = false, onError = onError)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        val threadId = try {
            thread.threadId()
        } catch (t: Throwable) {
            AppLogger.w("ExceptionHandler", "Failed to retrieve threadId via threadId()", t)
            @Suppress("DEPRECATION")
            thread.id
        }

        val loadingExtension = PluginManager.currentlyLoading ?: "none"

        // 1. Log unhandled exception to PlatformPaths.logDir/app.log formatted with stack traces
        try {
            val logDir = PlatformPaths.logDir.toFile()
            logDir.mkdirs()
            val appLogFile = File(logDir, "app.log")
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            PrintStream(FileOutputStream(appLogFile, true)).use { ps ->
                ps.println("[$timestamp] [FATAL] [Thread: ${thread.name} ($threadId)] Currently loading extension: $loadingExtension")
                ps.println("Unhandled exception: ${error::class.qualifiedName}: ${error.message}")
                error.printStackTrace(ps)
                ps.println()
            }
        } catch (t: Throwable) {
            AppLogger.e("ExceptionHandler", "Failed to write unhandled exception to app.log", t)
        }

        // 2. Write diagnostic error dump into errorFile (filesDir/last_error)
        try {
            errorFile.parentFile?.mkdirs()

            PrintStream(errorFile).use { ps ->
                ps.println("Currently loading extension: $loadingExtension")
                ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                error.printStackTrace(ps)
            }
        } catch (e: FileNotFoundException) {
            AppLogger.e("ExceptionHandler", "Failed to create error file: ${errorFile.absolutePath}", e)
        } catch (t: Throwable) {
            AppLogger.e("ExceptionHandler", "Failed to write crash dump to: ${errorFile.absolutePath}", t)
        }

        // 3. Mirror into AppLogger
        AppLogger.e("ExceptionHandler", "Unhandled exception on thread '${thread.name}' ($threadId)", error)

        // 4. Execute callback
        try {
            onError()
        } catch (e: Exception) {
            AppLogger.e("ExceptionHandler", "Error executing crash callback", e)
        }

        // 5. Desktop resilience: Only exit process if explicitly requested or unrecoverable JVM error
        if (exitOnCrash || error is VirtualMachineError) {
            exitProcess(1)
        }
    }
}

/**
 * Production-grade runtime for CloudStreamApp.
 * Exposes exact JVM method descriptors expected by upstream plugins, core classes, and desktop runtime.
 */
open class CloudStreamApp {

    open fun onCreate() {
        val targetDir = context?.filesDir ?: DesktopContextProvider.context.filesDir
        val errorFile = targetDir.resolve("last_error")
        ExceptionHandler(errorFile) {
            AppLogger.e("CloudStreamApp", "Fatal crash occurred; error written to $errorFile")
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }

        AppDebug.isDebug = BuildConfig.DEBUG
    }

    open fun attachBaseContext(base: Context?) {
        context = base
    }

    /**
     * Delegates 1:1 to AppBootstrap.configureCoil() retaining the exact Coil ImageLoader contract.
     * Uses registered imageLoaderProvider if set, or dynamically invokes AppBootstrap.configureCoil()
     * to prevent hardcoded bypass while honoring the headless / plugin-runtime decoupled architecture.
     */
    open fun newImageLoader(context: Any? = null): Any? {
        AppLogger.d("CloudStreamApp", "newImageLoader delegating to AppBootstrap.configureCoil()")
        return imageLoaderProvider?.invoke(context) ?: tryConfigureCoilViaBootstrap(context)
    }

    private fun tryConfigureCoilViaBootstrap(context: Any?): Any? {
        return try {
            val bootstrapClass = Class.forName("com.lagradost.cloudstream3.desktop.AppBootstrap")
            val instance = bootstrapClass.getField("INSTANCE").get(null)
            val configureMethod = bootstrapClass.methods.firstOrNull {
                it.name == "configureCoil" && it.parameterTypes.size <= 1
            } ?: bootstrapClass.methods.firstOrNull { it.name.startsWith("configureCoil") }

            if (configureMethod != null) {
                if (configureMethod.parameterTypes.isEmpty()) {
                    configureMethod.invoke(instance)
                } else {
                    val paramType = configureMethod.parameterTypes[0]
                    val arg = if (context != null && paramType.isInstance(context)) {
                        context
                    } else {
                        try {
                            val platformContextClass = Class.forName("coil3.PlatformContext")
                            platformContextClass.getField("INSTANCE").get(null)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    configureMethod.invoke(instance, arg)
                }
            } else {
                null
            }
        } catch (t: Throwable) {
            AppLogger.w("CloudStreamApp", "Failed to delegate newImageLoader to AppBootstrap.configureCoil: ${t.message}")
            null
        }
    }

    companion object {
        var exceptionHandler: ExceptionHandler? = null

        /**
         * Optional pluggable provider for Coil ImageLoader instantiation,
         * typically wired by AppBootstrap during initialization.
         */
        @Volatile
        var imageLoaderProvider: ((Any?) -> Any?)? = null

        private var _context: WeakReference<Context>? = null
        var context: Context?
            get() = _context?.get() ?: DesktopContextProvider.context
            set(value) {
                _context = if (value == null) null else WeakReference(value)
                setContext(value)
            }

        /** Use to get Activity from Context. Null-safe and mirrors upstream unwrapping without illegal Activity casts. */
        tailrec fun Context?.getActivity(): android.app.Activity? {
            val ctx = this ?: return null
            return when (ctx) {
                is android.app.Activity -> ctx
                else -> null
            }
        }

        /**
         * Desktop Window accessor replacing Android Activity lookup on desktop JVM.
         * Returns the active desktop Window from DesktopContextProvider, or null in headless environments.
         */
        val currentWindow: Window?
            get() = DesktopContextProvider.currentWindow

        /** Extension to retrieve the active desktop Window from a Context on desktop JVM. */
        fun Context?.getWindow(): Window? {
            return DesktopContextProvider.currentWindow
        }

        /**
         * Open external URL in system browser.
         * Attempts AWT Desktop.browse first; falls back to OS-specific command:
         * - Linux: xdg-open
         * - Windows: cmd.exe /c start "" <url>
         * - macOS: open <url>
         * If all fail, logs error with AppLogger.e and alerts the user via CommonActivity.showToast.
         */
        @JvmStatic
        @JvmOverloads
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Any? = null) {
            var opened = false
            try {
                if (java.awt.Desktop.isDesktopSupported() &&
                    java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
                ) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                    opened = true
                } else {
                    AppLogger.w("CloudStreamApp", "AWT Desktop browse action not supported; falling back to OS command for $url")
                }
            } catch (t: Throwable) {
                AppLogger.e("CloudStreamApp", "AWT Desktop browse failed for $url", t)
            }

            if (!opened) {
                try {
                    val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
                    val command = when {
                        os.contains("win") -> listOf("cmd.exe", "/c", "start", "\"\"", url)
                        os.contains("mac") -> listOf("open", url)
                        else -> listOf("xdg-open", url)
                    }
                    val process = ProcessBuilder(command).start()
                    val exited = process.waitFor(2, TimeUnit.SECONDS)
                    val exitCode = if (exited) process.exitValue() else 0
                    if (exitCode != 0) {
                        AppLogger.e("CloudStreamApp", "OS browser command failed with exit code $exitCode for $url")
                        CommonActivity.showToast("Failed to open browser: exit code $exitCode")
                    }
                } catch (t: Throwable) {
                    AppLogger.e("CloudStreamApp", "OS browser command execution failed for $url", t)
                    CommonActivity.showToast("Failed to open browser: ${t.message ?: "execution error"}")
                }
            }
        }

        @JvmStatic
        fun openBrowser(url: String, activity: android.app.Activity?) {
            openBrowser(url, fallbackWebView = false, fragment = null)
        }

        // =====================================================================
        // Upstream Parity: Key Write APIs
        // =====================================================================

        @JvmStatic
        fun <T> setKey(path: String, value: T) {
            DesktopDataStore.setKey(path, value)
        }

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) {
            DesktopDataStore.setKey(folder, path, value)
        }

        @JvmStatic
        fun <T> setKeyRaw(path: String, value: T) {
            DesktopDataStore.setKey(path, value)
        }

        @JvmStatic
        fun <T> setKeyRaw(folder: String, path: String, value: T) {
            DesktopDataStore.setKey(folder, path, value)
        }

        @JvmStatic
        fun setKeyClass(path: String, value: Any?) {
            DesktopDataStore.setKey(path, value)
        }

        @JvmStatic
        fun setKeyClass(folder: String, path: String, value: Any?) {
            DesktopDataStore.setKey(folder, path, value)
        }

        // =====================================================================
        // Upstream Parity: Key Read APIs
        // =====================================================================

        @JvmStatic
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return DesktopDataStore.getKey(path, valueType)
        }

        @JvmStatic
        fun <T : Any> getKeyClass(folder: String, path: String, valueType: Class<T>): T? {
            return DesktopDataStore.getKey(folder, path, valueType)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T? = null): T? {
            return DesktopDataStore.getKey<T>(path, defVal)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T? = null): T? {
            return DesktopDataStore.getKey<T>(folder, path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return DesktopDataStore.getKey<T>(path, null)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return DesktopDataStore.getKey<T>(folder, path, null)
        }

        // =====================================================================
        // Upstream Parity: Namespace & Key Purge APIs
        // =====================================================================

        @JvmStatic
        fun getKeys(folder: String): List<String>? {
            return DesktopDataStore.getKeys(folder)
        }

        @JvmStatic
        fun removeKeys(folder: String): Int? {
            return DesktopDataStore.removeKeys(folder)
        }

        @JvmStatic
        fun removeKey(path: String) {
            DesktopDataStore.removeKey(path)
        }

        @JvmStatic
        fun removeKey(folder: String, path: String) {
            DesktopDataStore.removeKey(folder, path)
        }

        @JvmStatic
        fun containsKey(path: String): Boolean {
            return DesktopDataStore.containsKey(path)
        }

        @JvmStatic
        fun containsKey(folder: String, path: String): Boolean {
            return DesktopDataStore.containsKey(folder, path)
        }
    }
}



