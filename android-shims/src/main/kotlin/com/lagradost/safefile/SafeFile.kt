package com.lagradost.safefile

import android.content.Context
import android.net.Uri
import com.lagradost.common.download.LinuxDownloadStorage
import com.lagradost.common.logging.AppLogger
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

enum class MediaFileContentType {
    Downloads,
    Video,
    Audio,
    Image
}

fun Closeable?.closeQuietly() {
    try {
        this?.close()
    } catch (t: Throwable) {
        AppLogger.d("SafeFile", "closeQuietly suppressed exception: ${t.message}")
    }
}

class SafeFile(val file: File) {
    fun gotoDirectory(path: String?, createMissingDirectories: Boolean = false): SafeFile? {
        if (path.isNullOrBlank()) return this
        val segments = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        var current = file
        for (seg in segments) {
            current = File(current, seg)
            if (createMissingDirectories && !current.exists()) {
                current.mkdirs()
            }
        }
        return if (current.exists() || createMissingDirectories) SafeFile(current) else null
    }

    fun findFile(name: String): SafeFile? {
        val child = File(file, name)
        return if (child.exists()) SafeFile(child) else null
    }

    fun listFiles(): List<SafeFile>? {
        val files = file.listFiles() ?: return null
        return files.map { SafeFile(it) }
    }

    fun createFileOrThrow(name: String): SafeFile {
        if (!file.exists()) {
            file.mkdirs()
        }
        val child = File(file, name)
        if (!child.exists()) {
            child.parentFile?.mkdirs()
            child.createNewFile()
        }
        return SafeFile(child)
    }

    fun lengthOrThrow(): Long {
        if (!file.exists()) throw java.io.FileNotFoundException("File does not exist: ${file.absolutePath}")
        return file.length()
    }

    fun deleteOrThrow(): Boolean {
        if (file.exists() && !file.delete()) {
            throw java.io.IOException("Failed to delete file: ${file.absolutePath}")
        }
        return true
    }

    fun delete(): Boolean = if (file.exists()) file.delete() else true

    fun exists(): Boolean = file.exists()

    fun isDirectory(): Boolean = file.isDirectory

    fun uriOrThrow(): Uri = Uri.fromFile(file)

    fun uri(): Uri? = if (file.exists()) Uri.fromFile(file) else null

    fun name(): String? = file.name

    fun filePath(): String? = file.absolutePath

    val javaFile: File get() = file

    val path: Path get() = file.toPath()

    fun openOutputStreamOrThrow(append: Boolean = false): OutputStream {
        file.parentFile?.mkdirs()
        return FileOutputStream(file, append)
    }

    fun openInputStreamOrThrow(): InputStream {
        return FileInputStream(file)
    }

    override fun toString(): String = file.absolutePath

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SafeFile) return false
        return file == other.file
    }

    override fun hashCode(): Int = file.hashCode()

    companion object {
        fun fromFile(file: File): SafeFile = SafeFile(file)

        fun fromFilePath(context: Context?, path: String?): SafeFile? {
            if (path.isNullOrBlank()) return null
            return SafeFile(File(path))
        }

        fun fromUri(context: Context?, uri: Uri?): SafeFile? {
            if (uri == null) return null
            val pathStr = uri.path ?: uri.toString().removePrefix("file://")
            return SafeFile(File(pathStr))
        }

        fun fromMedia(context: Context?, type: MediaFileContentType): SafeFile? {
            val dir = LinuxDownloadStorage.getActiveDownloadsDir().toFile()
            if (!dir.exists()) dir.mkdirs()
            return SafeFile(dir)
        }
    }
}
