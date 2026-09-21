package com.lagradost.common.download

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Linux-native download queue orchestrator and mirror failover manager.
 *
 * Capabilities:
 * - Multi-connection segmented byte-range downloader (splitting into 10 MiB chunks).
 * - HLS progressive stream recorder (.m3u8 demuxing and sequential .ts segment appending).
 * - In-flight mirror failover: if active mirror drops, captures byte offset and resumes seamlessly from next candidate mirror.
 * - Freedesktop XDG-compliant storage: saves to $XDG_DATA_HOME/cloudstream/downloads/.
 * - Crash-resilient queue persistence in DataStore (download_queue_key, download_resume_queue_key).
 */
object LinuxDownloadManager {
    const val QUEUE_KEY = "download_queue_key"
    const val RESUME_KEY = "download_resume_queue_key"
    const val COMPLETED_FILES_KEY = "download_info"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    var concurrencyLimit: Int = 3
        set(value) {
            field = value
            semaphore = Semaphore(value)
        }

    private var semaphore = Semaphore(concurrencyLimit)

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val _progressMap = MutableStateFlow<Map<Int, DownloadProgress>>(emptyMap())
    val progressMap: StateFlow<Map<Int, DownloadProgress>> = _progressMap.asStateFlow()

    private val activeStreamDownloaders = ConcurrentHashMap<Int, LinuxStreamDownloader>()
    private val activeHlsDownloaders = ConcurrentHashMap<Int, LinuxHlsDownloader>()
    private val activeJobs = ConcurrentHashMap<Int, Job>()

    /**
     * Crash recovery initialization: restores queued and in-flight downloads from DataStore.
     */
    fun init() {
        val savedQueue = try {
            val json = DesktopDataStore.cache[QUEUE_KEY]
            if (!json.isNullOrBlank()) {
                DesktopDataStore.mapper.readValue(json, object : TypeReference<List<DownloadItem>>() {})
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            AppLogger.w("LinuxDownloadManager", "Failed to deserialize saved queue: ${e.message}")
            emptyList()
        }

        val savedResumes = try {
            DesktopDataStore.cache.keys
                .filter { it.startsWith("$RESUME_KEY/") || it == RESUME_KEY }
                .mapNotNull { key ->
                    try {
                        val json = DesktopDataStore.cache[key] ?: return@mapNotNull null
                        if (json.startsWith("[")) {
                            val list = DesktopDataStore.mapper.readValue(json, object : TypeReference<List<DownloadResumePackage>>() {})
                            list.firstOrNull()
                        } else {
                            DesktopDataStore.mapper.readValue(json, DownloadResumePackage::class.java)
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
        } catch (e: Exception) {
            AppLogger.w("LinuxDownloadManager", "Failed to deserialize resume packages: ${e.message}")
            emptyList()
        }

        val recovered = (savedResumes.map { it.item } + savedQueue).distinctBy { it.id }
        _queue.value = recovered

        recovered.forEach { item ->
            updateProgress(
                item.id,
                DownloadProgress(
                    id = item.id,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    bytesPerSecond = 0L,
                    state = DownloadStatus.Pending,
                    totalMirrors = item.links.size
                )
            )
        }

        AppLogger.i("LinuxDownloadManager", "Initialized download manager. Recovered ${recovered.size} items from crash storage.")
        processQueue()
    }

    /**
     * Enqueues a download item.
     */
    fun enqueue(item: DownloadItem) {
        if (_queue.value.any { it.id == item.id }) return
        _queue.value = _queue.value + item
        persistQueue()
        updateProgress(
            item.id,
            DownloadProgress(
                id = item.id,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                bytesPerSecond = 0L,
                state = DownloadStatus.Pending,
                totalMirrors = item.links.size
            )
        )
        processQueue()
    }

    fun enqueue(descriptor: DownloadItemDescriptor) {
        enqueue(descriptor.toItem())
    }

    fun pause(id: Int) {
        activeStreamDownloaders[id]?.pause()
        activeHlsDownloaders[id]?.pause()
        activeJobs[id]?.cancel()
        activeStreamDownloaders.remove(id)
        activeHlsDownloaders.remove(id)
        activeJobs.remove(id)

        val curr = _progressMap.value[id]
        if (curr != null) {
            updateProgress(id, curr.copy(state = DownloadStatus.Paused, bytesPerSecond = 0L))
        }
    }

    fun resume(id: Int) {
        val curr = _progressMap.value[id]
        if (curr != null) {
            updateProgress(id, curr.copy(state = DownloadStatus.Pending))
        }
        processQueue()
    }

    fun cancel(id: Int) {
        activeStreamDownloaders[id]?.cancel()
        activeHlsDownloaders[id]?.cancel()
        activeJobs[id]?.cancel()
        activeStreamDownloaders.remove(id)
        activeHlsDownloaders.remove(id)
        activeJobs.remove(id)

        _queue.value = _queue.value.filterNot { it.id == id }
        persistQueue()
        DesktopDataStore.removeKey("$RESUME_KEY/$id")
        updateProgress(
            id,
            DownloadProgress(
                id = id,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                bytesPerSecond = 0L,
                state = DownloadStatus.Stopped
            )
        )
    }

    fun processQueue() {
        scope.launch {
            _queue.value.forEach { item ->
                val currentProgress = _progressMap.value[item.id]
                if (currentProgress?.state == DownloadStatus.Pending && !activeJobs.containsKey(item.id)) {
                    val job = launch {
                        semaphore.acquire()
                        try {
                            executeDownloadWithMirrorFailover(item)
                        } finally {
                            semaphore.release()
                            activeJobs.remove(item.id)
                            processQueue()
                        }
                    }
                    activeJobs[item.id] = job
                }
            }
        }
    }

    private suspend fun executeDownloadWithMirrorFailover(item: DownloadItem) {
        val relFolder = LinuxDownloadStorage.getRelativeFolder(
            tvType = item.ep.type,
            titleName = item.titleName,
            season = item.ep.season,
            episode = item.ep.episode
        )
        val displayName = LinuxDownloadStorage.getDisplayName(
            titleName = item.titleName,
            season = item.ep.season,
            episode = item.ep.episode,
            episodeName = item.episodeName,
            isMovie = item.ep.season == null && item.ep.episode == null
        )

        val targetFile = LinuxDownloadStorage.resolveTargetFile(relFolder, displayName, "mp4")
        val partFile = LinuxDownloadStorage.resolvePartFile(targetFile)

        // Check for existing checkpoint in DataStore
        val savedCheckpoint = DesktopDataStore.getKey<DownloadResumePackage>("$RESUME_KEY/${item.id}")
        val startMirrorIndex = savedCheckpoint?.linkIndex ?: 0
        var lastExtraInfo = savedCheckpoint?.extraInfo

        updateProgress(
            item.id,
            DownloadProgress(
                id = item.id,
                bytesDownloaded = if (partFile.exists()) partFile.length() else 0L,
                totalBytes = savedCheckpoint?.totalBytes ?: 0L,
                bytesPerSecond = 0L,
                state = DownloadStatus.Downloading,
                activeMirrorIndex = startMirrorIndex,
                totalMirrors = item.links.size
            )
        )

        var succeeded = false
        var lastError: String? = null

        for (index in startMirrorIndex until item.links.size) {
            val link = item.links[index]
            AppLogger.i("LinuxDownloadManager", "Attempting mirror ${index + 1}/${item.links.size}: ${link.name} (${link.url})")

            // Persist resume state
            val currentOffset = if (partFile.exists()) partFile.length() else 0L
            DesktopDataStore.setKey(
                "$RESUME_KEY/${item.id}",
                DownloadResumePackage(
                    item = item,
                    linkIndex = index,
                    bytesDownloaded = currentOffset,
                    totalBytes = 0L,
                    extraInfo = lastExtraInfo
                )
            )

            updateProgress(
                item.id,
                DownloadProgress(
                    id = item.id,
                    bytesDownloaded = currentOffset,
                    totalBytes = 0L,
                    bytesPerSecond = 0L,
                    state = DownloadStatus.Downloading,
                    activeMirrorIndex = index,
                    totalMirrors = item.links.size
                )
            )

            val success = if (link.isM3u8) {
                val initialSegment = lastExtraInfo?.toIntOrNull() ?: 0
                val hlsDownloader = LinuxHlsDownloader(
                    client = client,
                    targetFile = targetFile,
                    link = link,
                    initialSegmentIndex = initialSegment,
                    onProgress = { down, total, speed, seg, totalSeg ->
                        lastExtraInfo = seg.toString()
                        updateProgress(
                            item.id,
                            DownloadProgress(
                                id = item.id,
                                bytesDownloaded = down,
                                totalBytes = total,
                                bytesPerSecond = speed,
                                state = DownloadStatus.Downloading,
                                activeMirrorIndex = index,
                                totalMirrors = item.links.size
                            )
                        )
                    }
                )
                activeHlsDownloaders[item.id] = hlsDownloader
                try {
                    hlsDownloader.download()
                } finally {
                    activeHlsDownloaders.remove(item.id)
                }
            } else {
                val streamDownloader = LinuxStreamDownloader(
                    client = client,
                    targetFile = targetFile,
                    link = link,
                    parallelConnections = 3,
                    chunkSize = 10L * 1024L * 1024L,
                    onProgress = { down, total, speed ->
                        updateProgress(
                            item.id,
                            DownloadProgress(
                                id = item.id,
                                bytesDownloaded = down,
                                totalBytes = total,
                                bytesPerSecond = speed,
                                state = DownloadStatus.Downloading,
                                activeMirrorIndex = index,
                                totalMirrors = item.links.size
                            )
                        )
                    }
                )
                activeStreamDownloaders[item.id] = streamDownloader
                try {
                    streamDownloader.download()
                } finally {
                    activeStreamDownloaders.remove(item.id)
                }
            }

            if (success) {
                succeeded = true
                break
            } else {
                // Check if user paused or cancelled
                val curState = _progressMap.value[item.id]?.state
                if (curState == DownloadStatus.Paused || curState == DownloadStatus.Stopped) {
                    return
                }

                // In-flight mirror failover: capture byte offset and continue to next mirror
                val droppedOffset = if (partFile.exists()) partFile.length() else 0L
                lastError = "Mirror ${index + 1} (${link.name}) failed at offset $droppedOffset"
                AppLogger.w("LinuxDownloadManager", "$lastError. Failing over to mirror ${index + 2}...")
            }
        }

        if (succeeded) {
            val finalLen = targetFile.length()
            updateProgress(
                item.id,
                DownloadProgress(
                    id = item.id,
                    bytesDownloaded = finalLen,
                    totalBytes = finalLen,
                    bytesPerSecond = 0L,
                    state = DownloadStatus.Completed,
                    totalMirrors = item.links.size
                )
            )
            _queue.value = _queue.value.filterNot { it.id == item.id }
            persistQueue()
            DesktopDataStore.removeKey("$RESUME_KEY/${item.id}")

            // Persist completed metadata
            val completedInfo = LocalDownloadedFileInfo(
                id = item.id,
                parentId = item.parentId,
                absoluteFilePath = targetFile.absolutePath,
                displayName = displayName,
                totalBytes = finalLen,
                extraInfo = lastExtraInfo
            )
            DesktopDataStore.setKey("$COMPLETED_FILES_KEY/${item.id}", completedInfo)
            AppLogger.i("LinuxDownloadManager", "Download completed successfully: ${targetFile.absolutePath}")
        } else {
            val curState = _progressMap.value[item.id]?.state
            if (curState != DownloadStatus.Paused && curState != DownloadStatus.Stopped) {
                updateProgress(
                    item.id,
                    DownloadProgress(
                        id = item.id,
                        bytesDownloaded = if (partFile.exists()) partFile.length() else 0L,
                        totalBytes = 0L,
                        bytesPerSecond = 0L,
                        state = DownloadStatus.Failed,
                        errorMessage = lastError ?: "All mirrors exhausted"
                    )
                )
            }
        }
    }

    private fun updateProgress(id: Int, progress: DownloadProgress) {
        _progressMap.value = _progressMap.value + (id to progress)
    }

    private fun persistQueue() {
        DesktopDataStore.setKey(QUEUE_KEY, _queue.value)
    }

    fun getCompletedFile(id: Int): LocalDownloadedFileInfo? {
        return DesktopDataStore.getKey<LocalDownloadedFileInfo>("$COMPLETED_FILES_KEY/$id")
    }

    fun getAllCompletedFiles(): List<LocalDownloadedFileInfo> {
        return DesktopDataStore.cache.keys
            .filter { it.startsWith("$COMPLETED_FILES_KEY/") }
            .mapNotNull { key -> DesktopDataStore.getKey<LocalDownloadedFileInfo>(key) }
    }

    fun reset() {
        activeStreamDownloaders.values.forEach { it.cancel() }
        activeHlsDownloaders.values.forEach { it.cancel() }
        activeJobs.values.forEach { it.cancel() }
        activeStreamDownloaders.clear()
        activeHlsDownloaders.clear()
        activeJobs.clear()
        _queue.value = emptyList()
        _progressMap.value = emptyMap()
    }
}
