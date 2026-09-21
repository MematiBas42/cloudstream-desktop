package android.content

import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

open class ContentResolver {
    open fun openInputStream(uri: Uri): InputStream? {
        val path = uri.path ?: uri.toString().removePrefix("file://")
        val file = File(path)
        return if (file.exists()) FileInputStream(file) else null
    }

    open fun openOutputStream(uri: Uri): OutputStream? {
        val path = uri.path ?: uri.toString().removePrefix("file://")
        val file = File(path)
        file.parentFile?.mkdirs()
        return FileOutputStream(file)
    }
}
