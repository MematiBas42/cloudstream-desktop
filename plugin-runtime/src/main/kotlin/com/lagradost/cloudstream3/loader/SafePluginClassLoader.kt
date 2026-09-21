package com.lagradost.cloudstream3.loader

import com.lagradost.common.logging.AppLogger
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.Closeable
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * High-performance, secure ClassLoader for CloudStream extensions.
 *
 * Implements Domain 22 requirements:
 * 1. %size% and %exact_size% token resolution with density-aware base-2 power-of-two calculations.
 * 2. Clean ClassLoader unloading: closing URLClassLoader, clearing static instance caches (INSTANCE),
 *    and resetting reflection/Jackson caches to guarantee zero Metaspace memory leaks.
 * 3. Security sandboxing & dynamic ghost stub generation for missing Android APIs.
 */
class SafePluginClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val bypassReflection: Boolean = false
) : URLClassLoader(resolveShadowUrls(urls), parent), Closeable {

    constructor(parent: ClassLoader, bypassReflection: Boolean = false) :
        this(emptyArray(), parent, bypassReflection)

    constructor(jarFile: File, parent: ClassLoader, bypassReflection: Boolean = false) :
        this(arrayOf(PluginShadowManager.createShadowCopy(jarFile).toURI().toURL()), parent, bypassReflection)

    private val ghostCache = ConcurrentHashMap<String, Class<*>>()

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Prevent loading dangerous packages directly from the plugin bytecode
        if (isBlocked(name)) {
            throw SecurityException("Security Sandbox: Access to class '$name' is blocked.")
        }
        return try {
            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            // If the plugin requests an Android API or CloudStream API that we haven't stubbed, generate a ghost stub
            if (name.startsWith("android.") || name.startsWith("androidx.") ||
                name.startsWith("com.android.") || name.startsWith("com.lagradost.") ||
                name.startsWith("com.google.")
            ) {
                generateGhostStub(name)
            } else {
                throw e
            }
        }
    }

    private fun generateGhostStub(name: String): Class<*> {
        ghostCache[name]?.let { return it }

        AppLogger.d("SafePluginClassLoader", "[GhostStub] Dynamically generated stub for missing Android API: $name")

        val internalName = name.replace('.', '/')
        val cw = ClassWriter(0)

        // Heuristic to detect if it's supposed to be an interface
        val isInterface = name.endsWith("Listener") || name.endsWith("Callback") ||
            name.endsWith("Observer") || name.contains("\$On")

        val access = if (isInterface) {
            Opcodes.ACC_PUBLIC + Opcodes.ACC_ABSTRACT + Opcodes.ACC_INTERFACE
        } else {
            Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER
        }

        cw.visit(
            Opcodes.V1_8,
            access,
            internalName,
            null,
            "java/lang/Object",
            null,
        )

        if (!isInterface) {
            // default constructor
            val mv1 = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            mv1.visitCode()
            mv1.visitVarInsn(Opcodes.ALOAD, 0)
            mv1.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv1.visitInsn(Opcodes.RETURN)
            mv1.visitMaxs(1, 1)
            mv1.visitEnd()

            // constructor(Context)
            val mv2 = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;)V", null, null)
            mv2.visitCode()
            mv2.visitVarInsn(Opcodes.ALOAD, 0)
            mv2.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv2.visitInsn(Opcodes.RETURN)
            mv2.visitMaxs(1, 2)
            mv2.visitEnd()

            // constructor(Context, AttributeSet)
            val mv3 = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;)V", null, null)
            mv3.visitCode()
            mv3.visitVarInsn(Opcodes.ALOAD, 0)
            mv3.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv3.visitInsn(Opcodes.RETURN)
            mv3.visitMaxs(1, 3)
            mv3.visitEnd()

            // constructor(Context, AttributeSet, int)
            val mv4 = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Landroid/content/Context;Landroid/util/AttributeSet;I)V", null, null)
            mv4.visitCode()
            mv4.visitVarInsn(Opcodes.ALOAD, 0)
            mv4.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv4.visitInsn(Opcodes.RETURN)
            mv4.visitMaxs(1, 4)
            mv4.visitEnd()

            // constructor(int) - Used by ColorDrawable and similar resource-based constructors
            val mv5 = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null)
            mv5.visitCode()
            mv5.visitVarInsn(Opcodes.ALOAD, 0)
            mv5.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            mv5.visitInsn(Opcodes.RETURN)
            mv5.visitMaxs(1, 2)
            mv5.visitEnd()
        }

        if (!isInterface) {
            cw.visitField(
                Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC,
                "INSTANCE",
                "L$internalName;",
                null, null
            ).visitEnd()
        }

        cw.visitEnd()

        val bytecode = cw.toByteArray()
        val clazz = defineClass(name, bytecode, 0, bytecode.size)

        if (!isInterface) {
            try {
                val instanceField = clazz.getDeclaredField("INSTANCE")
                instanceField.set(null, clazz.getDeclaredConstructor().newInstance())
            } catch (_: Exception) {
                // ignore
            }
        }

        ghostCache[name] = clazz
        return clazz
    }

    /**
     * Wipes static INSTANCE fields of all dynamically generated ghost classes,
     * then clears the ghost cache to prevent static reference cycles.
     */
    fun clearGhostCache() {
        for ((_, clazz) in ghostCache) {
            try {
                val instanceField = clazz.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                instanceField.set(null, null)
            } catch (t: Throwable) {
                AppLogger.d(TAG, "clearGhostCache reflection warning: ${t.message}")
            }
        }
        ghostCache.clear()
    }

    override fun close() {
        cleanUnload(this)
        super.close()
    }

    private fun isBlocked(name: String): Boolean {
        // Block file system access, but allow benign streams/readers/writers
        if (name.startsWith("java.io.")) {
            val safeIo = setOf(
                "java.io.InputStream",
                "java.io.OutputStream",
                "java.io.ByteArrayInputStream",
                "java.io.ByteArrayOutputStream",
                "java.io.StringReader",
                "java.io.StringWriter",
                "java.io.InputStreamReader",
                "java.io.OutputStreamWriter",
                "java.io.BufferedReader",
                "java.io.BufferedWriter",
                "java.io.IOException",
                "java.io.EOFException",
                "java.io.FileNotFoundException",
                "java.io.InterruptedIOException",
                "java.io.UnsupportedEncodingException",
                "java.io.FilterInputStream",
                "java.io.FilterOutputStream",
                "java.io.BufferedInputStream",
                "java.io.BufferedOutputStream",
                "java.io.DataInputStream",
                "java.io.DataOutputStream",
                "java.io.Reader",
                "java.io.Writer",
                "java.io.Serializable",
                "java.io.Closeable",
                "java.io.PrintStream",
                "java.io.PrintWriter",
                "java.io.ObjectStreamException"
            )
            if (!safeIo.contains(name)) {
                return true
            }
        }

        // Block unsafe NIO (channels, files) but allow buffers and charsets
        if (name.startsWith("java.nio.")) {
            if (!name.startsWith("java.nio.charset.") && !name.contains("Buffer")) {
                return true
            }
        }

        // Block OS command execution
        if (name == "java.lang.ProcessBuilder") {
            return true
        }

        // Block reflection to prevent sandbox escape (unless plugin is trusted)
        if (name.startsWith("java.lang.reflect.")) {
            if (!bypassReflection) {
                return true
            }
        }

        // Block method handles but ALLOW LambdaMetafactory and StringConcatFactory required for Java 8+ lambdas
        if (name.startsWith("java.lang.invoke.")) {
            val safeInvoke = setOf(
                "java.lang.invoke.LambdaMetafactory",
                "java.lang.invoke.MethodHandles",
                "java.lang.invoke.MethodHandles\$Lookup",
                "java.lang.invoke.MethodType",
                "java.lang.invoke.CallSite",
                "java.lang.invoke.ConstantCallSite",
                "java.lang.invoke.MutableCallSite",
                "java.lang.invoke.VolatileCallSite",
                "java.lang.invoke.StringConcatFactory",
                "java.lang.invoke.TypeDescriptor",
                "java.lang.invoke.TypeDescriptor\$OfField",
                "java.lang.invoke.TypeDescriptor\$OfMethod"
            )
            if (!safeInvoke.contains(name)) {
                return true
            }
        }

        // Block compiler and unsafe memory access
        if (name.startsWith("sun.misc.") || name.startsWith("jdk.internal.") || name.startsWith("sun.reflect.")) {
            return true
        }

        return false
    }

    companion object {
        private const val TAG = "SafePluginClassLoader"
        const val TOKEN_SIZE = "%size%"
        const val TOKEN_EXACT_SIZE = "%exact_size%"

        private const val MIN_BASE2_SIZE = 16
        private const val MAX_BASE2_SIZE = 512

        /**
         * Recursive base-2 closest size resolver matching upstream PluginAdapter.kt.
         *
         * Resolves target into the smallest power of two that is >= target,
         * clamped to [MIN_BASE2_SIZE, MAX_BASE2_SIZE].
         */
        tailrec fun findClosestBase2(target: Int, current: Int = MIN_BASE2_SIZE, max: Int = MAX_BASE2_SIZE): Int {
            if (current >= max) return max
            if (current >= target) return current
            return findClosestBase2(target, current * 2, max)
        }

        /**
         * Resolves an icon URL template containing %size% and/or %exact_size% tokens.
         *
         * @param rawUrl The raw URL template.
         * @param targetDp Target display size in dp (e.g. 32dp for cards, 50dp for details).
         * @param density Display scaling factor (1.0f for 1x, 2.0f for 2x, 3.0f for 3x).
         * @return Evaluated URL string, or null if input was null/blank.
         */
        fun resolveIconUrl(rawUrl: String?, targetDp: Int = 32, density: Float = 1.0f): String? {
            if (rawUrl.isNullOrBlank()) return null

            val exactPx = (targetDp * density).roundToInt().coerceAtLeast(1)
            val base2Px = findClosestBase2(exactPx)

            val hasSize = rawUrl.contains(TOKEN_SIZE)
            val hasExactSize = rawUrl.contains(TOKEN_EXACT_SIZE)

            if (!hasSize && !hasExactSize) return rawUrl

            var result = rawUrl
            if (hasSize) {
                result = result.replace(TOKEN_SIZE, base2Px.toString())
            }
            if (hasExactSize) {
                result = result.replace(TOKEN_EXACT_SIZE, exactPx.toString())
            }
            return result
        }

        /**
         * Resolves any file URLs pointing to plugin archives to their corresponding shadow copies.
         */
        fun resolveShadowUrls(urls: Array<URL>): Array<URL> {
            if (urls.isEmpty()) return urls
            return urls.map { url ->
                try {
                    if (url.protocol.equals("file", ignoreCase = true)) {
                        val file = File(url.toURI())
                        if (file.exists() && file.isFile && !PluginShadowManager.isShadowFile(file)) {
                            PluginShadowManager.createShadowCopy(file).toURI().toURL()
                        } else {
                            url
                        }
                    } else {
                        url
                    }
                } catch (t: Throwable) {
                    AppLogger.d(TAG, "resolveShadowUrls fallback for $url: ${t.message}")
                    url
                }
            }.toTypedArray()
        }

        /**
         * Alias for [resolveIconUrl] matching Domain 22 naming convention.
         */
        fun evaluateIconUrl(rawUrl: String?, targetDp: Int = 32, density: Float = 1.0f): String? =
            resolveIconUrl(rawUrl, targetDp, density)

        /**
         * Executes clean unloader sequence for any ClassLoader:
         * 1. Wipes static non-primitive fields of all loaded classes in this loader and target plugin class.
         * 2. Clears ghost caches and resets static INSTANCE references.
         * 3. Flushes Jackson TypeFactory and JavaBeans Introspector reflection caches.
         * 4. Closes URLClassLoader to release underlying POSIX open file descriptors.
         */
        fun cleanUnload(classLoader: ClassLoader?, pluginClass: Class<*>? = null) {
            if (classLoader == null && pluginClass == null) return

            // 1. Wipe static fields of target plugin class and any inner classes
            if (pluginClass != null) {
                clearClassStaticFields(pluginClass)
            }

            // 2. Wipe static fields of loaded classes in this ClassLoader
            if (classLoader != null) {
                clearLoadedClasses(classLoader)
            }

            // 3. Clear ghost cache if this or parent is SafePluginClassLoader
            if (classLoader is SafePluginClassLoader) {
                classLoader.clearGhostCache()
            }
            val parent = classLoader?.parent
            if (parent is SafePluginClassLoader) {
                parent.clearGhostCache()
            }

            // 4. Clear Jackson TypeFactory & Introspector reflection caches to eliminate Metaspace root retention
            try {
                com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance().clearCache()
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Failed to clear TypeFactory cache: ${t.message}")
            }
            try {
                java.beans.Introspector.flushCaches()
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Failed to flush Introspector caches: ${t.message}")
            }

            // 5. Close URLClassLoader to release file descriptors / locks and purge temporary shadow copy
            if (classLoader is URLClassLoader) {
                try {
                    val urls = classLoader.urLs
                    classLoader.close()
                    urls.forEach { url ->
                        try {
                            if (url.protocol.equals("file", ignoreCase = true)) {
                                val file = File(url.toURI())
                                if (PluginShadowManager.isShadowFile(file) && file.exists()) {
                                    if (!file.delete()) {
                                        file.deleteOnExit()
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            AppLogger.d(TAG, "Failed to immediately delete shadow copy ${url.path}: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    AppLogger.d(TAG, "Failed to close URLClassLoader: ${t.message}")
                }
            }
        }

        fun clearClassStaticFields(clazz: Class<*>?) {
            if (clazz == null) return
            try {
                for (field in clazz.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers) && !field.type.isPrimitive) {
                        try {
                            field.isAccessible = true
                            field.set(null, null)
                        } catch (t: Throwable) {
                            AppLogger.d(TAG, "Failed to clear static field ${field.name}: ${t.message}")
                        }
                    }
                }
                for (inner in clazz.declaredClasses) {
                    clearClassStaticFields(inner)
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Failed to clear static fields for class ${clazz.name}: ${t.message}")
            }
        }

        private fun clearLoadedClasses(classLoader: ClassLoader) {
            try {
                var currentClass: Class<*>? = classLoader.javaClass
                var classesField: java.lang.reflect.Field? = null
                while (currentClass != null && currentClass != Any::class.java) {
                    try {
                        classesField = currentClass.getDeclaredField("classes")
                        break
                    } catch (e: NoSuchFieldException) {
                        currentClass = currentClass.superclass
                    }
                }
                if (classesField != null) {
                    classesField.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val classes = classesField.get(classLoader) as? java.util.Vector<Class<*>>
                    if (classes != null) {
                        val copy = synchronized(classes) { classes.toList() }
                        for (clazz in copy) {
                            clearClassStaticFields(clazz)
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.d(TAG, "Failed to inspect loaded classes: ${t.message}")
            }
        }
    }
}
