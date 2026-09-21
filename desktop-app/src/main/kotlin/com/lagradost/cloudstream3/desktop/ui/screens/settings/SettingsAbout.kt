package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@Composable
fun SettingsAbout() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "CloudStream TV (Linux Desktop)",
                    style = TvTypography.Headline,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "High-performance 10-foot Leanback television interface powered by Compose Multiplatform and hardware-accelerated MPV.",
                    style = TvTypography.Body,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(20.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { openUrl("https://recloudstream.github.io/csdocs/") },
                        modifier = Modifier.focusable(),
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                    ) {
                        Text("CloudStream Docs", style = TvTypography.Button)
                    }

                    OutlinedButton(
                        onClick = { openUrl("https://github.com/recloudstream/cloudstream") },
                        modifier = Modifier.focusable(),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusPill),
                    ) {
                        Text("Official Android Repo", style = TvTypography.Button)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "TMDb Attribution",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "This product uses the TMDb API but is not endorsed or certified by TMDb.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Legal Disclaimer",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "CloudStream acts strictly as an extensible media framework and media player interface. It does not host, index, or distribute media streams. All content is fetched directly via user-installed third-party plugins.",
                    style = TvTypography.Caption,
                    color = TvColors.TextMuted,
                )
            }
        }
    }
}

private fun openUrl(url: String) {
    try {
        val uri = java.net.URI(url)
        val desktop = java.awt.Desktop.getDesktop()
        desktop.browse(uri)
    } catch (e: Exception) {
        com.lagradost.common.logging.AppLogger.e("SettingsAbout", "Error opening URL $url: ${e.message}")
    }
}
