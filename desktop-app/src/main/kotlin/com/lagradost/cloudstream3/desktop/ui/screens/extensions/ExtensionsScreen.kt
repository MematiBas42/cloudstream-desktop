package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.repo.DesktopRepositoryManager
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ComposeExtensionScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Browse", "Installed", "Repositories")
    val syncGen by DesktopRepositoryManager.syncGeneration.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val viewModel = remember { ExtensionsViewModel(coroutineScope) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DesktopRepositoryManager.syncAll()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 16.dp),
    ) {
        // Tab Navigation
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.widthIn(max = 500.dp),
                containerColor = Color.Transparent,
                divider = {},
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTab = index },
                        modifier = Modifier.focusable(),
                        text = {
                            Text(
                                text = title,
                                style = TvTypography.Button.copy(
                                    color = if (isSelected) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                                ),
                            )
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> BrowseTab(viewModel = viewModel, syncGeneration = syncGen)
                1 -> InstalledTab(viewModel = viewModel, syncGeneration = syncGen)
                2 -> RepositoriesTab(viewModel = viewModel)
            }
        }
    }
}
