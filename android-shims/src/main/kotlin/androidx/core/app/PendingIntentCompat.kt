package androidx.core.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object PendingIntentCompat {
    @JvmStatic
    fun getActivity(
        context: Context?,
        requestCode: Int,
        intent: Intent?,
        flags: Int,
        isMutable: Boolean = false
    ): PendingIntent {
        return PendingIntent(context, requestCode, intent, flags)
    }

    @JvmStatic
    fun getService(
        context: Context?,
        requestCode: Int,
        intent: Intent?,
        flags: Int,
        isMutable: Boolean = false
    ): PendingIntent {
        return PendingIntent(context, requestCode, intent, flags)
    }
}
