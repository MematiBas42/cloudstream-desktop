package android.content

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface SharedPreferences {
    fun getAll(): Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?)
    }

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

class LinuxSharedPreferences(val name: String) : SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        loadFromFile()
    }

    private fun getPrefPath(): Path {
        return PlatformPaths.pluginPrefsDir.resolve("$name.json")
    }

    private fun loadFromFile() {
        try {
            val targetPath = getPrefPath()
            if (Files.exists(targetPath)) {
                val content = Files.readString(targetPath, StandardCharsets.UTF_8)
                val parsed = parseJson(content)
                values.putAll(parsed)
            } else {
                try {
                    val cached = DesktopDataStore.getKey<Map<String, Any>>("plugin_prefs_$name")
                    if (cached != null) {
                        values.putAll(cached)
                    }
                } catch (t: Throwable) {
                    AppLogger.w("LinuxSharedPreferences", "Failed to read cached preferences: ${t.message}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("LinuxSharedPreferences", "Failed to load preferences for $name", e)
        }
    }

    private fun persistPreferences() {
        try {
            val targetPath = getPrefPath()
            val json = serializeToJson(values)
            DesktopDataStore.atomicWrite(targetPath, json.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            AppLogger.e("LinuxSharedPreferences", "Failed to persist preferences for $name via DesktopDataStore", e)
        }
    }

    override fun getAll(): Map<String, *> {
        return HashMap(values)
    }

    override fun getString(key: String, defValue: String?): String? {
        val v = values[key] ?: return defValue
        return v as? String ?: v.toString()
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val v = values[key] ?: return defValues
        return when (v) {
            is Set<*> -> @Suppress("UNCHECKED_CAST") (v as Set<String>)
            is Collection<*> -> v.map { it.toString() }.toSet()
            else -> defValues
        }
    }

    override fun getInt(key: String, defValue: Int): Int {
        val v = values[key] ?: return defValue
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getLong(key: String, defValue: Long): Long {
        val v = values[key] ?: return defValue
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val v = values[key] ?: return defValue
        return when (v) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: defValue
            else -> defValue
        }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val v = values[key] ?: return defValue
        return when (v) {
            is Boolean -> v
            is String -> v.toBoolean()
            else -> defValue
        }
    }

    override fun contains(key: String): Boolean {
        return values.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor {
        return LinuxEditor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyListeners(changedKeys: Set<String>) {
        for (listener in listeners) {
            for (key in changedKeys) {
                try {
                    listener.onSharedPreferenceChanged(this, key)
                } catch (t: Throwable) {
                    AppLogger.w("LinuxSharedPreferences", "Listener threw exception for key $key", t)
                }
            }
        }
    }

    private inner class LinuxEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearFlag = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            pending[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVED_SENTINEL
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changedKeys = mutableSetOf<String>()
            synchronized(this@LinuxSharedPreferences) {
                if (clearFlag) {
                    changedKeys.addAll(values.keys)
                    values.clear()
                    clearFlag = false
                }
                for ((k, v) in pending) {
                    if (v === REMOVED_SENTINEL) {
                        if (values.remove(k) != null) {
                            changedKeys.add(k)
                        }
                    } else if (v != null) {
                        val prev = values.put(k, v)
                        if (prev != v) {
                            changedKeys.add(k)
                        }
                    }
                }
                pending.clear()
                persistPreferences()
            }
            if (changedKeys.isNotEmpty()) {
                notifyListeners(changedKeys)
            }
        }
    }

    companion object {
        private val REMOVED_SENTINEL = Any()

        private fun escapeJson(str: String): String {
            val sb = StringBuilder()
            for (ch in str) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            return sb.toString()
        }

        private fun serializeToJson(map: Map<String, Any>): String {
            val sb = StringBuilder()
            sb.append("{\n")
            val entries = map.entries.toList()
            for (i in entries.indices) {
                val (k, v) = entries[i]
                sb.append("  \"").append(escapeJson(k)).append("\": ")
                appendJsonValue(sb, v)
                if (i < entries.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("}")
            return sb.toString()
        }

        private fun appendJsonValue(sb: StringBuilder, v: Any?) {
            when (v) {
                null -> sb.append("null")
                is String -> sb.append("\"").append(escapeJson(v)).append("\"")
                is Boolean -> sb.append(v)
                is Number -> sb.append(v)
                is Collection<*> -> {
                    sb.append("[")
                    val items = v.toList()
                    for (j in items.indices) {
                        val item = items[j]
                        if (item != null) {
                            sb.append("\"").append(escapeJson(item.toString())).append("\"")
                        } else {
                            sb.append("null")
                        }
                        if (j < items.size - 1) sb.append(", ")
                    }
                    sb.append("]")
                }
                else -> sb.append("\"").append(escapeJson(v.toString())).append("\"")
            }
        }

        private fun parseJson(json: String): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            val trimmed = json.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return result

            var idx = 1
            val len = trimmed.length - 1

            fun skipWhitespace() {
                while (idx < len && trimmed[idx].isWhitespace()) idx++
            }

            fun parseString(): String? {
                if (idx >= len || trimmed[idx] != '"') return null
                idx++ // skip opening quote
                val sb = StringBuilder()
                while (idx < len) {
                    val c = trimmed[idx++]
                    if (c == '"') return sb.toString()
                    if (c == '\\' && idx < len) {
                        when (val esc = trimmed[idx++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (idx + 4 <= len) {
                                    val hex = trimmed.substring(idx, idx + 4)
                                    idx += 4
                                    sb.append(hex.toInt(16).toChar())
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return sb.toString()
            }

            while (idx < len) {
                skipWhitespace()
                if (idx >= len || trimmed[idx] == '}') break

                val key = parseString() ?: break
                skipWhitespace()
                if (idx >= len || trimmed[idx] != ':') break
                idx++ // skip ':'
                skipWhitespace()

                if (idx >= len) break
                val firstChar = trimmed[idx]
                val value: Any? = when {
                    firstChar == '"' -> parseString()
                    firstChar == '[' -> {
                        idx++ // skip '['
                        val list = mutableSetOf<String>()
                        while (idx < len) {
                            skipWhitespace()
                            if (idx < len && trimmed[idx] == ']') {
                                idx++
                                break
                            }
                            val elem = parseString()
                            if (elem != null) list.add(elem)
                            skipWhitespace()
                            if (idx < len && trimmed[idx] == ',') idx++
                        }
                        list
                    }
                    firstChar == '{' -> {
                        // Skip nested objects if any
                        var depth = 1
                        idx++
                        while (idx < len && depth > 0) {
                            if (trimmed[idx] == '{') depth++
                            else if (trimmed[idx] == '}') depth--
                            idx++
                        }
                        null
                    }
                    else -> {
                        // Parse primitive: boolean, number, null
                        val start = idx
                        while (idx < len && trimmed[idx] != ',' && trimmed[idx] != '}' && !trimmed[idx].isWhitespace()) {
                            idx++
                        }
                        val literal = trimmed.substring(start, idx).trim()
                        when {
                            literal == "true" -> true
                            literal == "false" -> false
                            literal == "null" -> null
                            literal.contains('.') -> literal.toDoubleOrNull() ?: literal
                            else -> literal.toLongOrNull()?.let {
                                if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it
                            } ?: literal
                        }
                    }
                }

                if (value != null) {
                    result[key] = value
                }

                skipWhitespace()
                if (idx < len && trimmed[idx] == ',') {
                    idx++
                }
            }
            return result
        }
    }
}

// Extension property — compiles to static method, no clash with getAll() instance method
val SharedPreferences.all: Map<String, *> get() = getAll()
