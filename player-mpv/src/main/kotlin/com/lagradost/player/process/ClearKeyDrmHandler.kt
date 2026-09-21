package com.lagradost.player.process

import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.Base64

/**
 * Handles ClearKey DRM parameters for external MPV playback.
 * Decodes Base64/URL-Safe Base64 KID and Key into hex strings and formats them
 * as demuxer parameters for ffmpeg/lavf.
 */
object ClearKeyDrmHandler {
    fun decodeBase64ToHex(b64: String?): String? {
        if (b64.isNullOrBlank()) return null
        val trimmed = b64.trim()
        val normalized = trimmed.padEnd((trimmed.length + 3) / 4 * 4, '=')
        val bytes = runCatching { Base64.getUrlDecoder().decode(normalized) }.getOrElse {
            runCatching { Base64.getDecoder().decode(normalized) }.getOrNull()
        } ?: return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun extractClearKeyHex(link: ExtractorLink): Pair<String, String>? {
        val drm = link as? DrmExtractorLink ?: return null
        val keyHex = decodeBase64ToHex(drm.key) ?: return null
        val kidHex = decodeBase64ToHex(drm.kid) ?: return null
        return Pair(keyHex, kidHex)
    }

    fun getLavfDrmArgs(link: ExtractorLink): List<String> {
        val (keyHex, kidHex) = extractClearKeyHex(link) ?: return emptyList()
        return listOf("--demuxer-lavf-o-append=decryption_key=$keyHex,decryption_kid=$kidHex")
    }
}
