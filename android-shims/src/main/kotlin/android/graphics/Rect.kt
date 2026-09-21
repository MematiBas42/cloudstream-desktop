package android.graphics

data class Rect(
    @JvmField var left: Int = 0,
    @JvmField var top: Int = 0,
    @JvmField var right: Int = 0,
    @JvmField var bottom: Int = 0
) {
    fun width(): Int = right - left
    fun height(): Int = bottom - top

    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    fun isEmpty(): Boolean = left >= right || top >= bottom
}
