package android.view

open class MotionEvent(
    val action: Int = ACTION_DOWN,
    val x: Float = 0f,
    val y: Float = 0f,
    val rawX: Float = x,
    val rawY: Float = y,
    val pointerCount: Int = 1,
    private val pointerCoords: List<Pair<Float, Float>> = listOf(Pair(x, y))
) {
    val actionMasked: Int get() = action and ACTION_MASK

    fun getX(pointerIndex: Int): Float = pointerCoords.getOrNull(pointerIndex)?.first ?: x
    fun getY(pointerIndex: Int): Float = pointerCoords.getOrNull(pointerIndex)?.second ?: y

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_OUTSIDE = 4
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val ACTION_MASK = 0xff
    }
}
