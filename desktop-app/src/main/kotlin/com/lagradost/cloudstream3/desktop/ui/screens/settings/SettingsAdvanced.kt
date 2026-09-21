package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.desktop.observability.DiagnosticsDialog
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.logging.SystemDiagnostics
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import java.io.File

@Composable
fun SettingsAdvanced() {
    val mapper = remember { ObjectMapper() }
    var isObservabilityEnabled by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>("deep_observability_enabled") ?: true)
    }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(onDismissRequest = { showDiagnosticsDialog = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Deep Observability & Live Diagnostics Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Derin Sistem Teşhisi (Deep Observability & Telemetry)",
                            style = TvTypography.Section,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ağ (OkHttp), Görseller (Coil), Oynatıcı (MPV), Eklentiler (DEX) ve Depolama (XDG) damarlarını otomatik izler.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = isObservabilityEnabled,
                        modifier = Modifier.focusable(),
                        onCheckedChange = {
                            isObservabilityEnabled = it
                            SystemDiagnostics.isEnabled = it
                            DesktopDataStore.setKey("deep_observability_enabled", it)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Canlı Röntgen & Hata İnceleyici",
                        style = TvTypography.Body,
                        color = TvColors.TextPrimary,
                    )
                    Button(
                        onClick = { showDiagnosticsDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        modifier = Modifier.focusable(),
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Canlı Röntgeni Aç (Live Inspector)")
                    }
                }
            }
        }

        // XDG Storage Directories
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Linux Storage Locations",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Strict Freedesktop XDG Base Directory specification paths.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                @Composable
                fun PathRow(title: String, file: File) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = TvTypography.Body, color = Color.White)
                            Text(file.absolutePath, style = TvTypography.Caption, color = TvColors.TextMuted)
                        }
                    }
                }

                PathRow("Configuration (\$XDG_CONFIG_HOME)", PlatformPaths.configDir.toFile())
                PathRow("Plugins & Data (\$XDG_DATA_HOME)", PlatformPaths.pluginsDir.toFile())
                PathRow("Transpiled JAR Cache (\$XDG_CACHE_HOME)", PlatformPaths.transpiledCacheDir.toFile())
                PathRow("Cover Image Cache (\$XDG_CACHE_HOME)", PlatformPaths.imageCacheDir.toFile())
                PathRow("MPV IPC Socket (\$XDG_RUNTIME_DIR)", PlatformPaths.socketDir.toFile())
            }
        }

        // Cache Clearance
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Cache Management",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Clear Coil 3 Image Cache",
                        style = TvTypography.Body,
                        color = Color.White,
                    )
                    Button(
                        onClick = {
                            runCatching {
                                PlatformPaths.imageCacheDir.toFile().deleteRecursively()
                            }
                        },
                        modifier = Modifier.focusable(),
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Clear Images")
                    }
                }
            }
        }
    }
}
