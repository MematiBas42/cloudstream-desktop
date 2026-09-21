package android.app

open class AppOpsManager {
    companion object {
        const val OPSTR_PICTURE_IN_PICTURE: String = "android:picture_in_picture"
        const val MODE_ALLOWED: Int = 0
        const val MODE_IGNORED: Int = 1
        const val MODE_ERRORED: Int = 2
        const val MODE_DEFAULT: Int = 3
    }

    open fun checkOpNoThrow(op: String, uid: Int, packageName: String): Int {
        return MODE_ALLOWED
    }
}
