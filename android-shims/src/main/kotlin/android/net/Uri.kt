package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap

class Uri private constructor(private val uriString: String) {
    private val uri: URI? = try {
        URI(uriString)
    } catch (e: Exception) {
        try {
            val fixed = uriString.replace(" ", "%20")
            URI(fixed)
        } catch (e2: Exception) {
            null
        }
    }

    fun getScheme(): String? {
        if (uri != null && uri.scheme != null) return uri.scheme
        val idx = uriString.indexOf("://")
        return if (idx != -1) uriString.substring(0, idx) else null
    }

    val scheme: String?
        @JvmName("schemeProperty")
        get() = getScheme()

    fun getHost(): String? {
        if (uri != null && uri.host != null) return uri.host
        val schemeEnd = uriString.indexOf("://")
        val start = if (schemeEnd != -1) schemeEnd + 3 else 0
        val end = uriString.indexOfAny(charArrayOf('/', '?', '#'), start)
        val hostPort = if (end != -1) uriString.substring(start, end) else uriString.substring(start)
        val colon = hostPort.indexOf(':')
        return if (colon != -1) hostPort.substring(0, colon) else hostPort
    }

    fun getPath(): String? {
        if (uri != null && uri.path != null) return uri.path
        val schemeEnd = uriString.indexOf("://")
        val start = if (schemeEnd != -1) schemeEnd + 3 else 0
        val pathStart = uriString.indexOf('/', start)
        if (pathStart == -1) return null
        val queryStart = uriString.indexOfAny(charArrayOf('?', '#'), pathStart)
        return if (queryStart != -1) uriString.substring(pathStart, queryStart) else uriString.substring(pathStart)
    }

    val path: String?
        @JvmName("pathProperty")
        get() = getPath()

    fun getEncodedQuery(): String? {
        val qIdx = uriString.indexOf('?')
        if (qIdx == -1) return null
        val fIdx = uriString.indexOf('#', qIdx)
        return if (fIdx != -1) uriString.substring(qIdx + 1, fIdx) else uriString.substring(qIdx + 1)
    }

    fun getQuery(): String? {
        if (uri != null && uri.query != null) return uri.query
        return getEncodedQuery()?.let { decodeComponent(it) }
    }

    fun getAuthority(): String? {
        if (uri != null && uri.authority != null) return uri.authority
        val schemeEnd = uriString.indexOf("://")
        val start = if (schemeEnd != -1) schemeEnd + 3 else 0
        val end = uriString.indexOfAny(charArrayOf('/', '?', '#'), start)
        return if (end != -1) uriString.substring(start, end) else uriString.substring(start)
    }

    fun getFragment(): String? {
        if (uri != null && uri.fragment != null) return uri.fragment
        val fIdx = uriString.indexOf('#')
        return if (fIdx != -1) uriString.substring(fIdx + 1) else null
    }

    fun getPort(): Int {
        return uri?.port ?: -1
    }

    fun getLastPathSegment(): String? {
        val path = getPath() ?: return null
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx >= 0) trimmed.substring(idx + 1) else trimmed.ifEmpty { null }
    }

    val lastPathSegment: String?
        @JvmName("lastPathSegmentProperty")
        get() = getLastPathSegment()

    fun getPathSegments(): List<String> {
        val path = getPath() ?: return emptyList()
        return path.split('/').filter { it.isNotEmpty() }
    }

    private fun parseQueryParametersMulti(): Map<String, List<String>> {
        val query = getEncodedQuery() ?: return emptyMap()
        if (query.isEmpty()) return emptyMap()

        val params = LinkedHashMap<String, MutableList<String>>()
        val parts = query.split('&')
        for (part in parts) {
            if (part.isEmpty()) continue
            val kv = part.split('=', limit = 2)
            val rawKey = kv[0]
            if (rawKey.isEmpty()) continue
            val key = decodeComponent(rawKey)
            val value = if (kv.size > 1) decodeComponent(kv[1]) else ""
            params.computeIfAbsent(key) { ArrayList() }.add(value)
        }
        return params
    }

    fun getQueryParameterNames(): Set<String> {
        return parseQueryParametersMulti().keys
    }

    fun getQueryParameter(key: String?): String? {
        if (key == null) return null
        val values = parseQueryParametersMulti()[key] ?: return null
        return values.firstOrNull()
    }

    fun getQueryParameters(key: String?): List<String> {
        if (key == null) return emptyList()
        val values = parseQueryParametersMulti()[key] ?: return emptyList()
        return Collections.unmodifiableList(values)
    }

    fun getBooleanQueryParameter(key: String?, defaultValue: Boolean): Boolean {
        val flag = getQueryParameter(key) ?: return defaultValue
        return flag.equals("true", ignoreCase = true) || flag == "1"
    }

    fun buildUpon(): Builder {
        val builder = Builder()
        builder.scheme(getScheme())
        builder.encodedAuthority(getAuthority())
        builder.path(getPath())
        builder.encodedQuery(getQuery())
        builder.fragment(getFragment())
        return builder
    }

    fun appendQueryParameter(key: String, value: String): Uri {
        return buildUpon().appendQueryParameter(key, value).build()
    }

    override fun toString(): String = uriString

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Uri) return false
        return uriString == other.uriString
    }

    override fun hashCode(): Int = uriString.hashCode()

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String? = null
        private var query: String? = null
        private var fragment: String? = null
        private val queryParams = mutableListOf<Pair<String, String>>()

        fun scheme(scheme: String?) = apply { this.scheme = scheme }
        fun authority(authority: String?) = apply { this.authority = authority }
        fun encodedAuthority(authority: String?) = apply { this.authority = authority }

        fun path(path: String?) = apply { this.path = path }
        fun encodedPath(path: String?) = apply { this.path = path }

        fun appendPath(newSegment: String) = apply {
            val current = this.path ?: ""
            this.path = if (current.endsWith("/")) {
                current + newSegment.trimStart('/')
            } else if (current.isEmpty()) {
                if (newSegment.startsWith("/")) newSegment else "/$newSegment"
            } else {
                "$current/${newSegment.trimStart('/')}"
            }
        }

        fun appendEncodedPath(newSegment: String) = appendPath(newSegment)

        fun query(query: String?) = apply { this.query = query }
        fun encodedQuery(query: String?) = apply { this.query = query }

        fun appendQueryParameter(key: String, value: String) = apply {
            queryParams.add(key to value)
        }

        fun clearQuery() = apply {
            this.query = null
            this.queryParams.clear()
        }

        fun fragment(fragment: String?) = apply { this.fragment = fragment }
        fun encodedFragment(fragment: String?) = apply { this.fragment = fragment }

        fun build(): Uri {
            val sb = StringBuilder()
            if (!scheme.isNullOrEmpty()) {
                sb.append(scheme).append("://")
            }
            if (!authority.isNullOrEmpty()) {
                sb.append(authority)
            }
            if (!path.isNullOrEmpty()) {
                if (!path!!.startsWith("/") && sb.isNotEmpty()) {
                    sb.append("/")
                }
                sb.append(path)
            }

            val queryParts = mutableListOf<String>()
            if (!query.isNullOrEmpty()) {
                queryParts.add(query!!)
            }
            for ((k, v) in queryParams) {
                queryParts.add(encodeComponent(k) + "=" + encodeComponent(v))
            }

            if (queryParts.isNotEmpty()) {
                sb.append("?").append(queryParts.joinToString("&"))
            }

            if (!fragment.isNullOrEmpty()) {
                sb.append("#").append(fragment)
            }

            return Uri(sb.toString())
        }
    }

    companion object {
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun fromFile(file: java.io.File): Uri {
            return Uri("file://" + file.absolutePath)
        }

        @JvmStatic
        fun parse(uriString: String?): Uri {
            if (uriString.isNullOrEmpty()) return EMPTY
            return Uri(uriString)
        }

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri {
            val sb = StringBuilder()
            sb.append(scheme).append(":").append(ssp)
            if (!fragment.isNullOrEmpty()) {
                sb.append("#").append(fragment)
            }
            return Uri(sb.toString())
        }

        @JvmStatic
        fun encode(s: String?): String? {
            if (s == null) return null
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name())
        }

        @JvmStatic
        fun decode(s: String?): String? {
            if (s == null) return null
            return decodeComponent(s)
        }

        private fun decodeComponent(value: String): String {
            return try {
                URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            } catch (e: Exception) {
                value
            }
        }

        private fun encodeComponent(value: String): String {
            return try {
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            } catch (e: Exception) {
                value
            }
        }
    }
}
