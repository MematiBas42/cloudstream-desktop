package com.lagradost.cloudstream3.desktop.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dimensions, spacing, and scaling constants for the 10-Foot TV Leanback experience.
 */
object TvDimensions {
    // Poster Aspect Ratio (2:3 standard theatrical poster ratio)
    const val PosterAspectRatio = 2f / 3f

    // Standard TV Poster Card Dimensions
    val CardWidth: Dp = 160.dp
    val CardHeight: Dp = 240.dp // 160.dp / (2/3)

    // Wide / Landscape Episode Card Dimensions (16:9)
    val EpisodeCardWidth: Dp = 260.dp
    val EpisodeCardHeight: Dp = 146.dp

    // Interactive Focus Scaling & Elevation
    const val FocusScale: Float = 1.08f
    const val UnfocusedScale: Float = 1.00f

    // Focus Border Glow & Thickness
    val BorderGlow: Dp = 2.5.dp
    val BorderNormal: Dp = 1.0.dp

    // Corner Radii
    val CornerRadiusSmall: Dp = 6.dp
    val CornerRadiusCard: Dp = 10.dp
    val CornerRadiusLarge: Dp = 16.dp
    val CornerRadiusPill: Dp = 24.dp

    // Depth & Elevation
    val ElevationNormal: Dp = 0.dp
    val ElevationFocused: Dp = 14.dp

    // Screen Gutters (safe zones for overscan and viewing from distance)
    val ScreenHorizontalPadding: Dp = 48.dp
    val ScreenVerticalPadding: Dp = 36.dp

    // Layout Grids & Spacing
    val RowSpacing: Dp = 28.dp
    val ItemSpacing: Dp = 16.dp
    val SectionHeaderBottomSpacing: Dp = 12.dp

    // Top Bar & Hero Dimensions
    val TopBarHeight: Dp = 64.dp
    val HeroBannerHeight: Dp = 380.dp
    val DrawerWidth: Dp = 360.dp

    // Micro-element Spacing
    val SpacingXSmall: Dp = 4.dp
    val SpacingSmall: Dp = 8.dp
    val SpacingMedium: Dp = 16.dp
    val SpacingLarge: Dp = 24.dp
}
