// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.DesktopContextProvider
import android.content.Intent
import android.os.Bundle
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_PLAYER
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_REPO
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_RESUME_WATCHING
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SEARCH
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SHARE
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_NAVIGATE_TO
import com.lagradost.cloudstream3.ui.player.BasicLink
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Job
import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import kotlin.reflect.full.createInstance

@JsonIgnoreProperties(ignoreUnknown = true)
@Serializable
data class CustomSite(
    @JsonProperty("parentClassName") @JsonAlias("parentJavaClass")
    @SerialName("parentClassName") @JsonNames("parentJavaClass")
    val parentClassName: String,
    @JsonProperty("name") @SerialName("name") val name: String,
    @JsonProperty("url") @SerialName("url") val url: String,
    @JsonProperty("lang") @SerialName("lang") val lang: String = "en",
) {
    @get:JsonIgnore
    val parentJavaClass: String
        get() = parentClassName
}

open class MainActivity : Activity() {
    companion object {
        const val TAG = "MAINACT"
        const val ANIMATED_OUTLINE: Boolean = false
        const val API_NAME_EXTRA_KEY = "API_NAME_EXTRA_KEY"
        private const val FILE_DELETE_KEY = "FILES_TO_DELETE_KEY"

        var lastError: String? = null

        @PlatformQuarantine(
            reason = "Android ActivityResult sözleşmesi. Masaüstünde AWT / XDG Portal dosya seçiciye uyarlanmıştır.",
            upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:199",
            status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
        )
        var activityResultLauncher: Any? = null

        /** Update lastError variable based on error file, to check if app crashed.
         * Can be called multiple times without changing the lastError variable changing.
         **/
        fun setLastError(context: Context) {
            if (lastError != null) return

            val errorFile = context.filesDir.resolve("last_error")
            if (errorFile.exists() && errorFile.isFile) {
                lastError = errorFile.readText(Charset.defaultCharset())
                val deleted = errorFile.delete()
                AppLogger.d(TAG, "Processed last_error crash diagnostic file (deleted=$deleted)")
            } else {
                lastError = null
            }
        }

        /**
         * Transient files to delete on application exit.
         * Deletes files on onDestroy() and JVM shutdown hook.
         */
        private var filesToDelete: Set<String>
            get() = CloudStreamApp.getKey<Set<String>>(FILE_DELETE_KEY)
                ?: CloudStreamApp.getKey<Array<String>>(FILE_DELETE_KEY)?.toSet()
                ?: emptySet()
            private set(value) = CloudStreamApp.setKey(FILE_DELETE_KEY, value)

        /**
         * Add file to delete on Exit.
         */
        fun deleteFileOnExit(file: File) {
            try {
                file.deleteOnExit()
            } catch (t: Throwable) {
                AppLogger.d(TAG, "file.deleteOnExit failed for ${file.path}: ${t.message}")
            }
            filesToDelete = filesToDelete + file.path
        }

        /**
         * Recursively deletes all files registered via deleteFileOnExit.
         * Upstream Parity: onDestroy() file purge loop.
         */
        fun cleanUpFilesToDelete() {
            val pending = filesToDelete
            if (pending.isEmpty()) return

            AppLogger.i(TAG, "Executing exit file cleanup for ${pending.size} file(s)...")
            pending.forEach { path ->
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val deleted = file.deleteRecursively()
                        if (deleted) {
                            AppLogger.d(TAG, "Deleted temporary file: $path")
                        } else {
                            AppLogger.w(TAG, "Failed to delete temporary file: $path")
                        }
                    }
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Error cleaning up temporary file $path: ${t.message}")
                }
            }
            filesToDelete = emptySet()
        }

        @Volatile
        private var shutdownHookRegistered = false

        /**
         * Registers a JVM shutdown hook to ensure pending temporary files are purged
         * even when the desktop client exits without a formal Activity lifecycle.
         */
        fun registerShutdownHook() {
            if (shutdownHookRegistered) return
            synchronized(this) {
                if (shutdownHookRegistered) return
                try {
                    Runtime.getRuntime().addShutdownHook(Thread({
                        cleanUpFilesToDelete()
                        try {
                            com.lagradost.cloudstream3.loader.PluginShadowManager.cleanRuntimeCache()
                        } catch (t: Throwable) {
                            AppLogger.d(TAG, "Shutdown shadow cache cleanup warning: ${t.message}")
                        }
                    }, "CloudStream-ExitCleanup"))
                    shutdownHookRegistered = true
                    AppLogger.d(TAG, "Registered JVM shutdown hook for exit file cleanup.")
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Failed to register JVM shutdown hook: ${t.message}")
                }
            }
        }

        /**
         * Fires every time a new batch of plugins have been loaded, no guarantee about how often this is run and on which thread.
         * Boolean signifies if stuff should be force reloaded (true if force reload, false if reload when necessary).
         */
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()

        init {
            registerShutdownHook()
            afterPluginsLoadedEvent += { onAllPluginsLoaded(it) }
        }

        /**
         * Setting this will automatically enter the query in the search
         * next time the search fragment is opened.
         * This variable will clear itself after one use. Null does nothing.
         **/
        var nextSearchQuery: String? = null

        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()

        // Desktop Intent Navigation & Action Events
        val searchIntentEvent = Event<String?>()
        val playerIntentEvent = Event<BasicLink>()
        val resumeWatchingIntentEvent = Event<Int>()
        val loadResultIntentEvent = Event<Triple<String, String, String>>()
        val navigateDownloadsEvent = Event<Boolean>()
        val magnetIntentEvent = Event<String>()
        val backPressedEvent = Event<Unit>()
        val backStackHistory = mutableListOf<String>()

        /**
         * Navigates back in history stack, checking active activity dispatcher or firing backPressedEvent.
         */
        fun navigateBack(): Boolean {
            val currentAct = CommonActivity.activity as? MainActivity
            if (currentAct != null && currentAct.onBackPressedDispatcher.hasEnabledCallbacks()) {
                return currentAct.onBackPressedDispatcher.onBackPressed()
            }
            backPressedEvent.invoke(Unit)
            return backStackHistory.removeLastOrNull() != null
        }

        /**
         * Lifecycle callback invoked when all plugins are loaded.
         * Connects directly to APIRepository.syncProviders() to populate plugin providers on startup.
         */
        fun onAllPluginsLoaded(success: Boolean = false) {
            APIRepository.syncProviders()
        }

        /**
         * @return true if the str has launched an app task (be it successful or not)
         * @param isWebview does not handle providers and opening download page if true. Can still add repos and login.
         */
        fun handleAppIntentUrl(
            activity: Activity?,
            str: String?,
            isWebview: Boolean,
            extraArgs: Bundle? = null
        ): Boolean {
            fun safeURI(uri: String): URI? = safe { URI(uri) }

            if (str != null) {
                if (str.startsWith("https://cs.repo") || str.startsWith("http://cs.repo") || str.startsWith("cs.repo")) {
                    val rawUrl = if (str.contains("?")) str.substringAfter("?") else str
                    val realUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                        rawUrl
                    } else {
                        "https://$rawUrl"
                    }
                    AppLogger.i(TAG, "Repository url: $realUrl")
                    loadRepository(activity, realUrl)
                    return true
                } else if (str.startsWith("magnet:")) {
                    AppLogger.i(TAG, "Magnet link received: ${str.take(60)}...")
                    val uri = safeURI(str)
                    val name = try {
                        uri?.query?.split("&")?.firstOrNull { it.startsWith("dn=") }?.substringAfter("dn=")?.let {
                            URLDecoder.decode(it, "UTF-8")
                        }
                    } catch (_: Throwable) { null } ?: "Magnet Stream"
                    magnetIntentEvent.invoke(str)
                    playerIntentEvent.invoke(BasicLink(str, name))
                    return true
                } else if (str.endsWith(".torrent", ignoreCase = true) || str.contains(".torrent?")) {
                    AppLogger.i(TAG, "Torrent URL received: $str")
                    val name = str.substringAfterLast("/").substringBefore("?")
                    magnetIntentEvent.invoke(str)
                    playerIntentEvent.invoke(BasicLink(str, name))
                    return true
                } else if (str.contains(APP_STRING)) {
                    for (api in AccountManager.allApis) {
                        if (api.isValidRedirectUrl(str)) {
                            ioSafe {
                                AppLogger.i(TAG, "handleAppIntent $str")
                                try {
                                    val isSuccessful = api.login(str)
                                    if (isSuccessful) {
                                        AppLogger.i(TAG, "authenticated ${api.name}")
                                    } else {
                                        AppLogger.i(TAG, "failed to authenticate ${api.name}")
                                    }
                                    CommonActivity.showToast("Authenticated: ${api.name}")
                                } catch (t: Throwable) {
                                    logError(t)
                                    CommonActivity.showToast("Authentication failed: ${api.name}")
                                }
                            }
                            return true
                        }
                    }
                    // This specific intent is used for gradle deployWithAdb / hot reload
                    if (str == "$APP_STRING:") {
                        ioSafe {
                            PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(
                                activity ?: DesktopContextProvider.context
                            )
                        }
                        return true
                    }
                    if (str.startsWith("$APP_STRING://search") || str.startsWith("$APP_STRING:search")) {
                        val query = if (str.contains("query=")) {
                            str.substringAfter("query=").substringBefore("&")
                        } else if (str.contains("q=")) {
                            str.substringAfter("q=").substringBefore("&")
                        } else {
                            str.substringAfter("search").removePrefix("://").removePrefix("/").removePrefix(":")
                        }
                        nextSearchQuery = try {
                            URLDecoder.decode(query, "UTF-8")
                        } catch (t: Throwable) {
                            logError(t)
                            query
                        }
                        searchIntentEvent.invoke(nextSearchQuery)
                        return true
                    }
                    if (str.startsWith("$APP_STRING://downloads") || str.startsWith("$APP_STRING://download") ||
                        str.startsWith("$APP_STRING:downloads") || str.startsWith("$APP_STRING:download")
                    ) {
                        navigateDownloadsEvent.invoke(true)
                        return true
                    }
                    if (str.startsWith("$APP_STRING://continuewatching") || str.startsWith("$APP_STRING:continuewatching")) {
                        val idStr = if (str.contains("id=")) {
                            str.substringAfter("id=").substringBefore("&")
                        } else {
                            str.substringAfter("continuewatching").removePrefix("://").removePrefix("/").removePrefix(":")
                        }
                        val id = idStr.toIntOrNull()
                        if (id != null) {
                            resumeWatchingIntentEvent.invoke(id)
                            return true
                        }
                    }
                    if (str.startsWith("$APP_STRING://player") || str.startsWith("$APP_STRING:player")) {
                        try {
                            val uri = URI(str)
                            val queryMap = uri.query?.split("&")?.associate {
                                val pair = it.split("=", limit = 2)
                                pair[0] to (if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else "")
                            } ?: emptyMap()
                            val name = queryMap["name"]
                            val url = queryMap["url"] ?: URLDecoder.decode(uri.authority ?: uri.host ?: "", "UTF-8")
                            playerIntentEvent.invoke(BasicLink(url, name))
                            return true
                        } catch (t: Throwable) {
                            logError(t)
                            return false
                        }
                    }
                } else if (safeURI(str)?.scheme == APP_STRING_REPO) {
                    val url = str.replaceFirst(APP_STRING_REPO, "https")
                    loadRepository(activity, url)
                    return true
                } else if (safeURI(str)?.scheme == APP_STRING_SEARCH ||
                    str.startsWith("cloudstreamsearch://") ||
                    str.startsWith("cloudstreamappsearch://")
                ) {
                    val query = if (str.contains("://")) str.substringAfter("://") else str.substringAfter("$APP_STRING_SEARCH:")
                    nextSearchQuery = try {
                        URLDecoder.decode(query, "UTF-8")
                    } catch (t: Throwable) {
                        logError(t)
                        query
                    }
                    searchIntentEvent.invoke(nextSearchQuery)
                    return true
                } else if (safeURI(str)?.scheme == APP_STRING_PLAYER) {
                    try {
                        val uri = URI(str)
                        val queryMap = uri.query?.split("&")?.associate {
                            val pair = it.split("=", limit = 2)
                            pair[0] to (if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else "")
                        } ?: emptyMap()
                        val name = queryMap["name"]
                        val url = URLDecoder.decode(uri.authority ?: uri.host ?: "", "UTF-8")
                        playerIntentEvent.invoke(BasicLink(url, name))
                        return true
                    } catch (t: Throwable) {
                        logError(t)
                        return false
                    }
                } else if (safeURI(str)?.scheme == APP_STRING_RESUME_WATCHING) {
                    val id = str.substringAfter("$APP_STRING_RESUME_WATCHING://").toIntOrNull()
                    if (id != null) {
                        resumeWatchingIntentEvent.invoke(id)
                        return true
                    }
                    return false
                } else if (str.startsWith(APP_STRING_SHARE)) {
                    try {
                        val data = str.substringAfter("$APP_STRING_SHARE:")
                        val parts = data.split("?", limit = 2)
                        val url = String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8)
                        val api = String(Base64.getDecoder().decode(parts[0]), Charsets.UTF_8)
                        loadResultIntentEvent.invoke(Triple(url, api, ""))
                        return true
                    } catch (e: Exception) {
                        CommonActivity.showToast("Invalid Uri")
                        return false
                    }
                } else if (!isWebview) {
                    if (str.startsWith(DOWNLOAD_NAVIGATE_TO)) {
                        navigateDownloadsEvent.invoke(true)
                        return true
                    } else {
                        val apiName = extraArgs?.getString(API_NAME_EXTRA_KEY)?.takeIf { it.isNotBlank() }
                        if (apiName != null) {
                            loadResultIntentEvent.invoke(Triple(str, apiName, ""))
                            return true
                        }

                        val matchedApi = APIHolder.apis.withLock {
                            APIHolder.apis.firstOrNull { str.startsWith(it.mainUrl) }
                        }
                        if (matchedApi != null) {
                            loadResultIntentEvent.invoke(Triple(str, matchedApi.name, ""))
                            return true
                        }
                    }
                }
                if (com.lagradost.cloudstream3.ui.player.DownloadedPlayerActivity.handleFileArgument(str, activity)) {
                    return true
                }
            }
            return false
        }

        fun handleAppIntent(intent: Intent?) {
            if (intent == null) return
            val str = intent.data?.toString() ?: intent.getStringExtra("data") ?: intent.action
            handleAppIntentUrl(null, str, false, null)
        }

        fun loadRepository(activity: Activity?, url: String) {
            ioSafe {
                try {
                    val repo = RepositoryManager.parseRepository(url)
                    if (repo != null) {
                        val data = RepositoryData(
                            repo.iconUrl ?: "",
                            repo.name,
                            url
                        )
                        RepositoryManager.addRepository(data)
                        CommonActivity.showToast("Repository added: ${repo.name}")
                        afterRepositoryLoadedEvent.invoke(true)
                    }
                } catch (t: Throwable) {
                    logError(t)
                    CommonActivity.showToast("Failed to add repository: ${t.message}")
                }
            }
        }

        suspend fun checkGithubConnectivity(): Boolean {
            return try {
                app.get(
                    "https://raw.githubusercontent.com/recloudstream/.github/master/connectivitycheck",
                    timeout = 5
                ).text.trim() == "ok"
            } catch (t: Throwable) {
                AppLogger.d(TAG, "GitHub connectivity check failed: ${t.message}")
                false
            }
        }

        @PlatformQuarantine(
            reason = "Android View scrolling/centering replaced by TvFocus in Compose Desktop",
            upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:425-441",
            status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
        )
        fun centerView(view: Any?) {
            AppLogger.d(TAG, "centerView is quarantined on desktop; handled by TvFocus.")
        }
    }

    class OnBackPressedDispatcher(private val activity: Activity? = null) {
        private val callbacks = mutableListOf<Pair<String?, OnBackPressedCallback>>()

        fun addCallback(callback: OnBackPressedCallback) {
            callbacks.add(null to callback)
        }

        fun addCallback(id: String, callback: OnBackPressedCallback) {
            callbacks.removeAll { it.first == id }
            callbacks.add(id to callback)
        }

        fun removeCallback(id: String) {
            callbacks.removeAll { it.first == id }
        }

        fun removeCallback(callback: OnBackPressedCallback) {
            callbacks.removeAll { it.second == callback }
        }

        fun hasEnabledCallbacks(): Boolean = callbacks.any { it.second.isEnabled }

        fun onBackPressed(): Boolean {
            for (i in callbacks.indices.reversed()) {
                val cb = callbacks[i].second
                if (cb.isEnabled) {
                    cb.handleOnBackPressed()
                    return true
                }
            }
            return false
        }
    }

    abstract class OnBackPressedCallback(var isEnabled: Boolean = true) {
        abstract fun handleOnBackPressed()
        open fun remove() {
            isEnabled = false
        }
    }

    class CallbackHelper(
        private val activity: Activity?,
        private val callback: OnBackPressedCallback
    ) {
        fun runDefault() {
            val wasEnabled = callback.isEnabled
            callback.isEnabled = false
            try {
                (activity as? MainActivity)?.onBackPressedDispatcher?.onBackPressed()
            } finally {
                callback.isEnabled = wasEnabled
            }
        }
    }

    val onBackPressedDispatcher = OnBackPressedDispatcher(this)

    fun attachBackPressedCallback(
        id: String,
        callback: CallbackHelper.() -> Unit
    ) {
        val newCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                CallbackHelper(this@MainActivity, this).callback()
            }
        }
        onBackPressedDispatcher.addCallback(id, newCallback)
    }

    fun detachBackPressedCallback(id: String) {
        onBackPressedDispatcher.removeCallback(id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setLastError(this)
        attachBackPressedCallback("MainActivityDefault") {
            runDefault()
        }
    }

    fun onAllPluginsLoaded(success: Boolean = false) {
        Companion.onAllPluginsLoaded(success)
    }

    override fun onResume() {
        afterPluginsLoadedEvent += ::onAllPluginsLoaded
        CommonActivity.setActivityInstance(this)
    }

    override fun onPause() {
        afterPluginsLoadedEvent -= ::onAllPluginsLoaded
    }

    override fun onDestroy() {
        cleanUpFilesToDelete()
        afterPluginsLoadedEvent -= ::onAllPluginsLoaded
        detachBackPressedCallback("MainActivityDefault")
    }

    override fun onNewIntent(intent: Intent) {
        handleAppIntent(intent)
    }

    open fun onBackPressed() {
        if (!onBackPressedDispatcher.onBackPressed()) {
            backPressedEvent.invoke(Unit)
            finish()
        }
    }

    @PlatformQuarantine(
        reason = "Android View preview popup dialog replaced by Compose Desktop TwoPaneDetailsDrawer",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:446-471",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    var lastPopup: SearchResponse? = null
    var lastPopupJob: Job? = null

    @PlatformQuarantine(
        reason = "Android View preview popup dialog replaced by Compose Desktop TwoPaneDetailsDrawer",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:446-471",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun loadPopup(result: SearchResponse, load: Boolean = true) {
        lastPopup = result
        AppLogger.d(TAG, "loadPopup for ${result.name} - handled by Compose Desktop drawer")
    }

    @PlatformQuarantine(
        reason = "Android Picture-in-Picture window mode is not applicable on Linux desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:677-681",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    override fun onUserLeaveHint() {
        AppLogger.d(TAG, "onUserLeaveHint (PiP) not applicable on desktop.")
    }

    @PlatformQuarantine(
        reason = "Biometric fingerprint authentication is not applicable to Linux desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:2057-2060",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun onAuthenticationSuccess() {
        AppLogger.d(TAG, "onAuthenticationSuccess not applicable on desktop.")
    }

    @PlatformQuarantine(
        reason = "Biometric fingerprint authentication is not applicable to Linux desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:2062-2064",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    fun onAuthenticationError() {
        AppLogger.d(TAG, "onAuthenticationError not applicable on desktop.")
    }

    @PlatformQuarantine(
        reason = "Android ColorPickerDialog is replaced by Compose desktop color picker",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:473-479",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun onColorSelected(dialogId: Int, color: Int) {
        AppLogger.d(TAG, "onColorSelected quarantined: dialogId=$dialogId, color=$color")
    }

    @PlatformQuarantine(
        reason = "Android ColorPickerDialog is replaced by Compose desktop color picker",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt:477-479",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    fun onDialogDismissed(dialogId: Int) {
        AppLogger.d(TAG, "onDialogDismissed quarantined: dialogId=$dialogId")
    }
}
