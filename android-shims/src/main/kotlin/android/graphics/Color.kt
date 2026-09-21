package android.graphics

object Color {
    const val BLACK: Int = -16777216
    const val DKGRAY: Int = -12303292
    const val GRAY: Int = -7829368
    const val LTGRAY: Int = -3355444
    const val WHITE: Int = -1
    const val RED: Int = -65536
    const val GREEN: Int = -16711936
    const val BLUE: Int = -16776961
    const val YELLOW: Int = -256
    const val CYAN: Int = -16711681
    const val MAGENTA: Int = -65281
    const val TRANSPARENT: Int = 0

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun rgb(red: Int, green: Int, blue: Int): Int {
        return -0x1000000 or (red shl 16) or (green shl 8) or blue
    }

    fun red(color: Int): Int = (color shr 16) and 0xFF
    fun green(color: Int): Int = (color shr 8) and 0xFF
    fun blue(color: Int): Int = color and 0xFF
    fun alpha(color: Int): Int = (color ushr 24)
}
