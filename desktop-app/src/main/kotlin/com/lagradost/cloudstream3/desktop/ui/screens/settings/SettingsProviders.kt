package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.settings.DesktopAppSettings
import com.lagradost.cloudstream3.ui.settings.ProviderPreferences
import com.lagradost.cloudstream3.utils.DataStoreHelper

@Composable
fun SettingsProviders() {
    val appSettings = remember { DesktopAppSettings.getInstance() }

    // 1. Pinned Providers State
    var pinnedProviders by remember {
        mutableStateOf(DataStoreHelper.pinnedProviders.toSet())
    }

    // 2. Preferred Media Types State
    var preferredMedia by remember {
        mutableStateOf(appSettings.provider.preferredMedia.get())
    }

    // 3. Sequential Main Page Delay State
    var sequentialMainPage by remember {
        mutableStateOf(appSettings.provider.sequentialMainPage.get())
    }
    var sequentialMainPageDelay by remember {
        mutableStateOf(appSettings.provider.sequentialMainPageDelay.get())
    }

    // 4. Provider Languages State
    var extensionLanguages by remember {
        mutableStateOf(appSettings.provider.extensionLanguages.get())
    }

    // 5. Dub / Sub Filter State
    var displayDubSub by remember {
        mutableStateOf(appSettings.provider.displayDubSub.get())
    }

    // Search query for providers
    var providerSearchQuery by remember { mutableStateOf("") }

    // Available Providers list from APIHolder (excluding internal MetaProviders)
    val allAvailableProviders: List<MainAPI> = remember {
        val baseList = if (APIHolder.allProviders.isNotEmpty()) {
            APIHolder.allProviders
        } else {
            APIHolder.apis
        }
        baseList.filter { it.providerType != ProviderType.MetaProvider }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    val filteredProviders = remember(providerSearchQuery, allAvailableProviders) {
        if (providerSearchQuery.isBlank()) {
            allAvailableProviders
        } else {
            allAvailableProviders.filter {
                it.name.contains(providerSearchQuery, ignoreCase = true) ||
                        it.mainUrl.contains(providerSearchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Pinned Providers Section
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Pinned Providers (USER_PINNED_PROVIDERS)",
                            style = TvTypography.Section,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${pinnedProviders.size} providers pinned. Pinned providers appear first in search and home screens.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }

                    if (pinnedProviders.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                pinnedProviders = emptySet()
                                DataStoreHelper.pinnedProviders = emptyArray()
                                appSettings.provider.pinnedProviders.set(emptySet())
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TvColors.ErrorRed
                            ),
                            border = BorderStroke(1.dp, TvColors.ErrorRed.copy(alpha = 0.5f)),
                        ) {
                            Text("Clear All Pinned", style = TvTypography.Caption)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search Bar
                OutlinedTextField(
                    value = providerSearchQuery,
                    onValueChange = { providerSearchQuery = it },
                    placeholder = { Text("Search installed providers by name or URL...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TvColors.TextSecondary,
                        )
                    },
                    trailingIcon = {
                        if (providerSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { providerSearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = TvColors.TextSecondary,
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Providers List
                if (filteredProviders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (allAvailableProviders.isEmpty()) {
                                "No providers installed yet. Install plugins from Extensions."
                            } else {
                                "No providers match \"$providerSearchQuery\""
                            },
                            style = TvTypography.Body,
                            color = TvColors.TextSecondary,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filteredProviders.forEach { provider ->
                            val isPinned = pinnedProviders.contains(provider.name)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isPinned) {
                                        TvColors.FocusElectricBlue.copy(alpha = 0.08f)
                                    } else {
                                        Color(0xFF141822)
                                    }
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isPinned) TvColors.FocusElectricBlue.copy(alpha = 0.5f) else TvColors.BorderSubtle
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = provider.name,
                                                style = TvTypography.Body.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White,
                                            )
                                            if (isPinned) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(TvColors.FocusElectricBlue.copy(alpha = 0.2f))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        text = "PINNED",
                                                        style = TvTypography.Caption.copy(
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = TvColors.FocusElectricBlue,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${provider.mainUrl} • Lang: [${provider.lang}]",
                                            style = TvTypography.Caption,
                                            color = TvColors.TextSecondary,
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            val newPinned = if (isPinned) {
                                                pinnedProviders - provider.name
                                            } else {
                                                pinnedProviders + provider.name
                                            }
                                            pinnedProviders = newPinned
                                            DataStoreHelper.pinnedProviders = newPinned.toTypedArray()
                                            appSettings.provider.pinnedProviders.set(newPinned)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = if (isPinned) "Unpin" else "Pin",
                                            tint = if (isPinned) Color(0xFFFFBF00) else TvColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Preferred Media Types Section
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Preferred Media Types",
                            style = TvTypography.Section,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Filter provider catalogs by preferred media formats on home and search.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val allTypes = TvType.entries.map { it.ordinal.toString() }.toSet()
                                preferredMedia = allTypes
                                appSettings.provider.preferredMedia.set(allTypes)
                                DataStoreHelper.currentHomePage = null
                            }
                        ) {
                            Text("Select All", style = TvTypography.Caption)
                        }
                        TextButton(
                            onClick = {
                                val defaultTypes = ProviderPreferences.defaultPreferredMedia
                                preferredMedia = defaultTypes
                                appSettings.provider.preferredMedia.set(defaultTypes)
                                DataStoreHelper.currentHomePage = null
                            }
                        ) {
                            Text("Reset Defaults", style = TvTypography.Caption)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // TvType Chips
                val mediaTypes = listOf(
                    TvType.Movie to "Movies",
                    TvType.TvSeries to "TV Series",
                    TvType.Anime to "Anime",
                    TvType.Cartoon to "Cartoons",
                    TvType.AnimeMovie to "Anime Movies",
                    TvType.OVA to "OVAs",
                    TvType.AsianDrama to "Asian Dramas",
                    TvType.Documentary to "Documentaries",
                    TvType.Live to "Live Streams",
                    TvType.Torrent to "Torrents",
                    TvType.NSFW to "NSFW (18+)",
                )

                OptInFlowRow(
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                ) {
                    mediaTypes.forEach { (type, label) ->
                        val key = type.ordinal.toString()
                        val isSelected = preferredMedia.contains(key)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val updated = if (isSelected) {
                                    preferredMedia - key
                                } else {
                                    preferredMedia + key
                                }
                                preferredMedia = updated
                                appSettings.provider.preferredMedia.set(updated)
                                DataStoreHelper.currentHomePage = null
                            },
                            label = { Text(label, style = TvTypography.Caption) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TvColors.FocusElectricBlue,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF141822),
                                labelColor = Color.White,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = TvColors.BorderSubtle,
                                selectedBorderColor = TvColors.FocusElectricBlue,
                                enabled = true,
                                selected = isSelected,
                            ),
                        )
                    }
                }
            }
        }

        // Anti-Scraping & Sequential Main Page Delay Section
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Anti-Scraping & Rate Limiting Protection",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sequential requests prevent Cloudflare HTTP 429 rate limiting when loading provider homepages.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Sequential Main Page Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sequential Homepage Requests", style = TvTypography.Body, color = Color.White)
                        Text(
                            "Load categories sequentially with throttling instead of parallel barrage",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = sequentialMainPage,
                        onCheckedChange = {
                            sequentialMainPage = it
                            appSettings.provider.sequentialMainPage.set(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TvColors.FocusElectricBlue,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Sequential Delay Options
                Text("Request Delay Interval", style = TvTypography.Body, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))

                val delayOptions = listOf(
                    0L to "0 ms (Instant)",
                    100L to "100 ms (Fast)",
                    250L to "250 ms (Balanced)",
                    500L to "500 ms (Safe)",
                    1000L to "1.0s (Strict)",
                    2000L to "2.0s (Maximum)",
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    delayOptions.forEach { (delayMs, label) ->
                        val isSelected = sequentialMainPageDelay == delayMs
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                sequentialMainPageDelay = delayMs
                                appSettings.provider.sequentialMainPageDelay.set(delayMs)
                            },
                            label = { Text(label, style = TvTypography.Caption) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TvColors.FocusElectricBlue,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF141822),
                                labelColor = Color.White,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = TvColors.BorderSubtle,
                                selectedBorderColor = TvColors.FocusElectricBlue,
                                enabled = true,
                                selected = isSelected,
                            ),
                        )
                    }
                }
            }
        }

        // Provider Extension Languages
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Provider Languages",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Filter which provider languages are active in search and catalogs.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                val languages = listOf(
                    AllLanguagesName to "All Languages",
                    "en" to "English (en)",
                    "tr" to "Türkçe (tr)",
                    "es" to "Español (es)",
                    "fr" to "Français (fr)",
                    "de" to "Deutsch (de)",
                    "ja" to "日本語 (ja)",
                    "it" to "Italiano (it)",
                    "pt" to "Português (pt)",
                    "ru" to "Русский (ru)",
                    "ar" to "العربية (ar)",
                )

                OptInFlowRow(
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 8.dp,
                ) {
                    languages.forEach { (code, label) ->
                        val isSelected = extensionLanguages.contains(code)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val updated = if (isSelected) {
                                    extensionLanguages - code
                                } else {
                                    extensionLanguages + code
                                }
                                extensionLanguages = updated
                                appSettings.provider.extensionLanguages.set(updated)
                            },
                            label = { Text(label, style = TvTypography.Caption) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TvColors.FocusElectricBlue,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF141822),
                                labelColor = Color.White,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = TvColors.BorderSubtle,
                                selectedBorderColor = TvColors.FocusElectricBlue,
                                enabled = true,
                                selected = isSelected,
                            ),
                        )
                    }
                }
            }
        }

        // Subbed / Dubbed Preference
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Dubbed / Subbed Release Preference",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DubStatus.entries.forEach { status ->
                        val isSelected = displayDubSub.contains(status.name)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val updated = if (isSelected) {
                                    displayDubSub - status.name
                                } else {
                                    displayDubSub + status.name
                                }
                                displayDubSub = updated
                                appSettings.provider.displayDubSub.set(updated)
                            },
                            label = { Text(status.name, style = TvTypography.Body) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TvColors.FocusElectricBlue,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF141822),
                                labelColor = Color.White,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = TvColors.BorderSubtle,
                                selectedBorderColor = TvColors.FocusElectricBlue,
                                enabled = true,
                                selected = isSelected,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptInFlowRow(
    horizontalSpacing: androidx.compose.ui.unit.Dp,
    verticalSpacing: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        content = content,
    ) { measurables, constraints ->
        val hGap = horizontalSpacing.roundToPx()
        val vGap = verticalSpacing.roundToPx()

        var currentX = 0
        var currentY = 0
        var lineHeight = 0

        val placeables = measurables.map { measurable ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            if (currentX + placeable.width > constraints.maxWidth && currentX > 0) {
                currentX = 0
                currentY += lineHeight + vGap
                lineHeight = 0
            }
            lineHeight = maxOf(lineHeight, placeable.height)
            currentX += placeable.width + hGap
            placeable
        }

        currentX = 0
        currentY = 0
        lineHeight = 0
        val positions = placeables.map { placeable ->
            if (currentX + placeable.width > constraints.maxWidth && currentX > 0) {
                currentX = 0
                currentY += lineHeight + vGap
                lineHeight = 0
            }
            val pos = Pair(currentX, currentY)
            lineHeight = maxOf(lineHeight, placeable.height)
            currentX += placeable.width + hGap
            pos
        }

        val totalHeight = (currentY + lineHeight).coerceAtLeast(0)
        layout(constraints.maxWidth, totalHeight) {
            placeables.forEachIndexed { index, placeable ->
                val (x, y) = positions[index]
                placeable.placeRelative(x, y)
            }
        }
    }
}
