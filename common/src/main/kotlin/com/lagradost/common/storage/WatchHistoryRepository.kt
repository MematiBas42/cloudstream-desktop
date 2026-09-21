package com.lagradost.common.storage

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.io.Serializable
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Active series-level resume pointer matching upstream DownloadObjects.ResumeWatching.
 * Stored under parentId to indicate which episode is currently queued for resume.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResumeWatching(
    @param:JsonProperty("parentId") val parentId: String,
    @param:JsonProperty("episodeId") val episodeId: String?,
    @param:JsonProperty("episode") val episode: Int?,
    @param:JsonProperty("season") val season: Int?,
    @param:JsonProperty("updateTime") val updateTime: Long = System.currentTimeMillis(),
    @param:JsonProperty("isFromDownload") val isFromDownload: Boolean = false,
)

/**
 * Canonical container for physical video playback position and duration in MILLISECONDS.
 * Replicates upstream com.lagradost.cloudstream3.utils.DataStoreHelper.PosDur.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PosDur(
    @param:JsonProperty("position") val position: Long = 0L,
    @param:JsonProperty("duration") val duration: Long = 0L,
) : Serializable {

    val percentage: Int
        get() = if (duration <= 0L) 0 else ((position * 100L) / duration).toInt()

    fun fixVisual(): PosDur {
        val visualPos = WatchHistoryRepository.getDisplayPosition(position, duration)
        return PosDur(visualPos, duration)
    }

    fun getRealPosition(): Long = WatchHistoryRepository.getRealPosition(position, duration)

    fun getWatchProgress(): Float {
        if (duration <= 0L) return 0f
        val visualPos = fixVisual().position
        val durSec = (duration / 1000L).toFloat()
        if (durSec <= 0f) return 0f
        val posSec = (visualPos / 1000L).toFloat()
        return (posSec / durSec).coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_DURATION_TO_SAVE_MS = 30_000L
        const val NEXT_WATCH_EPISODE_PERCENTAGE = 90
    }
}

/**
 * Progress clamping functions matching upstream ResultFragment.kt / DataStoreHelper.kt
 */
fun getDisplayPosition(position: Long, duration: Long): Long =
    WatchHistoryRepository.getDisplayPosition(position, duration)

fun getRealPosition(position: Long, duration: Long): Long =
    WatchHistoryRepository.getRealPosition(position, duration)

fun WatchHistory.getDisplayPosition(): Long =
    WatchHistoryRepository.getDisplayPosition(position, duration)

fun WatchHistory.getRealPosition(): Long =
    WatchHistoryRepository.getRealPosition(position, duration)

fun WatchHistory.getWatchProgress(): Float {
    if (duration <= 0L) return 0f
    val visualPos = getDisplayPosition()
    val durSec = (duration / 1000L).toFloat()
    if (durSec <= 0f) return 0f
    val posSec = (visualPos / 1000L).toFloat()
    return (posSec / durSec).coerceIn(0f, 1f)
}

/**
 * High-performance, thread-safe repository managing watch history and resume pointers.
 * Standardized on MILLISECONDS with automatic legacy seconds migration.
 */
object WatchHistoryRepository {
    const val MIN_DURATION_TO_SAVE_MS = 30_000L
    const val NEXT_WATCH_EPISODE_PERCENTAGE = 90

    private val mapper: ObjectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val lock = Any()
    private val historyCache = mutableListOf<WatchHistory>()
    private val resumePointers = mutableMapOf<String, ResumeWatching>()

    val historyUpdates = MutableStateFlow(0)

    private val historyPath: Path get() = PlatformPaths.dataDir.resolve("history.json")
    private val resumePath: Path get() = PlatformPaths.dataDir.resolve("resume_watching.json")

    private val historyFile: File get() = historyPath.toFile()
    private val resumeFile: File get() = resumePath.toFile()

    init {
        loadFromFile()
    }

    fun init() {
        try {
            historyFile.parentFile?.mkdirs()
        } catch (e: Exception) {
            AppLogger.w("WatchHistoryRepository", "Initialization warning: ${e.message}")
        }
    }

    private fun loadFromFile() {
        synchronized(lock) {
            try {
                // 1. Load History Items
                if (historyFile.exists() && historyFile.length() > 0) {
                    val rawList: List<WatchHistory> = mapper.readValue(
                        historyFile,
                        object : TypeReference<List<WatchHistory>>() {}
                    )
                    historyCache.clear()
                    historyCache.addAll(rawList.map { migrateRecordIfNeeded(it) })
                } else {
                    val legacy = DesktopDataStore.getKey<List<WatchHistory>>("user_watch_history")
                    if (!legacy.isNullOrEmpty()) {
                        historyCache.clear()
                        historyCache.addAll(legacy.map { migrateRecordIfNeeded(it) })
                        saveToFile()
                    }
                }

                // 2. Load Resume Pointers
                if (resumeFile.exists() && resumeFile.length() > 0) {
                    val pointers: List<ResumeWatching> = mapper.readValue(
                        resumeFile,
                        object : TypeReference<List<ResumeWatching>>() {}
                    )
                    resumePointers.clear()
                    pointers.forEach { resumePointers[it.parentId] = it }
                }
            } catch (e: Exception) {
                AppLogger.e("WatchHistoryRepository", "Failed to load watch history", e)
            }
        }
    }

    fun migrateRecordIfNeeded(record: WatchHistory): WatchHistory {
        // If duration is greater than 0 and less than 30,000, it was stored in seconds (max 8.3h).
        // 30,000ms is 30s. Media files are >= 30s.
        if (record.duration in 1 until MIN_DURATION_TO_SAVE_MS) {
            return record.copy(
                position = record.position * 1000L,
                duration = record.duration * 1000L
            )
        }
        return record
    }

    private fun saveToFile() {
        try {
            val list = synchronized(lock) { historyCache.sortedByDescending { it.updateTime } }
            val pointers = synchronized(lock) { resumePointers.values.toList() }

            val historyBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(list)
            DesktopDataStore.atomicWrite(historyPath, historyBytes)

            val resumeBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pointers)
            DesktopDataStore.atomicWrite(resumePath, resumeBytes)
        } catch (e: Exception) {
            AppLogger.e("WatchHistoryRepository", "Failed to persist watch history", e)
        }
    }

    /**
     * Upstream ResultFragment.kt getDisplayPosition:
     * - duration <= 0 -> 0
     * - percentage <= 1 -> 0
     * - percentage <= 5 -> 5 * duration / 100
     * - percentage >= 95 -> duration
     * - else -> position
     */
    fun getDisplayPosition(position: Long, duration: Long): Long {
        if (duration <= 0L) return 0L
        val percentage = position * 100L / duration
        return when {
            percentage <= 1L -> 0L
            percentage <= 5L -> 5L * duration / 100L
            percentage >= 95L -> duration
            else -> position
        }
    }

    /**
     * Upstream ResultFragment.kt getRealPosition:
     * - duration <= 0 -> 0
     * - percentage <= 5 || percentage >= 95 -> 0 (resume from beginning)
     * - else -> position
     */
    fun getRealPosition(position: Long, duration: Long): Long {
        if (duration <= 0L) return 0L
        val percentage = position * 100L / duration
        if (percentage <= 5L || percentage >= 95L) return 0L
        return position
    }

    /**
     * Upstream DataStoreHelper.setViewPosAndResume equivalent.
     * Commits per-episode telemetry and advances series resume pointer.
     */
    fun setViewPosAndResume(
        parentId: String?,
        episodeId: String?,
        positionMs: Long,
        durationMs: Long,
        currentEpisode: WatchHistory? = null,
        nextEpisode: WatchHistory? = null,
    ) {
        if (durationMs < MIN_DURATION_TO_SAVE_MS) return

        synchronized(lock) {
            val normalizedDur = durationMs.coerceAtLeast(0L)
            val normalizedPos = positionMs.coerceIn(0L, normalizedDur)
            val percentage = if (normalizedDur > 0L) (normalizedPos * 100L / normalizedDur).toInt() else 0
            val isCompleted = percentage >= NEXT_WATCH_EPISODE_PERCENTAGE

            // 1. Commit/Update Current Episode History Record
            val targetUrl = currentEpisode?.url ?: episodeId ?: parentId ?: return
            val updatedRecord = (currentEpisode ?: WatchHistory(
                url = targetUrl,
                parentId = parentId,
                episodeId = episodeId,
            )).copy(
                position = normalizedPos,
                duration = normalizedDur,
                isCompleted = isCompleted,
                updateTime = System.currentTimeMillis()
            )

            historyCache.removeAll { existing ->
                existing.url == updatedRecord.url ||
                (parentId != null && existing.parentId == parentId &&
                 existing.episodeId != null && existing.episodeId == episodeId)
            }
            historyCache.add(updatedRecord)

            // 2. Manage Series-Level Resume Pointer (90% Auto-Next Rule)
            if (parentId != null) {
                if (isCompleted) {
                    if (nextEpisode != null) {
                        // Advance to next episode
                        resumePointers[parentId] = ResumeWatching(
                            parentId = parentId,
                            episodeId = nextEpisode.episodeId ?: nextEpisode.url,
                            episode = nextEpisode.episode,
                            season = nextEpisode.season,
                            updateTime = System.currentTimeMillis(),
                        )
                        AppLogger.i("WatchHistoryRepository", "Advanced resume pointer for $parentId to S${nextEpisode.season}E${nextEpisode.episode}")
                    } else {
                        // Series finale or standalone movie: Prune from Continue Watching
                        resumePointers.remove(parentId)
                        AppLogger.i("WatchHistoryRepository", "Pruned completed show/movie $parentId from Continue Watching")
                    }
                } else {
                    // Retain current episode pointer
                    resumePointers[parentId] = ResumeWatching(
                        parentId = parentId,
                        episodeId = episodeId ?: updatedRecord.episodeId ?: updatedRecord.url,
                        episode = currentEpisode?.episode ?: updatedRecord.episode,
                        season = currentEpisode?.season ?: updatedRecord.season,
                        updateTime = System.currentTimeMillis(),
                    )
                }
            }

            saveToFile()
            historyUpdates.value++
        }
    }

    /**
     * Backward-compatible setter for direct WatchHistory commits.
     */
    fun setLastWatched(history: WatchHistory) {
        val normalizedRecord = migrateRecordIfNeeded(history)
        if (normalizedRecord.duration < MIN_DURATION_TO_SAVE_MS) return
        setViewPosAndResume(
            parentId = normalizedRecord.parentId,
            episodeId = normalizedRecord.episodeId,
            positionMs = normalizedRecord.position,
            durationMs = normalizedRecord.duration,
            currentEpisode = normalizedRecord,
            nextEpisode = null,
        )
    }

    fun setLastWatched(
        parentId: String,
        episodeId: String?,
        episode: Int?,
        season: Int?,
        isFromDownload: Boolean = false,
        updateTime: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            resumePointers[parentId] = ResumeWatching(
                parentId = parentId,
                episodeId = episodeId,
                episode = episode,
                season = season,
                updateTime = updateTime,
                isFromDownload = isFromDownload,
            )
            saveToFile()
            historyUpdates.value++
        }
    }

    fun removeLastWatched(parentId: String?) {
        if (parentId == null) return
        synchronized(lock) {
            val removed = resumePointers.remove(parentId) != null
            if (removed) {
                saveToFile()
                historyUpdates.value++
            }
        }
    }

    fun getResumePointer(parentId: String): ResumeWatching? {
        synchronized(lock) {
            return resumePointers[parentId]
        }
    }

    fun getLastWatched(parentId: String?, episodeId: String?): WatchHistory? {
        if (parentId == null && episodeId == null) return null
        synchronized(lock) {
            return historyCache.filter { item ->
                if (parentId != null && episodeId != null) {
                    (item.parentId == parentId && item.episodeId == episodeId) ||
                    (item.url == episodeId && item.parentId == parentId)
                } else if (parentId != null) {
                    item.parentId == parentId || item.url == parentId
                } else {
                    item.episodeId == episodeId || item.url == episodeId
                }
            }.maxByOrNull { it.updateTime }
        }
    }

    fun getLastWatched(parentId: String?): WatchHistory? = getLastWatched(parentId, null)

    fun getEpisodeWatched(parentId: String, episodeId: String?): WatchHistory? =
        getLastWatched(parentId, episodeId)

    fun getAllWatchHistory(): List<WatchHistory> {
        synchronized(lock) {
            return historyCache.sortedByDescending { it.updateTime }
        }
    }

    fun getAll(): List<WatchHistory> = getAllWatchHistory()

    fun removeWatchHistory(url: String) {
        synchronized(lock) {
            val removedHistory = historyCache.removeAll { it.url == url || it.parentId == url || it.episodeId == url }
            val removedResume = resumePointers.remove(url) != null
            if (removedHistory || removedResume) {
                saveToFile()
                historyUpdates.value++
            }
        }
    }

    fun removeByParent(parentId: String) {
        removeWatchHistory(parentId)
        removeLastWatched(parentId)
    }

    fun save(history: WatchHistory) = setLastWatched(history)

    fun saveProgress(
        parentId: String?,
        episodeId: String?,
        positionMs: Long,
        durationMs: Long,
        currentEpisode: WatchHistory? = null,
        nextEpisode: WatchHistory? = null,
    ) = setViewPosAndResume(parentId, episodeId, positionMs, durationMs, currentEpisode, nextEpisode)

    fun clearAll() {
        synchronized(lock) {
            historyCache.clear()
            resumePointers.clear()
            saveToFile()
            historyUpdates.value++
        }
    }
}
