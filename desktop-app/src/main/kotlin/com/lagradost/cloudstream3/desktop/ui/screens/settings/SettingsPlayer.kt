package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
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
import com.lagradost.cloudstream3.actions.AlwaysAskAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.cloudstream3.desktop.ui.player.QualityProfileModalDialog
import com.lagradost.common.storage.DesktopDataStore

const val PREF_HWDEC = "player_hwdec"
const val PREF_YTDL_FORMAT = "player_ytdl_format"
const val PREF_PLAYER_DEFAULT = "player_default_key"
const val PREF_EPISODE_SYNC = "episode_sync_enabled_key"
const val PREF_BUFFER_RAM = "video_buffer_size_key"
const val PREF_SEEK_TIME = "double_tap_seek_time_key2"
const val PREF_RESIZE_ENABLED = "player_resize_enabled_key"
const val PREF_SKIP_OP_DATABASE = "enable_skip_op_from_database"
const val PREF_AUTO_SKIP_INTRO = "auto_skip_intro"
const val PREF_AUTOPLAY_NEXT = "autoplay_next_key"

@Composable
fun SettingsPlayer(
    onNavigateToSubtitles: (() -> Unit)? = null,
) {
    val appSettings = remember { DesktopAppSettings.getInstance() }

    // 1. Core Engine States
    var defaultPlayer by remember {
        val current = DesktopDataStore.getKey<String>(PREF_PLAYER_DEFAULT)
        val normalized = when (current) {
            "internal", null -> ""
            "ask" -> AlwaysAskAction().uniqueId()
            "external" -> {
                if (VlcPackage.isBinaryInPath("mpv")) {
                    MpvPackage().uniqueId()
                } else if (VlcPackage.isBinaryInPath("vlc")) {
                    VlcPackage().uniqueId()
                } else {
                    ""
                }
            }
            else -> current
        }
        mutableStateOf(normalized)
    }
    var hwdec by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_HWDEC) ?: "auto-safe")
    }
    var ytdlFormat by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_YTDL_FORMAT) ?: "bestvideo[height<=?1080]+bestaudio/best")
    }
    var bufferRam by remember {
        mutableStateOf(DesktopDataStore.getKey<Int>(PREF_BUFFER_RAM) ?: 0)
    }

    // 2. Playback & Navigation States
    var seekTime by remember {
        mutableStateOf(DesktopDataStore.getKey<Int>(PREF_SEEK_TIME) ?: 10)
    }
    var resizeEnabled by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_RESIZE_ENABLED) ?: true)
    }
    var autoPlayNext by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_AUTOPLAY_NEXT) ?: true)
    }

    // 3. Intro & Timestamp States
    var skipOpDatabase by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_SKIP_OP_DATABASE) ?: true)
    }
    var autoSkipIntro by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_AUTO_SKIP_INTRO) ?: false)
    }

    // 4. Sync State
    var episodeSync by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_EPISODE_SYNC) ?: true)
    }

    // 5. Quality Profiles Dialog State
    var showQualityProfileDialog by remember { mutableStateOf(false) }

    // Dropdown Options
    val availablePlayers = remember { VideoClickActionHolder.getPlayers(null) }
    val playerOptions = remember(availablePlayers) {
        val options = mutableListOf<Pair<String, String>>()
        options.add("" to "Internal Player (Embedded Surface)")
        for (player in availablePlayers) {
            val label = when (player) {
                is AlwaysAskAction -> "Always Ask (Prompt on Every Playback)"
                is MpvPackage -> "External MPV (System Executable)"
                is VlcPackage -> "External VLC (System Executable)"
                else -> player.name.asStringNull(null) ?: player::class.simpleName ?: player.uniqueId()
            }
            options.add(player.uniqueId() to label)
        }
        options
    }

    val hwdecOptions = listOf(
        "auto-safe" to "Auto Safe (VA-API / NVDEC / DRM Hardware Acceleration)",
        "auto-copy" to "Auto Copy (Compatibility Fallback Mode)",
        "no" to "Software Decoding (CPU Only)",
    )

    val qualityOptions = listOf(
        "bestvideo[height<=?2160]+bestaudio/best" to "4K UHD (2160p)",
        "bestvideo[height<=?1080]+bestaudio/best" to "Full HD (1080p)",
        "bestvideo[height<=?720]+bestaudio/best" to "HD (720p)",
        "best" to "Highest Available Stream",
    )

    val bufferOptions = listOf(
        0 to "Automatic / Adaptive Default",
        50 to "50 MiB (Low Memory Footprint)",
        100 to "100 MiB (Recommended Standard)",
        250 to "250 MiB (High Bitrate / 4K Stream)",
        500 to "500 MiB (Maximum Buffer Headroom)",
    )

    val seekOptions = listOf(
        5 to "5 seconds",
        10 to "10 seconds (Standard)",
        15 to "15 seconds",
        30 to "30 seconds",
        60 to "60 seconds (1 minute)",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // Section 1: Default Player & Video Engine
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Video Player Engine & Hardware Acceleration",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Configure embedded libmpv rendering surface, hardware decoding, and default resolution.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Default Player Selection
                PlayerDropdownSetting(
                    label = "Default Video Player",
                    description = "Choose between embedded zero-overhead libmpv surface or external player.",
                    options = playerOptions,
                    currentValue = defaultPlayer,
                    onSelectionChanged = {
                        defaultPlayer = it
                        DesktopDataStore.setKey(PREF_PLAYER_DEFAULT, it)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hardware Acceleration
                PlayerDropdownSetting(
                    label = "Hardware Video Decoding (libmpv hwdec)",
                    description = "Offloads video decoding to GPU via VA-API (Intel/AMD) or NVDEC (NVIDIA).",
                    options = hwdecOptions,
                    currentValue = hwdec,
                    onSelectionChanged = {
                        hwdec = it
                        DesktopDataStore.setKey(PREF_HWDEC, it)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Default Video Quality
                PlayerDropdownSetting(
                    label = "Default Stream Quality",
                    description = "Preferred resolution target when resolving multi-quality extractor streams.",
                    options = qualityOptions,
                    currentValue = ytdlFormat,
                    onSelectionChanged = {
                        ytdlFormat = it
                        DesktopDataStore.setKey(PREF_YTDL_FORMAT, it)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // RAM Buffer Size
                PlayerDropdownSetting(
                    label = "Playback RAM Buffer Size",
                    description = "RAM allocated to caching upcoming video segments to prevent buffering pauses.",
                    options = bufferOptions.map { it.first.toString() to it.second },
                    currentValue = bufferRam.toString(),
                    onSelectionChanged = {
                        val intVal = it.toIntOrNull() ?: 0
                        bufferRam = intVal
                        DesktopDataStore.setKey(PREF_BUFFER_RAM, intVal)
                    },
                )
            }
        }

        // Section 2: Playback Controls & Ergonomics
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Controls & Navigation Ergonomics",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Keyboard shortcut seek duration, aspect ratio manipulation, and automatic progression.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Seek Duration
                PlayerDropdownSetting(
                    label = "Seek Duration (Arrow Keys / Double Tap)",
                    description = "Seconds to seek backward/forward on Left/Right Arrow key presses.",
                    options = seekOptions.map { it.first.toString() to it.second },
                    currentValue = seekTime.toString(),
                    onSelectionChanged = {
                        val intVal = it.toIntOrNull() ?: 10
                        seekTime = intVal
                        DesktopDataStore.setKey(PREF_SEEK_TIME, intVal)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(16.dp))

                // Aspect Ratio Resize Toggle
                PlayerToggleRow(
                    title = "Aspect Ratio Zoom & Resize",
                    description = "Enables cycling through Fit, Crop/Zoom, 16:9, and 4:3 aspect ratios.",
                    checked = resizeEnabled,
                    onCheckedChange = {
                        resizeEnabled = it
                        DesktopDataStore.setKey(PREF_RESIZE_ENABLED, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                // Autoplay Next Episode
                PlayerToggleRow(
                    title = "Autoplay Next Episode",
                    description = "Automatically transitions to the next episode when current playback finishes.",
                    checked = autoPlayNext,
                    onCheckedChange = {
                        autoPlayNext = it
                        DesktopDataStore.setKey(PREF_AUTOPLAY_NEXT, it)
                    },
                )


            }
        }

        // Section 3: Intro Skipping & Chapters
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Intro Skipping & Chapter Timestamps",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Crowdsourced AniSkip and TheIntroDB timestamps for automated opening/ending skips.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Skip OP from database
                PlayerToggleRow(
                    title = "Enable Intro / OP Chapter Database",
                    description = "Queries AniSkip & TheIntroDB for opening and ending chapter timestamps.",
                    checked = skipOpDatabase,
                    onCheckedChange = {
                        skipOpDatabase = it
                        DesktopDataStore.setKey(PREF_SKIP_OP_DATABASE, it)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(12.dp))

                // Auto skip intro
                PlayerToggleRow(
                    title = "Automatically Skip Intros",
                    description = "Automatically skips opening timestamps without requiring manual 'Skip Intro' click.",
                    checked = autoSkipIntro,
                    onCheckedChange = {
                        autoSkipIntro = it
                        DesktopDataStore.setKey(PREF_AUTO_SKIP_INTRO, it)
                    },
                )
            }
        }

        // Section 4: Progress Tracking & Account Sync
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Watch Progress & Scrobbling Sync",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Synchronize playback position to AniList, MyAnimeList, and Simkl tracking accounts.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                PlayerToggleRow(
                    title = "Episode Sync Enabled",
                    description = "Dispatches scrobble progress when video playback crosses 80% completion.",
                    checked = episodeSync,
                    onCheckedChange = {
                        episodeSync = it
                        DesktopDataStore.setKey(PREF_EPISODE_SYNC, it)
                    },
                )
            }
        }

        // Section 5: Subtitles Configuration Link
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Subtitles & Closed Captions",
                            style = TvTypography.Section,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Configure font family, font size, text colors, background box opacity, and text edge styling.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    if (onNavigateToSubtitles != null) {
                        Button(
                            onClick = onNavigateToSubtitles,
                            colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            modifier = Modifier.focusable(),
                        ) {
                            Text("Configure Subtitles")
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

        // Section 6: Source Priority & Quality Profiles
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Source Priority & Quality Profiles",
                            style = TvTypography.Section,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Configure priority ranking of streaming sources and video qualities across WiFi, Mobile Data, and Download profiles.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Button(
                        onClick = { showQualityProfileDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.focusable(),
                    ) {
                        Text("Manage Profiles")
                    }
                }
            }
        }

        if (showQualityProfileDialog) {
            QualityProfileModalDialog(
                onDismiss = { showQualityProfileDialog = false }
            )
        }
    }
}

@Composable
private fun PlayerDropdownSetting(
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
                    Text(
                        text = displayValue,
                        style = TvTypography.Button,
                        maxLines = 1,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(TvColors.SurfaceElevated)
                    .widthIn(min = 280.dp, max = 500.dp),
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

@Composable
private fun PlayerToggleRow(
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
