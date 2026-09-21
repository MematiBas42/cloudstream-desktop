package android.view

open class WindowInsets {
    object Type {
        fun systemBars(): Int = 1
    }

    class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)

    fun getInsetsIgnoringVisibility(typeMask: Int): Insets = Insets()
}
