package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKeyClass
import com.lagradost.common.storage.DesktopDataStore
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val DOWNLOAD_HEADER_CACHE = "download_header_cache"
const val DOWNLOAD_HEADER_CACHE_BACKUP = "BACKUP_download_header_cache"
const val DOWNLOAD_EPISODE_CACHE = "download_episode_cache"
const val DOWNLOAD_EPISODE_CACHE_BACKUP = "BACKUP_download_episode_cache"
const val VIDEO_PLAYER_BRIGHTNESS = "video_player_alpha_key"
const val USER_SELECTED_HOMEPAGE_API = "home_api_used"
const val USER_PROVIDER_API = "user_custom_sites"
const val PREFERENCES_NAME = "rebuild_preference"

class PreferenceDelegate<T : Any>(
    val key: String,
    val default: T
) {
    private val klass: KClass<out T> = default::class
    private var cache: T? = null

    operator fun getValue(self: Any?, property: KProperty<*>): T {
        return cache ?: getKeyClass(key, klass.java).also { cache = it } ?: default
    }

    operator fun setValue(self: Any?, property: KProperty<*>, t: T?) {
        cache = t
        if (t == null) {
            removeKey(key)
        } else {
            setKeyClass(key, t)
        }
    }
}

data class Editor(
    val editor: SharedPreferences.Editor
) {
    fun <T> setKeyRaw(path: String, value: T) {
        when (value) {
            is Boolean -> editor.putBoolean(path, value)
            is Int -> editor.putInt(path, value)
            is String -> editor.putString(path, value)
            is Float -> editor.putFloat(path, value)
            is Long -> editor.putLong(path, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(path, value as Set<String>)
            }
            null -> editor.remove(path)
            else -> editor.putString(path, DesktopDataStore.serializeValue(value))
        }
    }

    fun apply() {
        editor.apply()
    }
}

object DataStore {
    val mapper = DesktopDataStore.mapper

    @JvmStatic
    fun getFolderName(folder: String, path: String): String {
        return DesktopDataStore.getFolderName(folder, path)
    }

    fun Context.getSharedPrefs(): SharedPreferences {
        return this.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun Context.getDefaultSharedPrefs(): SharedPreferences {
        return this.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun editor(context: Context, isEditingAppSettings: Boolean = false): Editor {
        return Editor(context.getSharedPrefs().edit())
    }

    // Context extension methods (1:1 upstream signature parity)
    fun Context.getKeys(folder: String): List<String> {
        return DesktopDataStore.getKeys(folder)
    }

    fun Context.removeKey(folder: String, path: String) {
        DesktopDataStore.removeKey(folder, path)
    }

    fun Context.removeKey(path: String) {
        DesktopDataStore.removeKey(path)
    }

    fun Context.removeKeys(folder: String): Int {
        return DesktopDataStore.removeKeys(folder)
    }

    fun Context.containsKey(folder: String, path: String): Boolean {
        return DesktopDataStore.containsKey(folder, path)
    }

    fun Context.containsKey(path: String): Boolean {
        return DesktopDataStore.containsKey(path)
    }

    fun <T> Context.setKey(path: String, value: T) {
        DesktopDataStore.setKey(path, value)
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        DesktopDataStore.setKey(folder, path, value)
    }

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        return DesktopDataStore.getKey(path, valueType)
    }

    fun <T : Any> Context.getKey(folder: String, path: String, valueType: Class<T>): T? {
        return DesktopDataStore.getKey(folder, path, valueType)
    }

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T? = null): T? {
        return DesktopDataStore.getKey<T>(path, defVal)
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T? = null): T? {
        return DesktopDataStore.getKey<T>(folder, path, defVal)
    }

    // Direct methods for non-context callers or static invocations
    @JvmStatic
    fun getKeys(folder: String): List<String> {
        return DesktopDataStore.getKeys(folder)
    }

    @JvmStatic
    fun removeKeys(folder: String): Int {
        return DesktopDataStore.removeKeys(folder)
    }

    @JvmStatic
    fun removeKey(folder: String, path: String) {
        DesktopDataStore.removeKey(folder, path)
    }

    @JvmStatic
    fun removeKey(path: String) {
        DesktopDataStore.removeKey(path)
    }

    @JvmStatic
    fun containsKey(folder: String, path: String): Boolean {
        return DesktopDataStore.containsKey(folder, path)
    }

    @JvmStatic
    fun containsKey(path: String): Boolean {
        return DesktopDataStore.containsKey(path)
    }

    @JvmStatic
    fun <T> setKey(path: String, value: T) {
        DesktopDataStore.setKey(path, value)
    }

    @JvmStatic
    fun <T> setKey(folder: String, path: String, value: T) {
        DesktopDataStore.setKey(folder, path, value)
    }

    @JvmStatic
    fun <T : Any> getKey(path: String, valueType: Class<T>): T? {
        return DesktopDataStore.getKey(path, valueType)
    }

    @JvmStatic
    fun <T : Any> getKey(folder: String, path: String, valueType: Class<T>): T? {
        return DesktopDataStore.getKey(folder, path, valueType)
    }

    inline fun <reified T : Any> getKey(path: String, defVal: T? = null): T? {
        return DesktopDataStore.getKey<T>(path, defVal)
    }

    inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T? = null): T? {
        return DesktopDataStore.getKey<T>(folder, path, defVal)
    }

    inline fun <reified T : Any> String.toKotlinObject(): T {
        return DesktopDataStore.mapper.readValue(this, T::class.java)
    }

    fun <T : Any> String.toKotlinObject(valueType: Class<T>): T {
        return DesktopDataStore.deserializeValue(this, valueType)
            ?: throw IllegalArgumentException("Failed to deserialize $this to ${valueType.simpleName}")
    }
}
