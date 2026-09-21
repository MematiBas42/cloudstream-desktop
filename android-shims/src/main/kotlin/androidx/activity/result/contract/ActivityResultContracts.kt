package androidx.activity.result.contract

import android.net.Uri

abstract class ActivityResultContract<I, O>

object ActivityResultContracts {
    open class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
}
