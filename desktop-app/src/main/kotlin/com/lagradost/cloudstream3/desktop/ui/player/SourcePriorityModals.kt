package com.lagradost.cloudstream3.desktop.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.player.source_priority.LinkSource
import com.lagradost.cloudstream3.ui.player.source_priority.ProfileSettings
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.SourcePriority
import com.lagradost.cloudstream3.ui.player.source_priority.SourcePriorityDialog
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Desktop Modal Dialog for managing and adjusting source and quality priorities
 * for a specific [QualityDataHelper.QualityProfile].
 */
@Composable
fun SourcePriorityModalDialog(
    profile: QualityDataHelper.QualityProfile,
    links: List<LinkSource>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {}
) {
    val context = remember { CloudStreamApp.context ?: android.content.Context() }
    val dialogController = remember(profile.id, links) {
        SourcePriorityDialog(
            ctx = context,
            links = links,
            profile = profile,
            updatedCallback = onSaved
        )
    }

    var profileName by remember { mutableStateOf(dialogController.getProfileName()) }
    var sources by remember { mutableStateOf(dialogController.getSortedSources()) }
    var qualities by remember { mutableStateOf(dialogController.getSortedQualities()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Sources, 1 = Qualities

    if (showSettingsDialog) {
        SourceProfileSettingsModalDialog(
            profileId = profile.id,
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("Quality & Source Priorities") },
            text = {
                Text(
                    "Priorities determine which streaming links and resolutions are selected first.\n\n" +
                    "Higher priority numbers are attempted before lower numbers. " +
                    "Negative priorities or error sources can be suppressed in settings.",
                    color = TvColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("OK", color = TvColors.FocusElectricBlue)
                }
            },
            containerColor = TvColors.SurfaceElevated
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .width(680.dp)
                .heightIn(max = 700.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Source Priority",
                            style = TvTypography.Section.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = TvColors.TextPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                            color = TvColors.FocusElectricBlue.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, TvColors.FocusElectricBlue)
                        ) {
                            Text(
                                text = "Profile ${profile.id}",
                                style = TvTypography.Caption.copy(
                                    color = TvColors.FocusElectricBlue,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { showHelpDialog = true }) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "Help", tint = TvColors.TextSecondary)
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TvColors.TextSecondary)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TvColors.TextSecondary)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Profile Name Input
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Profile Name") },
                    placeholder = { Text("Profile ${profile.id}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = TvColors.TextPrimary,
                        unfocusedTextColor = TvColors.TextPrimary
                    )
                )

                Spacer(Modifier.height(16.dp))

                // Tabs: Sources vs Qualities
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = TvColors.SurfaceCard,
                    contentColor = TvColors.FocusElectricBlue
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Sources (${sources.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Qualities (${qualities.size})") }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Scrollable priority list
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedTab == 0) {
                        if (sources.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No sources found for this media.", color = TvColors.TextSecondary)
                            }
                        } else {
                            sources.forEachIndexed { index, item ->
                                PriorityItemRow(
                                    name = item.name,
                                    priority = item.priority,
                                    onIncrement = {
                                        val updated = sources.toMutableList()
                                        updated[index] = item.copy(priority = item.priority + 1)
                                        sources = updated.sortedBy { -it.priority }
                                    },
                                    onDecrement = {
                                        val updated = sources.toMutableList()
                                        updated[index] = item.copy(priority = item.priority - 1)
                                        sources = updated.sortedBy { -it.priority }
                                    }
                                )
                            }
                        }
                    } else {
                        qualities.forEachIndexed { index, item ->
                            PriorityItemRow(
                                name = item.name,
                                priority = item.priority,
                                onIncrement = {
                                    val updated = qualities.toMutableList()
                                    updated[index] = item.copy(priority = item.priority + 1)
                                    qualities = updated.sortedBy { -it.priority }
                                },
                                onDecrement = {
                                    val updated = qualities.toMutableList()
                                    updated[index] = item.copy(priority = item.priority - 1)
                                    qualities = updated.sortedBy { -it.priority }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TvColors.TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            dialogController.save(profileName, sources, qualities)
                            onSaved()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue)
                    ) {
                        Text("Save Changes", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Row displaying an individual priority item with title, priority number chip, and +/- buttons.
 */
@Composable
private fun PriorityItemRow(
    name: String,
    priority: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
        color = TvColors.SurfaceCard,
        border = BorderStroke(1.dp, TvColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = TvTypography.Body.copy(fontWeight = FontWeight.Medium),
                color = TvColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                    color = if (priority > 0) TvColors.FocusElectricBlue.copy(alpha = 0.2f) else TvColors.SurfaceElevated,
                    border = BorderStroke(1.dp, if (priority > 0) TvColors.FocusElectricBlue else TvColors.BorderSubtle)
                ) {
                    Text(
                        text = priority.toString(),
                        style = TvTypography.Caption.copy(
                            color = if (priority > 0) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                FilledIconButton(
                    onClick = onDecrement,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = TvColors.SurfaceElevated)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = TvColors.TextPrimary, modifier = Modifier.size(16.dp))
                }

                FilledIconButton(
                    onClick = onIncrement,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = TvColors.SurfaceElevated)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase", tint = TvColors.TextPrimary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * Desktop Modal Dialog for Quality Profile Overview & Selection.
 */
@Composable
fun QualityProfileModalDialog(
    links: List<LinkSource> = emptyList(),
    usedProfile: Int? = null,
    onDismiss: () -> Unit,
    onProfileSelected: ((QualityDataHelper.QualityProfile) -> Unit)? = null
) {
    var profiles by remember { mutableStateOf(QualityDataHelper.getProfiles()) }
    var selectedProfileId by remember { mutableStateOf(usedProfile ?: profiles.firstOrNull()?.id ?: 1) }
    var profileToEdit by remember { mutableStateOf<QualityDataHelper.QualityProfile?>(null) }
    var showSetDefaultDialog by remember { mutableStateOf(false) }

    val currentProfile = profiles.firstOrNull { it.id == selectedProfileId }

    if (profileToEdit != null) {
        SourcePriorityModalDialog(
            profile = profileToEdit!!,
            links = links,
            onDismiss = { profileToEdit = null },
            onSaved = {
                profiles = QualityDataHelper.getProfiles()
                profileToEdit = null
            }
        )
    }

    if (showSetDefaultDialog && currentProfile != null) {
        val availableTypes = listOf(
            QualityDataHelper.QualityProfileType.WiFi,
            QualityDataHelper.QualityProfileType.Data,
            QualityDataHelper.QualityProfileType.Download
        )
        var selectedTypes by remember { mutableStateOf(currentProfile.types.toSet()) }

        AlertDialog(
            onDismissRequest = { showSetDefaultDialog = false },
            title = { Text("Set Default Profile Types") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Configure which connection types automatically activate Profile ${currentProfile.id}.",
                        color = TvColors.TextSecondary
                    )
                    availableTypes.forEach { type ->
                        val isChecked = selectedTypes.contains(type)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedTypes = if (isChecked) {
                                        selectedTypes - type
                                    } else {
                                        selectedTypes + type
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedTypes = if (checked) selectedTypes + type else selectedTypes - type
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(type.name, color = TvColors.TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        availableTypes.forEach { type ->
                            if (selectedTypes.contains(type)) {
                                if (type.unique) {
                                    QualityDataHelper.getProfiles().filter { it.types.contains(type) }.forEach {
                                        QualityDataHelper.removeQualityProfileType(it.id, type)
                                    }
                                }
                                QualityDataHelper.addQualityProfileType(currentProfile.id, type)
                            } else {
                                QualityDataHelper.removeQualityProfileType(currentProfile.id, type)
                            }
                        }
                        profiles = QualityDataHelper.getProfiles()
                        showSetDefaultDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue)
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetDefaultDialog = false }) {
                    Text("Cancel", color = TvColors.TextSecondary)
                }
            },
            containerColor = TvColors.SurfaceElevated
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .width(620.dp)
                .heightIn(max = 680.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.VideoSettings,
                            contentDescription = null,
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Quality Profiles",
                            style = TvTypography.Section.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                            color = TvColors.TextPrimary
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TvColors.TextSecondary)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Profile cards list
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { profile ->
                        val isSelected = profile.id == selectedProfileId
                        val context = remember { CloudStreamApp.context ?: android.content.Context() }
                        val name = QualityDataHelper.getProfileName(profile.id).asString(context)

                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = if (isSelected) TvColors.FocusElectricBlue.copy(alpha = 0.12f) else TvColors.SurfaceCard,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                                .clickable { selectedProfileId = profile.id }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = name,
                                        style = TvTypography.Body.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) Color.White else TvColors.TextPrimary
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        profile.types.filter { it != QualityDataHelper.QualityProfileType.None }.forEach { type ->
                                            Surface(
                                                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                                                color = TvColors.FocusElectricBlue.copy(alpha = 0.2f),
                                                border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = type.name,
                                                    style = TvTypography.Caption.copy(
                                                        fontSize = 11.sp,
                                                        color = TvColors.FocusElectricBlue,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { profileToEdit = profile },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Edit")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { showSetDefaultDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceCard),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle)
                    ) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Set Default Types")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Close", color = TvColors.TextSecondary)
                        }

                        if (onProfileSelected != null && currentProfile != null) {
                            Button(
                                onClick = {
                                    onProfileSelected(currentProfile)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue)
                            ) {
                                Text("Use Profile", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Desktop Modal Dialog for toggling boolean profile settings (HideErrorSources, HideNegativeSources).
 */
@Composable
fun SourceProfileSettingsModalDialog(
    profileId: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit = {}
) {
    var hideErrorSources by remember {
        mutableStateOf(QualityDataHelper.getProfileSetting(profileId, ProfileSettings.HideErrorSources))
    }
    var hideNegativeSources by remember {
        mutableStateOf(QualityDataHelper.getProfileSetting(profileId, ProfileSettings.HideNegativeSources))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier.width(480.dp).padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Profile Settings",
                        style = TvTypography.Section.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        color = TvColors.TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TvColors.TextSecondary)
                    }
                }

                // Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide Error Sources", color = TvColors.TextPrimary, fontWeight = FontWeight.Medium)
                        Text("Suppress links from providers that failed during scraping", color = TvColors.TextSecondary, style = TvTypography.Caption)
                    }
                    Switch(
                        checked = hideErrorSources,
                        onCheckedChange = { hideErrorSources = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TvColors.FocusElectricBlue)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide Negative Sources", color = TvColors.TextPrimary, fontWeight = FontWeight.Medium)
                        Text("Suppress links with priority scores below zero", color = TvColors.TextSecondary, style = TvTypography.Caption)
                    }
                    Switch(
                        checked = hideNegativeSources,
                        onCheckedChange = { hideNegativeSources = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TvColors.FocusElectricBlue)
                    )
                }

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TvColors.TextSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            QualityDataHelper.setProfileSetting(profileId, ProfileSettings.HideErrorSources, hideErrorSources)
                            QualityDataHelper.setProfileSetting(profileId, ProfileSettings.HideNegativeSources, hideNegativeSources)
                            onSaved()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue)
                    ) {
                        Text("Apply", color = Color.White)
                    }
                }
            }
        }
    }
}
