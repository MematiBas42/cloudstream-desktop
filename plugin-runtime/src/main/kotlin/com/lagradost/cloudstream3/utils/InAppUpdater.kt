// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    const val GITHUB_USER_NAME = "recloudstream"
    const val GITHUB_REPO = "cloudstream"

    const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"
    const val LOG_TAG = "InAppUpdater"

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String = "",
        @JsonProperty("size") @SerialName("size") val size: Int = 0, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String = "", // Version code
        @JsonProperty("body") @SerialName("body") val body: String = "", // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset> = emptyList(),
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String = "master", // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean = false,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String = "", // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String = "",
        @JsonProperty("url") @SerialName("url") val url: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject = GithubObject(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
    )

    // Event and StateFlow for Desktop Compose UI consumption
    val updateAvailableEvent = Event<Update>()
    private val _updateState = MutableStateFlow<Update?>(null)
    val updateState: StateFlow<Update?> = _updateState.asStateFlow()

    /**
     * Sanitizes changelog markdown by stripping [Link Text](url) down to Link Text.
     */
    fun sanitizeChangelog(changelog: String?): String? {
        if (changelog == null) return null
        val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
        return changelog.replace(logRegex) { matchResult ->
            matchResult.groupValues[1]
        }
    }

    /**
     * Parses semantic version into a comparable weighted integer:
     * major * 100_000_000 + minor * 10_000 + patch
     */
    fun parseVersionScore(versionStr: String?): Int? {
        if (versionStr == null) return null
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(versionStr) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: 0
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        val patch = match.groupValues[3].toIntOrNull() ?: 0
        return major * 100_000_000 + minor * 10_000 + patch
    }

    /**
     * Verifies if a given release asset matches the target operating system.
     */
    fun isPlatformAsset(
        asset: GithubAsset,
        targetOS: PlatformPaths.OS = PlatformPaths.currentOS
    ): Boolean {
        val nameLower = asset.name.lowercase()
        return when (targetOS) {
            PlatformPaths.OS.LINUX -> {
                nameLower.endsWith(".appimage") ||
                    nameLower.endsWith(".deb") ||
                    nameLower.endsWith(".tar.gz") ||
                    nameLower.endsWith(".tgz") ||
                    nameLower.endsWith(".rpm") ||
                    asset.contentType.contains("gzip") ||
                    asset.contentType.contains("appimage") ||
                    asset.contentType.contains("debian")
            }
            PlatformPaths.OS.WINDOWS -> {
                nameLower.endsWith(".exe") ||
                    nameLower.endsWith(".zip") ||
                    nameLower.endsWith(".msix") ||
                    asset.contentType.contains("zip") ||
                    asset.contentType.contains("x-msdownload") ||
                    asset.contentType.contains("x-dosexec")
            }
            PlatformPaths.OS.MACOS -> {
                nameLower.endsWith(".dmg") ||
                    nameLower.endsWith(".tar.gz") ||
                    nameLower.endsWith(".zip")
            }
            else -> {
                asset.contentType == "application/vnd.android.package-archive" || nameLower.endsWith(".apk")
            }
        }
    }

    /**
     * Matches the most suitable platform-specific binary asset from a release.
     * Respects Linux (AppImage, deb, tar.gz, rpm), Windows (exe, zip, msix),
     * macOS (dmg, tar.gz), and Android (apk).
     */
    fun findPlatformAsset(
        assets: List<GithubAsset>,
        targetOS: PlatformPaths.OS = PlatformPaths.currentOS
    ): GithubAsset? {
        if (assets.isEmpty()) return null

        when (targetOS) {
            PlatformPaths.OS.LINUX -> {
                // If running inside AppImage container, prefer AppImage binary
                if (System.getenv("APPIMAGE") != null) {
                    assets.firstOrNull { it.name.endsWith(".AppImage", ignoreCase = true) }?.let { return it }
                }
                // Order of preference: AppImage > deb > tar.gz / tgz > rpm
                return assets.firstOrNull { it.name.endsWith(".AppImage", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".deb", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".tar.gz", ignoreCase = true) || it.name.endsWith(".tgz", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".rpm", ignoreCase = true) }
                    ?: assets.firstOrNull { it.contentType.contains("gzip") || it.contentType.contains("appimage") || it.contentType.contains("debian") }
                    ?: assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk", ignoreCase = true) }
            }
            PlatformPaths.OS.WINDOWS -> {
                // Order of preference: exe installer > zip portable > msix
                return assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".msix", ignoreCase = true) }
                    ?: assets.firstOrNull { it.contentType.contains("zip") || it.contentType.contains("x-msdownload") || it.contentType.contains("x-dosexec") }
                    ?: assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk", ignoreCase = true) }
            }
            PlatformPaths.OS.MACOS -> {
                // Order of preference: dmg > tar.gz > zip
                return assets.firstOrNull { it.name.endsWith(".dmg", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".tar.gz", ignoreCase = true) }
                    ?: assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                    ?: assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk", ignoreCase = true) }
            }
            else -> {
                return assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" || it.name.endsWith(".apk", ignoreCase = true) }
                    ?: assets.firstOrNull()
            }
        }
    }

    suspend fun getAppUpdate(installPrerelease: Boolean = false, context: Context? = null): Update {
        return try {
            when {
                // No updates on debug version
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate(context)
                else -> getReleaseUpdate(context)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            AppLogger.e(LOG_TAG, "Failed to resolve app update", e)
            Update(false, null, null, null, null)
        }
    }

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return getAppUpdate(installPrerelease, this)
    }

    suspend fun getReleaseUpdate(context: Context? = null): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(url, headers = headers).text
        ).toList()

        val versionRegex = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*?\.(AppImage|deb|tar\.gz|tgz|rpm|zip|exe|msix|dmg|apk))""", RegexOption.IGNORE_CASE)
        val versionRegexLocal = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*)""")

        val foundList = response.filter { rel ->
            !rel.prerelease
        }.sortedWith(compareBy { release ->
            val asset = findPlatformAsset(release.assets)
            val name = asset?.name ?: release.tagName
            versionRegex.find(name)?.groupValues?.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            } ?: versionRegexLocal.find(release.tagName)?.groupValues?.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            }
        }).toList()

        val found = foundList.lastOrNull()
        val foundAsset = found?.let { findPlatformAsset(it.assets) } ?: found?.assets?.getOrNull(0)
        val foundVersion = foundAsset?.name?.let { versionRegex.find(it) }
            ?: found?.tagName?.let { versionRegexLocal.find(it) }

        if (foundVersion == null || foundAsset == null) {
            return Update(false, null, null, null, null)
        }

        val currentContext = context ?: CloudStreamApp.context
        val currentVersion = currentContext?.packageName?.let {
            try {
                currentContext.packageManager.getPackageInfo(it, 0)
            } catch (_: Throwable) {
                null
            }
        }

        val shouldUpdate = if (foundAsset.browserDownloadUrl.isBlank()) {
            false
        } else {
            val localVersionStr = currentVersion?.versionName ?: BuildConfig.VERSION_NAME
            val localScore = versionRegexLocal.find(localVersionStr)?.groupValues?.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            } ?: 0

            val remoteScore = foundVersion.groupValues.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            }
            localScore < remoteScore
        }

        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            foundVersion.groupValues[2],
            found?.body,
            found?.nodeId
        )
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        return getReleaseUpdate(this)
    }

    suspend fun getPreReleaseUpdate(context: Context? = null): Update {
        val tagUrl =
            "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(releaseUrl, headers = headers).text
        ).toList()

        val found = response.lastOrNull { rel ->
            rel.prerelease || rel.tagName == "pre-release"
        }

        val foundAsset = found?.let { findPlatformAsset(it.assets) }
            ?: found?.assets?.firstOrNull {
                it.contentType == "application/vnd.android.package-archive" || isPlatformAsset(it)
            }
            ?: found?.assets?.getOrNull(0)

        if (foundAsset == null || found == null) {
            return Update(false, null, null, null, null)
        }

        val tagResponse = parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        val updateCommitHash = tagResponse.githubObject.sha.trim().take(7)
        Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash")
        AppLogger.d(LOG_TAG, "Fetched GitHub pre-release tag: $updateCommitHash")

        val currentHash = GitInfo.currentCommitHash()

        return Update(
            currentHash.isNotEmpty() && currentHash != updateCommitHash,
            foundAsset.browserDownloadUrl,
            updateCommitHash,
            found.body,
            found.nodeId
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        return getPreReleaseUpdate(this)
    }

    val updateLock = Mutex()

    suspend fun downloadUpdate(url: String, context: Context? = null): Boolean {
        try {
            Log.d(LOG_TAG, "Downloading update: $url")
            AppLogger.i(LOG_TAG, "Downloading update: $url")
            val appUpdateName = "CloudStream"
            val appUpdateSuffix = when {
                url.endsWith(".AppImage", ignoreCase = true) -> "AppImage"
                url.endsWith(".deb", ignoreCase = true) -> "deb"
                url.endsWith(".tar.gz", ignoreCase = true) -> "tar.gz"
                url.endsWith(".rpm", ignoreCase = true) -> "rpm"
                url.endsWith(".zip", ignoreCase = true) -> "zip"
                url.endsWith(".exe", ignoreCase = true) -> "exe"
                url.endsWith(".msix", ignoreCase = true) -> "msix"
                url.endsWith(".dmg", ignoreCase = true) -> "dmg"
                url.endsWith(".apk", ignoreCase = true) -> "apk"
                PlatformPaths.currentOS == PlatformPaths.OS.LINUX -> "AppImage"
                PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS -> "zip"
                else -> "apk"
            }

            val targetContext = context ?: CloudStreamApp.context
            val targetCacheDir = targetContext?.cacheDir ?: PlatformPaths.cacheDir.toFile().apply { mkdirs() }

            // Delete all old updates
            targetCacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && (it.extension.equals(appUpdateSuffix, ignoreCase = true) || it.name.endsWith(".$appUpdateSuffix", ignoreCase = true))
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix", targetCacheDir)
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                sink.writeAll(app.get(url).body.source())
                sink.close()

                if (PlatformPaths.currentOS == PlatformPaths.OS.LINUX && downloadedFile.extension.equals("AppImage", ignoreCase = true)) {
                    downloadedFile.setExecutable(true, false)
                }

                if (targetContext != null) {
                    openApk(targetContext, Uri.fromFile(downloadedFile))
                }
            }

            return true
        } catch (e: Exception) {
            logError(e)
            AppLogger.e(LOG_TAG, "Update download failed for: $url", e)
            return false
        }
    }

    private suspend fun Activity.downloadUpdate(url: String): Boolean {
        return downloadUpdate(url, this)
    }

    @PlatformQuarantine(
        reason = "Android FileProvider and Intent.ACTION_VIEW package installer is mobile-specific; Linux desktop updates launch native installer or notify user",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt:216-228",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val file = File(path)
        if (PlatformPaths.currentOS == PlatformPaths.OS.LINUX) {
            if (file.extension.equals("AppImage", ignoreCase = true)) {
                file.setExecutable(true, false)
            }
        }
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    fun installPreReleaseIfNeeded(activity: Activity? = null) = ioSafe {
        val targetContext = activity ?: CloudStreamApp.context
        val isInstalled = try {
            targetContext?.packageManager?.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(activity = activity, checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }

    @JvmName("installPreReleaseIfNeededOnActivity")
    fun Activity.installPreReleaseIfNeeded() = this@InAppUpdater.installPreReleaseIfNeeded(this)

    /**
     * @param activity active Activity or null for headless execution
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun runAutoUpdate(
        activity: Activity? = null,
        checkAutoUpdate: Boolean = true,
        installPrerelease: Boolean = false
    ): Boolean {
        val targetContext = activity ?: CloudStreamApp.context ?: return false
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(targetContext)
        val autoUpdateEnabled =
            settingsManager.getBoolean(targetContext.getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = getAppUpdate(installPrerelease, targetContext)
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            targetContext.getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        // Notify reactive listeners and update StateFlow for desktop UI
        _updateState.value = update
        updateAvailableEvent.invoke(update)

        val runOnUiThreadBlock: (() -> Unit) -> Unit = { action ->
            if (activity != null) {
                activity.runOnUiThread { action() }
            } else {
                action()
            }
        }

        runOnUiThreadBlock {
            safe {
                val currentVersion = targetContext.packageName.let {
                    try {
                        targetContext.packageManager.getPackageInfo(it, 0)
                    } catch (_: Throwable) {
                        null
                    }
                }

                val builder = AlertDialog.Builder(targetContext, R.style.AlertDialogCustom)
                builder.setTitle(
                    targetContext.getString(R.string.new_update_format).format(
                        currentVersion?.versionName ?: BuildConfig.VERSION_NAME, update.updateVersion
                    )
                )

                val sanitizedChangelog = sanitizeChangelog(update.changelog)

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                targetContext.getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(targetContext.getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            targetContext.getString(R.string.apk_installer_key), 1
                        )

                        when (currentInstaller) {
                            // New method (PackageInstallerService)
                            0 -> {
                                val intent = PackageInstallerService.getIntent(
                                    targetContext, update.updateURL
                                )
                                ContextCompat.startForegroundService(
                                    targetContext, intent
                                )
                            }
                            // Legacy / Direct download method
                            1 -> {
                                ioSafe {
                                    if (!downloadUpdate(update.updateURL, targetContext)) {
                                        runOnUiThreadBlock {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    targetContext.getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    @JvmName("runAutoUpdateOnActivity")
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true,
        installPrerelease: Boolean = false
    ): Boolean {
        return runAutoUpdate(this, checkAutoUpdate, installPrerelease)
    }

    @PlatformQuarantine(
        reason = "Xiaomi MIUI check via Android getprop is mobile-specific; not applicable to Linux/Windows desktop",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt:362",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    @PlatformQuarantine(
        reason = "Android getprop execution is mobile-specific; fails on Windows and standard Linux",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt:364-371",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    private fun getSystemProperty(propName: String): String? = try {
        if (PlatformPaths.currentOS == PlatformPaths.OS.WINDOWS) {
            null
        } else {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", propName))
            BufferedReader(InputStreamReader(p.inputStream), 1024).use {
                it.readLine()
            }
        }
    } catch (_: Throwable) {
        null
    }
}
