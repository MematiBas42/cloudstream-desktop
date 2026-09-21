package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@Composable
fun BrowseTab(viewModel: ExtensionsViewModel, syncGeneration: Int) {
    var searchQuery by remember { mutableStateOf("") }
    var languageFilter by remember { mutableStateOf("All") }
    var categoryFilter by remember { mutableStateOf("All") }
    var repoFilter by remember { mutableStateOf("All") }

    val plugins by viewModel.plugins.collectAsState()
    val isFetching by viewModel.isFetching.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val pluginRequiringBypass by viewModel.pluginRequiringBypass.collectAsState()
    val installedPlugins by viewModel.installedPlugins.collectAsState()
    val installingPlugins by viewModel.installingPlugins.collectAsState()

    val languages = remember(plugins) {
        listOf("All") + plugins.mapNotNull { it.second.language?.takeIf { l -> l.isNotBlank() } }.distinct().sorted()
    }
    val categories = remember(plugins) {
        listOf("All") + plugins.flatMap { it.second.tvTypes ?: emptyList() }.distinct().sorted()
    }
    val reposList = remember(plugins) {
        listOf("All") + plugins.map { it.first }.distinct().sorted()
    }

    var showLangDropdown by remember { mutableStateOf(false) }
    var showCatDropdown by remember { mutableStateOf(false) }
    var showRepoDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(syncGeneration) {
        if (syncGeneration > 0) {
            viewModel.loadPluginsFromManager()
        }
    }

    if (pluginRequiringBypass != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearBypass() },
            title = { Text("Security Warning") },
            text = {
                Text("Plugin '${pluginRequiringBypass!!.second.name}' requires reflection permissions blocked by sandbox. Bypass security?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val (r, p) = pluginRequiringBypass!!
                        viewModel.bypassSecurityAndInstall(r, p)
                    },
                    modifier = Modifier.focusable(),
                ) {
                    Text("Bypass & Install")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.clearBypass() },
                    modifier = Modifier.focusable(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Search and Filters Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f).focusable(),
                placeholder = { Text("Search available plugins...") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Box {
                Button(
                    onClick = { showLangDropdown = true },
                    modifier = Modifier.focusable(),
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated),
                ) {
                    Text(if (languageFilter == "All") "Languages" else languageFilter.uppercase())
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = showLangDropdown,
                    onDismissRequest = { showLangDropdown = false },
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(if (lang == "All") "All Languages" else lang.uppercase()) },
                            onClick = {
                                languageFilter = lang
                                showLangDropdown = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = { viewModel.fetchPlugins() },
                modifier = Modifier.focusable(),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = TvColors.FocusElectricBlue,
                )
            }
        }

        val filtered = plugins.filter { (repo, plugin) ->
            (searchQuery.isBlank() || plugin.name.contains(searchQuery, ignoreCase = true)) &&
            (languageFilter == "All" || plugin.language?.equals(languageFilter, ignoreCase = true) == true) &&
            (categoryFilter == "All" || plugin.tvTypes?.contains(categoryFilter) == true) &&
            (repoFilter == "All" || repo == repoFilter)
        }

        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (isFetching) "Fetching catalog..." else "No matching plugins found.",
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
                items(filtered) { (repoName, plugin) ->
                    val isInstalled = installedPlugins.any { it.internalName == plugin.internalName }
                    val isInstalling = installingPlugins.contains(plugin.internalName)

                    ExtensionCard(
                        name = plugin.name,
                        internalName = plugin.internalName,
                        version = plugin.version,
                        repoName = repoName,
                        language = plugin.language,
                        tvTypes = plugin.tvTypes,
                        iconUrl = plugin.iconUrl,
                        isInstalled = isInstalled,
                        installStatus = if (isInstalled) "Installed" else "Install",
                        isInstalling = isInstalling,
                        onInstallClick = {
                            if (!isInstalling) {
                                viewModel.installPlugin(repoName, plugin) {}
                            }
                        },
                    )
                }
            }
        }
    }
}
