package android.content.res

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

open class AssetManager {
    open fun open(fileName: String): InputStream {
        val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("assets/$fileName")
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(fileName)
            ?: javaClass.classLoader?.getResourceAsStream("assets/$fileName")
            ?: javaClass.classLoader?.getResourceAsStream(fileName)
            ?: ClassLoader.getSystemResourceAsStream("assets/$fileName")
            ?: ClassLoader.getSystemResourceAsStream(fileName)
            ?: File("assets", fileName).takeIf { it.exists() }?.inputStream()
            ?: File(fileName).takeIf { it.exists() }?.inputStream()
        return stream ?: ByteArrayInputStream(ByteArray(0))
    }

    open fun list(path: String): Array<String>? = emptyArray()
}
