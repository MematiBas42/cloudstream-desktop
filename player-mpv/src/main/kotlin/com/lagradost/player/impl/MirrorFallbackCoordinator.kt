package com.lagradost.player.impl

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.common.logging.AppLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Sealed hierarchy for playback errors and mirror exhaustion.
 */
sealed class PlayerError(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
    object NoMirrorsRemaining : PlayerError("All candidate mirrors have been exhausted and failed.")
}

/**
 * Dynamic In-Flight Mirror Fallback & Auto-Recovery Coordinator.
 *
 * Implements 1:1 behavioral parity with upstream GeneratorPlayer.kt and CS3IPlayer.kt:
 * - Maintains candidate mirrors, current link index, and errored links.
 * - Detects alternative viable mirrors with [hasNextMirror].
 * - Automatically advances through candidate mirrors via [nextMirror].
 * - Reinitializes mirror pool on new episode / media via [reset].
 */
class MirrorFallbackCoordinator(
    private val player: MpvPlayer? = null,
) {
    companion object {
        private const val TAG = "MirrorFallbackCoordinator"
    }

    val candidateMirrors: MutableList<ExtractorLink> = mutableListOf()
    var currentLinkIndex: Int = -1
    val erroredLinks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val lock = Any()

    val currentLink: ExtractorLink?
        get() = synchronized(lock) { candidateMirrors.getOrNull(currentLinkIndex) }

    /**
     * Initializes the candidate mirror pool for the active episode.
     */
    fun reset(links: List<ExtractorLink>) {
        synchronized(lock) {
            candidateMirrors.clear()
            candidateMirrors.addAll(links)
            erroredLinks.clear()
            currentLinkIndex = if (links.isNotEmpty()) 0 else -1
            AppLogger.d(TAG, "Reset candidate mirrors with ${links.size} links")
        }
    }

    /**
     * Checks if an un-errored candidate link exists.
     */
    fun hasNextMirror(): Boolean {
        synchronized(lock) {
            val currentUrl = candidateMirrors.getOrNull(currentLinkIndex)?.url
            return candidateMirrors.any { link ->
                link.url !in erroredLinks && (currentUrl == null || link.url != currentUrl)
            }
        }
    }

    /**
     * Marks current link as errored, finds next viable candidate link.
     */
    fun nextMirror(): ExtractorLink? {
        synchronized(lock) {
            // Mark current link as errored
            candidateMirrors.getOrNull(currentLinkIndex)?.url?.let { erroredLinks.add(it) }

            // Find next un-errored candidate link (starting after currentLinkIndex, or any un-errored)
            val nextIndex = candidateMirrors.indices.firstOrNull { idx ->
                idx > currentLinkIndex && candidateMirrors[idx].url !in erroredLinks
            } ?: candidateMirrors.indices.firstOrNull { idx ->
                candidateMirrors[idx].url !in erroredLinks
            }

            return if (nextIndex != null) {
                currentLinkIndex = nextIndex
                val next = candidateMirrors[nextIndex]
                AppLogger.i(TAG, "Selected next mirror [index $nextIndex]: ${next.name} (${next.url})")
                next
            } else {
                AppLogger.w(TAG, "No viable mirrors remaining in candidate pool (${candidateMirrors.size} total, ${erroredLinks.size} errored)")
                null
            }
        }
    }

    /**
     * Dynamically appends newly discovered background mirrors to the candidate pool.
     */
    fun addCandidateLinks(links: List<ExtractorLink>) {
        synchronized(lock) {
            val novel = links.filter { link -> candidateMirrors.none { it.url == link.url } }
            if (novel.isNotEmpty()) {
                candidateMirrors.addAll(novel)
                AppLogger.d(TAG, "Appended ${novel.size} background mirrors (Total: ${candidateMirrors.size})")
            }
        }
    }

    /**
     * Marks a specific link as errored.
     */
    fun markErrored(link: ExtractorLink) {
        erroredLinks.add(link.url)
    }

    /**
     * Marks a specific URL as errored.
     */
    fun markErrored(url: String) {
        erroredLinks.add(url)
    }
}
