package com.lagradost.cloudstream3.desktop.ui.screens.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.download.DownloadViewModel
import com.lagradost.cloudstream3.ui.download.VisualDownloadCached
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(java.util.Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

/**
 * Industrial-grade Native Desktop Dialog for viewing and managing downloaded episodes.
 * Provides offline playback, file revelation, single/multi episode deletion, and search.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DownloadedChildDialog(
    header: VisualDownloadCached.Header,
    viewModel: DownloadViewModel,
    onDismiss: () -> Unit,
    onPlayChild: (VisualDownloadCached.Child) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = remember { CloudStreamApp.context ?: android.content.Context() }
    val childCardsState by viewModel.childCards.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    val selectedBytes by viewModel.selectedBytes.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var episodePendingDelete by remember { mutableStateOf<VisualDownloadCached.Child?>(null) }

    LaunchedEffect(header.data.id) {
        val folder = getFolderName(DOWNLOAD_EPISODE_CACHE, header.data.id.toString())
        viewModel.updateChildList(context, folder)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.AmoledBackground,
            border = BorderStroke(1.dp, TvColors.BorderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header Bar: Poster, Series Name, Total Size, Search & Close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (!header.data.poster.isNullOrBlank()) {
                            AsyncImage(
                                model = header.data.poster,
                                contentDescription = header.data.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp, 80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, TvColors.BorderSubtle, RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }

                        Column {
                            Text(
                                text = header.data.name,
                                style = TvTypography.Section.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${header.totalDownloads} downloaded • ${formatBytes(header.totalBytes)}",
                                style = TvTypography.Caption,
                                color = TvColors.TextSecondary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // In-Dialog Episode Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter episodes...", style = TvTypography.Caption, color = TvColors.TextMuted) },
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
                                .width(220.dp)
                                .height(44.dp)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Close Dialog Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .background(TvColors.SurfaceCard, CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                HorizontalDivider(color = TvColors.BorderSubtle, thickness = 1.dp)

                // Multi-selection Action Toolbar (if active)
                if (selectedItemIds != null) {
                    val currentSelection = selectedItemIds ?: emptySet()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
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
                                    if (viewModel.isAllChildrenSelected()) {
                                        viewModel.clearSelectedItems()
                                    } else {
                                        viewModel.selectAllChildren()
                                    }
                                }
                            ) {
                                Text(
                                    text = if (viewModel.isAllChildrenSelected()) "Deselect All" else "Select All",
                                    color = TvColors.FocusElectricBlue
                                )
                            }

                            Button(
                                onClick = {
                                    viewModel.handleMultiDelete(context)
                                },
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

                Spacer(modifier = Modifier.height(12.dp))

                // Episodes Content
                when (val state = childCardsState) {
                    is Resource.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TvColors.FocusElectricBlue)
                        }
                    }
                    is Resource.Failure -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Failed to load episodes: ${state.errorString}",
                                style = TvTypography.Body,
                                color = TvColors.ErrorRed
                            )
                        }
                    }
                    is Resource.Success -> {
                        val allChildren = state.value
                        val filteredChildren = remember(allChildren, searchQuery) {
                            if (searchQuery.isBlank()) {
                                allChildren
                            } else {
                                val q = searchQuery.trim().lowercase()
                                allChildren.filter { child ->
                                    val nameMatch = child.data.name?.lowercase()?.contains(q) == true
                                    val epMatch = "episode ${child.data.episode}".contains(q) || "e${child.data.episode}".contains(q)
                                    val seasonMatch = child.data.season?.let { "s$it".contains(q) || "season $it".contains(q) } ?: false
                                    nameMatch || epMatch || seasonMatch
                                }
                            }
                        }

                        if (filteredChildren.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (searchQuery.isBlank()) "No episodes found" else "No episodes matching \"$searchQuery\"",
                                    style = TvTypography.Body,
                                    color = TvColors.TextSecondary
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 16.dp)
                            ) {
                                items(filteredChildren, key = { it.data.id }) { child ->
                                    DownloadedEpisodeRow(
                                        child = child,
                                        isMultiSelectMode = selectedItemIds != null,
                                        isSelected = selectedItemIds?.contains(child.data.id) == true,
                                        onPlay = { onPlayChild(child) },
                                        onToggleSelect = {
                                            if (selectedItemIds?.contains(child.data.id) == true) {
                                                viewModel.removeSelected(child.data.id)
                                            } else {
                                                viewModel.addSelected(child.data.id)
                                            }
                                        },
                                        onDelete = {
                                            episodePendingDelete = child
                                        },
                                        onOpenFileLocation = {
                                            openFileLocation(context, child.data.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Single Episode Delete Confirmation
    episodePendingDelete?.let { child ->
        AlertDialog(
            onDismissRequest = { episodePendingDelete = null },
            title = { Text("Delete Episode", color = Color.White) },
            text = {
                Text(
                    text = "Are you sure you want to delete \"${context.getNameFull(child.data.name, child.data.episode, child.data.season)}\"? This will permanently remove the file from your disk.",
                    color = TvColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.handleSingleDelete(context, child.data.id)
                        episodePendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { episodePendingDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = TvColors.SurfaceElevated,
            shape = RoundedCornerShape(12.dp)
        )
    }
}

/**
 * Individual Episode Row with Play button, Context Menu, Checkbox, and File Size.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DownloadedEpisodeRow(
    child: VisualDownloadCached.Child,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onOpenFileLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = remember { CloudStreamApp.context ?: android.content.Context() }
    var showContextMenu by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }

    val epNumText = remember(child.data.season, child.data.episode) {
        val s = child.data.season
        val e = child.data.episode
        if (s != null && s > 0) "S${s}E${e}" else "EP $e"
    }

    val fullTitle = remember(child.data.name, child.data.episode, child.data.season) {
        context.getNameFull(child.data.name, child.data.episode, child.data.season)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) TvColors.SurfaceHigh else if (isHovered) TvColors.SurfaceElevated else TvColors.SurfaceCard)
            .border(
                1.dp,
                if (isSelected) TvColors.FocusElectricBlue else if (isHovered) TvColors.BorderSubtle else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable {
                if (isMultiSelectMode) {
                    onToggleSelect()
                } else {
                    onPlay()
                }
            }
            .onClick(
                matcher = PointerMatcher.mouse(PointerButton.Secondary),
                onClick = { showContextMenu = true }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = TvColors.FocusElectricBlue,
                            uncheckedColor = TvColors.TextMuted
                        ),
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                // Episode Number Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TvColors.SurfaceElevated,
                    border = BorderStroke(1.dp, TvColors.BorderSubtle)
                ) {
                    Text(
                        text = epNumText,
                        style = TvTypography.Badge.copy(fontWeight = FontWeight.Bold),
                        color = TvColors.FocusElectricBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Episode Title and Description
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fullTitle,
                        style = TvTypography.Body.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!child.data.description.isNullOrBlank()) {
                        Text(
                            text = child.data.description!!,
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // File Size & Action Buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatBytes(child.totalBytes),
                    style = TvTypography.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = TvColors.TextSecondary,
                    modifier = Modifier.padding(end = 16.dp)
                )

                // Play Button
                IconButton(
                    onClick = onPlay,
                    modifier = Modifier
                        .size(36.dp)
                        .background(TvColors.FocusElectricBlue.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Offline",
                        tint = TvColors.FocusElectricBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Delete Button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = TvColors.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Right-Click Context Menu for Desktop Mouse Ergonomics
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.background(TvColors.SurfaceElevated)
        ) {
            DropdownMenuItem(
                text = { Text("Play Offline", style = TvTypography.Body) },
                leadingIcon = {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = TvColors.FocusElectricBlue)
                },
                onClick = {
                    showContextMenu = false
                    onPlay()
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
                text = { Text("Delete Episode", style = TvTypography.Body, color = TvColors.ErrorRed) },
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

/**
 * Cross-platform desktop helper to open the containing folder of a downloaded episode.
 */
fun openFileLocation(context: android.content.Context, id: Int) {
    try {
        val info = VideoDownloadManager.getDownloadFileInfo(context, id)
        val fileUri = info?.path
        val filePath = fileUri?.path ?: fileUri?.toString()
        if (filePath != null) {
            val cleanPath = if (filePath.startsWith("file://")) filePath.removePrefix("file://") else filePath
            val file = java.io.File(cleanPath)
            val parentDir = if (file.isDirectory) file else file.parentFile
            if (parentDir != null && parentDir.exists()) {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(parentDir)
                } else {
                    ProcessBuilder("xdg-open", parentDir.absolutePath).start()
                }
            }
        }
    } catch (e: Throwable) {
        com.lagradost.cloudstream3.CommonActivity.showToast("Cannot open folder: ${e.message}")
    }
}
