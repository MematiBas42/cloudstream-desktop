package com.lagradost.cloudstream3.desktop.ui.screens.extensions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@Composable
fun ExtensionCard(
    name: String,
    internalName: String,
    version: Int,
    repoName: String,
    language: String?,
    tvTypes: List<String>?,
    iconUrl: String?,
    isInstalled: Boolean,
    installStatus: String,
    isInstalling: Boolean,
    onInstallClick: () -> Unit,
    showCheckbox: Boolean = false,
    isChecked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    showSettings: Boolean = false,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDimensions.CornerRadiusCard)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 100.dp)
            .scale(if (isFocused) 1.02f else 1.0f)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) TvColors.SurfaceElevated else TvColors.SurfaceCard,
        ),
        border = if (isFocused) {
            BorderStroke(TvDimensions.BorderGlow, TvColors.FocusElectricBlue)
        } else {
            BorderStroke(1.dp, TvColors.BorderSubtle)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCheckbox && onCheckedChange != null) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.focusable(),
                    colors = CheckboxDefaults.colors(
                        checkedColor = TvColors.FocusElectricBlue,
                        uncheckedColor = TvColors.BorderSubtle,
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (!iconUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(16.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(TvDimensions.CornerRadiusSmall))
                        .background(TvColors.SurfaceContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = TvColors.FocusElectricBlue,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = TvTypography.Card.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isFocused) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                        ),
                        maxLines = 1,
                    )
                    if (!language.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            color = TvColors.SurfaceElevated,
                        ) {
                            Text(
                                text = language.uppercase(),
                                style = TvTypography.Badge.copy(color = TvColors.TextSecondary),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "v$version • $repoName",
                    style = TvTypography.Caption,
                    color = TvColors.TextMuted,
                    maxLines = 1,
                )

                if (!tvTypes.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tvTypes.take(3).forEach { type ->
                            Surface(
                                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                color = TvColors.SurfaceElevated,
                            ) {
                                Text(
                                    text = type,
                                    style = TvTypography.Badge.copy(color = TvColors.TextMuted),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSettings) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.focusable(),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TvColors.TextSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Button(
                    onClick = onInstallClick,
                    enabled = !isInstalling,
                    modifier = Modifier.focusable(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInstalled) TvColors.SurfaceElevated else TvColors.FocusElectricBlue,
                        contentColor = Color.White,
                        disabledContainerColor = TvColors.SurfaceElevated,
                        disabledContentColor = TvColors.TextMuted,
                    ),
                    shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    if (isInstalling) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = TvColors.FocusElectricBlue,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Loading...",
                                style = TvTypography.Button.copy(fontSize = 13.sp, color = TvColors.TextSecondary),
                            )
                        }
                    } else {
                        Text(
                            text = installStatus,
                            style = TvTypography.Button.copy(fontSize = 13.sp),
                        )
                    }
                }
            }
        }
    }
}
