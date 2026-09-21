package com.lagradost.common.notifications

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class NotificationUrgency {
    LOW,
    NORMAL,
    CRITICAL;

    fun toCli(): String = name.lowercase()
}

data class NotificationPayload(
    val appName: String = "CloudStream",
    val replacesId: Int = 0,
    val icon: String? = null,
    val title: String,
    val body: String,
    val timeoutMs: Int = 5000,
    val urgency: NotificationUrgency = NotificationUrgency.NORMAL,
    val category: String? = null,
    val actionUrl: String? = null,
    val actionLabel: String? = null,
    val apiName: String? = null
)

interface DesktopNotificationDispatcher {
    fun dispatch(payload: NotificationPayload): Boolean
}

open class LinuxCommandNotificationDispatcher : DesktopNotificationDispatcher {
    override fun dispatch(payload: NotificationPayload): Boolean {
        // 1. Try notify-send (libnotify CLI)
        if (tryNotifySend(payload)) {
            return true
        }

        // 2. Try gdbus (direct D-Bus session bus)
        if (tryGdbus(payload)) {
            return true
        }

        // 3. Try busctl (systemd D-Bus CLI)
        if (tryBusctl(payload)) {
            return true
        }

        AppLogger.d(
            "FreedesktopNotificationManager",
            "Desktop notification dispatch skipped or failed for '${payload.title}' (no active notification daemon)"
        )
        return false
    }

    private fun tryNotifySend(payload: NotificationPayload): Boolean {
        return try {
            val cmd = mutableListOf(
                "notify-send",
                "-a", payload.appName,
                "-u", payload.urgency.toCli(),
                "-t", payload.timeoutMs.toString()
            )
            if (payload.replacesId > 0) {
                cmd.add("-r")
                cmd.add(payload.replacesId.toString())
            }
            if (!payload.category.isNullOrBlank()) {
                cmd.add("-c")
                cmd.add(payload.category)
            }
            if (!payload.icon.isNullOrBlank()) {
                cmd.add("-i")
                cmd.add(payload.icon)
            }
            if (!payload.actionUrl.isNullOrBlank() || !payload.actionLabel.isNullOrBlank()) {
                val label = payload.actionLabel ?: "Open"
                cmd.add("-A")
                cmd.add("default=$label")
            }
            cmd.add(payload.title)
            cmd.add(payload.body)

            val process = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                true
            } else {
                if (!finished) process.destroyForcibly()
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryGdbus(payload: NotificationPayload): Boolean {
        return try {
            val iconArg = payload.icon ?: ""
            val replacesId = payload.replacesId.coerceAtLeast(0).toLong()
            val actionsArray = if (!payload.actionUrl.isNullOrBlank() || !payload.actionLabel.isNullOrBlank()) {
                val label = payload.actionLabel ?: "Open"
                "['default', '$label']"
            } else {
                "[]"
            }
            val cmd = listOf(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.Notify",
                payload.appName,
                replacesId.toString(),
                iconArg,
                payload.title,
                payload.body,
                actionsArray,
                "{}",
                payload.timeoutMs.toString()
            )
            val process = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                true
            } else {
                if (!finished) process.destroyForcibly()
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun tryBusctl(payload: NotificationPayload): Boolean {
        return try {
            val iconArg = payload.icon ?: ""
            val replacesId = payload.replacesId.coerceAtLeast(0)
            val hasAction = !payload.actionUrl.isNullOrBlank() || !payload.actionLabel.isNullOrBlank()
            val cmd = if (hasAction) {
                val label = payload.actionLabel ?: "Open"
                listOf(
                    "busctl", "--user", "call",
                    "org.freedesktop.Notifications",
                    "/org/freedesktop/Notifications",
                    "org.freedesktop.Notifications",
                    "Notify",
                    "susssasa{sv}i",
                    payload.appName,
                    replacesId.toString(),
                    iconArg,
                    payload.title,
                    payload.body,
                    "2", "default", label,
                    "0",
                    payload.timeoutMs.toString()
                )
            } else {
                listOf(
                    "busctl", "--user", "call",
                    "org.freedesktop.Notifications",
                    "/org/freedesktop/Notifications",
                    "org.freedesktop.Notifications",
                    "Notify",
                    "susssasa{sv}i",
                    payload.appName,
                    replacesId.toString(),
                    iconArg,
                    payload.title,
                    payload.body,
                    "0",
                    "0",
                    payload.timeoutMs.toString()
                )
            }
            val process = ProcessBuilder(cmd)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                true
            } else {
                if (!finished) process.destroyForcibly()
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}

object FreedesktopNotificationManager {
    private const val TAG = "FreedesktopNotificationManager"

    var dispatcher: DesktopNotificationDispatcher
        get() = DesktopNotificationBridge.dispatcher
        set(value) {
            DesktopNotificationBridge.dispatcher = value
        }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun showNotification(
        title: String,
        body: String,
        icon: String? = null,
        timeoutMs: Int = 5000,
        notificationId: Int = 0,
        urgency: NotificationUrgency = NotificationUrgency.NORMAL,
        category: String? = null,
        appName: String = "CloudStream",
        actionUrl: String? = null,
        actionLabel: String? = null,
        apiName: String? = null
    ): Boolean {
        if (title.isBlank() && body.isBlank()) return false
        val payload = NotificationPayload(
            appName = appName,
            replacesId = notificationId,
            icon = icon,
            title = title,
            body = body,
            timeoutMs = timeoutMs,
            urgency = urgency,
            category = category,
            actionUrl = actionUrl,
            actionLabel = actionLabel,
            apiName = apiName
        )
        return try {
            dispatcher.dispatch(payload)
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Notification dispatch failed: ${t.message}")
            false
        }
    }

    suspend fun showNotificationWithPoster(
        title: String,
        body: String,
        posterUrl: String?,
        posterHeaders: Map<String, String>? = null,
        timeoutMs: Int = 5000,
        notificationId: Int = 0,
        urgency: NotificationUrgency = NotificationUrgency.NORMAL,
        category: String? = null,
        appName: String = "CloudStream",
        actionUrl: String? = null,
        actionLabel: String? = null,
        apiName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val resolvedIconPath = resolvePosterPath(posterUrl, posterHeaders)
        showNotification(
            title = title,
            body = body,
            icon = resolvedIconPath,
            timeoutMs = timeoutMs,
            notificationId = notificationId,
            urgency = urgency,
            category = category,
            appName = appName,
            actionUrl = actionUrl,
            actionLabel = actionLabel,
            apiName = apiName
        )
    }

    fun resolvePosterPath(
        posterUrl: String?,
        posterHeaders: Map<String, String>? = null
    ): String? {
        if (posterUrl.isNullOrBlank()) return null

        if (posterUrl.startsWith("file://")) {
            val file = File(posterUrl.removePrefix("file://"))
            if (file.exists() && file.isFile) return file.absolutePath
        } else {
            val file = runCatching { File(posterUrl) }.getOrNull()
            if (file != null && file.isAbsolute && file.exists()) {
                return file.absolutePath
            }
        }

        if (!posterUrl.startsWith("http://") && !posterUrl.startsWith("https://")) {
            return posterUrl
        }

        return try {
            val hash = sha256Hex(posterUrl)
            val cacheDir = PlatformPaths.imageCacheDir.toFile().apply { mkdirs() }
            val destFile = File(cacheDir, "notif_$hash.jpg")
            if (destFile.exists() && destFile.length() > 0) {
                return destFile.absolutePath
            }

            val reqBuilder = Request.Builder().url(posterUrl)
            posterHeaders?.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                if (bytes != null && bytes.isNotEmpty()) {
                    val tmpFile = File(cacheDir, "notif_$hash.tmp.${System.nanoTime()}")
                    FileOutputStream(tmpFile).use { it.write(bytes) }
                    try {
                        java.nio.file.Files.move(
                            tmpFile.toPath(),
                            destFile.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: Throwable) {
                        try {
                            java.nio.file.Files.move(
                                tmpFile.toPath(),
                                destFile.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            )
                        } catch (_: Throwable) {
                            if (!tmpFile.renameTo(destFile)) {
                                destFile.delete()
                                tmpFile.renameTo(destFile)
                            }
                        }
                    }
                    return destFile.absolutePath
                }
            }
            null
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Failed to resolve poster for notification ($posterUrl): ${t.message}")
            null
        }
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
