package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RepositoriesTab(viewModel: ExtensionsViewModel) {
    var repoUrl by remember { mutableStateOf("") }
    var isAddingRepo by remember { mutableStateOf(false) }
    val repos by DesktopRepositoryManager.savedRepositories.collectAsState()
    var statusText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        val handleAddRepo = {
            if (!isAddingRepo && repoUrl.isNotBlank()) {
                val input = repoUrl.trim()
                isAddingRepo = true
                statusText = "Adding repository '$input'..."
                coroutineScope.launch {
                    try {
                        val addedRepos = withContext(Dispatchers.IO) {
                            DesktopRepositoryManager.addRepositoryFromInput(input)
                        }
                        if (addedRepos != null && addedRepos.isNotEmpty()) {
                            repoUrl = ""
                            statusText = "Added ${addedRepos.size} repository(s) successfully."
                            viewModel.loadPluginsFromManager()
                        } else {
                            statusText = "Failed to load repository from input."
                        }
                    } catch (e: Throwable) {
                        statusText = "Error: ${e.message}"
                    } finally {
                        isAddingRepo = false
                    }
                }
            }
        }

        // Add Repository Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = repoUrl,
                onValueChange = { repoUrl = it },
                enabled = !isAddingRepo,
                modifier = Modifier
                    .weight(1f)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (!isAddingRepo && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            handleAddRepo()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Repository URL or Shortcode") },
                singleLine = true,
                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = handleAddRepo,
                enabled = !isAddingRepo && repoUrl.isNotBlank(),
                modifier = Modifier.focusable(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvColors.FocusElectricBlue,
                    contentColor = Color.White,
                    disabledContainerColor = TvColors.SurfaceElevated,
                    disabledContentColor = TvColors.TextMuted,
                ),
                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
            ) {
                if (isAddingRepo) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Adding...",
                            style = TvTypography.Button.copy(color = TvColors.TextSecondary),
                        )
                    }
                } else {
                    Text("Add Repo", style = TvTypography.Button)
                }
            }
        }

        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                style = TvTypography.Caption,
                color = TvColors.FocusElectricBlue,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Repositories Grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(360.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(TvDimensions.ItemSpacing),
        ) {
            items(repos) { repo ->
                Card(
                    modifier = Modifier.fillMaxWidth().focusable(),
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                    colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!repo.iconUrl.isNullOrEmpty()) {
                            AsyncImage(
                                model = repo.iconUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Home,
                                    contentDescription = null,
                                    tint = TvColors.FocusElectricBlue,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = repo.name,
                                style = TvTypography.Card.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = repo.url,
                                style = TvTypography.Caption,
                                color = TvColors.TextMuted,
                                maxLines = 1,
                            )
                        }

                        IconButton(
                            onClick = {
                                DesktopRepositoryManager.removeRepository(repo.url)
                                viewModel.loadPluginsFromManager()
                            },
                            modifier = Modifier.focusable(),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = TvColors.ErrorRed,
                            )
                        }
                    }
                }
            }
        }
    }
}
