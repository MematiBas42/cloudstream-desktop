package android.content

interface DialogInterface {
    fun cancel()
    fun dismiss()

    fun interface OnDismissListener {
        fun onDismiss(dialog: DialogInterface)
    }

    fun interface OnCancelListener {
        fun onCancel(dialog: DialogInterface)
    }

    fun interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }

    fun interface OnMultiChoiceClickListener {
        fun onClick(dialog: DialogInterface, which: Int, isChecked: Boolean)
    }

    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_NEUTRAL = -3
    }
}
