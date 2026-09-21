package com.lagradost.common.notifications

import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Windows Toast Notification Dispatcher.
 * Uses PowerShell with WinRT / XML ToastNotificationManager to display
 * native Windows 10/11 toast notifications with image and text.
 */
open class WindowsToastNotificationDispatcher : DesktopNotificationDispatcher {
    override fun dispatch(payload: NotificationPayload): Boolean {
        return try {
            val safeTitle = payload.title.replace("'", "''").replace("\"", "`\"")
            val safeBody = payload.body.replace("'", "''").replace("\"", "`\"")
            val safeApp = payload.appName.replace("'", "''")

            val xmlBuilder = StringBuilder()
            xmlBuilder.append("<toast>")
            xmlBuilder.append("<visual><binding template='ToastGeneric'>")
            xmlBuilder.append("<text>$safeTitle</text>")
            xmlBuilder.append("<text>$safeBody</text>")
            if (!payload.icon.isNullOrBlank()) {
                val safeIcon = payload.icon.replace("'", "''")
                xmlBuilder.append("<image placement='appLogoOverride' src='$safeIcon'/>")
            }
            xmlBuilder.append("</binding></visual>")
            xmlBuilder.append("</toast>")

            val script = buildString {
                append("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null; ")
                append("[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null; ")
                append("\$xml = @\"${xmlBuilder}\"@; ")
                append("\$toastXml = New-Object Windows.Data.Xml.Dom.XmlDocument; ")
                append("\$toastXml.LoadXml(\$xml); ")
                append("\$toast = [Windows.UI.Notifications.ToastNotification]::new(\$toastXml); ")
                append("[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$safeApp').Show(\$toast)")
            }

            val process = ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                script
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

            val finished = process.waitFor(4, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                true
            } else {
                if (!finished) process.destroyForcibly()
                false
            }
        } catch (t: Throwable) {
            AppLogger.d("WindowsToastNotificationDispatcher", "Windows toast dispatch failed: ${t.message}")
            false
        }
    }
}

/**
 * macOS Notification Dispatcher.
 * Uses osascript to trigger system notification banners.
 */
open class MacNotificationDispatcher : DesktopNotificationDispatcher {
    override fun dispatch(payload: NotificationPayload): Boolean {
        return try {
            val safeTitle = payload.title.replace("\"", "\\\"")
            val safeBody = payload.body.replace("\"", "\\\"")
            val safeApp = payload.appName.replace("\"", "\\\"")
            val script = "display notification \"$safeBody\" with title \"$safeTitle\" subtitle \"$safeApp\""

            val process = ProcessBuilder("osascript", "-e", script)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                true
            } else {
                if (!finished) process.destroyForcibly()
                false
            }
        } catch (t: Throwable) {
            AppLogger.d("MacNotificationDispatcher", "macOS notification dispatch failed: ${t.message}")
            false
        }
    }
}

/**
 * Java AWT SystemTray Notification Dispatcher.
 * Cross-platform fallback when a system tray icon is active.
 */
open class AwtTrayNotificationDispatcher : DesktopNotificationDispatcher {
    override fun dispatch(payload: NotificationPayload): Boolean {
        return try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.SystemTray.isSupported()) {
                val tray = java.awt.SystemTray.getSystemTray()
                val trayIcons = tray.trayIcons
                val trayIcon = trayIcons.firstOrNull()
                if (trayIcon != null) {
                    val messageType = when (payload.urgency) {
                        NotificationUrgency.CRITICAL -> java.awt.TrayIcon.MessageType.ERROR
                        NotificationUrgency.NORMAL -> java.awt.TrayIcon.MessageType.INFO
                        NotificationUrgency.LOW -> java.awt.TrayIcon.MessageType.NONE
                    }
                    trayIcon.displayMessage(payload.title, payload.body, messageType)
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            AppLogger.d("AwtTrayNotificationDispatcher", "AWT Tray notification dispatch failed: ${t.message}")
            false
        }
    }
}

/**
 * In-process logging and event fallback notification dispatcher.
 * Ensures notifications are never silently dropped in headless or test environments.
 */
open class LoggingFallbackNotificationDispatcher : DesktopNotificationDispatcher {
    override fun dispatch(payload: NotificationPayload): Boolean {
        AppLogger.i("DesktopNotificationBridge", "[DESKTOP NOTIFICATION] ${payload.title}: ${payload.body}")
        return true
    }
}

/**
 * Composite dispatcher that attempts delivery across an ordered list of delegates
 * until one succeeds.
 */
open class CompositeDesktopNotificationDispatcher(
    val delegates: List<DesktopNotificationDispatcher>
) : DesktopNotificationDispatcher {
    override fun dispatch(payload: NotificationPayload): Boolean {
        for (delegate in delegates) {
            try {
                if (delegate.dispatch(payload)) {
                    return true
                }
            } catch (_: Throwable) {
                // Continue to next fallback
            }
        }
        return false
    }
}

/**
 * Unified cross-platform desktop notification bridge.
 */
object DesktopNotificationBridge {
    private const val TAG = "DesktopNotificationBridge"

    var dispatcher: DesktopNotificationDispatcher = createDefaultDispatcher()

    var onNotificationActionInvoked: ((actionUrl: String, apiName: String?) -> Unit)? = null

    fun createDefaultDispatcher(): DesktopNotificationDispatcher {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            os.contains("win") -> CompositeDesktopNotificationDispatcher(
                listOf(
                    WindowsToastNotificationDispatcher(),
                    AwtTrayNotificationDispatcher(),
                    LoggingFallbackNotificationDispatcher()
                )
            )
            os.contains("mac") -> CompositeDesktopNotificationDispatcher(
                listOf(
                    MacNotificationDispatcher(),
                    AwtTrayNotificationDispatcher(),
                    LoggingFallbackNotificationDispatcher()
                )
            )
            else -> CompositeDesktopNotificationDispatcher(
                listOf(
                    LinuxCommandNotificationDispatcher(),
                    AwtTrayNotificationDispatcher(),
                    LoggingFallbackNotificationDispatcher()
                )
            )
        }
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
        val resolvedIconPath = FreedesktopNotificationManager.resolvePosterPath(posterUrl, posterHeaders)
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

    fun triggerAction(actionUrl: String, apiName: String?) {
        AppLogger.i(TAG, "Triggered notification action: url=$actionUrl, apiName=$apiName")
        onNotificationActionInvoked?.invoke(actionUrl, apiName)
    }
}
