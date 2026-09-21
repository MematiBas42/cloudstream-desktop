package android.content

import android.net.Uri

open class Intent {
    var action: String? = null
    var data: Uri? = null
    var type: String? = null
    var `package`: String? = null
    var flags: Int = 0
    var component: ComponentName? = null
    var clipData: ClipData? = null
    private val extras = mutableMapOf<String, Any?>()

    constructor()
    constructor(action: String?) {
        this.action = action
    }
    constructor(action: String?, uri: Uri?) {
        this.action = action
        this.data = uri
    }
    constructor(packageContext: Context?, cls: Class<*>?) {
        if (cls != null) {
            this.component = ComponentName(packageContext?.getPackageName() ?: "", cls.name)
        }
    }

    fun setPackage(packageName: String?): Intent = apply { this.`package` = packageName }
    fun setData(data: Uri?): Intent = apply { this.data = data }
    fun setDataAndType(data: Uri?, type: String?): Intent = apply { this.data = data; this.type = type }
    fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }

    fun putExtra(name: String, value: String?): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Int): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Long): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Boolean): Intent = apply { extras[name] = value }
    fun putExtra(name: String, value: Any?): Intent = apply { extras[name] = value }

    fun getStringExtra(name: String): String? = extras[name] as? String
    @Suppress("UNCHECKED_CAST")
    fun getStringArrayExtra(name: String): Array<String>? = when (val value = extras[name]) {
        is Array<*> -> value as? Array<String>
        is List<*> -> (value as? List<String>)?.toTypedArray()
        else -> null
    }
    fun getIntExtra(name: String, defaultValue: Int): Int = (extras[name] as? Number)?.toInt() ?: defaultValue
    fun getLongExtra(name: String, defaultValue: Long): Long = (extras[name] as? Number)?.toLong() ?: defaultValue
    fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean = (extras[name] as? Boolean) ?: defaultValue

    fun hasExtra(name: String): Boolean = extras.containsKey(name)
    @Suppress("UNCHECKED_CAST")
    fun <T> getParcelableExtra(name: String): T? = extras[name] as? T

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_NOT_UNKNOWN_SOURCE = "android.intent.extra.NOT_UNKNOWN_SOURCE"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_ACTIVITY_CLEAR_TASK = 0x00008000
        const val FLAG_ACTIVITY_CLEAR_TOP = 0x04000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002
        const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040
        const val FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080
    }
}
