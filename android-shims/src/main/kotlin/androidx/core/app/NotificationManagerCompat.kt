package androidx.core.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

class NotificationManagerCompat private constructor(private val context: Context) {
    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: defaultManager

    val activeNotifications: List<ActiveNotificationWrapper>
        get() = manager.getActiveNotifications().map { (key, notif) ->
            val tag = key.substringBeforeLast(":", "")
            val id = key.substringAfterLast(":").toIntOrNull() ?: 0
            ActiveNotificationWrapper(id, tag.ifEmpty { null }, notif)
        }

    fun notify(tag: String?, id: Int, notification: Notification) {
        manager.notify(tag, id, notification)
    }

    fun notify(id: Int, notification: Notification) {
        manager.notify(null, id, notification)
    }

    fun cancel(tag: String?, id: Int) {
        manager.cancel(tag, id)
    }

    fun cancel(id: Int) {
        manager.cancel(null, id)
    }

    fun cancelAll() {
        manager.cancelAll()
    }

    fun areNotificationsEnabled(): Boolean = manager.areNotificationsEnabled()

    data class ActiveNotificationWrapper(
        val id: Int,
        val tag: String?,
        val notification: Notification
    )

    companion object {
        private val defaultManager by lazy { NotificationManager() }

        @JvmStatic
        fun from(context: Context): NotificationManagerCompat = NotificationManagerCompat(context)
    }
}
