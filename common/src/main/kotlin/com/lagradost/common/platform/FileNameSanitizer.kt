package com.lagradost.common.platform

import java.util.zip.CRC32

object FileNameSanitizer {

    const val MAX_FILE_NAME_LENGTH = 100
    const val MAX_DIRECTORY_NAME_LENGTH = 80
    const val DEFAULT_FALLBACK_NAME = "unnamed"

    private val FORBIDDEN_CHARS_REGEX = Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]")
    private val LEADING_SPACES_REGEX = Regex("^[\\s]+")
    private val MULTIPLE_SPACES_REGEX = Regex("\\s+")
    private val MULTIPLE_UNDERSCORES_REGEX = Regex("_+")

    private val DOS_DEVICE_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    // Compound extensions recognized to keep whole
    private val COMPOUND_EXTENSIONS = listOf(".mp4.part", ".mkv.part", ".tar.gz", ".tar.bz2")

    /**
     * Sanitizes a file name (stem + extension) for Windows NTFS, FAT32, and POSIX filesystems.
     * Preserves extension, cleans stem, avoids DOS device names, and deterministic hash truncation
     * when exceeding maxLength.
     */
    fun sanitizeFileName(
        name: String?,
        maxLength: Int = MAX_FILE_NAME_LENGTH,
        replacement: String = "_",
        preserveExtension: Boolean = true
    ): String {
        if (name.isNullOrBlank()) return DEFAULT_FALLBACK_NAME

        val rawTrimmed = name.trim()
        val (stem, ext) = if (preserveExtension) {
            splitNameAndExtension(rawTrimmed)
        } else {
            rawTrimmed to ""
        }

        val sanitizedStem = cleanSegment(stem, replacement)
        val sanitizedExt = if (ext.isNotEmpty()) {
            val cleanExt = cleanSegment(ext.removePrefix("."), replacement)
            if (cleanExt.isNotEmpty()) ".$cleanExt" else ""
        } else ""

        var finalStem = sanitizedStem.ifEmpty { DEFAULT_FALLBACK_NAME }

        // DOS Device Name check (at stem level)
        if (isReservedDeviceBaseName(finalStem)) {
            finalStem = "_$finalStem"
        }

        // Length budget and truncation
        val fullLength = finalStem.length + sanitizedExt.length
        if (fullLength > maxLength) {
            val hash = computeHashSuffix(rawTrimmed)
            val hashTag = "_$hash"
            val availableStemLength = maxLength - sanitizedExt.length - hashTag.length

            finalStem = if (availableStemLength > 0) {
                finalStem.take(availableStemLength).trimEnd('.', ' ', '_') + hashTag
            } else {
                hash
            }
        }

        val candidate = "$finalStem$sanitizedExt"
        return candidate.ifBlank { DEFAULT_FALLBACK_NAME }
    }

    /**
     * Sanitizes a directory/folder name. Directory names do not have extensions.
     */
    fun sanitizeDirectoryName(
        name: String?,
        maxLength: Int = MAX_DIRECTORY_NAME_LENGTH,
        replacement: String = "_"
    ): String {
        return sanitizeFileName(
            name = name,
            maxLength = maxLength,
            replacement = replacement,
            preserveExtension = false
        )
    }

    /**
     * Sanitizes a full or relative path segment by segment.
     */
    fun sanitizePath(path: String): String {
        if (path.isBlank()) return ""
        val normalized = path.replace('\\', '/')
        val segments = normalized.split('/')
        return segments.mapIndexed { index, segment ->
            if (segment.isEmpty()) {
                ""
            } else if (index == segments.lastIndex && !normalized.endsWith('/') && segment.contains('.')) {
                sanitizeFileName(segment)
            } else {
                sanitizeDirectoryName(segment)
            }
        }.joinToString("/")
    }

    private fun cleanSegment(segment: String, replacement: String): String {
        var res = segment.replace(FORBIDDEN_CHARS_REGEX, replacement)
        res = res.replace(MULTIPLE_SPACES_REGEX, " ")
        if (replacement.isNotEmpty()) {
            val escapedRep = Regex.escape(replacement)
            res = res.replace(Regex("$escapedRep+"), replacement)
        }
        res = res.replace(MULTIPLE_UNDERSCORES_REGEX, "_")
        res = res.replace(LEADING_SPACES_REGEX, "")
        val charsToTrim = mutableListOf('.', ' ')
        for (ch in replacement) charsToTrim.add(ch)
        charsToTrim.add('_')
        res = res.trimEnd(*charsToTrim.toCharArray())
        return res
    }

    fun splitNameAndExtension(fileName: String): Pair<String, String> {
        val lower = fileName.lowercase()
        for (compound in COMPOUND_EXTENSIONS) {
            if (lower.endsWith(compound)) {
                val stem = fileName.substring(0, fileName.length - compound.length)
                val ext = fileName.substring(fileName.length - compound.length)
                return stem to ext
            }
        }

        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0 && lastDotIndex < fileName.length - 1) {
            val stem = fileName.substring(0, lastDotIndex)
            val ext = fileName.substring(lastDotIndex)
            stem to ext
        } else {
            fileName to ""
        }
    }

    fun isReservedWindowsDeviceName(name: String): Boolean {
        val (stem, _) = splitNameAndExtension(name)
        return isReservedDeviceBaseName(stem)
    }

    private fun isReservedDeviceBaseName(baseName: String): Boolean {
        return DOS_DEVICE_NAMES.contains(baseName.trim().uppercase())
    }

    fun escapeReservedWindowsDeviceName(name: String, prefix: String = "_"): String {
        return if (isReservedWindowsDeviceName(name)) {
            "$prefix$name"
        } else {
            name
        }
    }

    fun computeHashSuffix(input: String): String {
        val crc = CRC32()
        crc.update(input.toByteArray(Charsets.UTF_8))
        return String.format("%08x", crc.value)
    }
}
