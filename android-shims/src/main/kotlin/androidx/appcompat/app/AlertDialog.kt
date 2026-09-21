package androidx.appcompat.app

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.view.View

open class AlertDialog(
    context: Context,
    themeResId: Int = 0
) : Dialog(context, themeResId) {

    open class Builder(
        open val context: Context,
        open val themeResId: Int = 0
    ) {
        var title: CharSequence? = null
        var message: CharSequence? = null
        var positiveButtonText: CharSequence? = null
        var positiveButtonListener: DialogInterface.OnClickListener? = null
        var negativeButtonText: CharSequence? = null
        var negativeButtonListener: DialogInterface.OnClickListener? = null
        var neutralButtonText: CharSequence? = null
        var neutralButtonListener: DialogInterface.OnClickListener? = null
        var isCancelable: Boolean = true
        var onDismissListener: DialogInterface.OnDismissListener? = null

        open fun setTitle(titleId: Int): Builder {
            this.title = context.getString(titleId)
            return this
        }

        open fun setTitle(title: CharSequence?): Builder {
            this.title = title
            return this
        }

        open fun setMessage(messageId: Int): Builder {
            this.message = context.getString(messageId)
            return this
        }

        open fun setMessage(message: CharSequence?): Builder {
            this.message = message
            return this
        }

        open fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.positiveButtonText = context.getString(textId)
            this.positiveButtonListener = listener
            return this
        }

        open fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.positiveButtonText = text
            this.positiveButtonListener = listener
            return this
        }

        open fun setPositiveButton(textId: Int, listener: (DialogInterface, Int) -> Unit): Builder {
            return setPositiveButton(context.getString(textId), DialogInterface.OnClickListener { dialog, which -> listener(dialog, which) })
        }

        open fun setPositiveButton(text: CharSequence?, listener: (DialogInterface, Int) -> Unit): Builder {
            return setPositiveButton(text, DialogInterface.OnClickListener { dialog, which -> listener(dialog, which) })
        }

        open fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.negativeButtonText = context.getString(textId)
            this.negativeButtonListener = listener
            return this
        }

        open fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.negativeButtonText = text
            this.negativeButtonListener = listener
            return this
        }

        open fun setNegativeButton(textId: Int, listener: (DialogInterface, Int) -> Unit): Builder {
            return setNegativeButton(context.getString(textId), DialogInterface.OnClickListener { dialog, which -> listener(dialog, which) })
        }

        open fun setNegativeButton(text: CharSequence?, listener: (DialogInterface, Int) -> Unit): Builder {
            return setNegativeButton(text, DialogInterface.OnClickListener { dialog, which -> listener(dialog, which) })
        }

        open fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.neutralButtonText = context.getString(textId)
            this.neutralButtonListener = listener
            return this
        }

        open fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.neutralButtonText = text
            this.neutralButtonListener = listener
            return this
        }

        open fun setNeutralButton(textId: Int, listener: (DialogInterface, Int) -> Unit): Builder {
            return setNeutralButton(context.getString(textId), DialogInterface.OnClickListener { dialog, which -> listener(dialog, which) })
        }

        open fun setNeutralButton(text: CharSequence?, listener: (DialogInterface, Int) -> Unit): Builder {
            return setNeutralButton(text, DialogInterface.OnClickListener { dialog, which -> listener(dialog, which) })
        }

        open fun setCancelable(cancelable: Boolean): Builder {
            this.isCancelable = cancelable
            return this
        }

        open fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): Builder {
            this.onDismissListener = listener
            return this
        }

        open fun setView(view: View?): Builder = this

        open fun create(): AlertDialog {
            return AlertDialog(context, themeResId)
        }

        open fun show(): AlertDialog {
            val dialog = create()
            dialog.show()
            return dialog
        }
    }
}
