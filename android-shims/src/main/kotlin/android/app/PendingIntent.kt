package android.app

import android.content.Context
import android.content.Intent

class PendingIntent(
    val context: Context?,
    val requestCode: Int,
    val intent: Intent?,
    val flags: Int
) {
    companion object {
        const val FLAG_UPDATE_CURRENT = 1 shl 27
        const val FLAG_IMMUTABLE = 1 shl 26
        const val FLAG_MUTABLE = 1 shl 25

        @JvmStatic
        fun getBroadcast(context: Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent {
            return PendingIntent(context, requestCode, intent, flags)
        }

        @JvmStatic
        fun getActivity(context: Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent {
            return PendingIntent(context, requestCode, intent, flags)
        }

        @JvmStatic
        fun getService(context: Context?, requestCode: Int, intent: Intent?, flags: Int): PendingIntent {
            return PendingIntent(context, requestCode, intent, flags)
        }
    }
}
