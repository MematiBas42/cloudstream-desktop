package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.Score
import java.util.Locale

/**
 * Chromatic color palette for TV rating badges matching upstream CloudStream Android TV:
 * - Red (#eb2f2f): rating < 5.0 (Poor)
 * - Yellow (#eda009): 5.0 <= rating < 8.0 (Average/Good)
 * - Green (#3bb33b): rating >= 8.0 (Acclaimed/Masterpiece)
 *
 * Sub-threshold values (< 0.1) are filtered/suppressed (yield null color).
 */
object TvRatingColors {
    val Red = Color(0xFFEB2F2F)
    val Yellow = Color(0xFFEDA009)
    val Green = Color(0xFF3BB33B)

    fun forScore(score: Double?): Color? {
        if (score == null || score < 0.1) return null
        return when {
            score < 5.0 -> Red
            score < 8.0 -> Yellow
            else -> Green
        }
    }

    fun forScore(score: Score?): Color? {
        if (score == null) return null
        val d = score.toDouble()
        return forScore(d)
    }
}

/**
 * Formats score according to upstream Score.toStringNull(0.1, 10, 1, false)
 * Suppresses values below 0.1 by returning null.
 */
fun formatTvRating(score: Double?): String? {
    if (score == null || score < 0.1) return null
    return Score.from10(score)?.toStringNull(minScore = 0.1, maxScore = 10, decimals = 1, removeTrailingZeros = false)
        ?: String.format(Locale.US, "%.1f", score)
}

fun formatTvRating(score: Score?): String? {
    return score?.toStringNull(minScore = 0.1, maxScore = 10, decimals = 1, removeTrailingZeros = false)
}

/**
 * Strict 3-tier chromatic IMDb/TMDb score badge encapsulated in a 4dp rounded pill
 * with high-contrast white typography.
 *
 * Automatically filters sub-threshold values (< 0.1).
 */
@Composable
fun TvRatingBadge(
    score: Double?,
    modifier: Modifier = Modifier,
    scoreText: String? = null,
    label: String = "IMDb",
) {
    val color = TvRatingColors.forScore(score)
    val formatted = scoreText ?: formatTvRating(score)
    if (color == null || formatted.isNullOrBlank()) return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "$label : $formatted",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

/**
 * Direct overload accepting precomputed scoreText and chromatic scoreColor.
 */
@Composable
fun TvRatingBadge(
    scoreText: String?,
    scoreColor: Color?,
    modifier: Modifier = Modifier,
    label: String = "IMDb",
) {
    if (scoreText.isNullOrBlank() || scoreColor == null) return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(scoreColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = "$label : $scoreText",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

/**
 * 1:1 Upstream compatibility alias for ChromaticScoreBadge.
 */
@Composable
fun ChromaticScoreBadge(
    scoreText: String?,
    scoreColor: Color?,
    modifier: Modifier = Modifier,
    label: String = "IMDb",
) {
    TvRatingBadge(
        scoreText = scoreText,
        scoreColor = scoreColor,
        modifier = modifier,
        label = label,
    )
}
