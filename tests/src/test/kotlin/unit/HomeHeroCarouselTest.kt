package unit

import androidx.compose.ui.graphics.Color
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.desktop.ui.components.TvRatingColors
import com.lagradost.cloudstream3.desktop.ui.components.formatTvRating
import com.lagradost.cloudstream3.desktop.ui.screens.home.CarouselCenteringDebouncer
import com.lagradost.cloudstream3.desktop.ui.screens.home.HeroMeta
import com.lagradost.cloudstream3.desktop.ui.screens.home.LogoWaterfallState
import com.lagradost.cloudstream3.desktop.ui.screens.home.canNavigateLeftInCarousel
import com.lagradost.cloudstream3.desktop.ui.screens.home.cleanHeroTitle
import com.lagradost.cloudstream3.desktop.ui.screens.home.getHeroRealIndex
import com.lagradost.cloudstream3.desktop.ui.screens.home.resolveLogoWaterfall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Unit test suite for Domain 02: Home Screen Hero Carousel & Watermark Logo Waterfalls.
 *
 * CRITICAL QUALITY MANDATE:
 * - Anti-mock testing using real domain models and pure mathematical specifications.
 * - Tests exact chromatic color thresholds (<5.0 Red #eb2f2f, 5.0-7.9 Yellow #eda009, >=8.0 Green #3bb33b).
 * - Tests sub-threshold value filtering (<0.1).
 * - Tests title cleaning regex edge cases (brackets, release tags, anime series suffixes).
 * - Tests logo waterfall fallback resolution state machine.
 * - Tests pager index circular math and TV D-pad boundary conditions.
 */
class HomeHeroCarouselTest {

    // =========================================================================
    // 1. Strict 3-Tier Chromatic Score & Sub-Threshold Filtering Tests
    // =========================================================================

    @ParameterizedTest(name = "Rating {0} maps to chromatic color {1}")
    @CsvSource(
        "0.1,    0xFFEB2F2F", // Red lower boundary (exact 0.1 threshold)
        "1.0,    0xFFEB2F2F", // Red low rating
        "2.5,    0xFFEB2F2F", // Red intermediate
        "4.0,    0xFFEB2F2F", // Red intermediate
        "4.9,    0xFFEB2F2F", // Red near upper bound
        "4.99,   0xFFEB2F2F", // Red precision upper bound
        "5.0,    0xFFEDA009", // Yellow lower boundary (exact 5.0 threshold)
        "5.01,   0xFFEDA009", // Yellow just above lower bound
        "6.5,    0xFFEDA009", // Yellow intermediate
        "7.5,    0xFFEDA009", // Yellow intermediate
        "7.9,    0xFFEDA009", // Yellow near upper bound
        "7.99,   0xFFEDA009", // Yellow precision upper bound
        "8.0,    0xFF3BB33B", // Green lower boundary (exact 8.0 threshold)
        "8.01,   0xFF3BB33B", // Green just above lower bound
        "8.5,    0xFF3BB33B", // Green intermediate
        "9.9,    0xFF3BB33B", // Green high rating
        "10.0,   0xFF3BB33B", // Green maximum rating
    )
    fun `test chromatic color mapping conforms exactly to upstream 3-tier thresholds`(
        score: Double,
        expectedHex: String,
    ) {
        val expectedColor = Color(expectedHex.removePrefix("0x").toLong(16))

        // 1. Verify TvRatingColors.forScore(Double)
        val colorFromHelper = TvRatingColors.forScore(score)
        assertEquals(expectedColor, colorFromHelper, "TvRatingColors.forScore($score) must match $expectedHex")

        // 2. Verify TvRatingColors.forScore(Score)
        val scoreObj = Score.from10(score)
        val colorFromScoreObj = TvRatingColors.forScore(scoreObj)
        assertEquals(expectedColor, colorFromScoreObj, "TvRatingColors.forScore(Score.from10($score)) must match $expectedHex")

        // 3. Verify HeroMeta.chromaticRatingColor
        val meta = HeroMeta(
            backdropUrl = null,
            logoUrl = null,
            posterHeaders = null,
            tags = emptyList(),
            plot = null,
            score = score,
            scoreText = score.toString(),
            year = 2024,
            duration = 120,
            actors = null,
            title = "Threshold Test",
        )
        assertEquals(expectedColor, meta.chromaticRatingColor, "HeroMeta.chromaticRatingColor for $score must match $expectedHex")
    }

    @ParameterizedTest(name = "Sub-threshold rating {0} must be suppressed (null color)")
    @CsvSource(
        "0.0",
        "0.01",
        "0.05",
        "0.09",
        "0.099",
        "-0.5",
        "-1.0",
    )
    fun `test score below 0_1 is filtered out and suppresses chromatic color`(score: Double) {
        assertNull(
            TvRatingColors.forScore(score),
            "TvRatingColors.forScore($score) must return null for sub-threshold ratings (< 0.1)",
        )

        val meta = HeroMeta(
            backdropUrl = null,
            logoUrl = null,
            posterHeaders = null,
            tags = emptyList(),
            plot = null,
            score = score,
            scoreText = null,
            year = 2024,
            duration = null,
            actors = null,
            title = "Sub-threshold Test",
        )
        assertNull(
            meta.chromaticRatingColor,
            "HeroMeta.chromaticRatingColor must be null for score $score (< 0.1)",
        )
    }

    @Test
    fun `test null score returns null color and suppresses badge`() {
        assertNull(TvRatingColors.forScore(null as Double?))
        assertNull(TvRatingColors.forScore(null as Score?))

        val meta = HeroMeta(
            backdropUrl = null,
            logoUrl = null,
            posterHeaders = null,
            tags = emptyList(),
            plot = null,
            score = null,
            scoreText = null,
            year = 2024,
            duration = null,
            actors = null,
            title = "Unrated",
        )
        assertNull(meta.chromaticRatingColor)
    }

    @Test
    fun `test formatTvRating suppresses sub-threshold and formats valid ratings`() {
        // Sub-threshold suppression (< 0.1)
        assertNull(formatTvRating(0.0))
        assertNull(formatTvRating(0.05))
        assertNull(formatTvRating(0.09))
        assertNull(formatTvRating(null as Double?))
        assertNull(formatTvRating(null as Score?))

        // Valid ratings formatting (1 decimal place)
        assertEquals("8.5", formatTvRating(8.5))
        assertEquals("8.0", formatTvRating(8.0))
        assertEquals("5.0", formatTvRating(5.0))
        assertEquals("4.9", formatTvRating(4.9))
        assertEquals("0.1", formatTvRating(0.1))
        assertEquals("10.0", formatTvRating(10.0))

        // Score object formatting
        val scoreObj = Score.from10(7.8)
        assertEquals("7.8", formatTvRating(scoreObj))
    }

    // =========================================================================
    // 2. Title Cleaning Regex Edge Cases Tests
    // =========================================================================

    @ParameterizedTest(name = "Clean title of ''{0}'' -> ''{1}''")
    @CsvSource(
        // Parentheses with release year
        "'Inception (2010)',                                  'Inception'",
        // Brackets with resolution/quality
        "'Breaking Bad [1080p]',                              'Breaking Bad'",
        // Curly braces with dub metadata
        "'Attack on Titan {Dub}',                             'Attack on Titan'",
        // Vertical pipe separator with 4K/HDR metadata
        "'Dune | 4K UHD HDR',                                 'Dune'",
        // Release group tags (WEB-DL)
        "'Stranger Things WEB-DL 1080p x264',                 'Stranger Things'",
        // Release group tags (BluRay)
        "'Oppenheimer BluRay Remux',                          'Oppenheimer'",
        // Release group tags (CAM)
        "'Spider-Man CAM',                                    'Spider-Man'",
        // Release group tags (HDTC)
        "'Avatar: The Way of Water HDTC 720p',                'Avatar: The Way of Water'",
        // Release group tags (HDRip)
        "'Top Gun: Maverick HDRip',                           'Top Gun: Maverick'",
        // Release group tags (DVDSCR)
        "'The Batman DVDSCR',                                 'The Batman'",
        // Anime series suffix (case variants)
        "'Naruto Anime Series',                               'Naruto'",
        "'BLEACH anime series',                               'BLEACH'",
        "'ONE PIECE ANIME SERIES',                            'ONE PIECE'",
        // Complex multi-tag composite
        "'The Last of Us (Season 1) [WEB-DL 2160p] | HDR',    'The Last of Us'",
        // Case-insensitivity on release tags
        "'Gladiator bluray',                                  'Gladiator'",
        "'Interstellar web-dl 4k',                            'Interstellar'",
    )
    fun `test cleanHeroTitle strips release tags and formatting brackets`(raw: String, expected: String) {
        assertEquals(expected, cleanHeroTitle(raw))
    }

    @ParameterizedTest(name = "Preserve raw title when cleaning results in blank: ''{0}''")
    @CsvSource(
        "'(2024)'",
        "'[1080p]'",
        "'{Special}'",
        "'| 4K'",
        "'   '",
        "''",
    )
    fun `test cleanHeroTitle preserves raw title when cleaning results in blank`(raw: String) {
        assertEquals(raw, cleanHeroTitle(raw))
    }

    // =========================================================================
    // 3. Logo Waterfall Fallback Resolution Tests
    // =========================================================================

    @Test
    fun `test resolveLogoWaterfall returns ShowLogo when valid URL and no error`() {
        val pngState = resolveLogoWaterfall("https://image.tmdb.org/t/p/original/logo.png", "Movie Title", isImageError = false)
        assertTrue(pngState is LogoWaterfallState.ShowLogo)
        assertEquals("https://image.tmdb.org/t/p/original/logo.png", (pngState as LogoWaterfallState.ShowLogo).url)

        val webpState = resolveLogoWaterfall("https://image.tmdb.org/t/p/original/logo.webp", "Movie Title", isImageError = false)
        assertTrue(webpState is LogoWaterfallState.ShowLogo)
        assertEquals("https://image.tmdb.org/t/p/original/logo.webp", (webpState as LogoWaterfallState.ShowLogo).url)

        val svgState = resolveLogoWaterfall("https://assets.fanart.tv/fanart/logo.svg", "Movie Title", isImageError = false)
        assertTrue(svgState is LogoWaterfallState.ShowLogo)
        assertEquals("https://assets.fanart.tv/fanart/logo.svg", (svgState as LogoWaterfallState.ShowLogo).url)
    }

    @Test
    fun `test resolveLogoWaterfall returns ShowTextTitle when logoUrl is null or blank`() {
        val nullState = resolveLogoWaterfall(null, "The Matrix", isImageError = false)
        assertTrue(nullState is LogoWaterfallState.ShowTextTitle)
        assertEquals("The Matrix", (nullState as LogoWaterfallState.ShowTextTitle).title)

        val emptyState = resolveLogoWaterfall("", "The Matrix", isImageError = false)
        assertTrue(emptyState is LogoWaterfallState.ShowTextTitle)
        assertEquals("The Matrix", (emptyState as LogoWaterfallState.ShowTextTitle).title)

        val blankState = resolveLogoWaterfall("   ", "The Matrix", isImageError = false)
        assertTrue(blankState is LogoWaterfallState.ShowTextTitle)
        assertEquals("The Matrix", (blankState as LogoWaterfallState.ShowTextTitle).title)
    }

    @Test
    fun `test resolveLogoWaterfall falls back to ShowTextTitle when image load errors`() {
        val errorState = resolveLogoWaterfall(
            logoUrl = "https://image.tmdb.org/t/p/original/non_existent_logo.png",
            title = "Fallback Title",
            isImageError = true,
        )
        assertTrue(errorState is LogoWaterfallState.ShowTextTitle)
        assertEquals("Fallback Title", (errorState as LogoWaterfallState.ShowTextTitle).title)
    }

    // =========================================================================
    // 4. Pager Index Math & Centering Debouncer Tests
    // =========================================================================

    @ParameterizedTest(name = "Page {0} with itemCount {1} -> realIndex {2}")
    @CsvSource(
        "0,      10, 0",
        "1,      10, 1",
        "9,      10, 9",
        "10,     10, 0",
        "15,     10, 5",
        "5000,   10, 0",
        "5003,   10, 3",
        "5009,   10, 9",
        "999999, 7,  0",  // 999999 is exactly divisible by 7 (7 * 142857)
        "1000003, 7, 4",
        "-1,     10, 9",  // Negative page modulo check
        "-10,    10, 0",
    )
    fun `test getHeroRealIndex calculates correct circular modulo`(page: Int, itemCount: Int, expectedIndex: Int) {
        assertEquals(expectedIndex, getHeroRealIndex(page, itemCount))
    }

    @Test
    fun `test getHeroRealIndex returns 0 for non-positive itemCount`() {
        assertEquals(0, getHeroRealIndex(5, 0))
        assertEquals(0, getHeroRealIndex(5, -1))
    }

    @Test
    fun `test canNavigateLeftInCarousel boundary condition`() {
        // At index 0, D-pad Left must exit to navigation rail / sidebar
        assertFalse(canNavigateLeftInCarousel(0), "Index 0 must not navigate left within carousel")

        // At index > 0, D-pad Left can retreat within carousel
        assertTrue(canNavigateLeftInCarousel(1))
        assertTrue(canNavigateLeftInCarousel(5))
        assertTrue(canNavigateLeftInCarousel(9))
    }

    @Test
    fun `test CarouselCenteringDebouncer suppresses rapid interactions and centers after dwell`() {
        var centerCallCount = 0
        val debouncer = CarouselCenteringDebouncer(debounceTimeoutMs = 500L) {
            centerCallCount++
        }

        // 1. Initial dwell at t = 1000ms (elapsed = 1000 - 0 = 1000 > 500ms)
        val firstResult = debouncer.onFocused(currentTimeMs = 1000L)
        assertTrue(firstResult, "Initial dwell should trigger centering")
        assertEquals(1, centerCallCount)

        // 2. Rapid browsing at t = 1100ms (elapsed = 100ms <= 500ms) -> suppressed
        val secondResult = debouncer.onFocused(currentTimeMs = 1100L)
        assertFalse(secondResult, "Rapid focus change (< 500ms) must be suppressed")
        assertEquals(1, centerCallCount)

        // 3. Rapid browsing at t = 1350ms (elapsed = 250ms <= 500ms) -> suppressed
        val thirdResult = debouncer.onFocused(currentTimeMs = 1350L)
        assertFalse(thirdResult, "Rapid focus change (< 500ms) must be suppressed")
        assertEquals(1, centerCallCount)

        // 4. User rests at t = 2000ms (elapsed = 650ms > 500ms) -> centering triggered
        val fourthResult = debouncer.onFocused(currentTimeMs = 2000L)
        assertTrue(fourthResult, "Dwell (> 500ms) must trigger centering")
        assertEquals(2, centerCallCount)
    }
}
