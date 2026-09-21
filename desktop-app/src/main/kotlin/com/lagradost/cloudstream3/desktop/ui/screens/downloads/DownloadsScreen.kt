package com.lagradost.cloudstream3.desktop.ui.screens.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.foundation.PointerMatcher
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.desktop.ui.components.TvCard
import com.lagradost.cloudstream3.desktop.ui.navigation.LocalVideoPlayer
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.navigation.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.DesktopViewModel
import com.lagradost.cloudstream3.ui.download.DownloadDialogEvent
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.ui.download.VisualDownloadCached
import com.lagradost.cloudstream3.ui.download.queue.DownloadQueueViewModel
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.common.storage.WatchHistory
import kotlinx.coroutines.launch

/**
 * Deterministic lifecycle-scoped ViewModel provider for Compose Desktop.
 */
@Composable
private fun <VM : DesktopViewModel> rememberCloseableViewModel(key: Any, factory: () -> VM): VM {
    val vm = remember(key) { factory() }
    DisposableEffect(key) {
        onDispose { vm.onCleared() }
    }
    return vm
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

/**
 * Official-Grade 1:1 Native Desktop Downloads & Queue Screen.
 *
 * Implements:
 * 1. Dual-Tab Architecture: "Downloaded" (tamamlananlar) & "Download Queue" (aktif kuyruk)
 * 2. Visual Segmented Storage Bar (CloudStream app storage vs System used vs Free disk)
 * 3. In-App Child Episodes Dialog (çevrimdışı oynatma, arama, bölüm silme)
 * 4. Desktop Mouse Ergonomics: Right-click Context Menus (Oynat, Dosya Konumunu Aç, Sil)
 * 5. Instant Search/Filtering across downloaded media
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ComposeDownloadsScreen(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel = rememberCloseableViewModel("canonical_downloads_view_model") { DownloadViewModel() }
    val queueViewModel = rememberCloseableViewModel("canonical_downloads_queue_view_model") { DownloadQueueViewModel() }
    val context = remember { CloudStreamApp.context ?: android.content.Context() }
    val videoPlayer = LocalVideoPlayer.current

    // 0: Downloaded, 1: Queue
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val headerCards by viewModel.headerCards.collectAsState()
    val downloadBytes by viewModel.downloadBytes.collectAsState()
    val usedBytes by viewModel.usedBytes.collectAsState()
    val availableBytes by viewModel.availableBytes.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    val selectedBytes by viewModel.selectedBytes.collectAsState()

    val queueData by queueViewModel.childCards.collectAsState()
    val totalQueueCount = queueData.currentDownloads.size + queueData.queue.size

    // Child Dialog state
    var selectedHeaderForChildView by remember { mutableStateOf<VisualDownloadCached.Header?>(null) }

    // Delete confirmation dialogs
    var pendingDeleteDialog by remember { mutableStateOf<DownloadDialogEvent.DeleteConfirmation?>(null) }
    var headerPendingSingleDelete by remember { mutableStateOf<VisualDownloadCached.Header?>(null) }

    // Observe upstream ViewModel delete confirmation events
    LaunchedEffect(Unit) {
        viewModel.dialogEvent.collect { event ->
            when (event) {
                is DownloadDialogEvent.DeleteConfirmation -> {
                    pendingDeleteDialog = event
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.updateHeaderList(context)
    }

    // Offline playback launcher
    val playDownloadedChild: (VisualDownloadCached.Child) -> Unit = { child ->
        val info = VideoDownloadManager.getDownloadFileInfo(context, child.data.id)
        val uri = info?.path
        val pathString = uri?.path ?: uri?.toString()
        if (pathString != null) {
            val cleanPath = if (pathString.startsWith("file://")) pathString.removePrefix("file://") else pathString
            val file = java.io.File(cleanPath)
            val fileUrl = if (file.exists()) file.absolutePath else pathString

            val parentHeader = context.getKey<DownloadObjects.DownloadHeaderCached>(
                DOWNLOAD_HEADER_CACHE,
                child.data.parentId.toString()
            )
            val showName = parentHeader?.name ?: child.data.name ?: "Downloaded Video"
            val episodeName = child.data.name ?: "Episode ${child.data.episode}"

            val launchData = VideoLaunchData(
                links = listOf(
                    ExtractorLink(
                        source = "Offline",
                        name = episodeName,
                        url = fileUrl,
                        referer = "",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO,
                    )
                ),
                initialIndex = 0,
                title = "$showName - $episodeName",
                subtitles = emptyList(),
                startPositionMs = 0L,
                history = WatchHistory(
                    url = fileUrl,
                    parentId = child.data.parentId.toString(),
                    episodeId = child.data.id.toString(),
                    title = showName,
                    episode = child.data.episode,
                    season = child.data.season,
                )
            )
            videoPlayer.invoke(launchData)
        } else {
            CommonActivity.showToast("Cannot play file: Downloaded file not found on disk")
        }
    }

    val playDownloadedHeaderMovie: (VisualDownloadCached.Header) -> Unit = { header ->
        val child = header.child ?: run {
            context.getKey<DownloadObjects.DownloadEpisodeCached>(
                DOWNLOAD_EPISODE_CACHE,
                getFolderName(header.data.id.toString(), header.data.id.toString())
            )
        }
        if (child != null) {
            playDownloadedChild(
                VisualDownloadCached.Child(
                    currentBytes = header.currentBytes,
                    totalBytes = header.totalBytes,
                    data = child,
                    isSelected = false
                )
            )
        } else {
            val folder = getFolderName(DOWNLOAD_EPISODE_CACHE, header.data.id.toString())
            val firstChild = context.getKeys(folder).firstNotNullOfOrNull { key ->
                context.getKey<DownloadObjects.DownloadEpisodeCached>(key)
            }
            if (firstChild != null) {
                playDownloadedChild(
                    VisualDownloadCached.Child(
                        currentBytes = header.currentBytes,
                        totalBytes = header.totalBytes,
                        data = firstChild,
                        isSelected = false
                    )
                )
            } else {
                CommonActivity.showToast("Cannot play: No downloaded media found")
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = TvDimensions.ScreenHorizontalPadding,
                vertical = 16.dp
            )
    ) {
        // Top Bar: Navigation, Title, Tabs, Storage Bar, and Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (navController.canGoBack()) {
                    IconButton(
                        onClick = { navController.goBack() },
                        modifier = Modifier.focusable()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Text(
                    text = "Downloads",
                    style = TvTypography.Headline,
                    color = Color.White
                )

                Spacer(modifier = Modifier.width(24.dp))

                // Tab Switcher: "Downloaded" vs "Queue"
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvColors.SurfaceCard,
                    border = BorderStroke(1.dp, TvColors.BorderSubtle)
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        // Downloaded Tab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedTab == 0) TvColors.FocusElectricBlue else Color.Transparent)
                                .clickable { selectedTab = 0 }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Downloaded",
                                style = TvTypography.Caption.copy(fontWeight = FontWeight.Bold),
                                color = if (selectedTab == 0) Color.White else TvColors.TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Queue Tab with live count badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedTab == 1) TvColors.FocusElectricBlue else Color.Transparent)
                                .clickable { selectedTab = 1 }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Queue",
                                    style = TvTypography.Caption.copy(fontWeight = FontWeight.Bold),
                                    color = if (selectedTab == 1) Color.White else TvColors.TextSecondary
                                )
                                if (totalQueueCount > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = if (selectedTab == 1) Color.White else TvColors.FocusElectricBlue
                                    ) {
                                        Text(
                                            text = totalQueueCount.toString(),
                                            style = TvTypography.Badge.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold),
                                            color = if (selectedTab == 1) TvColors.FocusElectricBlue else Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Right side: Search Box & Refresh Button
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filter downloads...", style = TvTypography.Caption, color = TvColors.TextMuted) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TvColors.TextMuted, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear", tint = TvColors.TextMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TvColors.FocusElectricBlue,
                            unfocusedBorderColor = TvColors.BorderSubtle,
                            focusedContainerColor = TvColors.SurfaceCard,
                            unfocusedContainerColor = TvColors.SurfaceCard,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        textStyle = TvTypography.Caption.copy(color = Color.White),
                        modifier = Modifier
                            .width(240.dp)
                            .height(44.dp)
                    )

                    Spacer(modifier = Modifier.width(12.dp))
                }

                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.updateHeaderList(context)
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(TvColors.SurfaceCard, RoundedCornerShape(8.dp))
                        .border(1.dp, TvColors.BorderSubtle, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = TvColors.FocusElectricBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Storage Usage Segmented Bar
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceCard,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                val totalDiskBytes = (usedBytes + availableBytes).coerceAtLeast(1L)
                val appRatio = (downloadBytes.toFloat() / totalDiskBytes.toFloat()).coerceIn(0f, 1f)
                val otherUsedBytes = (usedBytes - downloadBytes).coerceAtLeast(0L)
                val otherRatio = (otherUsedBytes.toFloat() / totalDiskBytes.toFloat()).coerceIn(0f, 1f)
                val freeRatio = (availableBytes.toFloat() / totalDiskBytes.toFloat()).coerceIn(0f, 1f)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // CloudStream App storage indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(TvColors.FocusEmerald, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "CloudStream: ${formatBytes(downloadBytes)}",
                                style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                                color = Color.White
                            )
                        }

                        // Other system storage indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(TvColors.AccentAmber, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "System Used: ${formatBytes(otherUsedBytes)}",
                                style = TvTypography.Caption,
                                color = TvColors.TextSecondary
                            )
                        }

                        // Free space indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(TvColors.TextMuted, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Free: ${formatBytes(availableBytes)}",
                                style = TvTypography.Caption,
                                color = TvColors.TextSecondary
                            )
                        }
                    }

                    Text(
                        text = "Total: ${formatBytes(totalDiskBytes)}",
                        style = TvTypography.Caption,
                        color = TvColors.TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Segmented Progress Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(TvColors.SurfaceHigh)
                ) {
                    if (appRatio > 0.001f) {
                        Box(
                            modifier = Modifier
                                .weight(appRatio)
                                .fillMaxHeight()
                                .background(TvColors.FocusEmerald)
                        )
                    }
                    if (otherRatio > 0.001f) {
                        Box(
                            modifier = Modifier
                                .weight(otherRatio)
                                .fillMaxHeight()
                                .background(TvColors.AccentAmber)
                        )
                    }
                    if (freeRatio > 0.001f) {
                        Box(
                            modifier = Modifier
                                .weight(freeRatio)
                                .fillMaxHeight()
                                .background(Color.Transparent)
                        )
                    }
                }
            }
        }

        // Multi-select bulk delete bar (when active)
        if (selectedItemIds != null && selectedTab == 0) {
            val currentSelection = selectedItemIds ?: emptySet()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(TvColors.SurfaceElevated, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${currentSelection.size} selected (${formatBytes(selectedBytes)})",
                    style = TvTypography.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (viewModel.isAllHeadersSelected()) {
                                viewModel.clearSelectedItems()
                            } else {
                                viewModel.selectAllHeaders()
                            }
                        }
                    ) {
                        Text(
                            text = if (viewModel.isAllHeadersSelected()) "Deselect All" else "Select All",
                            color = TvColors.FocusElectricBlue
                        )
                    }

                    Button(
                        onClick = { viewModel.handleMultiDelete(context) },
                        enabled = currentSelection.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete Selected", color = Color.White)
                    }

                    TextButton(onClick = { viewModel.cancelSelection() }) {
                        Text("Cancel", color = TvColors.TextSecondary)
                    }
                }
            }
        }

        // Main Tab Content
        if (selectedTab == 1) {
            // Queue Tab
            DownloadQueueTab(queueViewModel = queueViewModel)
        } else {
            // Downloaded Media Tab
            when (val state = headerCards) {
                is Resource.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TvColors.FocusElectricBlue)
                    }
                }
                is Resource.Failure -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Failed to load downloads: ${state.errorString}",
                                style = TvTypography.Body,
                                color = TvColors.ErrorRed
                            )
                            Button(
                                onClick = { viewModel.updateHeaderList(context) },
                                colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue)
                            ) {
                                Text("Retry", color = Color.White)
                            }
                        }
                    }
                }
                is Resource.Success -> {
                    val allHeaders = state.value
                    val filteredHeaders = remember(allHeaders, searchQuery) {
                        if (searchQuery.isBlank()) {
                            allHeaders
                        } else {
                            val q = searchQuery.trim().lowercase()
                            allHeaders.filter { it.data.name.lowercase().contains(q) }
                        }
                    }

                    if (filteredHeaders.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = TvColors.TextMuted,
                                    modifier = Modifier.size(64.dp)
                                )
                                Text(
                                    text = if (searchQuery.isBlank()) "No Downloads Found" else "No matching downloads",
                                    style = TvTypography.Section,
                                    color = TvColors.TextPrimary
                                )
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        "Episodes and movies you download will appear here for offline viewing."
                                    } else {
                                        "No downloads found matching \"$searchQuery\"."
                                    },
                                    style = TvTypography.Body,
                                    color = TvColors.TextSecondary
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 170.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredHeaders, key = { it.data.id }) { header ->
                                DownloadedMediaCard(
                                    header = header,
                                    isMultiSelectMode = selectedItemIds != null,
                                    isSelected = selectedItemIds?.contains(header.data.id) == true,
                                    onCardClick = {
                                        if (header.data.type.isEpisodeBased()) {
                                            selectedHeaderForChildView = header
                                        } else {
                                            playDownloadedHeaderMovie(header)
                                        }
                                    },
                                    onPlayClick = {
                                        if (header.data.type.isEpisodeBased()) {
                                            selectedHeaderForChildView = header
                                        } else {
                                            playDownloadedHeaderMovie(header)
                                        }
                                    },
                                    onOpenChildDialog = {
                                        selectedHeaderForChildView = header
                                    },
                                    onToggleSelect = {
                                        if (selectedItemIds?.contains(header.data.id) == true) {
                                            viewModel.removeSelected(header.data.id)
                                        } else {
                                            viewModel.addSelected(header.data.id)
                                        }
                                    },
                                    onDelete = {
                                        headerPendingSingleDelete = header
                                    },
                                    onOpenDetails = {
                                        val provider = APIHolder.getApiFromNameNull(header.data.apiName)
                                            ?: APIHolder.apis.firstOrNull { it.name == header.data.apiName }
                                            ?: APIHolder.apis.firstOrNull()
                                        if (provider != null) {
                                            navController.navigate(
                                                Screen.Details(
                                                    provider = provider,
                                                    url = header.data.url,
                                                    preloadedName = header.data.name,
                                                    preloadedPoster = header.data.poster,
                                                    preloadedBg = null
                                                )
                                            )
                                        }
                                    },
                                    onOpenFileLocation = {
                                        openFileLocation(context, header.data.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Child Episodes Dialog
    selectedHeaderForChildView?.let { header ->
        DownloadedChildDialog(
            header = header,
            viewModel = viewModel,
            onDismiss = { selectedHeaderForChildView = null },
            onPlayChild = { child ->
                playDownloadedChild(child)
            }
        )
    }

    // Single Header Delete Confirmation Dialog
    headerPendingSingleDelete?.let { header ->
        AlertDialog(
            onDismissRequest = { headerPendingSingleDelete = null },
            title = { Text("Delete Downloaded Media?", color = Color.White) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${header.data.name}\" and all its downloaded files (${formatBytes(header.totalBytes)})?",
                    color = TvColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.handleSingleDelete(context, header.data.id)
                        headerPendingSingleDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed)
                ) {
                    Text("Delete All", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { headerPendingSingleDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = TvColors.SurfaceElevated,
            shape = RoundedCornerShape(12.dp)
        )
    }

    // Upstream Multi/Single Delete Confirmation Dialog
    pendingDeleteDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { pendingDeleteDialog = null },
            title = { Text(context.getString(dialog.titleRes), color = Color.White) },
            text = { Text(dialog.message, color = TvColors.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        dialog.onConfirm()
                        pendingDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteDialog = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = TvColors.SurfaceElevated,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Card for a downloaded show or movie with hover controls, right-click context menu, and badges.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DownloadedMediaCard(
    header: VisualDownloadCached.Header,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onPlayClick: () -> Unit,
    onOpenChildDialog: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenFileLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        TvCard(
            title = header.data.name,
            posterUrl = header.data.poster,
            subtitle = "${header.totalDownloads} items • ${formatBytes(header.totalBytes)}",
            badge = if (header.currentOngoingDownloads > 0) "DOWNLOADING" else "DOWNLOADED",
            badgeColor = if (header.currentOngoingDownloads > 0) TvColors.FocusElectricBlue else TvColors.FocusEmerald,
            onPlayClick = onPlayClick,
            onClick = {
                if (isMultiSelectMode) {
                    onToggleSelect()
                } else {
                    onCardClick()
                }
            },
            modifier = Modifier
                .onClick(
                    matcher = PointerMatcher.mouse(PointerButton.Secondary),
                    onClick = { showContextMenu = true }
                )
        )

        // Delete button badge in top-right of card
        if (!isMultiSelectMode) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Download",
                    tint = TvColors.ErrorRed,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = TvColors.FocusElectricBlue,
                    uncheckedColor = Color.White
                ),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            )
        }

        // Right-Click Context Menu for Desktop Mouse Ergonomics
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(TvColors.SurfaceElevated)
        ) {
            DropdownMenuItem(
                text = { Text("Play", style = TvTypography.Body) },
                leadingIcon = {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TvColors.FocusElectricBlue)
                },
                onClick = {
                    showContextMenu = false
                    onPlayClick()
                }
            )

            if (header.data.type.isEpisodeBased()) {
                DropdownMenuItem(
                    text = { Text("View Episodes", style = TvTypography.Body) },
                    leadingIcon = {
                        Icon(Icons.Default.List, contentDescription = null, tint = TvColors.TextPrimary)
                    },
                    onClick = {
                        showContextMenu = false
                        onOpenChildDialog()
                    }
                )
            }

            DropdownMenuItem(
                text = { Text("Online Details", style = TvTypography.Body) },
                leadingIcon = {
                    Icon(Icons.Default.Info, contentDescription = null, tint = TvColors.TextSecondary)
                },
                onClick = {
                    showContextMenu = false
                    onOpenDetails()
                }
            )

            DropdownMenuItem(
                text = { Text("Open in File Manager", style = TvTypography.Body) },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = TvColors.TextSecondary)
                },
                onClick = {
                    showContextMenu = false
                    onOpenFileLocation()
                }
            )

            DropdownMenuItem(
                text = { Text("Delete All Files", style = TvTypography.Body, color = TvColors.ErrorRed) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = TvColors.ErrorRed)
                },
                onClick = {
                    showContextMenu = false
                    onDelete()
                }
            )
        }
    }
}
