package android.widget

import android.content.Context
import com.lagradost.common.logging.AppLogger

class Toast(private val context: Context? = null) {
    private var text: CharSequence = ""
    private var duration: Int = LENGTH_SHORT

    fun setText(text: CharSequence) {
        this.text = text
    }

    fun setText(resId: Int) {
        this.text = context?.getString(resId) ?: "res_$resId"
    }

    fun setDuration(duration: Int) {
        this.duration = duration
    }

    fun getDuration(): Int = duration

    fun show() {
        AppLogger.i("Toast", "[TOAST: duration=$duration] $text")
    }

    companion object {
        const val LENGTH_SHORT: Int = 0
        const val LENGTH_LONG: Int = 1

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): Toast {
            val toast = Toast(context)
            toast.setText(text ?: "")
            toast.setDuration(duration)
            return toast
        }

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): Toast {
            val toast = Toast(context)
            val msg = context?.getString(resId) ?: "res_$resId"
            toast.setText(msg)
            toast.setDuration(duration)
            return toast
        }
    }
}
