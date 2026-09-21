package androidx.core.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap

object NotificationCompat {
    const val PRIORITY_DEFAULT = 0
    const val PRIORITY_LOW = -1
    const val PRIORITY_MIN = -2
    const val PRIORITY_HIGH = 1
    const val PRIORITY_MAX = 2

    open class Style {
        internal var builder: Builder? = null
    }

    class BigTextStyle : Style() {
        var bigText: CharSequence? = null
        fun bigText(text: CharSequence?): BigTextStyle = apply { this.bigText = text }
    }

    class Action(
        val icon: Int,
        val title: CharSequence?,
        val actionIntent: PendingIntent?
    )

    class Builder(val context: Context, val channelId: String = "") {
        private val notification = Notification().apply {
            this.channelId = this@Builder.channelId.ifEmpty { null }
        }

        private val actions = mutableListOf<Action>()
        private var style: Style? = null

        fun setSmallIcon(icon: Int): Builder = apply { notification.icon = icon }
        fun setContentTitle(title: CharSequence?): Builder = apply { notification.title = title }
        fun setContentText(text: CharSequence?): Builder = apply { notification.text = text }
        fun setPriority(priority: Int): Builder = apply { notification.priority = priority }
        fun setChannelId(channelId: String): Builder = apply { notification.channelId = channelId }
        fun setAutoCancel(autoCancel: Boolean): Builder = apply { }
        fun setColorized(colorized: Boolean): Builder = apply { }
        fun setOnlyAlertOnce(onlyAlertOnce: Boolean): Builder = apply { }
        fun setShowWhen(showWhen: Boolean): Builder = apply { }
        fun setSilent(silent: Boolean): Builder = apply { }
        fun setOngoing(ongoing: Boolean): Builder = apply { }
        fun setColor(color: Int): Builder = apply { }
        fun setSubText(subText: CharSequence?): Builder = apply { }
        fun setLargeIcon(icon: Bitmap?): Builder = apply { notification.largeIcon = icon }
        fun setContentIntent(intent: PendingIntent?): Builder = apply { }
        fun setProgress(max: Int, progress: Int, indeterminate: Boolean): Builder = apply { }
        fun setStyle(style: Style?): Builder = apply {
            this.style = style
            style?.builder = this
            if (style is BigTextStyle && style.bigText != null) {
                notification.text = style.bigText
            }
        }
        fun addAction(action: Action): Builder = apply { actions.add(action) }

        fun build(): Notification = notification
    }
}
