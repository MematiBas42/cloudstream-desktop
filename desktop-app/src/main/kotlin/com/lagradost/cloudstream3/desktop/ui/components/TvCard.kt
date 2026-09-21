package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusState
import com.lagradost.cloudstream3.desktop.ui.focus.RegisterTvFocusItem
import com.lagradost.cloudstream3.desktop.ui.focus.TvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.trackTvFocusBounds
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography

/**
 * Reusable 10-Foot TV & Desktop Poster Card.
 *
 * Ergonomics:
 * - Scale factor on focus (1.08f) and hover (1.05f) animated with spring physics
 * - High-visibility glowing electric blue border on active hover/focus
 * - Automatic horizontal text marquee for titles exceeding card width
 * - Integrated 2D spatial focus engine with [TvFocusManager]
 * - Circular watch progress and play overlay matching upstream CloudStream Android UI
 * - Pointer hover detection for desktop/laptop mouse ergonomics
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvCard(
    title: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    badgeColor: Color = TvColors.FocusEmerald,
    progress: Float? = null,
    onPlayClick: (() -> Unit)? = null,
    cardWidth: Dp = TvDimensions.CardWidth,
    cardHeight: Dp = TvDimensions.CardHeight,
    row: Int? = null,
    col: Int? = null,
    focusManager: TvFocusManager? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onClick: () -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val isActive = isFocused || isHovered
    val focusState = LocalTvFocusState.current
    val coroutineScope = rememberCoroutineScope()

    // Register with 2D spatial focus engine if coordinates and manager are supplied
    if (row != null && col != null && focusManager != null) {
        RegisterTvFocusItem(
            row = row,
            col = col,
            focusManager = focusManager,
            requester = focusRequester
        )
    }

    // Spring physics animation for smooth, tactile focus scaling
    val animatedScale by animateFloatAsState(
        targetValue = when {
            isFocused -> TvDimensions.FocusScale
            isHovered -> 1.05f
            else -> TvDimensions.UnfocusedScale
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "TvCardScaleAnimation"
    )

    val shape = RoundedCornerShape(TvDimensions.CornerRadiusCard)
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .width(cardWidth)
            .scale(animatedScale)
            .zIndex(if (isActive) 10f else 1f)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused && row != null && col != null && focusManager != null) {
                    focusManager.notifyFocused(row, col)
                }
            }
            .trackTvFocusBounds(
                isFocused = isFocused,
                focusState = focusState,
                cornerRadius = TvDimensions.CornerRadiusCard.value,
                coroutineScope = coroutineScope
            )
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Spacebar || event.key == Key.MediaPlay) && onPlayClick != null) {
                    onPlayClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        // Poster Image Container
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .shadow(
                    elevation = when {
                        isFocused -> TvDimensions.ElevationFocused
                        isHovered -> 10.dp
                        else -> TvDimensions.ElevationNormal
                    },
                    shape = shape,
                    spotColor = if (isActive) TvColors.FocusElectricBlue else TvColors.CardShadow
                ),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = TvColors.SurfaceCard
            ),
            border = when {
                isFocused -> BorderStroke(3.dp, Color.White)
                isHovered -> BorderStroke(2.dp, TvColors.FocusElectricBlue)
                else -> BorderStroke(1.dp, TvColors.BorderSubtle)
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Async Poster Image via Coil 3
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shape)
                    )
                } else {
                    // Fallback visual ground
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(TvColors.SurfaceHigh, TvColors.SurfaceCard)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title.take(2).uppercase(),
                            style = TvTypography.Headline.copy(color = TvColors.TextMuted)
                        )
                    }
                }

                // Bottom vignette gradient for contrast
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xCC050508))
                            )
                        )
                )

                // Circular Watch Progress & Play Button Overlay (Matching Upstream CloudStream Android)
                if (progress != null) {
                    val progressRatio = progress.coerceIn(0f, 1f)
                    val playInteractionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Color(0xB3000000)) // Upstream playIconBackground (#B3000000)
                            .then(
                                if (onPlayClick != null) {
                                    Modifier.clickable(
                                        interactionSource = playInteractionSource,
                                        indication = null,
                                        onClick = onPlayClick
                                    )
                                } else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                            val strokeWidth = 3.dp.toPx()
                            // Circular background track (remaining/unwatched ring)
                            drawArc(
                                color = Color.White.copy(alpha = 0.25f),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = strokeWidth)
                            )
                            // Watched progress arc (filling clockwise from 12 o'clock)
                            if (progressRatio > 0f) {
                                drawArc(
                                    color = if (isActive) TvColors.FocusElectricBlue else Color.White,
                                    startAngle = -90f,
                                    sweepAngle = 360f * progressRatio,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume",
                            tint = if (isActive) TvColors.FocusElectricBlue else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else if (onPlayClick != null && isActive) {
                    // Hover play icon for cards with quick play support
                    val playInteractionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xB3000000))
                            .clickable(
                                interactionSource = playInteractionSource,
                                indication = null,
                                onClick = onPlayClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = TvColors.FocusElectricBlue,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // Optional Corner Badge (Quality, Rating, Status)
                if (!badge.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = badgeColor.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = badge,
                            style = TvTypography.Badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

            }
        }

        Spacer(modifier = Modifier.height(TvDimensions.SpacingSmall))

        // Title with automatic Marquee when focused/hovered or long
        Text(
            text = title,
            style = TvTypography.Card.copy(
                color = if (isActive) TvColors.TextPrimary else TvColors.TextSecondary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isActive) {
                        Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 600,
                            velocity = 40.dp
                        )
                    } else {
                        Modifier
                    }
                )
        )

        // Subtitle / Year / Type
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = TvTypography.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
