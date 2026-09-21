package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors

data class DesktopThemeColors(
    val Accent: Color = TvColors.FocusElectricBlue,
    val AccentSoft: Color = TvColors.FocusElectricBlue.copy(alpha = 0.22f),
    val Background: Color = TvColors.AmoledBackground,
    val SurfaceCard: Color = TvColors.SurfaceCard,
    val SurfaceElevated: Color = TvColors.SurfaceElevated,
    val TextPrimary: Color = TvColors.TextPrimary,
    val TextMuted: Color = TvColors.TextMuted,
    val Divider: Color = TvColors.BorderSubtle,
)

fun darkDesktopColors(accent: Color = TvColors.FocusElectricBlue, amoled: Boolean = true) = DesktopThemeColors(
    Accent = accent,
    AccentSoft = accent.copy(alpha = 0.22f),
    Background = if (amoled) TvColors.AmoledBackground else Color(0xFF0C0C16),
    SurfaceCard = TvColors.SurfaceCard,
    SurfaceElevated = TvColors.SurfaceElevated,
    TextPrimary = TvColors.TextPrimary,
    TextMuted = TvColors.TextMuted,
    Divider = TvColors.BorderSubtle,
)

fun lightDesktopColors(accent: Color = TvColors.FocusElectricBlue) = DesktopThemeColors(
    Accent = accent,
    AccentSoft = accent.copy(alpha = 0.15f),
    Background = Color(0xFFF8FAFC),
    SurfaceCard = Color(0xFFFFFFFF),
    SurfaceElevated = Color(0xFFF1F5F9),
    TextPrimary = Color(0xFF0F172A),
    TextMuted = Color(0xFF64748B),
    Divider = Color(0xFFE2E8F0),
)

val LocalDesktopTheme = staticCompositionLocalOf<DesktopThemeColors> { DesktopThemeColors() }

object DesktopUi {
    val Accent: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.Accent
    val AccentSoft: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.AccentSoft
    val Background: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.Background
    val SurfaceCard: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.SurfaceCard
    val SurfaceElevated: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.SurfaceElevated
    val TextPrimary: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.TextPrimary
    val TextMuted: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.TextMuted
    val Divider: Color
        @Composable @ReadOnlyComposable
        get() = LocalDesktopTheme.current.Divider
}
