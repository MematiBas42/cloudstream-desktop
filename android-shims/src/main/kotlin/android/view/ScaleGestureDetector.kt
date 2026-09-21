package android.view

import android.content.Context

open class ScaleGestureDetector(
    val context: Context?,
    val listener: OnScaleGestureListener
) {
    var scaleFactor: Float = 1.0f
    var focusX: Float = 0f
    var focusY: Float = 0f

    interface OnScaleGestureListener {
        fun onScale(detector: ScaleGestureDetector): Boolean
        fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        fun onScaleEnd(detector: ScaleGestureDetector) {}
    }

    open class SimpleOnScaleGestureListener : OnScaleGestureListener {
        override fun onScale(detector: ScaleGestureDetector): Boolean = false
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        override fun onScaleEnd(detector: ScaleGestureDetector) {}
    }

    open fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            focusX = (event.getX(0) + event.getX(1)) / 2f
            focusY = (event.getY(0) + event.getY(1)) / 2f
        } else {
            focusX = event.x
            focusY = event.y
        }
        return listener.onScale(this)
    }
}
