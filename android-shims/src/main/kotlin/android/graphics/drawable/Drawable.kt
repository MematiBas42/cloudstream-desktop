package android.graphics.drawable

import android.graphics.Canvas
import android.graphics.Rect

abstract class Drawable {
    open var intrinsicWidth: Int = 0
    open var intrinsicHeight: Int = 0
    open var bounds: Rect = Rect(0, 0, 0, 0)

    open fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        bounds = Rect(left, top, right, bottom)
    }

    open fun draw(canvas: Canvas) {}
}
