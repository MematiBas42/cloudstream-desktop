package android.graphics.drawable

import android.graphics.Bitmap
import android.graphics.Canvas

open class BitmapDrawable(
    open val bitmap: Bitmap
) : Drawable() {
    override var intrinsicWidth: Int
        get() = bitmap.width
        set(_) {}

    override var intrinsicHeight: Int
        get() = bitmap.height
        set(_) {}

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, bounds.left.toFloat(), bounds.top.toFloat())
    }
}
