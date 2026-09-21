package com.lagradost.player.embedded

import com.lagradost.common.logging.AppLogger
import java.awt.Component
import sun.misc.Unsafe

/**
 * Resolves the native Window ID (Linux X11 XID or Windows Win32 HWND) from an AWT [Component] (e.g. [java.awt.Canvas])
 * running under OpenJDK on Linux X11/XWayland or Windows.
 *
 * This provides zero-overhead, direct resolution without JNA or external C shims,
 * reading the underlying `sun.awt.X11.XBaseWindow.window` native handle field on Linux,
 * or `sun.awt.windows.WComponentPeer.hwnd` on Windows.
 */
object NativeWindowHandleResolver {

    private val unsafe: Unsafe? by lazy {
        runCatching {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null) as Unsafe
        }.onFailure {
            AppLogger.w("NativeWindowHandleResolver", "Failed to access sun.misc.Unsafe", it)
        }.getOrNull()
    }

    /**
     * Resolves the native window ID (Linux X11 XID or Windows Win32 HWND) from an AWT [Component] (e.g. [java.awt.Canvas]).
     * Returns a positive Long (> 0) if the component is displayable and has a native peer,
     * or 0L if unresolvable.
     */
    fun getWindowHandle(component: Component): Long {
        val u = unsafe ?: return 0L
        return runCatching {
            val peerField = Component::class.java.getDeclaredField("peer")
            val peerOffset = u.objectFieldOffset(peerField)
            val peer = u.getObject(component, peerOffset) ?: return 0L
            getHandleFromPeer(peer)
        }.onFailure {
            AppLogger.w("NativeWindowHandleResolver", "Failed to extract native window handle from component peer", it)
        }.getOrDefault(0L)
    }

    /**
     * Resolves the native window ID or HWND from an AWT component peer.
     * Supports Linux X11 (`window` field) and Windows Win32 (`hwnd` field),
     * as well as method getters (`getHWnd`, `getWindow`).
     */
    fun getHandleFromPeer(peer: Any?): Long {
        if (peer == null) return 0L
        val u = unsafe ?: return 0L
        return runCatching {
            var currentClass: Class<*>? = peer.javaClass
            while (currentClass != null && currentClass != Any::class.java) {
                val windowField = currentClass.declaredFields.firstOrNull {
                    it.name == "window" || it.name == "hwnd"
                }
                if (windowField != null) {
                    val windowOffset = u.objectFieldOffset(windowField)
                    val wid = u.getLong(peer, windowOffset)
                    if (wid > 0L) return wid
                }
                currentClass = currentClass.superclass
            }

            // Fallback for custom or wrapped peers providing getter methods
            val method = runCatching {
                peer.javaClass.getMethod("getHWnd").also { it.isAccessible = true }
            }.getOrNull() ?: runCatching {
                peer.javaClass.getMethod("getWindow").also { it.isAccessible = true }
            }.getOrNull()

            if (method != null) {
                val handle = (method.invoke(peer) as? Number)?.toLong() ?: 0L
                if (handle > 0L) return handle
            }

            0L
        }.onFailure {
            AppLogger.w("NativeWindowHandleResolver", "Failed to extract native handle from peer", it)
        }.getOrDefault(0L)
    }
}
