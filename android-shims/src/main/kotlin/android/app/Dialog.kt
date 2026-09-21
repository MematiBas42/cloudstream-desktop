package android.app

import android.content.Context
import android.content.DialogInterface
import android.view.View

open class Dialog(
    open val context: Context,
    open val themeResId: Int = 0
) : DialogInterface {

    private var _isShowing: Boolean = false
    private var _cancelable: Boolean = true
    private var _canceledOnTouchOutside: Boolean = true

    private var dismissListener: DialogInterface.OnDismissListener? = null
    private var cancelListener: DialogInterface.OnCancelListener? = null

    open fun isShowing(): Boolean = _isShowing

    open fun show() {
        _isShowing = true
    }

    override fun dismiss() {
        if (_isShowing) {
            _isShowing = false
            dismissListener?.onDismiss(this)
        }
    }

    override fun cancel() {
        if (_isShowing) {
            cancelListener?.onCancel(this)
            dismiss()
        }
    }

    open fun setContentView(view: View) {}
    open fun setContentView(layoutResID: Int) {}

    open fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        this.dismissListener = listener
    }

    open fun setOnDismissListener(listener: (DialogInterface) -> Unit) {
        this.dismissListener = DialogInterface.OnDismissListener { listener(it) }
    }

    open fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) {
        this.cancelListener = listener
    }

    open fun setOnCancelListener(listener: (DialogInterface) -> Unit) {
        this.cancelListener = DialogInterface.OnCancelListener { listener(it) }
    }

    open fun setCancelable(flag: Boolean) {
        _cancelable = flag
    }

    open fun setCanceledOnTouchOutside(cancel: Boolean) {
        _canceledOnTouchOutside = cancel
    }

    open fun <T : View> findViewById(id: Int): T? = null
}
