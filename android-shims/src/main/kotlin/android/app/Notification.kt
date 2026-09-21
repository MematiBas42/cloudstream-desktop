package android.app

import android.content.Context
import android.graphics.Bitmap

class Notification {
    var icon: Int = 0
    var title: CharSequence? = null
    var text: CharSequence? = null
    var priority: Int = PRIORITY_DEFAULT
    var channelId: String? = null
    var largeIcon: Bitmap? = null

    class Builder(val context: Context, val channelId: String = "") {
        private val notification = Notification().apply {
            this.channelId = this@Builder.channelId.ifEmpty { null }
        }

        constructor(context: Context) : this(context, "")

        fun setSmallIcon(icon: Int): Builder = apply { notification.icon = icon }
        fun setLargeIcon(icon: Bitmap?): Builder = apply { notification.largeIcon = icon }
        fun setContentTitle(title: CharSequence?): Builder = apply { notification.title = title }
        fun setContentText(text: CharSequence?): Builder = apply { notification.text = text }
        fun setPriority(priority: Int): Builder = apply { notification.priority = priority }
        fun setChannelId(channelId: String): Builder = apply { notification.channelId = channelId }
        fun setAutoCancel(autoCancel: Boolean): Builder = apply { }
        fun setOngoing(ongoing: Boolean): Builder = apply { }
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = apply { }
        fun setSubText(subText: CharSequence?): Builder = apply { }
        fun setContentInfo(info: CharSequence?): Builder = apply { }
        fun setContentIntent(intent: Any?): Builder = apply { }
        fun setDeleteIntent(intent: Any?): Builder = apply { }
        fun build(): Notification = notification
    }

    companion object {
        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_LOW = -1
        const val PRIORITY_MIN = -2
        const val PRIORITY_HIGH = 1
        const val PRIORITY_MAX = 2
    }
}
