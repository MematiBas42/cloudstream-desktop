package android.app

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.notifications.FreedesktopNotificationManager
import java.util.concurrent.ConcurrentHashMap

open class NotificationManager {
    private val channels = ConcurrentHashMap<String, NotificationChannel>()
    private val activeNotifications = ConcurrentHashMap<String, Notification>()

    open fun createNotificationChannel(channel: NotificationChannel) {
        channels[channel.id] = channel
        AppLogger.d("NotificationManager", "Registered notification channel: ${channel.id} ('${channel.name}')")
    }

    open fun createNotificationChannels(channelList: List<NotificationChannel>) {
        for (ch in channelList) {
            createNotificationChannel(ch)
        }
    }

    open fun getNotificationChannel(channelId: String): NotificationChannel? {
        return channels[channelId]
    }

    open fun getNotificationChannels(): List<NotificationChannel> {
        return channels.values.toList()
    }

    open fun deleteNotificationChannel(channelId: String) {
        channels.remove(channelId)
    }

    open fun notify(id: Int, notification: Notification) {
        notify(null, id, notification)
    }

    open fun notify(tag: String?, id: Int, notification: Notification) {
        val key = "${tag ?: ""}:$id"
        activeNotifications[key] = notification
        AppLogger.i("NotificationManager", "[NOTIFICATION $key] ${notification.title}: ${notification.text}")
        val iconPath = notification.largeIcon?.filePath
        dispatchLinuxDesktopNotification(notification.title?.toString(), notification.text?.toString(), iconPath)
    }

    open fun cancel(id: Int) {
        cancel(null, id)
    }

    open fun cancel(tag: String?, id: Int) {
        activeNotifications.remove("${tag ?: ""}:$id")
    }

    open fun cancelAll() {
        activeNotifications.clear()
    }

    open fun areNotificationsEnabled(): Boolean = true

    open fun getActiveNotification(id: Int): Notification? = getActiveNotification(null, id)

    open fun getActiveNotification(tag: String?, id: Int): Notification? =
        activeNotifications["${tag ?: ""}:$id"]

    open fun getActiveNotifications(): Map<String, Notification> =
        activeNotifications.toMap()

    private fun dispatchLinuxDesktopNotification(title: String?, body: String?, icon: String? = null) {
        if (title.isNullOrEmpty() && body.isNullOrEmpty()) return
        FreedesktopNotificationManager.showNotification(
            title = title ?: "CloudStream",
            body = body ?: "",
            icon = icon
        )
    }

    companion object {
        const val IMPORTANCE_NONE = 0
        const val IMPORTANCE_MIN = 1
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_HIGH = 4
        const val IMPORTANCE_MAX = 5
    }
}
