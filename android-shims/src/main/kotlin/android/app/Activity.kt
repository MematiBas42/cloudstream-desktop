package android.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View

open class Activity : Context() {
    open var currentFocus: View? = null
    open var intent: Intent? = null
    private val _window by lazy { android.view.Window() }
    open val window: android.view.Window get() = _window

    @Suppress("UNCHECKED_CAST")
    open fun <T : View> findViewById(id: Int): T? = null

    open fun runOnUiThread(action: Runnable?) {
        if (action == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    open fun isFinishing(): Boolean = false

    open fun isDestroyed(): Boolean = false

    open fun finish() {}

    open fun finishAndRemoveTask() {
        finish()
    }

    open fun recreate() {}

    open fun moveTaskToBack(nonRoot: Boolean): Boolean = true

    open fun dispatchKeyEvent(event: KeyEvent): Boolean = false

    open fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = false

    open fun onUserLeaveHint() {}

    open fun onNewIntent(intent: Intent) {}

    open fun onCreate(savedInstanceState: Bundle?) {}

    open fun onResume() {}

    open fun onPause() {}

    open fun onDestroy() {}

    open fun setContentView(layoutResID: Int) {}

    open fun setContentView(view: View?) {}

    open fun setPictureInPictureParams(params: PictureInPictureParams) {}

    open fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean = true

    open fun enterPictureInPictureMode(): Boolean = true
}
