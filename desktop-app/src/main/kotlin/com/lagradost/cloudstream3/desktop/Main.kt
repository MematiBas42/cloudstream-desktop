package com.lagradost.cloudstream3.desktop

import android.content.DesktopContextProvider
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lagradost.cloudstream3.desktop.observability.DiagnosticsDialog
import com.lagradost.cloudstream3.desktop.ui.components.TvNavigationRail
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.LocalTvFocusState
import com.lagradost.cloudstream3.desktop.ui.focus.TvFocusOverlay
import com.lagradost.cloudstream3.desktop.ui.focus.TvNavAction
import com.lagradost.cloudstream3.desktop.ui.focus.interceptTvKeyNavigation
import com.lagradost.cloudstream3.desktop.ui.focus.rememberTvFocusManager
import com.lagradost.cloudstream3.desktop.ui.focus.rememberTvFocusState
import com.lagradost.cloudstream3.desktop.ui.navigation.LocalVideoPlayer
import com.lagradost.cloudstream3.desktop.ui.navigation.NavController
import com.lagradost.cloudstream3.desktop.ui.navigation.Screen
import com.lagradost.cloudstream3.desktop.ui.navigation.VideoLaunchData
import com.lagradost.cloudstream3.desktop.ui.player.EmbeddedPlayerView
import com.lagradost.cloudstream3.desktop.ui.screens.ComposeCategoryGridScreen
import com.lagradost.cloudstream3.desktop.ui.screens.details.ComposeDetailsScreen
import com.lagradost.cloudstream3.desktop.ui.screens.extensions.ComposeExtensionScreen
import com.lagradost.cloudstream3.desktop.ui.screens.home.ComposeHomeScreen
import com.lagradost.cloudstream3.desktop.ui.screens.downloads.ComposeDownloadsScreen
import com.lagradost.cloudstream3.desktop.ui.screens.library.ComposeLibraryScreen
import com.lagradost.cloudstream3.desktop.ui.screens.search.ComposeSearchScreen
import com.lagradost.cloudstream3.desktop.ui.screens.settings.ComposeSettingsScreen
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvTheme

fun main(args: Array<String> = emptyArray()) {
    // Enable Swing/AWT interop blending so Compose UI renders over embedded AWT Canvas
    System.setProperty("compose.interop.blending", "true")
    // Execute deterministic bootstrap sequence before launching Compose Desktop
    AppBootstrap.init(args)

    application {
        val windowState = rememberWindowState(
            width = 1280.dp,
            height = 720.dp,
            position = WindowPosition.Aligned(Alignment.Center)
        )

        var isFullscreen by remember { mutableStateOf(false) }
        var showGlobalDiagnostics by remember { mutableStateOf(false) }
        var currentVideoLaunchData by remember { mutableStateOf<VideoLaunchData?>(null) }
        val navController = remember { NavController() }
        DisposableEffect(navController) {
            onDispose {
                navController.destroy()
            }
        }

        DisposableEffect(Unit) {
            val playerListener: (com.lagradost.cloudstream3.ui.player.BasicLink) -> Unit = { basicLink ->
                val launchData = VideoLaunchData(
                    links = listOf(
                        com.lagradost.cloudstream3.utils.ExtractorLink(
                            source = "Direct",
                            name = basicLink.name ?: "Stream",
                            url = basicLink.url,
                            referer = "",
                            quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value,
                            type = com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO,
                        )
                    ),
                    initialIndex = 0,
                    title = basicLink.name ?: "Stream",
                    subtitles = emptyList(),
                    startPositionMs = 0L,
                    history = com.lagradost.common.storage.WatchHistory(
                        url = basicLink.url,
                        parentId = basicLink.url,
                        episodeId = basicLink.url,
                        title = basicLink.name ?: "Stream",
                        episode = 1,
                        season = 1,
                    )
                )
                if (java.awt.EventQueue.isDispatchThread()) {
                    currentVideoLaunchData = launchData
                } else {
                    java.awt.EventQueue.invokeLater {
                        currentVideoLaunchData = launchData
                    }
                }
            }
            com.lagradost.cloudstream3.MainActivity.playerIntentEvent += playerListener
            onDispose {
                com.lagradost.cloudstream3.MainActivity.playerIntentEvent -= playerListener
            }
        }

        val icon = painterResource("logo_ui.png")

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "CloudStream TV",
            icon = icon
        ) {
            DisposableEffect(window) {
                DesktopContextProvider.currentWindow = window
                // Global OS/AWT-level key dispatcher: guarantees Escape always closes embedded video player regardless of surface focus
                val globalKeyDispatcher = java.awt.KeyEventDispatcher { e ->
                    if (e.id == java.awt.event.KeyEvent.KEY_PRESSED && e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
                        if (currentVideoLaunchData != null) {
                            currentVideoLaunchData?.onClosed?.invoke()
                            currentVideoLaunchData = null
                            return@KeyEventDispatcher true
                        }
                    }
                    false
                }
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(globalKeyDispatcher)
                onDispose {
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(globalKeyDispatcher)
                    DesktopContextProvider.currentWindow = null
                }
            }

            // Fullscreen state handler (F11 / Alt+Enter)
            windowState.placement = if (isFullscreen) {
                WindowPlacement.Fullscreen
            } else {
                WindowPlacement.Floating
            }

            if (showGlobalDiagnostics) {
                DiagnosticsDialog(onDismissRequest = { showGlobalDiagnostics = false })
            }

            val tvFocusManager = rememberTvFocusManager()
            val tvFocusState = rememberTvFocusState()
            val composeFocusManager = LocalFocusManager.current

            // Reset focus outline and spatial grid on screen transitions
            LaunchedEffect(navController.currentScreen) {
                tvFocusState.clearFocus()
                tvFocusManager.clear()
            }

            // Window-level key event interceptor: handles Fullscreen toggle (F11 / Alt+Enter),
            // Global Diagnostics toggle (F12),
            // Back navigation (ESC / Gamepad Back), and 2D spatial navigation (Arrows / D-Pad / Gamepad)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.F12) {
                                showGlobalDiagnostics = !showGlobalDiagnostics
                                return@onPreviewKeyEvent true
                            }
                            if (event.key == Key.F11 || (event.isAltPressed && event.key == Key.Enter)) {
                                isFullscreen = !isFullscreen
                                return@onPreviewKeyEvent true
                            }
                            if (event.key == Key.Escape) {
                                if (currentVideoLaunchData != null) {
                                    // Window-level immediate player escape: guarantees closing even if focused on Skiko surface or stalled
                                    currentVideoLaunchData?.onClosed?.invoke()
                                    currentVideoLaunchData = null
                                    return@onPreviewKeyEvent true
                                }
                                if (com.lagradost.cloudstream3.MainActivity.navigateBack()) {
                                    return@onPreviewKeyEvent true
                                }
                                if (navController.canGoBack()) {
                                    navController.goBack()
                                    return@onPreviewKeyEvent true
                                }
                            }
                        }
                        false
                    }
                    .interceptTvKeyNavigation { action ->
                        when (action) {
                            TvNavAction.UP, TvNavAction.DOWN, TvNavAction.LEFT, TvNavAction.RIGHT -> {
                                val handledByGrid = tvFocusManager.move(action)
                                if (handledByGrid) {
                                    true
                                } else {
                                    val focusDirection = when (action) {
                                        TvNavAction.UP -> FocusDirection.Up
                                        TvNavAction.DOWN -> FocusDirection.Down
                                        TvNavAction.LEFT -> FocusDirection.Left
                                        TvNavAction.RIGHT -> FocusDirection.Right
                                        else -> null
                                    }
                                    if (focusDirection != null) {
                                        composeFocusManager.moveFocus(focusDirection)
                                    } else false
                                }
                            }
                            TvNavAction.BACK -> {
                                if (currentVideoLaunchData != null) {
                                    false
                                } else if (navController.canGoBack()) {
                                    navController.goBack()
                                    true
                                } else {
                                    tvFocusManager.snapToFallbackAnchor()
                                }
                            }
                            TvNavAction.SELECT -> false
                        }
                    }
            ) {
                CompositionLocalProvider(
                    LocalTvFocusManager provides tvFocusManager,
                    LocalTvFocusState provides tvFocusState,
                    LocalVideoPlayer provides { data -> currentVideoLaunchData = data }
                ) {
                    TvTheme {
                        TvScaffold(
                            navController = navController,
                            isFullscreen = isFullscreen,
                            onToggleFullscreen = { isFullscreen = !isFullscreen }
                        )

                        // If video playback is requested, render single-window embedded player view atop UI
                        currentVideoLaunchData?.let { launchData ->
                            EmbeddedPlayerView(
                                launchData = launchData,
                                onClose = {
                                    currentVideoLaunchData?.onClosed?.invoke()
                                    currentVideoLaunchData = null
                                },
                                isFullscreen = isFullscreen,
                                onToggleFullscreen = { isFullscreen = !isFullscreen },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }


    }
}

/**
 * 10-Foot Leanback TV Application Scaffold.
 * Features a left-docked collapsible [TvNavigationRail], content viewport with crossfade
 * screen transitions, and an animated Skiko-accelerated traveling focus ring overlay [TvFocusOverlay].
 */
@Composable
fun TvScaffold(
    navController: NavController,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalTvFocusManager.current
    val focusState = LocalTvFocusState.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.AmoledBackground)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left-docked TV Navigation Rail (90dp collapsed width, 220dp expanded width on focus)
            TvNavigationRail(
                currentScreen = navController.currentScreen,
                onNavigate = { screen -> navController.navigate(screen) },
                onProfileClick = {
                    navController.navigate(Screen.Settings)
                },
                onExitRailToContent = {
                    focusManager.move(TvNavAction.RIGHT)
                }
            )

            // Screen Content Viewport with Crossfade Transition
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Crossfade(
                    targetState = navController.currentScreen,
                    modifier = Modifier.fillMaxSize()
                ) { currentScreen ->
                    when (currentScreen) {
                        is Screen.Home -> ComposeHomeScreen(navController)
                        is Screen.Search -> ComposeSearchScreen(navController, currentScreen.initialQuery, currentScreen.providers)
                        is Screen.Library -> ComposeLibraryScreen(navController)
                        is Screen.Downloads -> ComposeDownloadsScreen(navController)
                        is Screen.Extensions -> ComposeExtensionScreen(navController)
                        is Screen.Settings -> ComposeSettingsScreen(navController)
                        is Screen.Details -> ComposeDetailsScreen(
                            navController = navController,
                            provider = currentScreen.provider,
                            url = currentScreen.url,
                            preloadedName = currentScreen.preloadedName,
                            preloadedPoster = currentScreen.preloadedPoster,
                            preloadedBg = currentScreen.preloadedBg
                        )
                        is Screen.CategoryGrid -> ComposeCategoryGridScreen(
                            navController = navController,
                            provider = currentScreen.provider,
                            title = currentScreen.title,
                            items = currentScreen.items
                        )
                        else -> ComposeHomeScreen(navController)
                    }
                }
            }
        }

        // Global Traveling Focus Ring Overlay (renders atop active cards with 200ms lerp)
        TvFocusOverlay(focusState = focusState)
    }
}
