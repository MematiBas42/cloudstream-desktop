package com.lagradost.cloudstream3.loader

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * Enterprise Shadow Copy (Copy-on-Load) Plugin Loading Manager.
 *
 * Prevents JVM URLClassLoader file handle locks on Windows NTFS by isolating
 * persistent storage (PlatformPaths.pluginsDir) from runtime execution.
 * ClassLoaders only ever open temporary shadow copies in PlatformPaths.pluginsShadowDir,
 * guaranteeing zero ERROR_SHARING_VIOLATION lockouts during plugin deletion and hot updates.
 */
object PluginShadowManager {
    private const val TAG = "PluginShadowManager"

    @Volatile
    var customShadowDir: File? = null

    init {
        // Disable JVM-wide JarURLConnection caching to prevent permanent file handle leaks
        try {
            URLConnection.setDefaultUseCaches("jar", false)
            val dummyUrl = URI("jar:file:/fake.jar!/").toURL()
            val conn = dummyUrl.openConnection()
            conn.defaultUseCaches = false
        } catch (t: Throwable) {
            AppLogger.w(TAG, "JarURLConnection default caching could not be disabled: ${t.message}")
        }
    }

    /**
     * Resolves the active shadow directory, respecting test overrides and platform paths.
     */
    fun getShadowDirectory(): File {
        return customShadowDir ?: PlatformPaths.pluginsShadowDir.toFile().apply { mkdirs() }
    }

    /**
     * Determines whether a given file is located within the shadow copy directory.
     */
    fun isShadowFile(file: File): Boolean {
        val shadowDirPath = getShadowDirectory().absolutePath
        val filePath = file.absolutePath
        return filePath.startsWith(shadowDirPath) || filePath.contains("plugins-shadow")
    }

    /**
     * Calculates CRC-32 checksum to guarantee unique shadow filenames even across rapid in-millisecond updates.
     */
    private fun calculateCrc32(file: File): String {
        val crc = CRC32()
        val buffer = ByteArray(8192)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                crc.update(buffer, 0, read)
            }
        }
        return java.lang.Long.toHexString(crc.value)
    }

    /**
     * Copies the original JAR file to an isolated, lock-free shadow copy in PlatformPaths.pluginsShadowDir.
     *
     * @param originalJar The original plugin JAR file in plugins/
     * @param internalName Optional internal name from plugin manifest
     * @return The shadow File instance to be loaded by URLClassLoader
     */
    fun createShadowCopy(originalJar: File, internalName: String? = null): File {
        if (!originalJar.exists()) {
            throw IllegalArgumentException("Original jar file does not exist: ${originalJar.absolutePath}")
        }

        if (isShadowFile(originalJar)) {
            return originalJar
        }

        val shadowDir = getShadowDirectory().apply { mkdirs() }
        val namePrefix = (internalName ?: originalJar.nameWithoutExtension)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .removeSuffix("-jvm")
        val fileLength = originalJar.length()
        val lastModified = originalJar.lastModified().toString(16)
        val crc = calculateCrc32(originalJar)

        // Unique, deterministic shadow filename with cache-hit capability
        val shadowFileName = "${namePrefix}_${fileLength}_${lastModified}_${crc}.jar"
        val shadowFile = File(shadowDir, shadowFileName)

        // Cache hit: reuse existing identical shadow file if present and intact
        if (shadowFile.exists() && shadowFile.length() == fileLength) {
            return shadowFile
        }

        // Atomic copy using a temporary file in the shadow directory
        val tempCopy = File.createTempFile("cs3-shadow-tmp-", ".jar", shadowDir)
        try {
            originalJar.inputStream().use { input ->
                tempCopy.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            try {
                Files.move(
                    tempCopy.toPath(),
                    shadowFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                AppLogger.d(TAG, "Atomic move not supported, falling back to standard replace: ${e.message}")
                Files.move(
                    tempCopy.toPath(),
                    shadowFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (tempCopy.exists()) {
                tempCopy.delete()
            }
        }

        try {
            shadowFile.deleteOnExit()
        } catch (t: Throwable) {
            AppLogger.d(TAG, "deleteOnExit registration warning: ${t.message}")
        }

        return shadowFile
    }

    /**
     * Deletes any shadow copies associated with the given original JAR or plugin identifier.
     */
    fun deleteShadowCopies(originalJar: File, internalName: String? = null): Int {
        val shadowDir = getShadowDirectory()
        if (!shadowDir.exists()) return 0

        val namePrefix = (internalName ?: originalJar.nameWithoutExtension)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .removeSuffix("-jvm")

        var deletedCount = 0
        shadowDir.listFiles { _, name -> name.startsWith("${namePrefix}_") && name.endsWith(".jar") }?.forEach { file ->
            try {
                if (file.delete()) {
                    deletedCount++
                } else {
                    file.deleteOnExit()
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Failed to immediately delete shadow copy ${file.name}: ${t.message}")
                file.deleteOnExit()
            }
        }
        return deletedCount
    }

    /**
     * Purges stale shadow copies from prior runs or system crashes.
     */
    fun cleanRuntimeCache(targetDir: File = getShadowDirectory()): Int {
        var purgedCount = 0
        if (!targetDir.exists()) return 0

        targetDir.listFiles { _, name -> name.endsWith(".jar") }?.forEach { file ->
            try {
                if (file.delete()) {
                    purgedCount++
                } else {
                    file.deleteOnExit()
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Failed to immediately delete shadow copy ${file.name}: ${t.message}")
                file.deleteOnExit()
            }
        }
        return purgedCount
    }
}
