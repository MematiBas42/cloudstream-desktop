package android.graphics

import java.awt.Graphics2D

open class Canvas(val bitmap: Bitmap? = null) {
    open val width: Int get() = bitmap?.width ?: 0
    open val height: Int get() = bitmap?.height ?: 0

    val graphics: Graphics2D? = bitmap?.image?.createGraphics()

    open fun drawBitmap(bitmap: Bitmap, left: Float, top: Float) {
        bitmap.image?.let {
            graphics?.drawImage(it, left.toInt(), top.toInt(), null)
        }
    }
}
