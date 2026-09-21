package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.CaptionStyleCompat
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitleFont
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment
import com.lagradost.player.subtitles.MpvSubtitleStyler
import java.util.Locale

@Composable
fun SettingsSubtitles() {
    val currentSavedStyle by SubtitlesFragment.subtitleStyleState.collectAsState()
    var style by remember(currentSavedStyle) { mutableStateOf(currentSavedStyle) }

    fun updateStyle(newStyle: SaveCaptionStyle) {
        style = newStyle
        SubtitlesFragment.setSubtitleStyle(null, newStyle)
    }

    // Color presets
    val textColors = listOf(
        "White" to (-1 to Color.White),
        "Yellow" to (0xFFFFFF00.toInt() to Color(0xFFFFFF00)),
        "Cyan" to (0xFF00FFFF.toInt() to Color(0xFF00FFFF)),
        "Green" to (0xFF00FF00.toInt() to Color(0xFF00FF00)),
        "Amber" to (0xFFFFBF00.toInt() to Color(0xFFFFBF00)),
        "Coral" to (0xFFFF4444.toInt() to Color(0xFFFF4444)),
        "Light Gray" to (0xFFD0D0D0.toInt() to Color(0xFFD0D0D0)),
    )

    val bgColors = listOf(
        "None (0%)" to (0 to Color.Transparent),
        "Black 25%" to (0x40000000 to Color(0x40000000)),
        "Black 50%" to (0x80000000.toInt() to Color(0x80000000)),
        "Black 75%" to (0xBF000000.toInt() to Color(0xBF000000)),
        "Solid Black" to (0xFF000000.toInt() to Color(0xFF000000)),
        "Dark Navy" to (0x80001030.toInt() to Color(0x80001030)),
    )

    val edgeColors = listOf(
        "Black" to (0xFF000000.toInt() to Color.Black),
        "Dark Gray" to (0xFF222222.toInt() to Color(0xFF222222)),
        "White" to (-1 to Color.White),
        "Transparent" to (0 to Color.Transparent),
    )

    val edgeTypes = listOf(
        CaptionStyleCompat.EDGE_TYPE_OUTLINE to "Outline",
        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW to "Drop Shadow",
        CaptionStyleCompat.EDGE_TYPE_RAISED to "Raised",
        CaptionStyleCompat.EDGE_TYPE_DEPRESSED to "Depressed",
        CaptionStyleCompat.EDGE_TYPE_NONE to "None",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Live Subtitle Preview Surface
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0C14)),
            border = BorderStroke(1.dp, TvColors.FocusElectricBlue.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Live Subtitle Preview (MPV libass Engine)",
                        style = TvTypography.Section,
                        color = Color.White,
                    )
                    OutlinedButton(
                        onClick = {
                            updateStyle(SubtitlesFragment.defaultSubtitleStyle)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TvColors.FocusElectricBlue
                        ),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset",
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset to Defaults", style = TvTypography.Caption)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Cinematic Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E2430), Color(0xFF0D1117))
                            )
                        )
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Compose visual rendering of the active subtitle
                    val previewFontSize = (style.fixedTextSize ?: 25f).sp
                    val previewTextColor = Color(style.foregroundColor)
                    val previewBgColor = Color(style.backgroundColor)
                    val previewShadow = when (style.edgeType) {
                        CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW -> Shadow(
                            color = Color(style.edgeColor),
                            offset = Offset(3f, 3f),
                            blurRadius = (style.edgeSize ?: 3f) * 1.5f,
                        )
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE -> Shadow(
                            color = Color(style.edgeColor),
                            offset = Offset(0f, 0f),
                            blurRadius = (style.edgeSize ?: 3f) * 2f,
                        )
                        CaptionStyleCompat.EDGE_TYPE_RAISED -> Shadow(
                            color = Color(style.edgeColor),
                            offset = Offset(-2f, -2f),
                            blurRadius = 1f,
                        )
                        CaptionStyleCompat.EDGE_TYPE_DEPRESSED -> Shadow(
                            color = Color(style.edgeColor),
                            offset = Offset(2f, 2f),
                            blurRadius = 1f,
                        )
                        else -> null
                    }

                    val previewFamily = when (style.font) {
                        SubtitleFont.Consola -> FontFamily.Monospace
                        SubtitleFont.STIX, SubtitleFont.TimesNewRoman -> FontFamily.Serif
                        else -> FontFamily.SansSerif
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(previewBgColor)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (style.upperCase) {
                                "CLOUDSTREAM 10-FOOT DESKTOP\nLIVE SUBTITLE PREVIEW (00:01:23)"
                            } else {
                                "CloudStream 10-Foot Desktop\nLive Subtitle Preview (00:01:23)"
                            },
                            style = TextStyle(
                                color = previewTextColor,
                                fontSize = previewFontSize,
                                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                                fontFamily = previewFamily,
                                shadow = previewShadow,
                                textAlign = TextAlign.Center,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Technical MPV IPC Properties HUD
                val mpvProps = MpvSubtitleStyler.translateToProperties(style)
                Text(
                    text = "MPV IPC: font=\"${mpvProps[MpvSubtitleStyler.PROP_SUB_FONT]}\" • " +
                            "size=${mpvProps[MpvSubtitleStyler.PROP_SUB_FONT_SIZE]}px • " +
                            "color=${mpvProps[MpvSubtitleStyler.PROP_SUB_COLOR]} • " +
                            "bg=${mpvProps[MpvSubtitleStyler.PROP_SUB_BACK_COLOR]} • " +
                            "border=${mpvProps[MpvSubtitleStyler.PROP_SUB_BORDER_COLOR]} (${mpvProps[MpvSubtitleStyler.PROP_SUB_BORDER_SIZE]}px) • " +
                            "pos=${mpvProps[MpvSubtitleStyler.PROP_SUB_POS]}%",
                    style = TvTypography.Caption.copy(fontSize = 11.sp),
                    color = TvColors.TextSecondary,
                )
            }
        }

        // Font Family Selector (15 Fonts)
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Font Family (15 Fonts Available)",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select from 15 embedded subtitle typefaces with desktop fontconfig matching.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                var fontDropdownExpanded by remember { mutableStateOf(false) }
                val currentFont = style.font ?: SubtitleFont.Trebuchet

                Box {
                    OutlinedButton(
                        onClick = { fontDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = TvColors.SurfaceCard,
                            contentColor = Color.White,
                        ),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${currentFont.label} (${currentFont.name})",
                                style = TvTypography.Body,
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Font",
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = fontDropdownExpanded,
                        onDismissRequest = { fontDropdownExpanded = false },
                        modifier = Modifier
                            .background(TvColors.SurfaceCard)
                            .heightIn(max = 300.dp),
                    ) {
                        SubtitleFont.entries.forEach { font ->
                            val isSelected = style.font == font
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = "${font.label} (${font.name})",
                                            color = if (isSelected) TvColors.FocusElectricBlue else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = TvColors.FocusElectricBlue,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    updateStyle(style.copy(font = font, typefaceFilePath = if (font == SubtitleFont.Custom) style.typefaceFilePath else null))
                                    fontDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Font Size & Positioning Sliders
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Size & Vertical Elevation",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Font Size Slider
                val currentSize = style.fixedTextSize ?: 25f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Font Size", style = TvTypography.Body, color = Color.White)
                    Text(
                        "${currentSize.toInt()} sp (${MpvSubtitleStyler.calculateFontSize(currentSize).toInt()} px MPV)",
                        style = TvTypography.Body,
                        color = TvColors.FocusElectricBlue,
                    )
                }
                Slider(
                    value = currentSize,
                    onValueChange = { updateStyle(style.copy(fixedTextSize = it)) },
                    valueRange = 12f..50f,
                    steps = 37,
                    colors = SliderDefaults.colors(
                        thumbColor = TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.BorderSubtle,
                    ),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Elevation Slider
                val currentElevation = style.elevation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Vertical Elevation", style = TvTypography.Body, color = Color.White)
                    Text(
                        "$currentElevation dp (${MpvSubtitleStyler.calculateSubPos(currentElevation, style.alignment).toInt()}% MPV pos)",
                        style = TvTypography.Body,
                        color = TvColors.FocusElectricBlue,
                    )
                }
                Slider(
                    value = currentElevation.toFloat(),
                    onValueChange = { updateStyle(style.copy(elevation = it.toInt())) },
                    valueRange = 0f..100f,
                    steps = 99,
                    colors = SliderDefaults.colors(
                        thumbColor = TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.BorderSubtle,
                    ),
                )
            }
        }

        // Color & Styling Presets
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Color & Contrast",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Text (Foreground) Color Swatches
                Text("Text Color", style = TvTypography.Body, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    textColors.forEach { (name, pair) ->
                        val (intColor, color) = pair
                        val isSelected = style.foregroundColor == intColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle,
                                    shape = CircleShape,
                                )
                                .clickable { updateStyle(style.copy(foregroundColor = intColor)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = name,
                                    tint = if (color == Color.White) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Background Color Swatches
                Text("Background Opacity / Color", style = TvTypography.Body, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    bgColors.forEach { (name, pair) ->
                        val (intColor, color) = pair
                        val isSelected = style.backgroundColor == intColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle,
                                    shape = CircleShape,
                                )
                                .clickable { updateStyle(style.copy(backgroundColor = intColor)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = name,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Edge / Shadow Styling
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Edge & Outline Effect",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Edge Type Chips
                Text("Edge Type", style = TvTypography.Body, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    edgeTypes.forEach { (typeId, label) ->
                        val isSelected = style.edgeType == typeId
                        FilterChip(
                            selected = isSelected,
                            onClick = { updateStyle(style.copy(edgeType = typeId)) },
                            label = { Text(label, style = TvTypography.Caption) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TvColors.FocusElectricBlue,
                                selectedLabelColor = Color.Black,
                                containerColor = TvColors.SurfaceCard,
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

                Spacer(modifier = Modifier.height(16.dp))

                // Edge Size Slider
                val currentEdgeSize = style.edgeSize ?: 3f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Edge / Outline Thickness", style = TvTypography.Body, color = Color.White)
                    Text(
                        "${currentEdgeSize.toInt()} px",
                        style = TvTypography.Body,
                        color = TvColors.FocusElectricBlue,
                    )
                }
                Slider(
                    value = currentEdgeSize,
                    onValueChange = { updateStyle(style.copy(edgeSize = it)) },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(
                        thumbColor = TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.BorderSubtle,
                    ),
                )
            }
        }

        // Typography Formatting Toggles
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Text Formatting & Sanitization",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Bold Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Bold Font", style = TvTypography.Body, color = Color.White)
                        Text("Render subtitles in bold weight", style = TvTypography.Caption, color = TvColors.TextSecondary)
                    }
                    Switch(
                        checked = style.bold,
                        onCheckedChange = { updateStyle(style.copy(bold = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TvColors.FocusElectricBlue,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Italic Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Italic Font", style = TvTypography.Body, color = Color.White)
                        Text("Render subtitles with italic slant", style = TvTypography.Caption, color = TvColors.TextSecondary)
                    }
                    Switch(
                        checked = style.italic,
                        onCheckedChange = { updateStyle(style.copy(italic = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TvColors.FocusElectricBlue,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // All Caps Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Uppercase Text", style = TvTypography.Body, color = Color.White)
                        Text("Convert all subtitles to uppercase for readability", style = TvTypography.Caption, color = TvColors.TextSecondary)
                    }
                    Switch(
                        checked = style.upperCase,
                        onCheckedChange = { updateStyle(style.copy(upperCase = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TvColors.FocusElectricBlue,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Remove Bloat Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Clean Subtitle Bloat", style = TvTypography.Body, color = Color.White)
                        Text("Strip author credits and HTML artifact tags using SubtitleSanitizer", style = TvTypography.Caption, color = TvColors.TextSecondary)
                    }
                    Switch(
                        checked = style.removeBloat,
                        onCheckedChange = { updateStyle(style.copy(removeBloat = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TvColors.FocusElectricBlue,
                        ),
                    )
                }
            }
        }
    }
}
