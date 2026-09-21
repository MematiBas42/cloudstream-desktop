package com.lagradost.cloudstream3.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 10-Foot Distance Typography Scale.
 * Specifically tuned for legibility on living room TV screens from 3+ meters away:
 * - Headline: 36sp Bold
 * - Section: 22sp SemiBold
 * - Card: 16sp Medium
 * - Caption: 14sp Regular
 */
object TvTypography {
    val Headline: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp,
        color = TvColors.TextPrimary
    )

    val Section: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        color = TvColors.TextPrimary
    )

    val Card: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
        color = TvColors.TextPrimary
    )

    val Caption: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.25.sp,
        color = TvColors.TextSecondary
    )

    val Body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        color = TvColors.TextPrimary
    )

    val Button: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
        color = TvColors.TextPrimary
    )

    val Badge: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = TvColors.TextPrimary
    )

    val MaterialTypography = Typography(
        displayLarge = Headline.copy(fontSize = 44.sp, lineHeight = 52.sp),
        displayMedium = Headline,
        displaySmall = Headline.copy(fontSize = 30.sp, lineHeight = 38.sp),

        headlineLarge = Headline,
        headlineMedium = Section.copy(fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = Section,

        titleLarge = Section,
        titleMedium = Card,
        titleSmall = Card.copy(fontSize = 15.sp, lineHeight = 20.sp),

        bodyLarge = Body.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = Body,
        bodySmall = Caption,

        labelLarge = Button,
        labelMedium = Caption,
        labelSmall = Badge
    )
}

/**
 * Global Compose Theme for CloudStream TV Desktop.
 * Applies the high-contrast AMOLED Material 3 color scheme and 10-foot typography scale.
 */
@Composable
fun TvTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TvColors.DarkColorScheme,
        typography = TvTypography.MaterialTypography
    ) {
        Surface(
            color = TvColors.AmoledBackground,
            contentColor = TvColors.TextPrimary,
            content = content
        )
    }
}
