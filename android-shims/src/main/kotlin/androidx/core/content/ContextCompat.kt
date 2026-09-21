package androidx.core.content

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

object ContextCompat {
    fun getString(context: Context, resId: Int): String = context.getString(resId)
    fun getColor(context: Context, resId: Int): Int = 0xFF000000.toInt()
    fun getDrawable(context: Context, resId: Int): Drawable? = null
    fun startForegroundService(context: Context, intent: Intent) {}
}
