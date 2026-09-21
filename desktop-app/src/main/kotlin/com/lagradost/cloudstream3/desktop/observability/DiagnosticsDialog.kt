package com.lagradost.cloudstream3.desktop.observability

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.logging.DiagnosticEvent
import com.lagradost.common.logging.DiagnosticOrgan
import com.lagradost.common.logging.SystemDiagnostics
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun DiagnosticsDialog(
    onDismissRequest: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val allEvents by SystemDiagnostics.events.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedOrgan by remember { mutableStateOf<DiagnosticOrgan?>(null) }
    var onlyErrors by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }

    // Memory stats
    val runtime = Runtime.getRuntime()
    val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
    val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)
    val usedMemoryMb = totalMemoryMb - freeMemoryMb
    val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)

    // Filter events
    val filteredEvents = remember(allEvents, searchQuery, selectedOrgan, onlyErrors) {
        val q = searchQuery.trim().lowercase()
        allEvents.filter { event ->
            (selectedOrgan == null || event.organ == selectedOrgan) &&
            (!onlyErrors || event.level == AppLogger.Level.ERROR || event.level == AppLogger.Level.WARN) &&
            (q.isEmpty() ||
                event.message.lowercase().contains(q) ||
                event.tag.lowercase().contains(q) ||
                (event.details?.lowercase()?.contains(q) == true) ||
                (event.error?.lowercase()?.contains(q) == true))
        }.reversed() // Newest on top
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = Color(0xFF0D1117), // GitHub Dark style AMOLED surface
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Live System Telemetry & Diagnostics",
                            style = TvTypography.Headline.copy(fontSize = 22.sp),
                            color = Color.White,
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Heap Badge
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color(0xFF30363D)),
                        ) {
                            Text(
                                text = "JVM Heap: ${usedMemoryMb}M / ${maxMemoryMb}M",
                                style = TvTypography.Badge.copy(fontFamily = FontFamily.Monospace, color = TvColors.TextSecondary),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // Event Count Badge
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF161B22),
                            border = BorderStroke(1.dp, Color(0xFF30363D)),
                        ) {
                            Text(
                                text = "Showing ${filteredEvents.size} / ${allEvents.size} events",
                                style = TvTypography.Badge.copy(fontFamily = FontFamily.Monospace, color = TvColors.FocusElectricBlue),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = onDismissRequest, modifier = Modifier.focusable()) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Search Bar with High Readability
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF161B22),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF8B949E), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TvTypography.Body.copy(fontFamily = FontFamily.Monospace, color = Color.White),
                            cursorBrush = SolidColor(TvColors.FocusElectricBlue),
                            modifier = Modifier.weight(1f).focusable(),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search logs (e.g., 'filmmodu', '404', '503', 'mpv', 'reconnect', 'null')...",
                                            style = TvTypography.Body.copy(fontFamily = FontFamily.Monospace, color = Color(0xFF8B949E)),
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp).focusable()) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Filter Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // All Chip
                    FilterChip(
                        selected = selectedOrgan == null && !onlyErrors,
                        onClick = { selectedOrgan = null; onlyErrors = false },
                        label = { Text("ALL") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TvColors.FocusElectricBlue.copy(alpha = 0.2f),
                            selectedLabelColor = TvColors.FocusElectricBlue,
                        ),
                    )

                    // Errors Only Chip
                    FilterChip(
                        selected = onlyErrors,
                        onClick = { onlyErrors = !onlyErrors },
                        label = { Text("ERRORS & WARNINGS") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TvColors.ErrorRed.copy(alpha = 0.25f),
                            selectedLabelColor = TvColors.ErrorRed,
                        ),
                    )

                    DiagnosticOrgan.entries.forEach { organ ->
                        val isSelected = selectedOrgan == organ
                        val organColor = Color(organ.badgeColorHex)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedOrgan = if (isSelected) null else organ
                            },
                            label = { Text(organ.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = organColor.copy(alpha = 0.25f),
                                selectedLabelColor = organColor,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Status Message if Exported
                if (exportStatusMessage != null) {
                    Text(
                        text = exportStatusMessage!!,
                        style = TvTypography.Caption.copy(color = TvColors.FocusElectricBlue),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                // Log Stream Box (High Contrast Monospace Console)
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF030712), // Deep black terminal
                    border = BorderStroke(1.dp, Color(0xFF1F2937)),
                ) {
                    val listState = rememberLazyListState()
                    if (filteredEvents.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No matching telemetry events found." else "Telemetry buffer is empty.",
                                style = TvTypography.Body.copy(fontFamily = FontFamily.Monospace, color = Color(0xFF6B7280)),
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filteredEvents, key = { it.id }) { event ->
                                EventRow(event)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Footer Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val text = SystemDiagnostics.exportToString(searchQuery, selectedOrgan, onlyErrors)
                                clipboardManager.setText(AnnotatedString(text))
                                exportStatusMessage = "Copied ${filteredEvents.size} events to clipboard."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                            modifier = Modifier.focusable(),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy Filtered Logs")
                        }

                        Button(
                            onClick = {
                                try {
                                    val logsDir = File(PlatformPaths.dataDir.toFile(), "logs").apply { mkdirs() }
                                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                    val logFile = File(logsDir, "telemetry_$stamp.log")
                                    val text = SystemDiagnostics.exportToString(searchQuery, selectedOrgan, onlyErrors)
                                    logFile.writeText(text)
                                    exportStatusMessage = "Exported to: ${logFile.absolutePath}"
                                } catch (e: Exception) {
                                    exportStatusMessage = "Export failed: ${e.message}"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated),
                            modifier = Modifier.focusable(),
                        ) {
                            Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Export to File")
                        }

                        OutlinedButton(
                            onClick = {
                                SystemDiagnostics.clear()
                                exportStatusMessage = "Buffer cleared."
                            },
                            modifier = Modifier.focusable(),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Clear")
                        }
                    }

                    Button(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated),
                        modifier = Modifier.focusable(),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: DiagnosticEvent) {
    val organColor = Color(event.organ.badgeColorHex)
    val levelColor = when (event.level) {
        AppLogger.Level.ERROR -> Color(0xFFFF453A)
        AppLogger.Level.WARN -> Color(0xFFFFD60A)
        AppLogger.Level.INFO -> Color(0xFF30D158)
        AppLogger.Level.DEBUG -> Color(0xFF64D2FF)
    }

    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (event.level == AppLogger.Level.ERROR) Color(0xFF2A0808) else Color(0xFF0F141C))
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Timestamp
            Text(
                text = event.timestamp,
                style = TvTypography.Caption.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF6E7681),
                ),
            )
            Spacer(Modifier.width(6.dp))

            // Organ Badge
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = organColor.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, organColor.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = event.organ.label,
                    style = TvTypography.Badge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = organColor,
                    ),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.width(6.dp))

            // Level Indicator
            Text(
                text = event.level.label,
                style = TvTypography.Badge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = levelColor,
                ),
            )
            Spacer(Modifier.width(6.dp))

            // Tag
            Text(
                text = "[${event.tag}]",
                style = TvTypography.Caption.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E),
                ),
            )
            Spacer(Modifier.width(8.dp))

            // Message
            Text(
                text = event.message,
                style = TvTypography.Body.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (event.level == AppLogger.Level.ERROR) Color(0xFFFF9999) else Color(0xFFE6EDF3),
                ),
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                modifier = Modifier.weight(1f),
            )
        }

        // Details / Error Expansion
        if (!event.details.isNullOrBlank() && (isExpanded || event.level == AppLogger.Level.ERROR)) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "  └─ ${event.details}",
                style = TvTypography.Caption.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF7EE787),
                ),
            )
        }

        if (!event.error.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "  └─ ERROR: ${event.error}",
                style = TvTypography.Caption.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFFF7B72),
                ),
            )
        }
    }
}
