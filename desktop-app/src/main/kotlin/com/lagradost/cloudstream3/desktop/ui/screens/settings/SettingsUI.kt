package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.settings.DesktopAppSettings
import com.lagradost.common.storage.DesktopDataStore

const val PREF_SHOW_HD = "show_hd_key"
const val PREF_SHOW_DUB = "show_dub_key"
const val PREF_SHOW_SUB = "show_sub_key"
const val PREF_SHOW_RATING = "show_rating_key"
const val PREF_SHOW_TITLE = "show_title_key"
const val PREF_SHOW_EPISODE_TEXT = "show_episode_text_key"
const val PREF_ADVANCED_SEARCH = "advanced_search"
const val PREF_SEARCH_SUGGESTIONS = "search_suggestions_enabled"
const val PREF_SHOW_TRAILERS = "show_trailers_key"
const val PREF_SHOW_KITSU_POSTERS = "show_kitsu_posters_key"
const val PREF_SHOW_CAST = "show_cast_in_details_key"
const val PREF_SHOW_FILLERS = "show_fillers_key"
const val PREF_RANDOM_BUTTON = "random_button_key"

@Composable
fun SettingsUI() {
    val appSettings = remember { DesktopAppSettings.getInstance() }

    // 1. Poster Badge States
    var showHd by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_HD) ?: true)
    }
    var showDub by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_DUB) ?: true)
    }
    var showSub by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_SUB) ?: true)
    }
    var showRating by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_RATING) ?: true)
    }
    var showTitle by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_TITLE) ?: true)
    }
    var showEpisodeText by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_EPISODE_TEXT) ?: true)
    }

    // 2. Search & Discovery States
    var advancedSearch by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_ADVANCED_SEARCH) ?: true)
    }
    var searchSuggestions by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SEARCH_SUGGESTIONS) ?: true)
    }
    var randomButton by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_RANDOM_BUTTON) ?: false)
    }

    // 3. Detail Page States
    var showTrailers by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_TRAILERS) ?: true)
    }
    var showKitsuPosters by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_KITSU_POSTERS) ?: true)
    }
    var showCast by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_CAST) ?: true)
    }
    var showFillers by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SHOW_FILLERS) ?: false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // Section 1: Poster UI Badges & Overlays
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Poster UI Badges & Information Overlays",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Control visual indicators and metadata badges rendered on media cards and home grids.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Live Preview Card
                Surface(
                    color = TvColors.SurfaceElevated,
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                    border = BorderStroke(1.dp, TvColors.BorderSubtle),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Poster Mockup
                        Surface(
                            color = Color(0xFF1E2433),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f)),
                            modifier = Modifier.width(100.dp).height(145.dp),
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                                // Top Badges
                                Row(
                                    modifier = Modifier.align(Alignment.TopStart),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (showHd) {
                                        BadgeChip("HD", TvColors.FocusElectricBlue)
                                    }
                                    if (showDub) {
                                        BadgeChip("DUB", TvColors.FocusEmerald)
                                    }
                                    if (showSub) {
                                        BadgeChip("SUB", TvColors.AccentAmber)
                                    }
                                }

                                // Rating Badge Top End
                                if (showRating) {
                                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                                        BadgeChip("★ 8.9", Color(0xFFEAB308))
                                    }
                                }

                                // Bottom Content (Title & Episode)
                                Column(
                                    modifier = Modifier.align(Alignment.BottomStart),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    if (showTitle) {
                                        Text(
                                            text = "Sample Title",
                                            style = TvTypography.Caption.copy(color = Color.White),
                                            maxLines = 1,
                                        )
                                    }
                                    if (showEpisodeText) {
                                        Text(
                                            text = "Ep 12 / 24",
                                            style = TvTypography.Caption.copy(color = TvColors.TextSecondary),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(20.dp))

                        Column {
                            Text(
                                text = "Live Badge Preview",
                                style = TvTypography.Body,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Toggling options below immediately reflects how cards appear across the desktop UI.",
                                style = TvTypography.Caption,
                                color = TvColors.TextMuted,
                            )
                        }
                    }
                }

                SettingToggleRow(
                    title = "Show HD Quality Badge",
                    description = "Displays the 'HD' badge on titles with high-definition streams.",
                    checked = showHd,
                    onCheckedChange = {
                        showHd = it
                        DesktopDataStore.setKey(PREF_SHOW_HD, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Dubbed (DUB) Badge",
                    description = "Displays the 'DUB' badge if audio tracks are available in alternate languages.",
                    checked = showDub,
                    onCheckedChange = {
                        showDub = it
                        DesktopDataStore.setKey(PREF_SHOW_DUB, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Subtitled (SUB) Badge",
                    description = "Displays the 'SUB' badge on titles with subtitle availability.",
                    checked = showSub,
                    onCheckedChange = {
                        showSub = it
                        DesktopDataStore.setKey(PREF_SHOW_SUB, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Rating Badge",
                    description = "Displays community/IMDb/MAL numerical ratings in the top corner.",
                    checked = showRating,
                    onCheckedChange = {
                        showRating = it
                        DesktopDataStore.setKey(PREF_SHOW_RATING, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Card Titles",
                    description = "Renders text titles at the bottom of media cards.",
                    checked = showTitle,
                    onCheckedChange = {
                        showTitle = it
                        DesktopDataStore.setKey(PREF_SHOW_TITLE, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Episode Progress Text",
                    description = "Renders episode numbers (e.g. 'Ep 12') or remaining count under titles.",
                    checked = showEpisodeText,
                    onCheckedChange = {
                        showEpisodeText = it
                        DesktopDataStore.setKey(PREF_SHOW_EPISODE_TEXT, it)
                    },
                )
            }
        }

        // Section 2: Search & Discovery
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Search & Discovery Engine",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Configure keyboard search ergonomics, query suggestions, and random discovery.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                SettingToggleRow(
                    title = "Advanced Search Features",
                    description = "Enables granular quality filters, media type selectors, and tag exclusions in search.",
                    checked = advancedSearch,
                    onCheckedChange = {
                        advancedSearch = it
                        DesktopDataStore.setKey(PREF_ADVANCED_SEARCH, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Search Suggestions (TMDB / Provider Autocomplete)",
                    description = "Fetches live search suggestions and auto-complete as you type in the search bar.",
                    checked = searchSuggestions,
                    onCheckedChange = {
                        searchSuggestions = it
                        DesktopDataStore.setKey(PREF_SEARCH_SUGGESTIONS, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Random Discovery Button",
                    description = "Places a 'Random' dice button on the home screen to pick a random unwatched title.",
                    checked = randomButton,
                    onCheckedChange = {
                        randomButton = it
                        DesktopDataStore.setKey(PREF_RANDOM_BUTTON, it)
                    },
                )
            }
        }

        // Section 3: Media Details Page
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Media Details Screen Customization",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Control what elements appear on the title details and episode listing page.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                SettingToggleRow(
                    title = "Show Official Trailers",
                    description = "Displays trailer player button and embedded YouTube preview in details header.",
                    checked = showTrailers,
                    onCheckedChange = {
                        showTrailers = it
                        DesktopDataStore.setKey(PREF_SHOW_TRAILERS, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Kitsu High-Res Anime Posters",
                    description = "Enriches anime details with canonical Kitsu artwork, banners, and character metadata.",
                    checked = showKitsuPosters,
                    onCheckedChange = {
                        showKitsuPosters = it
                        DesktopDataStore.setKey(PREF_SHOW_KITSU_POSTERS, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Cast & Crew in Details",
                    description = "Displays horizontal actor/director carousel under the synopsis.",
                    checked = showCast,
                    onCheckedChange = {
                        showCast = it
                        DesktopDataStore.setKey(PREF_SHOW_CAST, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                SettingToggleRow(
                    title = "Show Anime Filler Indicators",
                    description = "Marks non-canon filler episodes with an orange 'FILLER' badge.",
                    checked = showFillers,
                    onCheckedChange = {
                        showFillers = it
                        DesktopDataStore.setKey(PREF_SHOW_FILLERS, it)
                    },
                )
            }
        }
    }
}

@Composable
private fun BadgeChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, color),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = TvTypography.Caption.copy(color = color),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TvTypography.Body,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = TvTypography.Caption,
                color = TvColors.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            modifier = Modifier.focusable(),
            onCheckedChange = onCheckedChange,
        )
    }
}
