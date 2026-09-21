package com.lagradost.cloudstream3.desktop.ui.screens.links

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelector(
    availableQualities: List<String>,
    selectedQuality: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Quality", style = TvTypography.Caption, color = TvColors.TextMuted)
        Spacer(modifier = Modifier.width(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedQuality == null,
                    onClick = { onSelect(null) },
                    modifier = Modifier.focusable(),
                    label = { Text("All", style = TvTypography.Badge) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TvColors.FocusElectricBlue.copy(alpha = 0.25f),
                        selectedLabelColor = TvColors.FocusElectricBlue,
                        containerColor = TvColors.SurfaceCard,
                        labelColor = TvColors.TextSecondary,
                    ),
                )
            }
            items(availableQualities) { q ->
                FilterChip(
                    selected = selectedQuality == q,
                    onClick = { onSelect(q) },
                    modifier = Modifier.focusable(),
                    label = { Text(q, style = TvTypography.Badge) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TvColors.FocusElectricBlue.copy(alpha = 0.25f),
                        selectedLabelColor = TvColors.FocusElectricBlue,
                        containerColor = TvColors.SurfaceCard,
                        labelColor = TvColors.TextSecondary,
                    ),
                )
            }
        }
    }
}
