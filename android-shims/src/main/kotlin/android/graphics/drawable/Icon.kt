package android.graphics.drawable

import android.content.Context

class Icon private constructor(
    val resId: Int = 0
) {
    companion object {
        @JvmStatic
        fun createWithResource(context: Context?, resId: Int): Icon {
            return Icon(resId)
        }
    }
}
