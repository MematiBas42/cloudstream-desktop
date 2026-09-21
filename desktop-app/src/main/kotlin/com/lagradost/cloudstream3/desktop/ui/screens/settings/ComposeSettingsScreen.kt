package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

enum class SettingsTab(val title: String) {
    GENERAL("General"),
    PLAYER("Player"),
    SUBTITLES("Subtitles"),
    PROVIDERS("Providers"),
    ACCOUNTS("Accounts"),
    UI("UI"),
    BACKUP("Backup"),
    NETWORK("Network"),
    ADVANCED("Advanced"),
    ABOUT("About"),
}

@Composable
fun ComposeSettingsScreen(navController: NavController) {
    var selectedTab by remember { mutableStateOf(SettingsTab.GENERAL) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = TvDimensions.ScreenHorizontalPadding,
                vertical = 16.dp,
            ),
    ) {
        // Settings Section Header
        Text(
            text = "Settings",
            style = TvTypography.Headline,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Tab Navigation
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = Color.Transparent,
            contentColor = TvColors.FocusElectricBlue,
            edgePadding = 0.dp,
            divider = {},
            indicator = { tabPositions ->
                if (selectedTab.ordinal < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                        color = TvColors.FocusElectricBlue,
                    )
                }
            },
        ) {
            SettingsTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Tab(
                    selected = isSelected,
                    onClick = { selectedTab = tab },
                    modifier = Modifier.focusable(),
                    text = {
                        Text(
                            text = tab.title,
                            style = TvTypography.Button.copy(
                                color = if (isSelected) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                            ),
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                SettingsTab.GENERAL -> SettingsGeneral()
                SettingsTab.PLAYER -> SettingsPlayer(
                    onNavigateToSubtitles = { selectedTab = SettingsTab.SUBTITLES },
                )
                SettingsTab.SUBTITLES -> SettingsSubtitles()
                SettingsTab.PROVIDERS -> SettingsProviders()
                SettingsTab.ACCOUNTS -> SettingsAccount()
                SettingsTab.UI -> SettingsUI()
                SettingsTab.BACKUP -> SettingsBackup()
                SettingsTab.NETWORK -> SettingsNetwork()
                SettingsTab.ADVANCED -> SettingsAdvanced()
                SettingsTab.ABOUT -> SettingsAbout()
            }
        }
    }
}
