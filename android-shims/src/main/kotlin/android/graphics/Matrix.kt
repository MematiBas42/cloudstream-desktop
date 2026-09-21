package android.graphics

open class Matrix {
    private val values = FloatArray(9) { 0f }

    init {
        reset()
    }

    constructor()

    constructor(src: Matrix) {
        set(src)
    }

    open fun reset() {
        values[MSCALE_X] = 1f
        values[MSKEW_X] = 0f
        values[MTRANS_X] = 0f
        values[MSKEW_Y] = 0f
        values[MSCALE_Y] = 1f
        values[MTRANS_Y] = 0f
        values[MPERSP_0] = 0f
        values[MPERSP_1] = 0f
        values[MPERSP_2] = 1f
    }

    open fun set(src: Matrix) {
        System.arraycopy(src.values, 0, values, 0, 9)
    }

    open fun setScale(sx: Float, sy: Float) {
        reset()
        values[MSCALE_X] = sx
        values[MSCALE_Y] = sy
    }

    open fun setScale(sx: Float, sy: Float, px: Float, py: Float) {
        reset()
        postTranslate(-px, -py)
        postScale(sx, sy)
        postTranslate(px, py)
    }

    open fun setTranslate(dx: Float, dy: Float) {
        reset()
        values[MTRANS_X] = dx
        values[MTRANS_Y] = dy
    }

    open fun postTranslate(dx: Float, dy: Float): Boolean {
        values[MTRANS_X] += dx
        values[MTRANS_Y] += dy
        return true
    }

    open fun postScale(sx: Float, sy: Float): Boolean {
        values[MSCALE_X] *= sx
        values[MSKEW_X] *= sx
        values[MTRANS_X] *= sx

        values[MSKEW_Y] *= sy
        values[MSCALE_Y] *= sy
        values[MTRANS_Y] *= sy
        return true
    }

    open fun postScale(sx: Float, sy: Float, px: Float, py: Float): Boolean {
        postTranslate(-px, -py)
        postScale(sx, sy)
        postTranslate(px, py)
        return true
    }

    open fun mapPoints(dst: FloatArray, src: FloatArray) {
        val count = minOf(dst.size, src.size) / 2
        for (i in 0 until count) {
            val x = src[i * 2]
            val y = src[i * 2 + 1]
            dst[i * 2] = values[MSCALE_X] * x + values[MSKEW_X] * y + values[MTRANS_X]
            dst[i * 2 + 1] = values[MSKEW_Y] * x + values[MSCALE_Y] * y + values[MTRANS_Y]
        }
    }

    open fun mapPoints(pts: FloatArray) {
        mapPoints(pts, pts)
    }

    open fun getValues(values: FloatArray) {
        System.arraycopy(this.values, 0, values, 0, 9)
    }

    open fun setValues(values: FloatArray) {
        System.arraycopy(values, 0, this.values, 0, 9)
    }

    companion object {
        const val MSCALE_X = 0
        const val MSKEW_X = 1
        const val MTRANS_X = 2
        const val MSKEW_Y = 3
        const val MSCALE_Y = 4
        const val MTRANS_Y = 5
        const val MPERSP_0 = 6
        const val MPERSP_1 = 7
        const val MPERSP_2 = 8
    }
}
