package android.content.res

class Configuration {
    var orientation: Int = ORIENTATION_LANDSCAPE

    companion object {
        const val ORIENTATION_UNDEFINED = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
