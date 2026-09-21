package com.lagradost.cloudstream3.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusState
import com.lagradost.cloudstream3.desktop.ui.focus.RegisterTvFocusItem
import com.lagradost.cloudstream3.desktop.ui.focus.TvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.trackTvFocusBounds
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.common.account.AccountManagerDesktop

/**
 * Spatial row indices reserved for navigation rail elements in [TvFocusManager] (Column 0).
 */
object TvRailFocusRows {
    const val HOME = 0
    const val SEARCH = 1
    const val LIBRARY = 2
    const val EXTENSIONS = 3
    const val SETTINGS = 4
    const val PROFILE = 5
}

/**
 * Standard TV Navigation destinations mapped to [Screen] hierarchy.
 */
enum class TvRailDestination(
    val title: String,
    val icon: ImageVector,
    val rowIndex: Int
) {
    HOME("Home", Icons.Default.Home, TvRailFocusRows.HOME),
    SEARCH("Search", Icons.Default.Search, TvRailFocusRows.SEARCH),
    LIBRARY("Library", Icons.Default.List, TvRailFocusRows.LIBRARY),
    EXTENSIONS("Extensions", Icons.Default.Extension, TvRailFocusRows.EXTENSIONS),
    SETTINGS("Settings", Icons.Default.Settings, TvRailFocusRows.SETTINGS);

    fun toScreen(): Screen = when (this) {
        HOME -> Screen.Home
        SEARCH -> Screen.Search()
        LIBRARY -> Screen.Library
        EXTENSIONS -> Screen.Extensions
        SETTINGS -> Screen.Settings
    }

    companion object {
        fun fromScreen(screen: Screen): TvRailDestination? = when (screen) {
            is Screen.Home -> HOME
            is Screen.Search -> SEARCH
            is Screen.Library -> LIBRARY
            is Screen.Extensions -> EXTENSIONS
            is Screen.Settings -> SETTINGS
            else -> null
        }
    }
}

/**
 * Material You Laptop Icon for Desktop Edition Branding.
 * Replaces legacy TV branding with a sleek desktop/laptop identity icon.
 */
val IconLaptop: ImageVector by lazy {
    ImageVector.Builder(
        name = "Laptop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.EvenOdd
        ) {
            moveTo(20f, 18f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(6f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(10f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineTo(0f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(24f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-4f)
            close()
            moveTo(4f, 6f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(10f)
            horizontalLineTo(4f)
            verticalLineTo(6f)
            close()
        }
    }.build()
}

/**
 * 10-Foot Vertical Navigation Rail for Compose Multiplatform on Linux Desktop.
 *
 * Upstream Reference: NavigationRailView (activity_main_tv.xml:27-58, MainActivity.kt:1778-1828).
 *
 * Dimensions:
 * - Collapsed width: 76dp
 * - Expanded width on focus / mouse hover: 200dp
 * - Smooth animated expansion: animateDpAsState(tween(200, easing = FastOutSlowInEasing))
 *
 * Jitter-Free & Compact Geometry:
 * - Fixed start margins anchor icons symmetrically when collapsed (centroid at x = 30dp).
 * - Icons never shift or bounce along the X-axis during expand or collapse transitions.
 * - Labels and laptop icon are tightly compressed towards the logo without empty gaps.
 *
 * Branding:
 * - Top Header: CloudStream Logo + "CloudStream" + Material You Laptop Icon.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvNavigationRail(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: () -> Unit = {},
    onExitRailToContent: () -> Unit = {},
    collapsedWidth: Dp = 76.dp,
    expandedWidth: Dp = 200.dp
) {
    val focusManager = LocalTvFocusManager.current
    var focusedChildCount by remember { mutableStateOf(0) }
    var isRailHovered by remember { mutableStateOf(false) }
    val isRailFocused = focusedChildCount > 0
    val isRailExpanded = isRailFocused || isRailHovered

    // Smooth width animation: 76dp -> 200dp (compact desktop profile)
    val railWidth by animateDpAsState(
        targetValue = if (isRailExpanded) expandedWidth else collapsedWidth,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "TvRailWidthAnimation"
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(railWidth)
            .background(TvColors.SurfaceElevated)
            .border(
                width = 1.dp,
                color = TvColors.BorderSubtle.copy(alpha = 0.5f)
            )
            .onPointerEvent(PointerEventType.Enter) { isRailHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isRailHovered = false }
            .padding(vertical = 20.dp)
            .zIndex(100f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header: Logo + "CloudStream" branding + Material You Laptop Icon
        TvRailHeader(
            isExpanded = isRailExpanded,
            onHeaderClick = { onNavigate(Screen.Home) }
        )

        // Center Destinations
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TvRailDestination.entries.forEach { destination ->
                val isSelected = when (destination) {
                    TvRailDestination.HOME -> currentScreen is Screen.Home
                    TvRailDestination.SEARCH -> currentScreen is Screen.Search
                    TvRailDestination.LIBRARY -> currentScreen is Screen.Library
                    TvRailDestination.EXTENSIONS -> currentScreen is Screen.Extensions
                    TvRailDestination.SETTINGS -> currentScreen is Screen.Settings
                }

                TvRailItemView(
                    destination = destination,
                    isSelected = isSelected,
                    isExpanded = isRailExpanded,
                    focusManager = focusManager,
                    onSelect = { onNavigate(destination.toScreen()) },
                    onFocusChanged = { focused ->
                        if (focused) focusedChildCount++ else focusedChildCount = maxOf(0, focusedChildCount - 1)
                    },
                    onExitRight = onExitRailToContent
                )
            }
        }

        // Profile Footer Card
        TvRailProfileFooter(
            isExpanded = isRailExpanded,
            focusManager = focusManager,
            onClick = onProfileClick,
            onFocusChanged = { focused ->
                if (focused) focusedChildCount++ else focusedChildCount = maxOf(0, focusedChildCount - 1)
            },
            onExitRight = onExitRailToContent
        )
    }
}

/**
 * Top branding header in the navigation rail.
 * Shows CloudStream logo icon, tightly revealing "CloudStream" and Material You Laptop icon when expanded.
 */
@Composable
private fun TvRailHeader(
    isExpanded: Boolean,
    onHeaderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onHeaderClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Symmetrically centered when collapsed (item_w=60dp, logo=32dp -> left=14dp)
            Spacer(modifier = Modifier.width(14.dp))

            Image(
                painter = painterResource("logo_ui.png"),
                contentDescription = "CloudStream Logo",
                modifier = Modifier.size(32.dp)
            )

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(durationMillis = 140, delayMillis = 30)) + expandHorizontally(
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start
                ),
                exit = fadeOut(tween(durationMillis = 70)) + shrinkHorizontally(
                    animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "CloudStream",
                        style = TvTypography.Section.copy(
                            color = TvColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            letterSpacing = 0.2.sp
                        ),
                        maxLines = 1
                    )
                    Icon(
                        imageVector = IconLaptop,
                        contentDescription = "Desktop Edition",
                        tint = TvColors.FocusElectricBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual navigation item inside [TvNavigationRail].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvRailItemView(
    destination: TvRailDestination,
    isSelected: Boolean,
    isExpanded: Boolean,
    focusManager: TvFocusManager,
    onSelect: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onExitRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val focusState = LocalTvFocusState.current

    RegisterTvFocusItem(
        row = destination.rowIndex,
        col = 0,
        focusManager = focusManager,
        requester = requester
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused || isHovered -> Color.White
            isSelected -> TvColors.FocusElectricBlue
            else -> TvColors.TextSecondary
        },
        animationSpec = tween(150),
        label = "RailItemContentColor"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .focusRequester(requester)
            .trackTvFocusBounds(isFocused = isFocused, focusState = focusState, cornerRadius = 12f)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                onFocusChanged(state.isFocused)
                if (state.isFocused) {
                    focusManager.notifyFocused(destination.rowIndex, 0)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> {
                            onExitRight()
                            false
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> {
                            onSelect()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            )
            .background(
                when {
                    isFocused -> TvColors.FocusElectricBlue.copy(alpha = 0.25f)
                    isHovered -> TvColors.SurfaceHigh.copy(alpha = 0.6f)
                    isSelected -> TvColors.SurfaceHigh
                    else -> Color.Transparent
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Symmetrically centered when collapsed (item_w=60dp, icon=24dp -> left=18dp)
            Spacer(modifier = Modifier.width(18.dp))

            Icon(
                imageVector = destination.icon,
                contentDescription = destination.title,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(durationMillis = 140, delayMillis = 30)) + expandHorizontally(
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start
                ),
                exit = fadeOut(tween(durationMillis = 70)) + shrinkHorizontally(
                    animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start
                )
            ) {
                Text(
                    text = destination.title,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected || isFocused || isHovered) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp, end = 8.dp)
                )
            }
        }
    }
}

/**
 * Active profile footer card in the navigation rail.
 * Traced 1:1 to upstream rail_footer.xml (36dp circle profile picture, user label).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvRailProfileFooter(
    isExpanded: Boolean,
    focusManager: TvFocusManager,
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onExitRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val requester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val focusState = LocalTvFocusState.current

    val activeAccount by AccountManagerDesktop.activeAccountFlow.collectAsState()

    RegisterTvFocusItem(
        row = TvRailFocusRows.PROFILE,
        col = 0,
        focusManager = focusManager,
        requester = requester
    )

    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(54.dp)
            .clip(shape)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .focusRequester(requester)
            .trackTvFocusBounds(isFocused = isFocused, focusState = focusState, cornerRadius = 12f)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                onFocusChanged(state.isFocused)
                if (state.isFocused) {
                    focusManager.notifyFocused(TvRailFocusRows.PROFILE, 0)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> {
                            onExitRight()
                            false
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .background(
                when {
                    isFocused -> TvColors.FocusElectricBlue.copy(alpha = 0.25f)
                    isHovered -> TvColors.SurfaceHigh.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Symmetrically centered when collapsed (item_w=60dp, avatar=34dp -> left=13dp)
            Spacer(modifier = Modifier.width(13.dp))

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(TvColors.SurfaceHigh)
                    .border(
                        width = if (isFocused || isHovered) 2.dp else 1.dp,
                        color = if (isFocused || isHovered) TvColors.FocusElectricBlue else TvColors.BorderSubtle,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "User Avatar",
                    tint = if (isFocused || isHovered) Color.White else TvColors.FocusElectricBlue,
                    modifier = Modifier.size(32.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(durationMillis = 140, delayMillis = 30)) + expandHorizontally(
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start
                ),
                exit = fadeOut(tween(durationMillis = 70)) + shrinkHorizontally(
                    animationSpec = tween(durationMillis = 130, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start
                )
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(start = 10.dp, end = 8.dp)
                ) {
                    Text(
                        text = activeAccount.name.ifBlank { "Profile" },
                        color = if (isFocused || isHovered) Color.White else TvColors.TextPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Switch User",
                        color = TvColors.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
