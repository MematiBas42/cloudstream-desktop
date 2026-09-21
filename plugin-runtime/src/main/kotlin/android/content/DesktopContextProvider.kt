package android.content

import android.app.Activity
import java.awt.Window

/**
 * Desktop Context provider backing plugins with an Activity-capable Context instance
 * and tracking active desktop Window references.
 */
object DesktopContextProvider {
    private class DesktopActivity : Activity()

    val context: Context = DesktopActivity()

    /**
     * Active desktop AWT/Swing Window instance, or null in headless environments.
     */
    @Volatile
    var currentWindow: Window? = null
}
