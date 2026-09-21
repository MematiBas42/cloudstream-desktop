package android.os

import java.util.concurrent.ConcurrentHashMap

open class Bundle() {
    private val map = ConcurrentHashMap<String, Any>()

    constructor(other: Bundle) : this() {
        putAll(other)
    }

    open fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    open fun getString(key: String): String? {
        val v = map[key] ?: return null
        return v as? String ?: v.toString()
    }

    open fun getString(key: String, defaultValue: String): String {
        return getString(key) ?: defaultValue
    }

    open fun putInt(key: String, value: Int) {
        map[key] = value
    }

    open fun getInt(key: String): Int {
        return getInt(key, 0)
    }

    open fun getInt(key: String, defaultValue: Int): Int {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    open fun putLong(key: String, value: Long) {
        map[key] = value
    }

    open fun getLong(key: String): Long {
        return getLong(key, 0L)
    }

    open fun getLong(key: String, defaultValue: Long): Long {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    open fun putFloat(key: String, value: Float) {
        map[key] = value
    }

    open fun getFloat(key: String): Float {
        return getFloat(key, 0.0f)
    }

    open fun getFloat(key: String, defaultValue: Float): Float {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    open fun putDouble(key: String, value: Double) {
        map[key] = value
    }

    open fun getDouble(key: String): Double {
        return getDouble(key, 0.0)
    }

    open fun getDouble(key: String, defaultValue: Double): Double {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    open fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    open fun getBoolean(key: String): Boolean {
        return getBoolean(key, false)
    }

    open fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val v = map[key] ?: return defaultValue
        return when (v) {
            is Boolean -> v
            is String -> v.toBoolean()
            else -> defaultValue
        }
    }

    open fun putByteArray(key: String, value: ByteArray?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    open fun getByteArray(key: String): ByteArray? {
        return map[key] as? ByteArray
    }

    open fun putStringArrayList(key: String, value: ArrayList<String>?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    open fun getStringArrayList(key: String): ArrayList<String>? {
        val v = map[key] ?: return null
        return when (v) {
            is ArrayList<*> -> v as ArrayList<String>
            is List<*> -> ArrayList(v.map { it.toString() })
            else -> null
        }
    }

    open fun putBundle(key: String, value: Bundle?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    open fun getBundle(key: String): Bundle? {
        return map[key] as? Bundle
    }

    open fun putSerializable(key: String, value: java.io.Serializable?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    open fun <T : java.io.Serializable> getSerializable(key: String): T? {
        return map[key] as? T
    }

    open fun putAll(bundle: Bundle) {
        map.putAll(bundle.map)
    }

    open fun containsKey(key: String): Boolean {
        return map.containsKey(key)
    }

    open fun remove(key: String) {
        map.remove(key)
    }

    open fun clear() {
        map.clear()
    }

    open fun isEmpty(): Boolean {
        return map.isEmpty()
    }

    open fun size(): Int {
        return map.size
    }

    open fun keySet(): Set<String> {
        return map.keys
    }

    open fun get(key: String): Any? {
        return map[key]
    }

    open fun put(key: String, value: Any?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
