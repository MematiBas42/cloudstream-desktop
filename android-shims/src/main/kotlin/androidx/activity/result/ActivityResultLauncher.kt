package androidx.activity.result

open class ActivityResultLauncher<I> {
    open fun launch(input: I) {}
    open fun unregister() {}
}

fun interface ActivityResultCallback<O> {
    fun onActivityResult(result: O)
}
