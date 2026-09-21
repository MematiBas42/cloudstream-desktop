package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.network.DohDnsResolver
import com.lagradost.common.network.DohDnsResolver.DohProvider
import com.lagradost.common.storage.DesktopDataStore

@Composable
fun SettingsNetwork() {
    var expanded by remember { mutableStateOf(false) }

    // Read canonical PREF_DNS key with fallback to legacy doh_provider
    var selectedProvider by remember {
        val savedValue = DesktopDataStore.getKey<Int>(PREF_DNS)
            ?: DesktopDataStore.getKey<Int>("doh_provider")
            ?: DohDnsResolver.activeProvider.prefValue
        mutableStateOf(DohProvider.fromPrefValue(savedValue))
    }
    var statusMessage by remember { mutableStateOf("") }

    val dohOptions = remember {
        DohProvider.entries.map { provider ->
            provider.displayName to provider
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
        border = BorderStroke(1.dp, TvColors.BorderSubtle),
        shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "DNS over HTTPS (DoH)",
                style = TvTypography.Section,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Bypasses ISP-level DNS poisoning, blocking, and domain hijacking by encrypting DNS resolutions.",
                style = TvTypography.Caption,
                color = TvColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DoH Resolver: ",
                    style = TvTypography.Body,
                    color = TvColors.TextPrimary,
                )
                Spacer(modifier = Modifier.width(12.dp))

                Box {
                    Button(
                        onClick = { expanded = true },
                        modifier = Modifier.focusable(),
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.SurfaceElevated),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                    ) {
                        Text(selectedProvider.displayName, style = TvTypography.Button)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }

                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(TvColors.SurfaceElevated),
                    ) {
                        dohOptions.forEach { (label, provider) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        style = TvTypography.Body.copy(
                                            color = if (provider == selectedProvider) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                                        ),
                                    )
                                },
                                onClick = {
                                    selectedProvider = provider
                                    DesktopDataStore.setKey(PREF_DNS, provider.prefValue)
                                    // Remove legacy divergent key to maintain zero-drift DataStore
                                    DesktopDataStore.removeKey("doh_provider")
                                    DohDnsResolver.setProvider(provider)

                                    // Real application to shared OkHttpClient instances (Zero-Shim compliance)
                                    runCatching {
                                        app.baseClient = app.baseClient.newBuilder()
                                            .dns(DohDnsResolver.INSTANCE)
                                            .build()
                                        insecureApp.baseClient = insecureApp.baseClient.newBuilder()
                                            .dns(DohDnsResolver.INSTANCE)
                                            .build()
                                    }

                                    statusMessage = "DoH configuration updated successfully: ${provider.displayName}"
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }

            if (statusMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = statusMessage,
                    color = TvColors.FocusEmerald,
                    style = TvTypography.Caption,
                )
            }
        }
    }
}
