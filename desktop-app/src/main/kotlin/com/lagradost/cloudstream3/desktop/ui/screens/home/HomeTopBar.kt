package com.lagradost.cloudstream3.desktop.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.desktop.observability.DiagnosticsDialog
import com.lagradost.cloudstream3.desktop.ui.components.KeyboardShortcutsDialog
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.storage.PluginUpdateRecord

@Composable
fun HomeTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearch: androidx.compose.foundation.text.KeyboardActionScope.() -> Unit,
    providers: List<MainAPI>,
    selectedProvider: MainAPI?,
    onProviderSelected: (String) -> Unit,
    mergedPluginIcons: Map<String, String>,
    hasUnreadUpdates: Boolean = false,
    updatesHistory: List<PluginUpdateRecord> = emptyList(),
    onMarkUpdatesRead: () -> Unit = {},
    onRandomClick: (() -> Unit)? = null,
    onShowShortcuts: (() -> Unit)? = null,
) {
    var isProviderDropdownExpanded by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showInternalShortcutsDialog by remember { mutableStateOf(false) }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(onDismissRequest = { showDiagnosticsDialog = false })
    }

    if (showInternalShortcutsDialog) {
        KeyboardShortcutsDialog(onDismissRequest = { showInternalShortcutsDialog = false })
    }

    fun fuzzyMatchIcon(providerName: String): String? {
        val pName = providerName.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .replace("provider", "")
            .replace("plugin", "")

        return mergedPluginIcons.entries.firstOrNull { (k, _) ->
            val kName = k.lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .replace("provider", "")
                .replace("plugin", "")
            if (kName.length < 3) return@firstOrNull false
            pName.isNotEmpty() && (pName.contains(kName) || kName.contains(pName))
        }?.value
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TvDimensions.ScreenHorizontalPadding, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Search Input Field (Pill Shape with Focus Glow)
            Surface(
                shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                color = TvColors.SurfaceElevated,
                border = BorderStroke(1.dp, TvColors.BorderSubtle),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 320.dp, max = 560.dp)
                    .height(46.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TvColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = TvTypography.Body.copy(color = TvColors.TextPrimary),
                        cursorBrush = SolidColor(TvColors.FocusElectricBlue),
                        modifier = Modifier.weight(1f).focusable(),
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = onSearch),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search movies, series, anime... (Press / to focus)",
                                        style = TvTypography.Body.copy(color = TvColors.TextMuted),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onSearchQueryChange("") },
                            modifier = Modifier.size(28.dp).focusable(),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TvColors.TextSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right Actions Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Upstream 1:1 Random Pick ("Surprise Me" / Dice) Button
                if (onRandomClick != null) {
                    val randomInteraction = remember { MutableInteractionSource() }
                    var isRandomHovered by remember { mutableStateOf(false) }

                    Surface(
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                        color = if (isRandomHovered) TvColors.FocusElectricBlue.copy(alpha = 0.2f) else TvColors.SurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (isRandomHovered) TvColors.FocusElectricBlue else TvColors.BorderSubtle
                        ),
                        modifier = Modifier
                            .height(46.dp)
                            .focusable(interactionSource = randomInteraction)
                            .clickable(
                                interactionSource = randomInteraction,
                                indication = null,
                                onClick = onRandomClick
                            ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = "🎲",
                                fontSize = 16.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Random",
                                style = TvTypography.Button.copy(
                                    color = if (isRandomHovered) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                ),
                            )
                        }
                    }
                }

                // Provider Selector Dropdown
                Box {
                    val providerInteraction = remember { MutableInteractionSource() }
                    Surface(
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                        color = TvColors.SurfaceElevated,
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        modifier = Modifier
                            .height(46.dp)
                            .focusable(interactionSource = providerInteraction)
                            .clickable(interactionSource = providerInteraction, indication = null) {
                                isProviderDropdownExpanded = true
                            },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            val iconUrl = selectedProvider?.name?.let { fuzzyMatchIcon(it) }
                            if (!iconUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                val initial = selectedProvider?.name?.firstOrNull()?.uppercase() ?: "P"
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(TvColors.SurfaceContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = initial,
                                        style = TvTypography.Badge.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = TvColors.FocusElectricBlue,
                                        ),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }

                            Text(
                                text = selectedProvider?.name ?: "Select Provider",
                                style = TvTypography.Button.copy(color = TvColors.TextPrimary),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = "Dropdown",
                                tint = TvColors.TextSecondary,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isProviderDropdownExpanded,
                        onDismissRequest = { isProviderDropdownExpanded = false },
                        modifier = Modifier.background(TvColors.SurfaceCard),
                    ) {
                        providers.forEach { provider ->
                            val isSelected = provider.name == selectedProvider?.name
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val icon = fuzzyMatchIcon(provider.name)
                                        if (!icon.isNullOrBlank()) {
                                            AsyncImage(
                                                model = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp).clip(CircleShape),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        } else {
                                            val initial = provider.name.firstOrNull()?.uppercase() ?: "P"
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(TvColors.SurfaceContainer),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    text = initial,
                                                    style = TvTypography.Badge.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp,
                                                        color = if (isSelected) TvColors.FocusElectricBlue else TvColors.TextSecondary,
                                                    ),
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = provider.name,
                                            style = TvTypography.Body.copy(
                                                color = if (isSelected) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                                            ),
                                        )
                                    }
                                },
                                trailingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = TvColors.FocusElectricBlue,
                                        )
                                    }
                                } else null,
                                onClick = {
                                    onProviderSelected(provider.name)
                                    isProviderDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Keyboard Shortcuts Guide Button
                IconButton(
                    onClick = {
                        if (onShowShortcuts != null) {
                            onShowShortcuts()
                        } else {
                            showInternalShortcutsDialog = true
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusPill))
                        .background(TvColors.SurfaceElevated)
                        .focusable(),
                ) {
                    Text(
                        text = "⌨",
                        fontSize = 20.sp,
                        color = TvColors.TextSecondary
                    )
                }

                // Fast-Access Live Diagnostics Inspector Button
                IconButton(
                    onClick = { showDiagnosticsDialog = true },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusPill))
                        .background(TvColors.SurfaceElevated)
                        .focusable(),
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = "Live System Diagnostics",
                        tint = TvColors.FocusElectricBlue,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
