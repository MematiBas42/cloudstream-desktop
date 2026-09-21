package androidx.preference

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {
    private const val DEFAULT_PREFS_NAME = "default_preferences"

    @JvmStatic
    fun getDefaultSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun setDefaultValues(context: Context, resId: Int, readAgain: Boolean) {
        // No-op for desktop runtime
    }

    @JvmStatic
    fun setDefaultValues(
        context: Context,
        sharedPreferencesName: String?,
        sharedPreferencesMode: Int,
        resId: Int,
        readAgain: Boolean
    ) {
        // No-op for desktop runtime
    }
}
