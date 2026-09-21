package com.lagradost.cloudstream3.desktop.ui.screens.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.ui.download.queue.DownloadQueueViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatEta(bytesPerSecond: Long, progress: Long, total: Long): String {
    if (bytesPerSecond <= 0L || total <= progress) return ""
    val remainingBytes = total - progress
    val remainingSeconds = remainingBytes / bytesPerSecond
    return when {
        remainingSeconds < 60 -> "${remainingSeconds}s left"
        remainingSeconds < 3600 -> "${remainingSeconds / 60}m ${remainingSeconds % 60}s left"
        else -> "${remainingSeconds / 3600}h ${(remainingSeconds % 3600) / 60}m left"
    }
}

/**
 * Real-time Active Download Queue Tab for Desktop.
 * Displays live progress, speed, ETA, pause/resume/cancel controls, and queue reordering.
 */
@Composable
fun DownloadQueueTab(
    queueViewModel: DownloadQueueViewModel,
    modifier: Modifier = Modifier
) {
    val context = remember { CloudStreamApp.context ?: android.content.Context() }
    val queueData by queueViewModel.childCards.collectAsState()

    val currentDownloads = queueData.currentDownloads
    val queuedDownloads = queueData.queue
    val totalCount = currentDownloads.size + queuedDownloads.size

    // Live progress state mapping: id -> (downloadedBytes, totalBytes, bytesPerSec, lastUpdatedMs)
    val progressMap = remember { mutableStateMapOf<Int, Triple<Long, Long, Long>>() }
    val statusMap = remember { mutableStateMapOf<Int, VideoDownloadManager.DownloadType>() }

    var showCancelAllDialog by remember { mutableStateOf(false) }

    // Register real-time listeners from VideoDownloadManager
    DisposableEffect(Unit) {
        val progressListener = { triple: Triple<Int, Long, Long> ->
            val id = triple.first
            val downloaded = triple.second
            val total = triple.third
            val prev = progressMap[id]
            val speed = if (prev != null && prev.first < downloaded) {
                (downloaded - prev.first) * 2L // approx 500ms intervals
            } else {
                prev?.third ?: 0L
            }
            progressMap[id] = Triple(downloaded, total, speed)
        }

        val statusListener = { pair: Pair<Int, VideoDownloadManager.DownloadType> ->
            statusMap[pair.first] = pair.second
        }

        VideoDownloadManager.downloadProgressEvent += progressListener
        VideoDownloadManager.downloadStatusEvent += statusListener

        // Pre-populate status and progress for initial items
        currentDownloads.forEach { wrapper ->
            VideoDownloadManager.downloadStatus[wrapper.id]?.let { statusMap[wrapper.id] = it }
            val fileInfo = VideoDownloadManager.getDownloadFileInfo(context, wrapper.id)
            if (fileInfo != null) {
                progressMap[wrapper.id] = Triple(fileInfo.fileLength, fileInfo.totalBytes, 0L)
            }
        }

        onDispose {
            VideoDownloadManager.downloadProgressEvent -= progressListener
            VideoDownloadManager.downloadStatusEvent -= statusListener
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Queue Toolbar: Status Badge & Bulk Action Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Download Queue",
                    style = TvTypography.Section.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )

                Spacer(modifier = Modifier.width(12.dp))

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (currentDownloads.isNotEmpty()) TvColors.FocusElectricBlue.copy(alpha = 0.2f) else TvColors.SurfaceCard,
                    border = BorderStroke(1.dp, if (currentDownloads.isNotEmpty()) TvColors.FocusElectricBlue else TvColors.BorderSubtle)
                ) {
                    Text(
                        text = "${currentDownloads.size} active • ${queuedDownloads.size} queued",
                        style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                        color = if (currentDownloads.isNotEmpty()) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (totalCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Pause All
                    Button(
                        onClick = {
                            queueViewModel.pauseAll()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, tint = TvColors.AccentAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pause All", style = TvTypography.Caption, color = Color.White)
                    }

                    // Resume All
                    Button(
                        onClick = {
                            queueViewModel.resumeAll()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TvColors.FocusEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resume All", style = TvTypography.Caption, color = Color.White)
                    }

                    // Cancel All
                    Button(
                        onClick = { showCancelAllDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed.copy(alpha = 0.15f)),
                        border = BorderStroke(1.dp, TvColors.ErrorRed.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = TvColors.ErrorRed, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel All", style = TvTypography.Caption, color = TvColors.ErrorRed)
                    }
                }
            }
        }

        // Queue Body
        if (totalCount == 0) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = TvColors.TextMuted,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "Download Queue is Empty",
                        style = TvTypography.Section,
                        color = TvColors.TextPrimary
                    )
                    Text(
                        text = "New downloads you start will appear here with live speed, progress, and queue management.",
                        style = TvTypography.Body,
                        color = TvColors.TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Active Downloads Section
                if (currentDownloads.isNotEmpty()) {
                    item {
                        Text(
                            text = "ACTIVE DOWNLOADS (${currentDownloads.size})",
                            style = TvTypography.Badge.copy(fontWeight = FontWeight.Bold),
                            color = TvColors.FocusElectricBlue,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    itemsIndexed(currentDownloads, key = { _, it -> "active_${it.id}" }) { _, wrapper ->
                        val progressData = progressMap[wrapper.id]
                        val currentStatus = statusMap[wrapper.id] ?: if (wrapper.isCurrentlyDownloading()) {
                            VideoDownloadManager.DownloadType.IsDownloading
                        } else {
                            VideoDownloadManager.DownloadType.IsPending
                        }

                        ActiveDownloadItemCard(
                            wrapper = wrapper,
                            progressData = progressData,
                            status = currentStatus,
                            onPause = { queueViewModel.pauseDownload(wrapper.id) },
                            onResume = { queueViewModel.resumeDownload(wrapper.id) },
                            onCancel = { queueViewModel.deleteQueueItem(wrapper.id, context) }
                        )
                    }
                }

                // Queued Items Section
                if (queuedDownloads.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "UP NEXT IN QUEUE (${queuedDownloads.size})",
                            style = TvTypography.Badge.copy(fontWeight = FontWeight.Bold),
                            color = TvColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }

                    itemsIndexed(queuedDownloads, key = { _, it -> "queued_${it.id}" }) { index, wrapper ->
                        QueuedDownloadItemCard(
                            wrapper = wrapper,
                            index = index,
                            totalQueued = queuedDownloads.size,
                            onMoveUp = {
                                if (index > 0) {
                                    queueViewModel.reorderItem(wrapper, index - 1)
                                }
                            },
                            onMoveDown = {
                                if (index < queuedDownloads.size - 1) {
                                    queueViewModel.reorderItem(wrapper, index + 1)
                                }
                            },
                            onCancel = {
                                queueViewModel.deleteQueueItem(wrapper.id, context)
                            }
                        )
                    }
                }
            }
        }
    }

    // Cancel All Confirmation Dialog
    if (showCancelAllDialog) {
        AlertDialog(
            onDismissRequest = { showCancelAllDialog = false },
            title = { Text("Cancel All Downloads?", color = Color.White) },
            text = {
                Text(
                    text = "Are you sure you want to cancel all active and queued downloads? Incomplete files will be removed.",
                    color = TvColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        queueViewModel.removeAllFromQueue(context)
                        showCancelAllDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed)
                ) {
                    Text("Cancel All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllDialog = false }) {
                    Text("Keep Downloading", color = Color.White)
                }
            },
            containerColor = TvColors.SurfaceElevated,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Card for an actively downloading item showing progress bar, speed, ETA, and Pause/Resume/Cancel buttons.
 */
@Composable
private fun ActiveDownloadItemCard(
    wrapper: DownloadQueueWrapper,
    progressData: Triple<Long, Long, Long>?,
    status: VideoDownloadManager.DownloadType,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = remember { CloudStreamApp.context ?: android.content.Context() }

    val mainTitle = remember(wrapper) {
        wrapper.downloadItem?.resultName ?: wrapper.resumePackage?.item?.ep?.mainName ?: "Unknown Show"
    }

    val epTitle = remember(wrapper) {
        val name = wrapper.downloadItem?.episode?.name ?: wrapper.resumePackage?.item?.ep?.name
        val ep = wrapper.downloadItem?.episode?.episode ?: wrapper.resumePackage?.item?.ep?.episode
        val season = wrapper.downloadItem?.episode?.season ?: wrapper.resumePackage?.item?.ep?.season
        context.getNameFull(name, ep, season)
    }

    val downloaded = progressData?.first ?: 0L
    val total = progressData?.second ?: 0L
    val speed = progressData?.third ?: 0L
    val progressRatio = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val progressPercent = (progressRatio * 100).toInt()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
        color = TvColors.SurfaceCard,
        border = BorderStroke(1.dp, TvColors.BorderSubtle)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Top Row: Main Title, Episode Title, Status Badge, and Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mainTitle,
                        style = TvTypography.Body.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (epTitle.isNotBlank() && epTitle != mainTitle) {
                        Text(
                            text = epTitle,
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status Badge
                    val (badgeText, badgeColor) = when (status) {
                        VideoDownloadManager.DownloadType.IsDownloading -> "DOWNLOADING" to TvColors.FocusElectricBlue
                        VideoDownloadManager.DownloadType.IsPaused -> "PAUSED" to TvColors.AccentAmber
                        VideoDownloadManager.DownloadType.IsFailed -> "FAILED" to TvColors.ErrorRed
                        VideoDownloadManager.DownloadType.IsDone -> "COMPLETED" to TvColors.FocusEmerald
                        else -> "PENDING" to TvColors.TextMuted
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = badgeText,
                            style = TvTypography.Badge,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Pause / Resume Button
                    if (status == VideoDownloadManager.DownloadType.IsDownloading) {
                        IconButton(
                            onClick = onPause,
                            modifier = Modifier
                                .size(34.dp)
                                .background(TvColors.AccentAmber.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = TvColors.AccentAmber, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(
                            onClick = onResume,
                            modifier = Modifier
                                .size(34.dp)
                                .background(TvColors.FocusEmerald.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = TvColors.FocusEmerald, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Cancel Button
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(34.dp)
                            .background(TvColors.SurfaceElevated, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TvColors.TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { progressRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (status == VideoDownloadManager.DownloadType.IsPaused) TvColors.AccentAmber else TvColors.FocusElectricBlue,
                trackColor = TvColors.SurfaceHigh
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Metrics Row: Progress %, Downloaded/Total, Speed, ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$progressPercent% • ${formatBytes(downloaded)} / ${formatBytes(total)}",
                    style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = TvColors.TextPrimary
                )

                if (status == VideoDownloadManager.DownloadType.IsDownloading && speed > 0L) {
                    val eta = formatEta(speed, downloaded, total)
                    Text(
                        text = "${formatBytes(speed)}/s ${if (eta.isNotBlank()) "• $eta" else ""}",
                        style = TvTypography.Caption,
                        color = TvColors.FocusElectricBlue
                    )
                }
            }
        }
    }
}

/**
 * Card for a queued item showing title, queue position, reorder buttons, and cancel button.
 */
@Composable
private fun QueuedDownloadItemCard(
    wrapper: DownloadQueueWrapper,
    index: Int,
    totalQueued: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = remember { CloudStreamApp.context ?: android.content.Context() }

    val mainTitle = remember(wrapper) {
        wrapper.downloadItem?.resultName ?: wrapper.resumePackage?.item?.ep?.mainName ?: "Unknown Show"
    }

    val epTitle = remember(wrapper) {
        val name = wrapper.downloadItem?.episode?.name ?: wrapper.resumePackage?.item?.ep?.name
        val ep = wrapper.downloadItem?.episode?.episode ?: wrapper.resumePackage?.item?.ep?.episode
        val season = wrapper.downloadItem?.episode?.season ?: wrapper.resumePackage?.item?.ep?.season
        context.getNameFull(name, ep, season)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
        color = TvColors.SurfaceElevated,
        border = BorderStroke(1.dp, TvColors.BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Queue Position Chip
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TvColors.SurfaceCard,
                    border = BorderStroke(1.dp, TvColors.BorderSubtle)
                ) {
                    Text(
                        text = "#${index + 1}",
                        style = TvTypography.Badge.copy(fontWeight = FontWeight.Bold),
                        color = TvColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = mainTitle,
                        style = TvTypography.Body.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (epTitle.isNotBlank() && epTitle != mainTitle) {
                        Text(
                            text = epTitle,
                            style = TvTypography.Caption,
                            color = TvColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Controls: Move Up, Move Down, Cancel
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Move Up
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = "Move Up in Queue",
                        tint = if (index > 0) Color.White else TvColors.BorderSubtle,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Move Down
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalQueued - 1,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Move Down in Queue",
                        tint = if (index < totalQueued - 1) Color.White else TvColors.BorderSubtle,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Cancel
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove from Queue",
                        tint = TvColors.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
