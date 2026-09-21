package android.util

open class DisplayMetrics {
    var widthPixels: Int = 1920
    var heightPixels: Int = 1080
    var density: Float = 1.0f
    var densityDpi: Int = 160
    var scaledDensity: Float = 1.0f
    var xdpi: Float = 160.0f
    var ydpi: Float = 160.0f

    companion object {
        const val DENSITY_LOW: Int = 120
        const val DENSITY_MEDIUM: Int = 160
        const val DENSITY_HIGH: Int = 240
        const val DENSITY_XHIGH: Int = 320
        const val DENSITY_XXHIGH: Int = 480
        const val DENSITY_XXXHIGH: Int = 640
        const val DENSITY_DEFAULT: Int = DENSITY_MEDIUM
    }
}
