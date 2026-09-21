package com.lagradost.cloudstream3.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import com.lagradost.common.logging.AppLogger
import com.lagradost.player.api.MpvPlayer
import com.lagradost.player.embedded.NativeWindowHandleResolver
import java.awt.Canvas
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener

/**
 * High-performance Compose Desktop / Skiko embedded media surface.
 * Hosts an in-process AWT [Canvas] via [SwingPanel] and automatically binds
 * its native X11 window handle (wid) to [MpvPlayer] for zero-window, single-surface playback.
 */
@Deprecated(
    message = "Replaced by pure Compose Multiplatform MpvMediampPlayerSurface (org.openani.mediamp:mediamp-all-desktop)",
    level = DeprecationLevel.WARNING
)
@Composable
fun MpvEmbeddedSurface(
    player: MpvPlayer,
    modifier: Modifier = Modifier,
    onSurfaceCreated: ((Long) -> Unit)? = null,
    onUserInteraction: (() -> Unit)? = null,
) {
    var boundWid by remember { mutableStateOf(0L) }

    val canvas = remember {
        Canvas().apply {
            background = java.awt.Color.BLACK
            isFocusable = false // Prevent AWT Canvas from hijacking Compose keyboard focus
        }
    }

    DisposableEffect(canvas) {
        val mouseAdapter = object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                onUserInteraction?.invoke()
            }
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                onUserInteraction?.invoke()
            }
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                onUserInteraction?.invoke()
            }
        }
        canvas.addMouseListener(mouseAdapter)
        canvas.addMouseMotionListener(mouseAdapter)

        val hierarchyListener = HierarchyListener { event ->
            if ((event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L && canvas.isDisplayable) {
                val wid = NativeWindowHandleResolver.getWindowHandle(canvas)
                if (wid > 0L && wid != boundWid) {
                    boundWid = wid
                    AppLogger.i("MpvEmbeddedSurface", "Resolved native window handle (wid=$wid) via HierarchyListener")
                    player.attachWindow(wid)
                    onSurfaceCreated?.invoke(wid)
                }
            }
        }
        canvas.addHierarchyListener(hierarchyListener)

        // If already displayable, resolve immediately
        if (canvas.isDisplayable) {
            val wid = NativeWindowHandleResolver.getWindowHandle(canvas)
            if (wid > 0L) {
                boundWid = wid
                AppLogger.i("MpvEmbeddedSurface", "Resolved native window handle (wid=$wid) immediately")
                player.attachWindow(wid)
                onSurfaceCreated?.invoke(wid)
            }
        }

        onDispose {
            canvas.removeMouseListener(mouseAdapter)
            canvas.removeMouseMotionListener(mouseAdapter)
            canvas.removeHierarchyListener(hierarchyListener)
            player.detachWindow()
        }
    }

    SwingPanel(
        background = Color.Black,
        factory = { canvas },
        modifier = modifier,
        update = { c ->
            if (c.isDisplayable) {
                val wid = NativeWindowHandleResolver.getWindowHandle(c)
                if (wid > 0L && wid != boundWid) {
                    boundWid = wid
                    AppLogger.i("MpvEmbeddedSurface", "Resolved native window handle (wid=$wid) via SwingPanel update")
                    player.attachWindow(wid)
                    onSurfaceCreated?.invoke(wid)
                }
            }
        }
    )
}
