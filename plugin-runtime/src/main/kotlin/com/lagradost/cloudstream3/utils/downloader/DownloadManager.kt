// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils.downloader

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.IDownloadableMinimum
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.VideoDownloadService
import com.lagradost.cloudstream3.sortUrls
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_NAVIGATE_TO
import com.lagradost.cloudstream3.ui.player.LOADTYPE_INAPP_DOWNLOAD
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getLinkPriority
import com.lagradost.cloudstream3.ui.result.ExtractorSubtitleLink
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import com.lagradost.cloudstream3.utils.AppContextUtils.sortSubs
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToEnglishLanguageName
import com.lagradost.cloudstream3.utils.SubtitleUtils.deleteMatchingSubtitles
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getDefaultDir
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFileName
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getFolder
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.toFile
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.CreateNotificationMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeMetadata
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadItem
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadResumePackage
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadStatus
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfoResult
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.LazyStreamDownloadResponse
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.StreamData
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.appendAndDontOverride
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.cancel
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.downloadSubtitle
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getEstimatedTimeLeft
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.getImageBitmapFromUrl
import com.lagradost.cloudstream3.utils.downloader.DownloadUtils.join
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.io.SafeFileOperations
import com.lagradost.common.logging.AppLogger
import com.lagradost.safefile.SafeFile
import com.lagradost.safefile.closeQuietly
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val DOWNLOAD_CHANNEL_ID = "cloudstream3.general"
const val DOWNLOAD_CHANNEL_NAME = "Downloads"
const val DOWNLOAD_CHANNEL_DESCRIPT = "The download notification channel"

object VideoDownloadManager {
    fun maxConcurrentDownloads(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            ?.getInt(context.getString(R.string.download_parallel_key), 3) ?: 3

    private fun maxConcurrentConnections(context: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            ?.getInt(context.getString(R.string.download_concurrent_key), 3) ?: 3

    private val _currentDownloads: MutableStateFlow<Set<Int>> = MutableStateFlow(emptySet())
    val currentDownloads: StateFlow<Set<Int>> = _currentDownloads

    const val TAG = "VDM"
    private const val DOWNLOAD_NOTIFICATION_TAG = "FROM_DOWNLOADER"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    @get:DrawableRes
    val imgDone get() = R.drawable.rddone

    @get:DrawableRes
    val imgDownloading get() = R.drawable.rdload

    @get:DrawableRes
    val imgPaused get() = R.drawable.rdpause

    @get:DrawableRes
    val imgStopped get() = R.drawable.rderror

    @get:DrawableRes
    val imgError get() = R.drawable.rderror

    @get:DrawableRes
    val pressToPauseIcon get() = R.drawable.ic_baseline_pause_24

    @get:DrawableRes
    val pressToResumeIcon get() = R.drawable.ic_baseline_play_arrow_24

    @get:DrawableRes
    val pressToStopIcon get() = R.drawable.baseline_stop_24

    enum class DownloadType {
        IsPaused,
        IsDownloading,
        IsDone,
        IsFailed,
        IsStopped,
        IsPending
    }

    enum class DownloadActionType {
        Pause,
        Resume,
        Stop,
    }

    /** Invalid input, just skip to the next one as the same args will give the same error */
    private val DOWNLOAD_INVALID_INPUT =
        DownloadStatus(retrySame = false, tryNext = true, success = false)

    /** no need to try any other mirror as we have downloaded the file */
    private val DOWNLOAD_SUCCESS =
        DownloadStatus(retrySame = false, tryNext = false, success = true)

    /** the user pressed stop, so no need to download anything else */
    private val DOWNLOAD_STOPPED =
        DownloadStatus(retrySame = false, tryNext = false, success = true)

    /** the process failed due to some reason, so we retry and also try the next mirror */
    private val DOWNLOAD_FAILED = DownloadStatus(retrySame = true, tryNext = true, success = false)

    /** The download only downloaded partial */
    private val DOWNLOAD_PARTIAL_SUCCESS =
        DownloadStatus(retrySame = true, tryNext = false, success = true)

    /** 50MB minimum size */
    const val DOWNLOAD_PARTIAL_MIN_SIZE = 1_048_576L * 50L

    /** bad config, skip all mirrors as every call to download will have the same bad config */
    private val DOWNLOAD_BAD_CONFIG =
        DownloadStatus(retrySame = false, tryNext = false, success = false)

    const val KEY_RESUME_PACKAGES = "download_resume_2"
    const val KEY_DOWNLOAD_INFO = "download_info"

    /** A key to save all the downloads which have not yet started and those currently running, using [DownloadQueueWrapper]
     * [KEY_RESUME_PACKAGES] can store keys which should not be automatically queued, unlike this key.
     */
    const val KEY_RESUME_IN_QUEUE = "download_resume_queue_key"

    val downloadStatus = HashMap<Int, DownloadType>()
    val downloadStatusEvent = Event<Pair<Int, DownloadType>>()
    val downloadDeleteEvent = Event<Int>()
    val downloadEvent = Event<Pair<Int, DownloadActionType>>()
    val downloadProgressEvent = Event<Triple<Int, Long, Long>>()

    fun pause(id: Int) {
        downloadEvent.invoke(id to DownloadActionType.Pause)
    }

    fun resume(id: Int) {
        downloadEvent.invoke(id to DownloadActionType.Resume)
    }

    fun delete(context: Context, id: Int): Boolean {
        return deleteFileAndUpdateSettings(context, id)
    }

    private var hasCreatedNotChannel = false

    private fun Context.createNotificationChannel() {
        hasCreatedNotChannel = true

        this.createNotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            DOWNLOAD_CHANNEL_NAME,
            DOWNLOAD_CHANNEL_DESCRIPT
        )
    }

    fun cancelAllDownloadNotifications(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.activeNotifications.forEach { notification ->
            if (notification.tag == DOWNLOAD_NOTIFICATION_TAG) {
                manager.cancel(DOWNLOAD_NOTIFICATION_TAG, notification.id)
            }
        }
    }

    /**
     * @param hlsProgress will together with hlsTotal display another notification if used, to lessen the confusion about estimated size.
     * */
    private suspend fun createDownloadNotification(
        context: Context,
        source: String?,
        linkName: String?,
        ep: DownloadEpisodeMetadata,
        state: DownloadType,
        progress: Long,
        total: Long,
        notificationCallback: (Int, Notification) -> Unit,
        hlsProgress: Long? = null,
        hlsTotal: Long? = null,
        bytesPerSecond: Long
    ): Notification? {
        try {
            if (total <= 0) return null

            val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setAutoCancel(true)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(ep.mainName)
                .setSmallIcon(
                    when (state) {
                        DownloadType.IsDone -> imgDone
                        DownloadType.IsDownloading -> imgDownloading
                        DownloadType.IsPaused -> imgPaused
                        DownloadType.IsFailed -> imgError
                        DownloadType.IsStopped -> imgStopped
                        DownloadType.IsPending -> imgDownloading
                    }
                )

            if (ep.sourceApiName != null) {
                builder.setSubText(ep.sourceApiName)
            }

            if (source != null) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    data = source.toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent =
                    PendingIntentCompat.getActivity(context, 0, intent, 0, false)
                builder.setContentIntent(pendingIntent)
            }

            if (state == DownloadType.IsDownloading || state == DownloadType.IsPaused) {
                builder.setProgress((total / 1000).toInt(), (progress / 1000).toInt(), false)
            } else if (state == DownloadType.IsPending) {
                builder.setProgress(0, 0, true)
            }

            val rowTwoExtra = if (ep.name != null) " - ${ep.name}\n" else ""
            val rowTwo = if (ep.season != null && ep.episode != null) {
                "${context.getString(R.string.season_short)}${ep.season}:${context.getString(R.string.episode_short)}${ep.episode}" + rowTwoExtra
            } else if (ep.episode != null) {
                "${context.getString(R.string.episode)} ${ep.episode}" + rowTwoExtra
            } else {
                (ep.name ?: "") + ""
            }
            val downloadFormat = context.getString(R.string.download_format)

            if (SDK_INT >= Build.VERSION_CODES.O) {
                if (ep.poster != null) {
                    val poster = withContext(Dispatchers.IO) {
                        context.getImageBitmapFromUrl(ep.poster)
                    }
                    if (poster != null)
                        builder.setLargeIcon(poster)
                }

                val progressPercentage: Long
                val progressMbString: String
                val totalMbString: String
                val suffix: String

                val mbFormat = "%.1f MB"

                if (hlsProgress != null && hlsTotal != null) {
                    progressPercentage = hlsProgress * 100 / hlsTotal
                    progressMbString = hlsProgress.toString()
                    totalMbString = hlsTotal.toString()
                    suffix = " - $mbFormat".format(progress / 1000000f)
                } else {
                    progressPercentage = progress * 100 / total
                    progressMbString = mbFormat.format(progress / 1000000f)
                    totalMbString = mbFormat.format(total / 1000000f)
                    suffix = ""
                }

                val mbPerSecondString =
                    if (state == DownloadType.IsDownloading) {
                        " ($mbFormat/s)".format(bytesPerSecond.toFloat() / 1000000f)
                    } else ""

                val remainingTime =
                    if (state == DownloadType.IsDownloading) {
                        getEstimatedTimeLeft(context, bytesPerSecond, progress, total)
                    } else ""

                val bigText =
                    when (state) {
                        DownloadType.IsDownloading, DownloadType.IsPaused -> {
                            (if (linkName == null) "" else "$linkName\n") + "$rowTwo\n$progressPercentage % ($progressMbString/$totalMbString)$suffix$mbPerSecondString $remainingTime"
                        }

                        DownloadType.IsPending -> {
                            (if (linkName == null) "" else "$linkName\n") + rowTwo
                        }

                        DownloadType.IsFailed -> {
                            downloadFormat.format(
                                context.getString(R.string.download_failed),
                                rowTwo
                            )
                        }

                        DownloadType.IsDone -> {
                            downloadFormat.format(context.getString(R.string.download_done), rowTwo)
                        }

                        DownloadType.IsStopped -> {
                            downloadFormat.format(
                                context.getString(R.string.download_canceled),
                                rowTwo
                            )
                        }
                    }

                val bodyStyle = NotificationCompat.BigTextStyle()
                bodyStyle.bigText(bigText)
                builder.setStyle(bodyStyle)
            } else {
                val txt =
                    when (state) {
                        DownloadType.IsDownloading, DownloadType.IsPaused, DownloadType.IsPending -> {
                            rowTwo
                        }

                        DownloadType.IsFailed -> {
                            downloadFormat.format(
                                context.getString(R.string.download_failed),
                                rowTwo
                            )
                        }

                        DownloadType.IsDone -> {
                            downloadFormat.format(context.getString(R.string.download_done), rowTwo)
                        }

                        DownloadType.IsStopped -> {
                            downloadFormat.format(
                                context.getString(R.string.download_canceled),
                                rowTwo
                            )
                        }
                    }

                builder.setContentText(txt)
            }

            if ((state == DownloadType.IsDownloading || state == DownloadType.IsPaused || state == DownloadType.IsPending) && SDK_INT >= Build.VERSION_CODES.O) {
                val actionTypes: MutableList<DownloadActionType> = ArrayList()
                if (state == DownloadType.IsDownloading) {
                    actionTypes.add(DownloadActionType.Pause)
                    actionTypes.add(DownloadActionType.Stop)
                }

                if (state == DownloadType.IsPaused) {
                    actionTypes.add(DownloadActionType.Resume)
                    actionTypes.add(DownloadActionType.Stop)
                }
                if (state == DownloadType.IsPending) {
                    actionTypes.add(DownloadActionType.Stop)
                }

                for ((index, i) in actionTypes.withIndex()) {
                    val actionResultIntent = Intent(context, VideoDownloadService::class.java)

                    actionResultIntent.putExtra(
                        "type", when (i) {
                            DownloadActionType.Resume -> "resume"
                            DownloadActionType.Pause -> "pause"
                            DownloadActionType.Stop -> "stop"
                        }
                    )

                    actionResultIntent.putExtra("id", ep.id)

                    val pending: PendingIntent = PendingIntent.getService(
                        context, (4337 + index * 1000000 + ep.id),
                        actionResultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    builder.addAction(
                        NotificationCompat.Action(
                            when (i) {
                                DownloadActionType.Resume -> pressToResumeIcon
                                DownloadActionType.Pause -> pressToPauseIcon
                                DownloadActionType.Stop -> pressToStopIcon
                            }, when (i) {
                                DownloadActionType.Resume -> context.getString(R.string.resume)
                                DownloadActionType.Pause -> context.getString(R.string.pause)
                                DownloadActionType.Stop -> context.getString(R.string.cancel)
                            }, pending
                        )
                    )
                }
            }

            if (!hasCreatedNotChannel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            notificationCallback(ep.id, notification)
            with(NotificationManagerCompat.from(context)) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return null
                }
                notify(DOWNLOAD_NOTIFICATION_TAG, ep.id, notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    @Throws(IOException::class)
    fun setupStream(
        context: Context,
        name: String,
        folder: String?,
        extension: String,
        tryResume: Boolean,
    ): StreamData {
        return setupStream(
            context.getBasePath().first ?: getDefaultDir(context)
            ?: throw IOException("Bad config"),
            name,
            folder,
            extension,
            tryResume
        )
    }

    /**
     * Sets up the appropriate file and creates a data stream from the file.
     * Uses a .part temporary file during download, committed atomically upon completion.
     * */
    @Throws(IOException::class)
    fun setupStream(
        baseFile: SafeFile,
        name: String,
        folder: String?,
        extension: String,
        tryResume: Boolean,
    ): StreamData {
        val displayName = getDisplayName(name, extension)
        val partName = "$displayName.part"

        val subDir = baseFile.gotoDirectory(folder, createMissingDirectories = true)
            ?: throw IOException("Cant create directory")
        val foundPartFile = subDir.findFile(partName)
        val foundFinalFile = subDir.findFile(displayName)

        val (file, fileLength) = if (foundPartFile != null && foundPartFile.exists()) {
            if (tryResume) {
                foundPartFile to foundPartFile.lengthOrThrow()
            } else {
                foundPartFile.deleteOrThrow()
                subDir.createFileOrThrow(partName) to 0L
            }
        } else if (foundFinalFile != null && foundFinalFile.exists()) {
            if (tryResume) {
                foundFinalFile to foundFinalFile.lengthOrThrow()
            } else {
                foundFinalFile.deleteOrThrow()
                subDir.createFileOrThrow(partName) to 0L
            }
        } else {
            subDir.createFileOrThrow(partName) to 0L
        }

        return StreamData(fileLength, file)
    }

    /**
     * Atomically commits a .part file to its final destination file.
     */
    internal fun commitDownloadedFile(partFile: SafeFile, finalFile: SafeFile) {
        if (!partFile.exists()) return
        val srcPath = partFile.javaFile.toPath()
        val dstPath = finalFile.javaFile.toPath()

        try {
            SafeFileOperations.safeMove(srcPath, dstPath, verifyIntegrity = false)
        } catch (e: Exception) {
            logError(e)
            throw e
        }
    }

    /** This class handles the notifications, as well as the relevant key */
    data class DownloadMetaData(
        private val id: Int?,
        private val linkHash: Int,
        var bytesDownloaded: Long = 0,
        var bytesWritten: Long = 0,

        var totalBytes: Long? = null,

        private var lastUpdatedMs: Long = 0,
        private var lastDownloadedBytes: Long = 0,
        private val createNotificationCallback: (CreateNotificationMetadata) -> Unit,

        private var internalType: DownloadType = DownloadType.IsPending,
        val isHLS: Boolean,
        var hlsProgress: Int = 0,
        var hlsTotal: Int? = null,
        var hlsWrittenProgress: Int = 0,

        private var downloadFileInfoTemplate: DownloadedFileInfo? = null
    ) : Closeable {
        fun setResumeLength(length: Long) {
            bytesDownloaded = length
            bytesWritten = length
            lastDownloadedBytes = length
        }

        fun failedStatus() = if (this.bytesWritten > DOWNLOAD_PARTIAL_MIN_SIZE)
            DOWNLOAD_PARTIAL_SUCCESS
        else
            DOWNLOAD_FAILED

        val approxTotalBytes: Long
            get() = totalBytes ?: hlsTotal?.let { total ->
                (bytesDownloaded * (total / hlsProgress.toFloat())).toLong()
            } ?: bytesDownloaded

        private var stopListener: (() -> Unit)? = null

        fun setOnStop(callback: (() -> Unit)) {
            stopListener = callback
        }

        fun removeStopListener() {
            stopListener = null
        }

        private val downloadEventListener = { event: Pair<Int, DownloadActionType> ->
            if (event.first == id) {
                when (event.second) {
                    DownloadActionType.Pause -> {
                        type = DownloadType.IsPaused
                    }

                    DownloadActionType.Stop -> {
                        type = DownloadType.IsStopped
                        stopListener?.invoke()
                        stopListener = null
                    }

                    DownloadActionType.Resume -> {
                        type = DownloadType.IsDownloading
                    }
                }
            }
        }

        private fun updateFileInfo() {
            if (id == null) return
            val template = downloadFileInfoTemplate ?: return
            val totalBytesValue = if (approxTotalBytes <= bytesDownloaded) {
                val prevInfo = DataStore.getKey<DownloadedFileInfo>(
                    KEY_DOWNLOAD_INFO,
                    id.toString()
                )

                if (prevInfo != null && prevInfo.linkHash == linkHash) {
                    totalBytes ?: maxOf(prevInfo.totalBytes, bytesDownloaded)
                } else {
                    approxTotalBytes
                }
            } else {
                approxTotalBytes
            }

            DataStore.setKey(
                KEY_DOWNLOAD_INFO,
                id.toString(),
                template.copy(
                    linkHash = linkHash,
                    totalBytes = totalBytesValue,
                    extraInfo = if (isHLS) hlsWrittenProgress.toString() else null
                )
            )
        }

        fun setDownloadFileInfoTemplate(template: DownloadedFileInfo) {
            downloadFileInfoTemplate = template
            updateFileInfo()
        }

        init {
            if (id != null) {
                downloadEvent += downloadEventListener
            }
        }

        override fun close() {
            if (isHLS || totalBytes == null) {
                updateFileInfo()
            }
            if (id != null) {
                downloadEvent -= downloadEventListener
                downloadStatus -= id
            }
            stopListener = null
        }

        var type
            get() = internalType
            set(value) {
                internalType = value
                notify()
            }

        fun onDelete() {
            bytesDownloaded = 0
            hlsWrittenProgress = 0
            hlsProgress = 0
            if (id != null)
                downloadDeleteEvent(id)

            notify()
        }

        companion object {
            const val UPDATE_RATE_MS: Long = 1000L
        }

        @JvmName("DownloadMetaDataNotify")
        private fun notify() {
            val dt = (System.currentTimeMillis() - lastUpdatedMs).coerceIn(100, 10000)

            val bytesPerSecond =
                ((bytesDownloaded - lastDownloadedBytes) * 1000L) / dt

            lastDownloadedBytes = bytesDownloaded
            lastUpdatedMs = System.currentTimeMillis()
            try {
                val bytes = approxTotalBytes

                if (isHLS) {
                    createNotificationCallback(
                        CreateNotificationMetadata(
                            internalType,
                            bytesDownloaded,
                            bytes,
                            hlsTotal = hlsTotal?.toLong(),
                            hlsProgress = hlsProgress.toLong(),
                            bytesPerSecond = bytesPerSecond
                        )
                    )
                } else {
                    createNotificationCallback(
                        CreateNotificationMetadata(
                            internalType,
                            bytesDownloaded,
                            bytes,
                            bytesPerSecond = bytesPerSecond
                        )
                    )
                }

                if (isHLS) {
                    updateFileInfo()
                }

                if (internalType == DownloadType.IsStopped || internalType == DownloadType.IsFailed) {
                    stopListener?.invoke()
                    stopListener = null
                }

                if (id != null) {
                    downloadStatus[id] = type
                    downloadProgressEvent(Triple(id, bytesDownloaded, bytes))
                    downloadStatusEvent(id to type)
                }
            } catch (t: Throwable) {
                logError(t)
                if (BuildConfig.DEBUG) {
                    throw t
                }
            }
        }

        private fun checkNotification() {
            if (lastUpdatedMs + UPDATE_RATE_MS > System.currentTimeMillis()) return
            notify()
        }

        fun addBytes(length: Long) {
            bytesDownloaded += length
            if (type == DownloadType.IsDownloading) checkNotification()
        }

        fun addBytesWritten(length: Long) {
            bytesWritten += length
        }

        fun addSegment(length: Long) {
            hlsProgress += 1
            addBytes(length)
        }

        fun setWrittenSegment(segmentIndex: Int) {
            hlsWrittenProgress = segmentIndex + 1
            updateFileInfo()
        }
    }

    data class LazyStreamDownloadData(
        private val url: String,
        private val headers: Map<String, String>,
        private val referer: String,
        private val chuckStartByte: LongArray,
        val totalLength: Long?,
        val downloadLength: Long?,
        val chuckSize: Long,
        val bufferSize: Int,
        val isResumed: Boolean,
    ) {
        val size get() = chuckStartByte.size

        @Throws
        private suspend fun resolve(
            startByte: Long,
            endByte: Long?,
            buffer: ByteArray,
            callback: (suspend CoroutineScope.(LazyStreamDownloadResponse) -> Unit)
        ): Long = withContext(Dispatchers.IO) {
            var currentByte: Long = startByte
            val stopAt = endByte ?: Long.MAX_VALUE
            if (currentByte >= stopAt) return@withContext currentByte

            val request = app.get(
                url,
                headers = headers + mapOf(
                    "Range" to "bytes=$startByte-"
                ),
                referer = referer,
                verify = false
            )
            val requestStream = request.body.byteStream()

            var read: Int

            try {
                while (requestStream.read(buffer, 0, bufferSize).also { read = it } >= 0) {
                    val start = currentByte
                    currentByte += read.toLong()

                    if (currentByte >= stopAt) {
                        callback(LazyStreamDownloadResponse(buffer, start, stopAt))
                        break
                    } else {
                        callback(LazyStreamDownloadResponse(buffer, start, currentByte))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logError(t)
            } finally {
                requestStream.closeQuietly()
            }

            return@withContext currentByte
        }

        suspend fun resolveSafe(
            index: Int,
            retries: Int = 3,
            buffer: ByteArray,
            callback: (suspend CoroutineScope.(LazyStreamDownloadResponse) -> Unit)
        ): Boolean {
            var start = chuckStartByte.getOrNull(index) ?: return false
            val end = chuckStartByte.getOrNull(index + 1)

            for (i in 0 until retries) {
                try {
                    start = resolve(start, end, buffer, callback)
                    if (end == null) return true
                    if (start >= end) return true
                } catch (_: IllegalStateException) {
                    return false
                } catch (_: CancellationException) {
                    return false
                } catch (_: Throwable) {
                    continue
                }
            }
            return false
        }
    }

    @Throws
    suspend fun streamLazy(
        url: String,
        headers: Map<String, String>,
        referer: String,
        startByte: Long,
        chuckSize: Long = (1 shl 20) * 10,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
        maximumSmallSize: Long = chuckSize * 2
    ): LazyStreamDownloadData {
        require(chuckSize > 1000)

        val headRequest = app.head(url = url, headers = headers, referer = referer, verify = false)
        var contentLength = headRequest.size
        if (contentLength != null && contentLength <= 0) contentLength = null

        val hasRangeSupport = when (headRequest.headers["Accept-Ranges"]?.lowercase()?.trim()) {
            "none" -> false
            "bytes" -> true
            else -> {
                headRequest.headers["Accept-Ranges"]?.let { range ->
                    AppLogger.v(TAG, "Unknown Accept-Ranges tag: $range")
                }
                val getRequest = app.get(
                    url,
                    headers = headers + mapOf(
                        "Range" to "bytes=0-${
                            contentLength?.let { max ->
                                minOf(maxOf(max - 1L, 3L), 1023L)
                            } ?: 1023L
                        }"
                    ),
                    referer = referer,
                    verify = false
                )
                if (contentLength == null) {
                    contentLength =
                        getRequest.headers["Content-Range"]?.trim()?.lowercase()?.let { range ->
                            if (range.startsWith("bytes")) {
                                range.substringAfter("/").toLongOrNull()
                            } else {
                                AppLogger.v(TAG, "Unknown Content-Range unit: $range")
                                null
                            }
                        }
                }

                getRequest.code == 206
            }
        }

        AppLogger.d(
            TAG,
            "Starting stream with url=$url, startByte=$startByte, contentLength=$contentLength, hasRangeSupport=$hasRangeSupport"
        )

        var downloadLength: Long? = null

        val ranges = if (!hasRangeSupport) {
            downloadLength = contentLength
            LongArray(1) { 0 }
        } else if (contentLength == null || contentLength < maximumSmallSize) {
            if (contentLength != null) {
                downloadLength = contentLength - startByte
            }
            LongArray(1) { startByte }
        } else {
            downloadLength = contentLength - startByte
            LongArray(((downloadLength + chuckSize - 1) / chuckSize).toInt()) { idx ->
                startByte + idx * chuckSize
            }
        }

        return LazyStreamDownloadData(
            url = url,
            headers = headers,
            referer = referer,
            chuckStartByte = ranges,
            downloadLength = downloadLength,
            totalLength = contentLength,
            chuckSize = chuckSize,
            bufferSize = bufferSize,
            isResumed = startByte > 0 && hasRangeSupport
        )
    }

    /** download a file that consist of a single stream of data */
    suspend fun downloadThing(
        context: Context,
        link: IDownloadableMinimum,
        name: String,
        folder: String,
        extension: String,
        tryResume: Boolean,
        parentId: Int?,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
        parallelConnections: Int = 3,
        minimumSize: Long = 100
    ): DownloadStatus = withContext(Dispatchers.IO) {
        if (parallelConnections < 1) {
            return@withContext DOWNLOAD_INVALID_INPUT
        }

        var fileStream: OutputStream? = null
        val metadata = DownloadMetaData(
            totalBytes = 0,
            bytesDownloaded = 0,
            createNotificationCallback = createNotificationCallback,
            id = parentId,
            linkHash = link.url.hashCode(),
            isHLS = false
        )
        try {
            val (baseFile, basePath) = context.getBasePath()
            val displayName = getDisplayName(name, extension)
            if (baseFile == null) return@withContext DOWNLOAD_BAD_CONFIG

            var stream = setupStream(baseFile, name, folder, extension, tryResume)

            fileStream = stream.open()

            metadata.setResumeLength(stream.startAt)
            metadata.type = DownloadType.IsPending

            val items = streamLazy(
                url = link.url.replace(" ", "%20"),
                referer = link.referer,
                startByte = stream.startAt,
                headers = link.headers.appendAndDontOverride(
                    mapOf(
                        "user-agent" to USER_AGENT,
                    )
                )
            )

            if (items.totalLength != null && items.totalLength < minimumSize) {
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_INVALID_INPUT
            }

            if (!items.isResumed && stream.startAt > 0) {
                fileStream.closeQuietly()
                stream.delete()
                metadata.setResumeLength(0)
                stream = setupStream(baseFile, name, folder, extension, false)
                fileStream = stream.open()
            }

            metadata.totalBytes = items.totalLength
            metadata.type = DownloadType.IsDownloading
            metadata.setDownloadFileInfoTemplate(
                DownloadedFileInfo(
                    totalBytes = metadata.approxTotalBytes,
                    relativePath = folder,
                    displayName = displayName,
                    basePath = basePath
                )
            )

            val currentMutex = Mutex()
            val current = (0 until items.size).iterator()

            val fileMutex = Mutex()
            val pendingData: HashMap<Long, LazyStreamDownloadResponse> = hashMapOf()

            val fileChecker = launch(Dispatchers.IO) {
                while (isActive) {
                    if (stream.exists) {
                        delay(5000)
                        continue
                    }
                    fileMutex.withLock {
                        metadata.type = DownloadType.IsStopped
                    }
                    break
                }
            }

            val jobs = (0 until parallelConnections).map {
                launch(Dispatchers.IO) {
                    val callback: (suspend CoroutineScope.(LazyStreamDownloadResponse) -> Unit) =
                        callback@{ response ->
                            if (!isActive) return@callback
                            fileMutex.withLock {
                                while (metadata.type == DownloadType.IsPaused) delay(100)
                                if (metadata.type == DownloadType.IsStopped || metadata.type == DownloadType.IsFailed) {
                                    this.cancel()
                                    return@callback
                                }

                                val responseSize = response.size
                                metadata.addBytes(response.size)

                                val out = fileStream ?: return@callback
                                if (response.startByte == metadata.bytesWritten) {
                                    out.write(
                                        response.bytes,
                                        0,
                                        responseSize.toInt()
                                    )
                                    metadata.addBytesWritten(responseSize)
                                } else {
                                    pendingData[response.startByte] =
                                        response.copy(bytes = response.bytes.clone())
                                }

                                while (true) {
                                    val pending = pendingData.remove(metadata.bytesWritten) ?: break

                                    val size = pending.size

                                    out.write(
                                        pending.bytes,
                                        0,
                                        size.toInt()
                                    )
                                    metadata.addBytesWritten(size)
                                }
                            }
                        }

                    val buffer = ByteArray(items.bufferSize)

                    while (true) {
                        if (!isActive) return@launch

                        var isTooFarAhead = false
                        fileMutex.withLock {
                            if (metadata.type == DownloadType.IsStopped
                                || metadata.type == DownloadType.IsFailed
                            ) return@launch

                            if (metadata.bytesDownloaded - metadata.bytesWritten > 50_000_000) {
                                isTooFarAhead = true
                            }
                        }

                        if (isTooFarAhead) {
                            delay(500)
                            continue
                        }

                        val index = currentMutex.withLock {
                            if (!current.hasNext()) return@launch
                            current.nextInt()
                        }

                        if (!items.resolveSafe(index, buffer = buffer, callback = callback)) {
                            fileMutex.withLock {
                                if (metadata.type != DownloadType.IsStopped) {
                                    metadata.type = DownloadType.IsFailed
                                }
                            }
                            return@launch
                        }
                    }
                }
            }

            metadata.setOnStop {
                jobs.cancel()
            }

            jobs.join()
            fileChecker.cancel()

            metadata.removeStopListener()
            if (!stream.exists) metadata.type = DownloadType.IsStopped

            if (metadata.type == DownloadType.IsFailed) {
                return@withContext metadata.failedStatus()
            }

            if (metadata.type == DownloadType.IsStopped) {
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_STOPPED
            }

            if (metadata.bytesDownloaded < minimumSize) {
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_INVALID_INPUT
            }

            fileStream.flush()
            fileStream.closeQuietly()
            fileStream = null

            // Commit .part file to final destination atomically
            val subDir = baseFile.gotoDirectory(folder, createMissingDirectories = true)
            if (subDir != null) {
                val finalFile = subDir.findFile(displayName) ?: SafeFile(File(subDir.javaFile, displayName))
                commitDownloadedFile(stream.file, finalFile)
            }

            metadata.type = DownloadType.IsDone
            return@withContext DOWNLOAD_SUCCESS
        } catch (e: IOException) {
            logError(e)
            throw e
        } catch (t: Throwable) {
            logError(t)
            metadata.type = DownloadType.IsFailed
            return@withContext metadata.failedStatus()
        } finally {
            fileStream?.closeQuietly()
            metadata.close()
        }
    }

    private suspend fun downloadHLS(
        context: Context,
        link: ExtractorLink,
        name: String,
        folder: String,
        parentId: Int?,
        startIndex: Int?,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
        parallelConnections: Int = 3
    ): DownloadStatus = withContext(Dispatchers.IO) {
        if (parallelConnections < 1) return@withContext DOWNLOAD_INVALID_INPUT

        val metadata = DownloadMetaData(
            createNotificationCallback = createNotificationCallback,
            id = parentId,
            linkHash = link.url.hashCode(),
            isHLS = true
        )
        var fileStream: OutputStream? = null
        try {
            val extension = "mp4"
            var startAt = startIndex ?: 0

            val (baseFile, basePath) = context.getBasePath()
            if (baseFile == null) return@withContext DOWNLOAD_BAD_CONFIG

            val displayName = getDisplayName(name, extension)
            val stream =
                setupStream(baseFile, name, folder, extension, startAt > 0)

            if (!stream.resume) startAt = 0
            fileStream = stream.open()

            metadata.setResumeLength(stream.startAt)
            metadata.hlsProgress = startAt
            metadata.hlsWrittenProgress = startAt
            metadata.type = DownloadType.IsPending
            metadata.setDownloadFileInfoTemplate(
                DownloadedFileInfo(
                    totalBytes = 0,
                    relativePath = folder,
                    displayName = displayName,
                    basePath = basePath
                )
            )

            val m3u8 = M3u8Helper.M3u8Stream(
                link.url, link.quality, link.headers.appendAndDontOverride(
                    mapOf(
                        "user-agent" to USER_AGENT,
                    ) + if (link.referer.isNotBlank()) mapOf("referer" to link.referer) else emptyMap()
                )
            )

            val items = M3u8Helper2.hslLazy(m3u8, selectBest = true, requireAudio = true)

            metadata.hlsTotal = items.size
            metadata.type = DownloadType.IsDownloading

            val currentMutex = Mutex()
            val current = (startAt until items.size).iterator()

            val fileMutex = Mutex()
            val pendingData: HashMap<Int, ByteArray> = hashMapOf()

            val fileChecker = launch(Dispatchers.IO) {
                while (isActive) {
                    if (stream.exists) {
                        delay(5000)
                        continue
                    }
                    fileMutex.withLock {
                        metadata.type = DownloadType.IsStopped
                    }
                    break
                }
            }

            val jobs = (0 until parallelConnections).map {
                launch(Dispatchers.IO) {
                    while (true) {
                        if (!isActive) return@launch

                        var isTooFarAhead = false
                        fileMutex.withLock {
                            if (metadata.type == DownloadType.IsStopped
                                || metadata.type == DownloadType.IsFailed
                            ) return@launch

                            if (metadata.bytesDownloaded - metadata.bytesWritten > 50_000_000) {
                                isTooFarAhead = true
                            }
                        }

                        if (isTooFarAhead) {
                            delay(500)
                            continue
                        }

                        val index = currentMutex.withLock {
                            if (!current.hasNext()) return@launch
                            current.nextInt()
                        }

                        val bytes = items.resolveLinkSafe(index) ?: run {
                            fileMutex.withLock {
                                if (metadata.type != DownloadType.IsStopped) {
                                    metadata.type = DownloadType.IsFailed
                                }
                            }
                            return@launch
                        }

                        fileMutex.withLock {
                            try {
                                while (metadata.type == DownloadType.IsPaused) delay(100)
                                if (metadata.type == DownloadType.IsStopped || metadata.type == DownloadType.IsFailed || !isActive) return@launch

                                val segmentLength = bytes.size.toLong()
                                metadata.addSegment(segmentLength)

                                val out = fileStream ?: return@launch
                                if (metadata.hlsWrittenProgress == index) {
                                    out.write(bytes)

                                    metadata.addBytesWritten(segmentLength)
                                    metadata.setWrittenSegment(index)
                                } else {
                                    pendingData[index] = bytes
                                }

                                while (true) {
                                    val cache =
                                        pendingData.remove(metadata.hlsWrittenProgress) ?: break
                                    val cacheLength = cache.size.toLong()

                                    out.write(cache)

                                    metadata.addBytesWritten(cacheLength)
                                    metadata.setWrittenSegment(metadata.hlsWrittenProgress)
                                }
                            } catch (t: Throwable) {
                                logError(t)
                                if (metadata.type != DownloadType.IsStopped) {
                                    metadata.type = DownloadType.IsFailed
                                }
                            }
                        }
                    }
                }
            }

            metadata.setOnStop {
                jobs.cancel()
            }

            jobs.join()
            fileChecker.cancel()

            metadata.removeStopListener()

            if (!stream.exists) metadata.type = DownloadType.IsStopped

            if (metadata.type == DownloadType.IsFailed) {
                return@withContext metadata.failedStatus()
            }

            if (metadata.type == DownloadType.IsStopped) {
                fileStream.closeQuietly()
                metadata.onDelete()
                stream.delete()
                return@withContext DOWNLOAD_STOPPED
            }

            fileStream.flush()
            fileStream.closeQuietly()
            fileStream = null

            val subDir = baseFile.gotoDirectory(folder, createMissingDirectories = true)
            if (subDir != null) {
                val finalFile = subDir.findFile(displayName) ?: SafeFile(File(subDir.javaFile, displayName))
                commitDownloadedFile(stream.file, finalFile)
            }

            metadata.type = DownloadType.IsDone
            return@withContext DOWNLOAD_SUCCESS
        } catch (t: Throwable) {
            logError(t)
            metadata.type = DownloadType.IsFailed
            return@withContext metadata.failedStatus()
        } finally {
            fileStream?.closeQuietly()
            metadata.close()
        }
    }

    private fun getDisplayName(name: String, extension: String): String {
        return "$name.$extension"
    }

    private suspend fun downloadSingleEpisode(
        context: Context,
        source: String?,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        link: ExtractorLink,
        notificationCallback: (Int, Notification) -> Unit,
        tryResume: Boolean = false,
    ): DownloadStatus {
        if (link.type == ExtractorLinkType.MAGNET || link.type == ExtractorLinkType.TORRENT || link.type == ExtractorLinkType.DASH) {
            return DOWNLOAD_INVALID_INPUT
        }

        val name = getFileName(context, ep)

        val extractorJob = ioSafe {
            if (link.extractorData != null) {
                getApiFromNameNull(link.source)?.extractorVerifierJob(link.extractorData)
            }
        }

        val callback: (CreateNotificationMetadata) -> Unit = { meta ->
            main {
                createDownloadNotification(
                    context,
                    source,
                    link.name,
                    ep,
                    meta.type,
                    meta.bytesDownloaded,
                    meta.bytesTotal,
                    notificationCallback,
                    meta.hlsProgress,
                    meta.hlsTotal,
                    meta.bytesPerSecond
                )
            }
        }

        try {
            when (link.type) {
                ExtractorLinkType.M3U8 -> {
                    val startIndex = if (tryResume) {
                        DataStore.getKey<DownloadedFileInfo>(
                            KEY_DOWNLOAD_INFO,
                            ep.id.toString(),
                            null
                        )?.extraInfo?.toIntOrNull()
                    } else null

                    return downloadHLS(
                        context,
                        link,
                        name,
                        folder ?: "",
                        ep.id,
                        startIndex,
                        callback, parallelConnections = maxConcurrentConnections(context)
                    )
                }

                ExtractorLinkType.VIDEO -> {
                    return downloadThing(
                        context,
                        link,
                        name,
                        folder ?: "",
                        "mp4",
                        tryResume,
                        ep.id,
                        callback,
                        parallelConnections = maxConcurrentConnections(context),
                        minimumSize = (1 shl 20) * 10
                    )
                }

                else -> throw IllegalArgumentException("Unsupported download type")
            }
        } catch (_: Throwable) {
            return DOWNLOAD_FAILED
        } finally {
            extractorJob.cancel()
        }
    }

    fun getDownloadFileInfo(
        context: Context,
        id: Int,
    ): DownloadedFileInfoResult? {
        try {
            val info =
                DataStore.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString()) ?: return null
            val file = info.toFile(context)

            if (file == null || file.exists() == false) {
                return null
            }

            return DownloadedFileInfoResult(
                file.lengthOrThrow(),
                info.totalBytes,
                file.uriOrThrow()
            )
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    fun deleteFilesAndUpdateSettings(
        context: Context,
        ids: Set<Int>,
        scope: CoroutineScope,
        onComplete: (Set<Int>) -> Unit = {}
    ) {
        scope.launchSafe(Dispatchers.IO) {
            val deleteJobs = ids.map { id ->
                async {
                    id to deleteFileAndUpdateSettings(context, id)
                }
            }
            val results = deleteJobs.awaitAll()

            val (successfulResults, failedResults) = results.partition { it.second }
            val successfulIds = successfulResults.map { it.first }.toSet()

            if (failedResults.isNotEmpty()) {
                failedResults.forEach { (id, _) ->
                    AppLogger.e("FileDeletion", "Failed to delete file with ID: $id")
                }
            } else {
                AppLogger.i("FileDeletion", "All files deleted successfully")
            }

            onComplete.invoke(successfulIds)
        }
    }

    private fun deleteFileAndUpdateSettings(context: Context, id: Int): Boolean {
        val success = deleteFile(context, id)
        if (success) DataStore.removeKey(KEY_DOWNLOAD_INFO, id.toString())
        return success
    }

    private fun deleteFile(context: Context, id: Int): Boolean {
        val info =
            DataStore.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString()) ?: return false
        val file = info.toFile(context)

        val isFileDeleted = file?.delete() == true || file?.exists() == false

        if (isFileDeleted) {
            deleteMatchingSubtitles(context, info)
            downloadEvent.invoke(id to DownloadActionType.Stop)
            downloadProgressEvent.invoke(Triple(id, 0, 0))
            downloadStatusEvent.invoke(id to DownloadType.IsStopped)
            downloadDeleteEvent.invoke(id)
        }

        return isFileDeleted
    }

    fun getDownloadResumePackage(context: Context, id: Int): DownloadResumePackage? {
        return DataStore.getKey<DownloadResumePackage>(KEY_RESUME_PACKAGES, id.toString())
    }

    fun getDownloadQueuePackage(context: Context, id: Int): DownloadQueueWrapper? {
        return DataStore.getKey<DownloadQueueWrapper>(KEY_RESUME_IN_QUEUE, id.toString())
    }

    fun getDownloadEpisodeMetadata(
        episode: ResultEpisode,
        titleName: String,
        apiName: String,
        currentPoster: String?,
        currentIsMovie: Boolean,
        tvType: TvType,
    ): DownloadEpisodeMetadata {
        return DownloadEpisodeMetadata(
            episode.id,
            episode.parentId,
            sanitizeFilename(titleName),
            apiName,
            episode.poster ?: currentPoster,
            episode.name,
            if (currentIsMovie) null else episode.season,
            if (currentIsMovie) null else episode.episode,
            tvType,
        )
    }

    class EpisodeDownloadInstance(
        val context: Context,
        val downloadQueueWrapper: DownloadQueueWrapper
    ) {
        private val TAG = "EpisodeDownloadInstance"
        private var subtitleDownloadJob: Job? = null
        private var downloadJob: Job? = null
        private var linkLoadingJob: Job? = null

        var isCompleted = false
            set(value) {
                field = value
                if (value) {
                    DataStore.removeKey(KEY_RESUME_IN_QUEUE, downloadQueueWrapper.id.toString())
                    DownloadQueueManager.forceRefreshQueue()
                }
            }

        fun cancelDownload() {
            val cause = "Cancel call from cancelDownload"
            this.subtitleDownloadJob?.cancel(cause)
            this.linkLoadingJob?.cancel(cause)
            isCancelled = true
        }

        private fun cleanup(status: DownloadType) {
            DataStore.removeKey(KEY_RESUME_IN_QUEUE, downloadQueueWrapper.id.toString())
            val id = downloadQueueWrapper.id

            safe {
                val info = DataStore.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString())
                if (info != null) {
                    deleteMatchingSubtitles(context, info)
                }
            }

            downloadStatusEvent.invoke(Pair(id, status))
            downloadStatus[id] = status
            downloadEvent.invoke(Pair(id, DownloadActionType.Stop))

            DownloadQueueManager.forceRefreshQueue()
        }

        var isCancelled = false
            set(value) {
                val oldField = field
                field = value

                if (value && !oldField) {
                    cleanup(DownloadType.IsStopped)
                }
            }

        var isFailed = false
            set(value) {
                val oldField = field
                field = value

                if (value && !oldField) {
                    cleanup(DownloadType.IsFailed)
                }
            }

        companion object {
            private fun displayNotification(context: Context, id: Int, notification: Notification) {
                safe {
                    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) return@safe

                    NotificationManagerCompat.from(context)
                        .notify(DOWNLOAD_NOTIFICATION_TAG, id, notification)
                }
            }
        }

        private suspend fun downloadFromResume(
            downloadResumePackage: DownloadResumePackage,
            notificationCallback: (Int, Notification) -> Unit,
        ) {
            val item = downloadResumePackage.item
            val id = item.ep.id
            if (currentDownloads.value.contains(id)) {
                downloadEvent.invoke(id to DownloadActionType.Resume)
                return
            }

            _currentDownloads.update { downloads ->
                downloads + id
            }

            try {
                for (index in (downloadResumePackage.linkIndex ?: 0) until item.links.size) {
                    val link = item.links[index]
                    val resume = downloadResumePackage.linkIndex == index

                    DataStore.setKey(
                        KEY_RESUME_PACKAGES,
                        id.toString(),
                        DownloadResumePackage(item, index)
                    )

                    var connectionResult =
                        downloadSingleEpisode(
                            context,
                            item.source,
                            item.folder,
                            item.ep,
                            link,
                            notificationCallback,
                            resume
                        )

                    if (connectionResult.retrySame) {
                        connectionResult = downloadSingleEpisode(
                            context,
                            item.source,
                            item.folder,
                            item.ep,
                            link,
                            notificationCallback,
                            true
                        )
                    }

                    if (connectionResult.success) {
                        isCompleted = true
                        break
                    } else if (!connectionResult.tryNext || index >= item.links.lastIndex) {
                        isFailed = true
                        break
                    }
                }
            } catch (e: Exception) {
                isFailed = true
                logError(e)
            } finally {
                isFailed = !isCompleted
                _currentDownloads.update { downloads ->
                    downloads - id
                }
            }
        }

        private suspend fun startDownload(
            info: DownloadItem?,
            pkg: DownloadResumePackage?
        ) {
            try {
                if (info != null) {
                    getDownloadResumePackage(context, info.ep.id)?.let { dpkg ->
                        downloadFromResume(dpkg) { id, notification ->
                            displayNotification(context, id, notification)
                        }
                    } ?: run {
                        if (info.links.isEmpty()) return
                        downloadFromResume(
                            DownloadResumePackage(info, null)
                        ) { id, notification ->
                            displayNotification(context, id, notification)
                        }
                    }
                } else if (pkg != null) {
                    downloadFromResume(pkg) { id, notification ->
                        displayNotification(context, id, notification)
                    }
                }
                return
            } catch (e: Exception) {
                isFailed = true
                logError(e)
                return
            }
        }

        private suspend fun downloadFromResume() {
            val resumePackage = downloadQueueWrapper.resumePackage ?: return
            downloadFromResume(resumePackage) { id, notification ->
                displayNotification(context, id, notification)
            }
        }

        fun startDownload() {
            AppLogger.d(TAG, "Starting download ${downloadQueueWrapper.id}")
            DataStore.setKey(KEY_RESUME_IN_QUEUE, downloadQueueWrapper.id.toString(), downloadQueueWrapper)

            ioSafe {
                if (downloadQueueWrapper.resumePackage != null) {
                    downloadFromResume()
                } else if (downloadQueueWrapper.downloadItem != null && downloadQueueWrapper.downloadItem.links.isNullOrEmpty()) {
                    downloadEpisodeWithoutLinks()
                } else if (downloadQueueWrapper.downloadItem?.links != null) {
                    downloadEpisodeWithLinks(
                        sortUrls(downloadQueueWrapper.downloadItem.links.toSet()),
                        downloadQueueWrapper.downloadItem.subs
                    )
                }
            }
        }

        private fun downloadEpisodeWithLinks(
            links: List<ExtractorLink>,
            subs: List<SubtitleData>?
        ) {
            val downloadItem = downloadQueueWrapper.downloadItem ?: return
            try {
                DataStore.setKey(
                    DOWNLOAD_HEADER_CACHE,
                    downloadItem.resultId.toString(),
                    DownloadObjects.DownloadHeaderCached(
                        apiName = downloadItem.apiName,
                        url = downloadItem.resultUrl,
                        type = downloadItem.resultType,
                        name = downloadItem.resultName,
                        poster = downloadItem.resultPoster,
                        id = downloadItem.resultId,
                        cacheTime = System.currentTimeMillis(),
                    )
                )
                DataStore.setKey(
                    getFolderName(
                        DOWNLOAD_EPISODE_CACHE,
                        downloadItem.resultId.toString()
                    ),
                    downloadItem.episode.id.toString(),
                    DownloadObjects.DownloadEpisodeCached(
                        name = downloadItem.episode.name,
                        poster = downloadItem.episode.poster,
                        episode = downloadItem.episode.episode,
                        season = downloadItem.episode.season,
                        id = downloadItem.episode.id,
                        parentId = downloadItem.resultId,
                        score = downloadItem.episode.score,
                        description = downloadItem.episode.description,
                        cacheTime = System.currentTimeMillis(),
                    )
                )

                val meta =
                    getDownloadEpisodeMetadata(
                        downloadItem.episode,
                        downloadItem.resultName,
                        downloadItem.apiName,
                        downloadItem.resultPoster,
                        downloadItem.isMovie,
                        downloadItem.resultType
                    )

                val folder =
                    getFolder(downloadItem.resultType, downloadItem.resultName)
                val src = "$DOWNLOAD_NAVIGATE_TO/${downloadItem.resultId}"

                val info = DownloadItem(src, folder, meta, links)

                this.downloadJob = ioSafe {
                    startDownload(info, null)
                }

                this.subtitleDownloadJob = ioSafe {
                    try {
                        val downloadList = SubtitlesFragment.getDownloadSubsLanguageTagIETF()

                        subs?.filter { subtitle ->
                            downloadList.any { langTagIETF ->
                                subtitle.languageCode == langTagIETF ||
                                        subtitle.originalName.contains(
                                            fromTagToEnglishLanguageName(
                                                langTagIETF
                                            ) ?: langTagIETF
                                        )
                            }
                        }
                            ?.map { ExtractorSubtitleLink(it.name, it.url, "", it.headers) }
                            ?.take(3)
                            ?.forEach { link ->
                                val fileName = getFileName(context, meta)
                                downloadSubtitle(context, link, fileName, folder)
                            }

                    } catch (_: CancellationException) {
                        val fileName = getFileName(context, meta)

                        val fileInfo = DownloadedFileInfo(
                            totalBytes = 0,
                            relativePath = folder,
                            displayName = fileName,
                            basePath = context.getBasePath().second
                        )

                        deleteMatchingSubtitles(context, fileInfo)
                    }
                }
            } catch (e: Exception) {
                if (this.downloadJob == null) {
                    isFailed = true
                }
                logError(e)
            }
        }

        private suspend fun downloadEpisodeWithoutLinks() {
            val downloadItem = downloadQueueWrapper.downloadItem ?: return

            val generator = RepoLinkGenerator(listOf(downloadItem.episode))
            val currentLinks = mutableSetOf<ExtractorLink>()
            val currentSubs = mutableSetOf<SubtitleData>()
            val meta =
                getDownloadEpisodeMetadata(
                    downloadItem.episode,
                    downloadItem.resultName,
                    downloadItem.apiName,
                    downloadItem.resultPoster,
                    downloadItem.isMovie,
                    downloadItem.resultType
                )

            createDownloadNotification(
                context,
                downloadItem.apiName,
                txt(R.string.loading).asString(context),
                meta,
                DownloadType.IsPending,
                0,
                1,
                { _, _ -> },
                null,
                null,
                0
            )?.let { linkLoadingNotification ->
                displayNotification(context, downloadItem.episode.id, linkLoadingNotification)
            }

            linkLoadingJob = ioSafe {
                generator.generateLinks(
                    offset = 0,
                    isCasting = false,
                    clearCache = false,
                    sourceTypes = LOADTYPE_INAPP_DOWNLOAD,
                    callback = {
                        it.first?.let { link ->
                            currentLinks.add(link)
                        }
                    },
                    subtitleCallback = { sub ->
                        currentSubs.add(sub)
                    })
            }

            linkLoadingJob?.join()

            NotificationManagerCompat.from(context)
                .cancel(DOWNLOAD_NOTIFICATION_TAG, downloadItem.episode.id)

            if (linkLoadingJob?.isCancelled == true) {
                isCancelled = true
                return
            } else if (currentLinks.isEmpty()) {
                main {
                    showToast(
                        R.string.no_links_found_toast,
                        Toast.LENGTH_SHORT
                    )
                }
                isFailed = true
                return
            } else {
                main {
                    showToast(
                        R.string.download_started,
                        Toast.LENGTH_SHORT
                    )
                }
            }

            val profile = QualityDataHelper.getProfiles().first {
                it.types.contains(
                    QualityDataHelper.QualityProfileType.Download
                )
            }

            val sortedLinks = currentLinks.sortedBy { link ->
                -getLinkPriority(profile.id, link)
            }

            downloadEpisodeWithLinks(
                sortedLinks,
                sortSubs(currentSubs),
            )
        }
    }
}
