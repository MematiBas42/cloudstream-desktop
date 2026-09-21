package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@Composable
fun KeyboardShortcutsDialog(
    onDismissRequest: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusLarge),
            color = TvColors.SurfaceCard,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier
                .width(680.dp)
                .wrapContentHeight()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(TvColors.FocusElectricBlue.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "⌨",
                                fontSize = 18.sp,
                                color = TvColors.FocusElectricBlue
                            )
                        }
                        Column {
                            Text(
                                text = "Keyboard Shortcuts & Controls",
                                style = TvTypography.Headline.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                color = Color.White
                            )
                            Text(
                                text = "PC / Desktop Navigation Ergonomics Guide",
                                style = TvTypography.Caption.copy(color = TvColors.TextSecondary)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TvColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // General Navigation
                    ShortcutSection(
                        title = "General Navigation",
                        shortcuts = listOf(
                            "/" to "Focus Search Bar directly",
                            "Esc" to "Go Back / Close active dialog or panel",
                            "F1  or  ?" to "Toggle this Keyboard Shortcuts Guide",
                            "F11  or  Alt+Enter" to "Toggle Fullscreen mode",
                            "F12" to "Toggle System Diagnostics & Logcat",
                            "Arrow Keys / D-Pad" to "2D Spatial Grid Focus Navigation",
                            "Tab / Shift+Tab" to "Next / Previous Focusable Element"
                        )
                    )

                    // Media Player
                    ShortcutSection(
                        title = "Embedded Media Player",
                        shortcuts = listOf(
                            "Space  or  K" to "Play / Pause playback",
                            "Left / Right Arrow" to "Seek -10s / +10s backward or forward",
                            "Up / Down Arrow" to "Volume Up / Down (5% steps)",
                            "M" to "Mute / Unmute audio",
                            "F  or  F11" to "Toggle Fullscreen",
                            "S" to "Skip Intro / Outro (when timestamp is active)",
                            "N" to "Next Episode (auto-resolves next links)",
                            "D" to "Toggle In-Player Episode & Subtitle Drawer",
                            "Esc" to "Exit Player & save playback position"
                        )
                    )

                    // Mouse Ergonomics
                    ShortcutSection(
                        title = "Mouse Ergonomics",
                        shortcuts = listOf(
                            "Mouse Wheel" to "Smooth horizontal row & vertical list scrolling",
                            "Card Hover" to "Smooth tactile scale (1.05x) and glowing elevation",
                            "Right-Click" to "Context Menu (Play, Details, Mark as Watched, Remove)",
                            "Play Overlay Click" to "1-Click direct resume on Continue Watching cards"
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill)
                    ) {
                        Text("Got it", style = TvTypography.Button.copy(color = Color.White))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutSection(
    title: String,
    shortcuts: List<Pair<String, String>>
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = TvTypography.Section.copy(fontSize = 14.sp, color = TvColors.FocusElectricBlue),
            fontWeight = FontWeight.SemiBold
        )

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                shortcuts.forEach { (key, description) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = TvColors.SurfaceContainer,
                            border = BorderStroke(1.dp, TvColors.BorderSubtle)
                        ) {
                            Text(
                                text = key,
                                style = TvTypography.Caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TvColors.TextPrimary,
                                    fontSize = 12.sp
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Text(
                            text = description,
                            style = TvTypography.Body.copy(
                                fontSize = 13.sp,
                                color = TvColors.TextSecondary
                            ),
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
