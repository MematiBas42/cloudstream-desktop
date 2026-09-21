package android.view

import android.content.Context

open class LayoutInflater(val context: Context?) {
    open fun inflate(resource: Int, root: ViewGroup?, attachToRoot: Boolean = false): View {
        return View().apply { this.context = this@LayoutInflater.context }
    }

    companion object {
        fun from(context: Context?): LayoutInflater = LayoutInflater(context)
    }
}
