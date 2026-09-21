// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt", upstreamCommit = "caeec18")
package com.lagradost.player.subtitles

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class SubtitleFormat {
    SRT,
    WEBVTT,
    ASS,
    UNKNOWN
}

/**
 * High-performance, pure JVM/Kotlin statistical charset detector.
 * Sniffs BOMs, multi-byte UTF-8/UTF-16 encodings, and disambiguates legacy single-byte
 * code pages (Windows-1254 Turkish, Windows-1256 Arabic, Windows-1251 Russian, Windows-1252 Western European).
 */
object SubtitleCharsetDetector {
    private const val TAG = "SubtitleCharsetDetector"

    private val TURKISH_DISTINCTIVE_CHARS = setOf('ğ', 'Ğ', 'ı', 'İ', 'ş', 'Ş')
    private val TURKISH_SHARED_CHARS = setOf('ç', 'Ç', 'ö', 'Ö', 'ü', 'Ü')
    private val WESTERN_EU_ACCENTS = setOf(
        'é', 'è', 'à', 'â', 'ê', 'î', 'ô', 'û', 'ë', 'ï', 'ü', 'ö', 'ä', 'ÿ', 'ç', 'ñ', 'ß', 'á', 'í', 'ó', 'ú',
        'É', 'È', 'À', 'Â', 'Ê', 'Î', 'Ô', 'Û', 'Ë', 'Ï', 'Ü', 'Ö', 'Ä', 'Ç', 'Ñ', 'Á', 'Í', 'Ó', 'Ú'
    )
    private val ICELANDIC_RARE = setOf('ð', 'Ð', 'ý', 'Ý', 'þ', 'Þ')
    private val RUSSIAN_COMMON_LOWER = setOf('о', 'е', 'а', 'и', 'н', 'т', 'с', 'р', 'в', 'л', 'к', 'м', 'д', 'п')

    /**
     * Decodes [bytes] into a Unicode String using either [forcedEncoding] or auto-detected charset.
     * Automatically strips leading UTF-8 BOM if present.
     */
    fun decodeToString(bytes: ByteArray, forcedEncoding: String? = null): Pair<String, Charset> {
        if (bytes.isEmpty()) {
            return Pair("", StandardCharsets.UTF_8)
        }

        val requestedCharset = forcedEncoding?.takeIf { it.isNotBlank() }
        val detectedCharsetName = requestedCharset ?: detectCharsetName(bytes)
        val charset = resolveCharset(detectedCharsetName)

        AppLogger.i(TAG, "Decoding subtitle (${bytes.size} bytes) with charset: ${charset.name()} (detected/requested: $detectedCharsetName)")

        return try {
            val decoded = String(bytes, charset).removePrefix("﻿")
            Pair(decoded, charset)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed decoding with ${charset.name()}, falling back to UTF-8: ${e.message}")
            Pair(bytes.decodeToString().removePrefix("﻿"), StandardCharsets.UTF_8)
        }
    }

    fun detectCharsetName(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "UTF-8"

        // 1. Tier 1: Byte Order Mark (BOM) Sniffer
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return "UTF-8"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return "UTF-16BE"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return "UTF-16LE"
        }

        // 2. Tier 2: UTF-16 without BOM (alternating null bytes in ASCII range)
        if (bytes.size >= 8) {
            val sampleLen = minOf(bytes.size, 256)
            var evenZeros = 0
            var oddZeros = 0
            for (i in 0 until sampleLen step 2) {
                if (bytes[i] == 0.toByte()) evenZeros++
                if (i + 1 < sampleLen && bytes[i + 1] == 0.toByte()) oddZeros++
            }
            val halfSample = sampleLen / 2
            if (evenZeros > halfSample * 0.7) return "UTF-16BE"
            if (oddZeros > halfSample * 0.7) return "UTF-16LE"
        }

        // 3. Tier 3: Strict UTF-8 validation
        if (isValidUtf8(bytes)) {
            return "UTF-8"
        }

        // 4. Tier 4: Statistical / Language Frequency Scoring for Single-Byte Encodings
        return scoreSingleByteEncodings(bytes)
    }

    fun resolveCharset(name: String?): Charset {
        if (name.isNullOrBlank()) return StandardCharsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            try {
                when (name.trim().uppercase()) {
                    "CP1256", "WIN-1256", "WINDOWS-1256" -> Charset.forName("windows-1256")
                    "CP1254", "WIN-1254", "WINDOWS-1254" -> Charset.forName("windows-1254")
                    "CP1251", "WIN-1251", "WINDOWS-1251" -> Charset.forName("windows-1251")
                    "CP1252", "WIN-1252", "WINDOWS-1252" -> Charset.forName("windows-1252")
                    "CP1250", "WIN-1250", "WINDOWS-1250" -> Charset.forName("windows-1250")
                    "ISO-8859-9" -> Charset.forName("ISO-8859-9")
                    "ISO-8859-1" -> Charset.forName("ISO-8859-1")
                    "ISO-8859-6" -> Charset.forName("ISO-8859-6")
                    "UTF-8", "UTF8" -> StandardCharsets.UTF_8
                    "UTF-16", "UTF16" -> StandardCharsets.UTF_16
                    else -> StandardCharsets.UTF_8
                }
            } catch (_: Exception) {
                StandardCharsets.UTF_8
            }
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scoreSingleByteEncodings(bytes: ByteArray): String {
        val nonAscii = bytes.filter { (it.toInt() and 0xFF) >= 0x80 }
        if (nonAscii.isEmpty()) return "UTF-8"

        // Candidate decodings
        val cs1256 = resolveCharset("windows-1256")
        val cs1254 = resolveCharset("windows-1254")
        val cs1251 = resolveCharset("windows-1251")
        val cs1252 = resolveCharset("windows-1252")

        val text1256 = String(bytes, cs1256)
        val text1254 = String(bytes, cs1254)
        val text1251 = String(bytes, cs1251)
        val text1252 = String(bytes, cs1252)

        val latinLetters = text1254.count { (it in 'a'..'z') || (it in 'A'..'Z') }

        // 1. Arabic Windows-1256: Arabic letters (ء..ي)
        val arCount = text1256.count { it in 'ء'..'ي' }
        val score1256 = if (arCount > 0) {
            arCount * 10 - latinLetters * 2
        } else {
            -1000
        }

        // 2. Russian Windows-1251: Cyrillic letters (А..я)
        val ruCount = text1251.count { it in 'А'..'я' }
        val ruCommon = text1251.count { it.lowercaseChar() in RUSSIAN_COMMON_LOWER }
        val score1251 = if (ruCount > 0) {
            ruCount * 5 + ruCommon * 5 - latinLetters * 5
        } else {
            -1000
        }

        // 3. Turkish Windows-1254: Distinctive letters (ğ, Ğ, ı, İ, ş, Ş) vs shared (ç, Ç, ö, Ö, ü, Ü)
        val trDistinct = text1254.count { it in TURKISH_DISTINCTIVE_CHARS }
        val trShared = text1254.count { it in TURKISH_SHARED_CHARS }
        val score1254 = if (trDistinct > 0) {
            trDistinct * 25 + trShared * 5 + latinLetters * 2
        } else {
            trShared * 5 + latinLetters
        }

        // 4. Western European Windows-1252: Accents vs Icelandic penalties
        val euAccents = text1252.count { it in WESTERN_EU_ACCENTS }
        val icelandic = text1252.count { it in ICELANDIC_RARE }
        val score1252 = euAccents * 10 + latinLetters - icelandic * 50

        val scores = listOf(
            "windows-1256" to score1256,
            "windows-1251" to score1251,
            "windows-1254" to score1254,
            "windows-1252" to score1252,
        )

        val best = scores.maxByOrNull { it.second }?.first ?: "windows-1252"
        AppLogger.i(TAG, "Single-byte charset scores: $scores -> chosen $best")
        return best
    }
}

/**
 * Text normalizer, bloat regex purger, SDH auditory tag stripper, and HTML tag sanitizer.
 *
 * Implements 1:1 parity with upstream CustomSubtitleDecoderFactory and CustomSubripParser:
 * - Sanitizes malformed or valid HTML <font> tags while strictly preserving inner dialogue text (L85).
 * - Sanitizes HTML artifact tags (<span>, <p>, <div>, <br>) while preserving standard formatting (<i>, <b>, <u>) for MPV.
 * - Normalizes Unicode whitespace, zero-width characters, and control characters (trimStr parity).
 * - Purges promotional bloat advertisements (bloatRegex parity).
 * - Strips SDH auditory captions while preserving ASS alignment tags (captionRegex parity).
 */
object SubtitleSanitizer {

    /**
     * Upstream bloatRegex list from CustomSubtitleDecoderFactory.kt:72-90
     * augmented with modern subtitle scraper advertisement patterns.
     */
    val bloatPatterns: List<Regex> = listOf(
        Regex(
            """Support\s+us\s+and\s+become\s+VIP\s+member\s+to\s+remove\s+all\s+ads\s+from\s+(www\.|)OpenSubtitles(\.org|)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """Please\s+rate\s+this\s+subtitle\s+at\s+.*\s+Help\s+other\s+users\s+to\s+choose\s+the\s+best\s+subtitles""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """Contact\s(www\.|)OpenSubtitles(\.org|)\s+today""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """Advertise\s+your\s+product\s+or\s+brand\s+here""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """(?:Subtitles?\s+downloaded\s+from|Downloaded\s+from)\s+(?:www\.)?[a-zA-Z0-9.-]+\.(?:org|com|net|me|is|vip)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """https?://(?:www\.)?opensubtitles\.(?:org|com)/[^\s]+""",
            RegexOption.IGNORE_CASE
        ),
    )

    /**
     * Upstream captionRegex from SubtitlesFragment.kt:156,
     * with negative lookahead on curly braces to preserve SSA/ASS positioning tags (e.g., {\an8}, {\pos}, {\b1}).
     */
    val sdhRegex: Regex = Regex(
        """(?:-\s?)?(?:\[[\S\s]*?\]|\([\S\s]*?\)|(?<!\\)\{(?!\\)[\S\s]*?\})\s*"""
    )

    /** Pattern matching ASS style override tags like {\an8}, {\b1}, {\i1} */
    private val assTagRegex = Regex("""\{\\.*?\}""")

    /**
     * Matches HTML <font ...> opening tags, </font> closing tags, and self-closing <font.../> tags,
     * including malformed variations (unquoted attributes, extra whitespace, extra closing brackets).
     * Stripping ONLY the tag delimiters guarantees 100% preservation of inner dialogue text,
     * completely avoiding the destructive defect where the entire <font>...</font> element
     * (and its dialogue content) was stripped.
     */
    val htmlFontTagRegex: Regex = Regex(
        """<\s*/?\s*font(?:\s+[^>]*)?>+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Matches malformed font tags lacking a closing '>' bracket before dialogue text.
     * Example: `<font color="#ffffff" Hello world` -> strips `<font color="#ffffff" ` while keeping `Hello world`.
     */
    val malformedUnclosedFontTagRegex: Regex = Regex(
        """<\s*/?\s*font(?:\s+[^>]*?)?(?=\s+[^\s<]|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Matches general HTML tags that may appear as artifacts from web scrapers or subtitle rips:
     * <span>, <div>, <p>, <br>, <a>, etc., while preserving formatting tags (<i>, <b>, <u>) for MPV.
     */
    val htmlArtifactTagRegex: Regex = Regex(
        """<\s*/?\s*(?:span|p|div|br|a|style|body|html)(?:\s+[^>]*)?>+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Matches <br> line break tags to replace them with a space rather than concatenating adjacent words.
     */
    val htmlBrRegex: Regex = Regex(
        """<\s*br\s*/?>""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Matches all HTML tags (<...>) for cases where full HTML tag removal is desired
     * while preserving all enclosed dialogue text.
     */
    val allHtmlTagsRegex: Regex = Regex(
        """<[^>]+>"""
    )

    /**
     * Sanitizes malformed or valid HTML <font> tags while strictly preserving inner text.
     * Unlike naive tag-stripping that discards the entire <font>...</font> element and loses dialogue,
     * this only strips the opening, closing, self-closing, and malformed tag delimiters.
     */
    fun sanitizeHtmlFontTags(text: String): String {
        if (!text.contains("font", ignoreCase = true)) return text
        var result = text
        // 1. Remove standard and malformed font tags with '>'
        result = htmlFontTagRegex.replace(result, "")
        // 2. Remove malformed font tags without '>' preceding dialogue
        result = malformedUnclosedFontTagRegex.replace(result, "")
        return result
    }

    /**
     * Sanitizes HTML artifact tags (font, span, p, div, br, a) while preserving inner text.
     * If [preserveFormattingTags] is true, formatting tags like <i>, <b>, <u> are preserved for MPV libass.
     * If false, all HTML tags are stripped while strictly preserving all enclosed dialogue text.
     */
    fun sanitizeHtmlArtifactTags(text: String, preserveFormattingTags: Boolean = true): String {
        var result = sanitizeHtmlFontTags(text)
        if (!result.contains("<")) return result

        // Replace <br> with space so words aren't merged
        result = htmlBrRegex.replace(result, " ")

        result = if (preserveFormattingTags) {
            htmlArtifactTagRegex.replace(result, "")
        } else {
            allHtmlTagsRegex.replace(result, "")
        }
        return result
    }

    /**
     * Convenience alias for [sanitizeHtmlArtifactTags].
     */
    fun sanitizeHtmlTags(text: String, preserveFormattingTags: Boolean = true): String {
        return sanitizeHtmlArtifactTags(text, preserveFormattingTags)
    }

    /**
     * Normalizes Unicode whitespace, strips BOM, zero-width spaces, and control characters.
     * Aligns with upstream trimStr() (CustomSubtitleDecoderFactory.kt:94-99).
     */
    fun normalizeWhitespaceAndInvisibleChars(text: String): String {
        return text
            .trimStart()
            .trim('﻿', '​')
            .replace(Regex("[  -  ]"), " ")
            .replace(Regex("""[\p{Cf}]|[\p{Cntrl}&&[^\t\r\n]]"""), "")
    }

    /**
     * Sanitizes a single line of subtitle text.
     * Returns null if the line matches promotional bloat or has no remaining dialogue.
     *
     * In accordance with upstream and desktop parity:
     * - HTML font tags (valid and malformed) are stripped while strictly preserving enclosed dialogue.
     * - Promotional bloat patterns are purged.
     * - HTML artifact tags (<span>, <p>, <div>) are stripped while preserving <i>, <b>, <u> formatting.
     * - SDH auditory captions are stripped if configured, protecting ASS positioning tags.
     * - Uppercase conversion is applied if configured.
     */
    fun sanitizeLine(
        line: String,
        config: SubtitleSanitizerConfig,
    ): String? {
        var current = normalizeWhitespaceAndInvisibleChars(line).trim()
        if (current.isBlank()) return null

        // 1. Always sanitize malformed and standard HTML font tags to preserve inner text
        current = sanitizeHtmlFontTags(current).trim()
        if (current.isBlank()) return null

        // 2. Clean promotional bloat and HTML artifact tags
        if (config.removeBloat) {
            for (pattern in bloatPatterns) {
                if (pattern.containsMatchIn(current)) {
                    current = pattern.replace(current, "").trim()
                    if (current.isBlank()) return null
                }
            }
            // Strip artifact tags (<span>, <p>, <div>, <br>) preserving dialogue and standard formatting (i, b, u)
            current = sanitizeHtmlArtifactTags(current, preserveFormattingTags = true).trim()
            if (current.isBlank()) return null
        }

        // 3. Clean SDH auditory captions if requested
        if (config.removeCaptions) {
            if (sdhRegex.containsMatchIn(current)) {
                current = sdhRegex.replace(current, "").trim()
                // Check if the remaining line actually contains dialogue after removing style tags
                val dialogueOnly = assTagRegex.replace(current, "").trim()
                if (dialogueOnly.isBlank() || dialogueOnly == "-" || dialogueOnly == "–" || dialogueOnly == "—") {
                    return null
                }
            }
        }

        // 4. Caps lock text transformation
        if (config.upperCase) {
            current = current.uppercase()
        }

        return current.ifBlank { null }
    }
}

/**
 * Public entry-point engine for subtitle charset sniffing, ad purging,
 * SDH stripping, and atomic disk caching.
 */
object SubtitlePreProcessor {
    private const val TAG = "SubtitlePreProcessor"

    val cacheDirectory: File = runCatching {
        if (System.getProperty("os.name")?.lowercase()?.contains("win") == true) {
            PlatformPaths.cacheDir.resolve("subtitles").toFile().apply { mkdirs() }
        } else {
            File("/tmp/cloudstream/subtitles").apply { mkdirs() }
        }
    }.getOrElse {
        File(System.getProperty("java.io.tmpdir", "/tmp"), "cloudstream/subtitles").apply { mkdirs() }
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Preprocesses a collection of subtitle paths or URLs, converting them into clean UTF-8
     * cached local files suitable for MPV --sub-file parameters.
     */
    suspend fun processAll(
        subtitles: List<String>,
        config: SubtitleSanitizerConfig = SubtitleSanitizerConfig.fromDataStore(),
    ): List<String> = withContext(Dispatchers.IO) {
        subtitles.filter { it.isNotBlank() }.map { subPathOrUrl ->
            runCatching {
                processSingle(subPathOrUrl, config)
            }.getOrElse { error ->
                AppLogger.w(TAG, "Failed preprocessing subtitle '$subPathOrUrl', falling back to raw source: ${error.message}")
                subPathOrUrl
            }
        }
    }

    /**
     * Downloads (if remote), transcodes, sanitizes, and atomically caches a single subtitle file.
     * Returns the absolute path of the sanitized UTF-8 file.
     */
    suspend fun processSingle(
        subPathOrUrl: String,
        config: SubtitleSanitizerConfig = SubtitleSanitizerConfig.fromDataStore(),
    ): String = withContext(Dispatchers.IO) {
        val rawBytes = loadBytes(subPathOrUrl)
        val (decodedText, originalCharset) = SubtitleCharsetDetector.decodeToString(rawBytes, config.forcedEncoding)

        val format = SubtitleFormatParser.sniffFormat(decodedText)
        AppLogger.i(TAG, "Processing subtitle format $format for $subPathOrUrl (Original charset: ${originalCharset.name()})")

        val sanitizedText = when (format) {
            SubtitleFormat.SRT -> SubtitleFormatParser.processSrtOrVtt(decodedText, isVtt = false, config)
            SubtitleFormat.WEBVTT -> SubtitleFormatParser.processSrtOrVtt(decodedText, isVtt = true, config)
            SubtitleFormat.ASS -> SubtitleFormatParser.processAss(decodedText, config)
            SubtitleFormat.UNKNOWN -> {
                decodedText.lines().mapNotNull { SubtitleSanitizer.sanitizeLine(it, config) }.joinToString("\n") + "\n"
            }
        }

        val extension = when (format) {
            SubtitleFormat.WEBVTT -> "vtt"
            SubtitleFormat.ASS -> "ass"
            else -> "srt"
        }

        val fileHash = computeHash("$subPathOrUrl:${config.forcedEncoding}:${config.removeBloat}:${config.removeCaptions}:${config.upperCase}")
        val targetFile = File(cacheDirectory, "sub_${fileHash}.$extension")

        DesktopDataStore.atomicWrite(targetFile, sanitizedText.toByteArray(StandardCharsets.UTF_8))
        AppLogger.i(TAG, "Sanitized subtitle cached at: ${targetFile.absolutePath} (${targetFile.length()} bytes)")

        targetFile.absolutePath
    }

    /**
     * Sanitizes subtitle text in-memory without disk caching.
     */
    fun processText(
        content: String,
        config: SubtitleSanitizerConfig = SubtitleSanitizerConfig.fromDataStore(),
    ): String {
        val format = SubtitleFormatParser.sniffFormat(content)
        return when (format) {
            SubtitleFormat.SRT -> SubtitleFormatParser.processSrtOrVtt(content, isVtt = false, config)
            SubtitleFormat.WEBVTT -> SubtitleFormatParser.processSrtOrVtt(content, isVtt = true, config)
            SubtitleFormat.ASS -> SubtitleFormatParser.processAss(content, config)
            SubtitleFormat.UNKNOWN -> {
                content.lines().mapNotNull { SubtitleSanitizer.sanitizeLine(it, config) }.joinToString("\n") + "\n"
            }
        }
    }

    private suspend fun loadBytes(source: String): ByteArray = withContext(Dispatchers.IO) {
        if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
            val request = Request.Builder()
                .url(source)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) CloudStream-Desktop/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code} loading subtitle: $source")
                }
                response.body.bytes()
            }
        } else {
            val file = File(source)
            if (!file.exists()) {
                throw IllegalArgumentException("Local subtitle file does not exist: $source")
            }
            file.readBytes()
        }
    }

    fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Clears cached preprocessed subtitles older than [maxAgeHours].
     */
    fun pruneCache(maxAgeHours: Long = 24) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(maxAgeHours)
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
            }
        }
    }

    fun clearCache() {
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.isFile) {
                runCatching { file.delete() }
            }
        }
    }
}
