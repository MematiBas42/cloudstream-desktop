package com.lagradost.cloudstream3.desktop.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.services.BACKUP_DIR_PATH_KEY
import com.lagradost.cloudstream3.services.BackupScheduler
import com.lagradost.cloudstream3.services.BackupWorkManager
import com.lagradost.cloudstream3.ui.settings.DesktopAppSettings
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

const val PREF_AUTOMATIC_BACKUP = "automatic_backup_key"
const val PREF_BACKUP_PATH = "backup_path_key"

@Composable
fun SettingsBackup() {
    val appSettings = remember { DesktopAppSettings.getInstance() }
    val coroutineScope = rememberCoroutineScope()
    val context = remember { Context() }

    // 1. Frequency State
    var backupFrequency by remember {
        mutableStateOf(DesktopDataStore.getKey<Int>(PREF_AUTOMATIC_BACKUP) ?: 0)
    }

    // 2. Directory State
    val defaultBackupDir = remember { PlatformPaths.backupsDir.toAbsolutePath().toString() }
    var backupPath by remember {
        mutableStateOf(
            DesktopDataStore.getKey<String>(PREF_BACKUP_PATH)?.ifBlank { null }
                ?: DesktopDataStore.getKey<String>(BACKUP_DIR_PATH_KEY)?.ifBlank { null }
                ?: defaultBackupDir
        )
    }

    // 3. Action States
    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var refreshBackupsList by remember { mutableStateOf(0) }

    // Frequency Options
    val frequencyOptions = listOf(
        0 to "Off (Manual Backups Only)",
        24 to "Daily (Every 24 Hours)",
        168 to "Weekly (Every 7 Days)",
        720 to "Monthly (Every 30 Days)",
    )

    // Existing backups scan
    val existingBackups = remember(backupPath, refreshBackupsList) {
        val dir = File(backupPath)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file ->
                file.isFile && (file.name.endsWith(".cs3backup") || file.name.endsWith(".json"))
            }?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // Section 1: Automatic Backup Frequency
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Automated Periodic Backups",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Configure background coroutine daemon to automatically export atomic JSON and multi-partition ZIP backups.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                BackupDropdownSetting(
                    label = "Backup Frequency",
                    description = "How often automatic backups are taken in the background.",
                    options = frequencyOptions.map { it.first.toString() to it.second },
                    currentValue = backupFrequency.toString(),
                    onSelectionChanged = { value ->
                        val intVal = value.toIntOrNull() ?: 0
                        backupFrequency = intVal
                        DesktopDataStore.setKey(PREF_AUTOMATIC_BACKUP, intVal)
                        BackupWorkManager.enqueuePeriodicWork(context, intVal.toLong())
                    },
                )
            }
        }

        // Section 2: Backup Destination Directory
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Backup Storage Location",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Filesystem path where .json and .cs3backup archives are stored.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = TvColors.SurfaceElevated,
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = backupPath,
                                style = TvTypography.Caption,
                                color = TvColors.TextPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val chooser = JFileChooser(backupPath).apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Select Backup Directory"
                                }
                                val result = chooser.showOpenDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    val selected = chooser.selectedFile?.absolutePath
                                    if (!selected.isNullOrBlank()) {
                                        withContext(Dispatchers.Main) {
                                            backupPath = selected
                                            DesktopDataStore.setKey(PREF_BACKUP_PATH, selected)
                                            DesktopDataStore.setKey(BACKUP_DIR_PATH_KEY, selected)
                                            DesktopDataStore.setKey("backup_dir_key", selected)
                                            refreshBackupsList++
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.height(48.dp).focusable(),
                    ) {
                        Text("Browse...")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            backupPath = defaultBackupDir
                            DesktopDataStore.setKey(PREF_BACKUP_PATH, defaultBackupDir)
                            DesktopDataStore.setKey(BACKUP_DIR_PATH_KEY, defaultBackupDir)
                            DesktopDataStore.setKey("backup_dir_key", defaultBackupDir)
                            refreshBackupsList++
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvColors.TextSecondary),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.height(48.dp).focusable(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Default")
                    }
                }
            }
        }

        // Section 3: Manual Backup & Restore Operations
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Manual Backup & Restore Operations",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Create an immediate snapshot or restore watch history, bookmarks, and settings from a file.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Create Backup Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isBackingUp = true
                                statusMessage = null
                                val success = withContext(Dispatchers.IO) {
                                    BackupWorkManager.createBackup(context)
                                }
                                isBackingUp = false
                                statusMessage = if (success) {
                                    true to "Backup created successfully in $backupPath!"
                                } else {
                                    false to "Failed to create backup. Check logs for details."
                                }
                                refreshBackupsList++
                            }
                        },
                        enabled = !isBackingUp && !isRestoring,
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.weight(1f).height(48.dp).focusable(),
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Creating Backup...")
                        } else {
                            Text("Create Backup Now", style = TvTypography.Button)
                        }
                    }

                    // Restore Backup Button
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val chooser = JFileChooser(backupPath).apply {
                                    fileSelectionMode = JFileChooser.FILES_ONLY
                                    dialogTitle = "Select Backup File to Restore"
                                    fileFilter = FileNameExtensionFilter(
                                        "CloudStream Backups (*.cs3backup, *.json)",
                                        "cs3backup",
                                        "json",
                                    )
                                }
                                val result = chooser.showOpenDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    val selectedFile = chooser.selectedFile
                                    if (selectedFile != null && selectedFile.exists()) {
                                        withContext(Dispatchers.Main) {
                                            isRestoring = true
                                            statusMessage = null
                                        }
                                        val success = BackupWorkManager.restoreBackup(context, selectedFile)
                                        withContext(Dispatchers.Main) {
                                            isRestoring = false
                                            statusMessage = if (success) {
                                                true to "Restored successfully from ${selectedFile.name}!"
                                            } else {
                                                false to "Failed to restore from ${selectedFile.name}."
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isBackingUp && !isRestoring,
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusEmerald),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.weight(1f).height(48.dp).focusable(),
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restoring...")
                        } else {
                            Text("Restore Backup", style = TvTypography.Button)
                        }
                    }
                }

                // Status Message Feedback
                statusMessage?.let { (isSuccess, message) ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        color = if (isSuccess) TvColors.FocusEmerald.copy(alpha = 0.15f) else TvColors.ErrorRed.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (isSuccess) TvColors.FocusEmerald else TvColors.ErrorRed),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = message,
                            style = TvTypography.Caption.copy(
                                color = if (isSuccess) TvColors.FocusEmerald else TvColors.ErrorRed,
                            ),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        // Section 4: Available Backups in Directory
        if (existingBackups.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
                border = BorderStroke(1.dp, TvColors.BorderSubtle),
                shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "Available Backups (${existingBackups.size})",
                        style = TvTypography.Section,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Detected backup snapshots in the active backup directory.",
                        style = TvTypography.Caption,
                        color = TvColors.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                    existingBackups.forEach { file ->
                        val sizeKb = file.length() / 1024
                        val dateStr = dateFormat.format(Date(file.lastModified()))

                        Surface(
                            color = TvColors.SurfaceElevated,
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            border = BorderStroke(1.dp, TvColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = TvTypography.Body,
                                        color = Color.White,
                                    )
                                    Text(
                                        text = "$dateStr • ${sizeKb} KB",
                                        style = TvTypography.Caption,
                                        color = TvColors.TextSecondary,
                                    )
                                }
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isRestoring = true
                                            statusMessage = null
                                            val success = BackupWorkManager.restoreBackup(context, file)
                                            isRestoring = false
                                            statusMessage = if (success) {
                                                true to "Restored successfully from ${file.name}!"
                                            } else {
                                                false to "Failed to restore from ${file.name}."
                                            }
                                        }
                                    },
                                    enabled = !isBackingUp && !isRestoring,
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                                    border = BorderStroke(1.dp, TvColors.FocusEmerald),
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    modifier = Modifier.focusable(),
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = TvColors.FocusEmerald,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Restore",
                                        style = TvTypography.Caption.copy(color = TvColors.FocusEmerald),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupDropdownSetting(
    label: String,
    description: String? = null,
    options: List<Pair<String, String>>,
    currentValue: String,
    onSelectionChanged: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = options.firstOrNull { it.first == currentValue }?.second ?: currentValue

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = TvTypography.Body, color = Color.White)
        if (!description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, style = TvTypography.Caption, color = TvColors.TextSecondary)
        }
        Spacer(modifier = Modifier.height(6.dp))

        Box {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(48.dp).focusable(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvColors.SurfaceElevated,
                    contentColor = TvColors.TextPrimary,
                ),
                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                border = BorderStroke(1.dp, TvColors.BorderSubtle),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = displayValue, style = TvTypography.Button)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(TvColors.SurfaceElevated)
                    .widthIn(min = 280.dp),
            ) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = title,
                                style = TvTypography.Body.copy(
                                    color = if (value == currentValue) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                                ),
                            )
                        },
                        onClick = {
                            onSelectionChanged(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
