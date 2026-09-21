package com.lagradost.cloudstream3.desktop.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 10-Foot Leanback TV Color System.
 *
 * Implements an AMOLED true-black baseline (0xFF050508) to maximize contrast
 * on OLED and HDR televisions at a 10-foot viewing distance.
 * Features high-visibility electric blue (0xFF3B82F6) and emerald green (0xFF10B981)
 * focus indicators to guide spatial navigation.
 */
object TvColors {
    // AMOLED Black Baseline
    val AmoledBackground = Color(0xFF050508)
    val SurfaceCard = Color(0xFF12121A)
    val SurfaceElevated = Color(0xFF1A1A26)
    val SurfaceContainer = Color(0xFF161622)
    val SurfaceHigh = Color(0xFF222232)

    // High-Visibility Focus Glow Accents
    val FocusElectricBlue = Color(0xFF3B82F6)
    val FocusEmerald = Color(0xFF10B981)

    // Secondary & Functional Colors
    val AccentAmber = Color(0xFFF59E0B)
    val ErrorRed = Color(0xFFEF4444)
    val RatingGold = Color(0xFFFFD700)
    val ProgressWatched = Color(0xFFE50914) // Streaming Red

    // Text & Content Hierarchies (High Contrast on Dark Backgrounds)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)

    // Border & Divider Strokes
    val BorderSubtle = Color(0xFF1E293B)
    val BorderGlowActive = FocusElectricBlue
    val BorderGlowSecondary = FocusEmerald

    // Overlays & Scrims
    val ScrimOverlay = Color(0xCC050508)
    val CardShadow = Color(0x80000000)

    val DarkColorScheme = darkColorScheme(
        primary = FocusElectricBlue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1E3A8A),
        onPrimaryContainer = Color(0xFFDBEAFE),

        secondary = FocusEmerald,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF064E3B),
        onSecondaryContainer = Color(0xFFD1FAE5),

        tertiary = AccentAmber,
        onTertiary = Color.Black,

        background = AmoledBackground,
        onBackground = TextPrimary,

        surface = SurfaceCard,
        onSurface = TextPrimary,

        surfaceVariant = SurfaceElevated,
        onSurfaceVariant = TextSecondary,

        surfaceContainer = SurfaceContainer,
        surfaceContainerHigh = SurfaceHigh,

        outline = BorderSubtle,
        outlineVariant = BorderSubtle.copy(alpha = 0.6f),

        error = ErrorRed,
        onError = Color.White
    )
}
