package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.storage.PluginSettingsSchemaRegistry

@Composable
fun InstalledTab(viewModel: ExtensionsViewModel, syncGeneration: Int) {
    val installedPlugins by viewModel.installedPlugins.collectAsState()
    val reloadingPlugins by viewModel.reloadingPlugins.collectAsState()
    var selectedPlugins by remember { mutableStateOf(setOf<LocalPlugin>()) }
    val remoteIcons by DesktopRepositoryManager.remotePluginIcons.collectAsState()
    val settingsGeneration by PluginSettingsSchemaRegistry.schemaUpdates.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pluginForDialog by remember { mutableStateOf<LocalPlugin?>(null) }

    LaunchedEffect(syncGeneration) {
        viewModel.refreshInstalled()
    }

    val localPluginBypass by viewModel.localPluginRequiringBypass.collectAsState()
    if (localPluginBypass != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLocalPluginBypass() },
            title = { Text("Security Sandbox Warning") },
            text = {
                Text("The plugin uses reflection which is restricted by the security sandbox.\n\nBypass security and load it anyway? Only do this for plugins you trust.")
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.bypassSecurityAndLoadLocalPlugin() },
                    modifier = Modifier.focusable(),
                ) {
                    Text("Bypass & Load")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissLocalPluginBypass() },
                    modifier = Modifier.focusable(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Confirm Uninstall") },
            text = { Text("Are you sure you want to uninstall ${selectedPlugins.size} plugins?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        val toDelete = selectedPlugins.toList()
                        if (toDelete.isNotEmpty()) {
                            viewModel.uninstallPlugins(toDelete)
                            selectedPlugins = emptySet()
                        }
                    },
                    modifier = Modifier.focusable(),
                ) {
                    Text("Uninstall", color = TvColors.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.focusable(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    if (pluginForDialog != null) {
        val prefName = PluginSettingsSchemaRegistry.findPrefNameForPlugin(
            pluginForDialog!!.internalName,
            pluginForDialog!!.file.nameWithoutExtension,
        ) ?: "${pluginForDialog!!.internalName}_"

        PluginSettingsDialog(
            pluginName = pluginForDialog!!.name,
            prefName = prefName,
            onReload = {
                viewModel.reloadPlugin(pluginForDialog!!) {}
                pluginForDialog = null
            },
            onDismiss = { pluginForDialog = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${installedPlugins.size} Plugin(s) Installed",
                style = TvTypography.Section,
            )

            if (selectedPlugins.isNotEmpty()) {
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed),
                    modifier = Modifier.focusable(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Uninstall (${selectedPlugins.size})")
                }
            }
        }

        if (installedPlugins.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No plugins currently installed.",
                    style = TvTypography.Section.copy(color = TvColors.TextMuted),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
                verticalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
            ) {
                items(installedPlugins) { plugin ->
                    val resolvedPrefName = PluginSettingsSchemaRegistry.findPrefNameForPlugin(
                        plugin.internalName,
                        plugin.file.nameWithoutExtension,
                    ) ?: "${plugin.internalName}_"

                    val hasSchemaSettings = PluginSettingsSchemaRegistry.hasSettings(resolvedPrefName)
                    val icon = plugin.iconUrl ?: remoteIcons[plugin.internalName] ?: remoteIcons[plugin.name]
                    val isChecked = selectedPlugins.contains(plugin)
                    val isReloading = reloadingPlugins.contains(plugin.internalName)

                    ExtensionCard(
                        name = plugin.name,
                        internalName = plugin.internalName,
                        version = plugin.version,
                        repoName = plugin.repoName,
                        language = plugin.language,
                        tvTypes = plugin.tvTypes,
                        iconUrl = icon,
                        isInstalled = true,
                        installStatus = if (isReloading) "Reloading..." else "Reload",
                        isInstalling = isReloading,
                        onInstallClick = {
                            if (!isReloading) {
                                viewModel.reloadPlugin(plugin) {}
                            }
                        },
                        showCheckbox = true,
                        isChecked = isChecked,
                        onCheckedChange = { checked ->
                            selectedPlugins = if (checked) {
                                selectedPlugins + plugin
                            } else {
                                selectedPlugins - plugin
                            }
                        },
                        showSettings = hasSchemaSettings,
                        onSettingsClick = { pluginForDialog = plugin },
                    )
                }
            }
        }
    }
}
