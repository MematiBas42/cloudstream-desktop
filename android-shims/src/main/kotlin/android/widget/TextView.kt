package android.widget

import android.content.Context
import android.view.View

open class TextView(context: Context? = null) : View() {
    init {
        this.context = context
    }

    open var maxLines: Int = Int.MAX_VALUE
    open var inputType: Int = 0

    enum class BufferType {
        NORMAL, SPANNABLE, EDITABLE
    }

    open fun setText(text: CharSequence?, type: BufferType = BufferType.NORMAL) {
        this.text = text
    }
}
