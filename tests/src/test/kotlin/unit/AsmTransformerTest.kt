package unit

import com.lagradost.runtime.loader.PluginBytecodeTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Unit tests for BytecodeTransformer verifying:
 * - Demangling synthetic Kotlin value class methods from "_impl" to "-impl"
 * - Neutralizing android.view.* and android.widget.* calls to LinuxUiSink
 * - Stripping @kotlin.Metadata to prevent Jackson/DEX reflection failures
 */
class AsmTransformerTest {

    @Test
    fun testFixMethodNameMappings() {
        assertEquals("constructor-impl", PluginBytecodeTransformer.fixMethodName("constructor_impl"))
        assertEquals("box-impl", PluginBytecodeTransformer.fixMethodName("box_impl"))
        assertEquals("unbox-impl", PluginBytecodeTransformer.fixMethodName("unbox_impl"))
        assertEquals("isSuccess-impl", PluginBytecodeTransformer.fixMethodName("isSuccess_impl"))
        assertEquals("isFailure-impl", PluginBytecodeTransformer.fixMethodName("isFailure_impl"))
        assertEquals("getOrNull-impl", PluginBytecodeTransformer.fixMethodName("getOrNull_impl"))
        assertEquals("exceptionOrNull-impl", PluginBytecodeTransformer.fixMethodName("exceptionOrNull_impl"))
        assertEquals("equals-impl", PluginBytecodeTransformer.fixMethodName("equals_impl"))
        assertEquals("hashCode-impl", PluginBytecodeTransformer.fixMethodName("hashCode_impl"))
        assertEquals("toString-impl", PluginBytecodeTransformer.fixMethodName("toString_impl"))
        assertEquals("custom_property-impl", PluginBytecodeTransformer.fixMethodName("custom_property_impl"))
        assertEquals("already-impl", PluginBytecodeTransformer.fixMethodName("already-impl"))
        assertEquals("regularMethod", PluginBytecodeTransformer.fixMethodName("regularMethod"))
    }

    @Test
    fun testBytecodeTransformationOnSyntheticClass(@TempDir tempDir: Path) {
        val testJar = tempDir.resolve("synthetic-plugin.jar").toFile()

        // 1. Generate a synthetic class using ASM ClassWriter
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "com/lagradost/testplugin/SyntheticPlugin",
            null,
            "java/lang/Object",
            null
        )

        // Add @kotlin.Metadata annotation (must be stripped by transformer)
        val metadataAnnotation = cw.visitAnnotation("Lkotlin/Metadata;", true)
        metadataAnnotation.visit("k", 1)
        metadataAnnotation.visit("mv", intArrayOf(2, 0, 0))
        metadataAnnotation.visitEnd()

        // Add standard @java.lang.Deprecated annotation (must be preserved)
        val deprecatedAnnotation = cw.visitAnnotation("Ljava/lang/Deprecated;", true)
        deprecatedAnnotation.visitEnd()

        // Default constructor
        val initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        initMv.visitCode()
        initMv.visitVarInsn(Opcodes.ALOAD, 0)
        initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        initMv.visitInsn(Opcodes.RETURN)
        initMv.visitMaxs(1, 1)
        initMv.visitEnd()

        // Synthetic method with _impl: constructor_impl(I)I
        val constructorImplMv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "constructor_impl",
            "(I)I",
            null,
            null
        )
        constructorImplMv.visitCode()
        constructorImplMv.visitVarInsn(Opcodes.ILOAD, 0)
        constructorImplMv.visitInsn(Opcodes.IRETURN)
        constructorImplMv.visitMaxs(1, 1)
        constructorImplMv.visitEnd()

        // Synthetic method with _impl: box_impl(I)Ljava/lang/Object;
        val boxImplMv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "box_impl",
            "(I)Ljava/lang/Object;",
            null,
            null
        )
        boxImplMv.visitCode()
        boxImplMv.visitVarInsn(Opcodes.ILOAD, 0)
        boxImplMv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/Integer",
            "valueOf",
            "(I)Ljava/lang/Integer;",
            false
        )
        boxImplMv.visitInsn(Opcodes.ARETURN)
        boxImplMv.visitMaxs(1, 1)
        boxImplMv.visitEnd()

        // Method containing Android UI calls and invocations to constructor_impl
        val executeMv = cw.visitMethod(
            Opcodes.ACC_PUBLIC,
            "executeUiAndLogic",
            "()V",
            null,
            null
        )
        executeMv.visitCode()

        // Call constructor_impl (should be rewritten to call constructor-impl)
        executeMv.visitIntInsn(Opcodes.BIPUSH, 42)
        executeMv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "com/lagradost/testplugin/SyntheticPlugin",
            "constructor_impl",
            "(I)I",
            false
        )
        executeMv.visitInsn(Opcodes.POP)

        // Android View Call: View.setVisibility(8)
        executeMv.visitInsn(Opcodes.ACONST_NULL) // dummy view receiver
        executeMv.visitIntInsn(Opcodes.BIPUSH, 8)
        executeMv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "android/view/View",
            "setVisibility",
            "(I)V",
            false
        )

        // Android View Call: ViewGroup.addView(View)
        executeMv.visitInsn(Opcodes.ACONST_NULL) // dummy viewgroup receiver
        executeMv.visitInsn(Opcodes.ACONST_NULL) // dummy child view
        executeMv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "android/view/ViewGroup",
            "addView",
            "(Landroid/view/View;)V",
            false
        )

        // Android Widget Call: TextView.setText(CharSequence)
        executeMv.visitInsn(Opcodes.ACONST_NULL) // dummy textview receiver
        executeMv.visitLdcInsn("Episode 1 Streaming")
        executeMv.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "android/widget/TextView",
            "setText",
            "(Ljava/lang/CharSequence;)V",
            false
        )

        // Android Static Call: View.generateViewId()
        executeMv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "android/view/View",
            "generateViewId",
            "()I",
            false
        )
        executeMv.visitInsn(Opcodes.POP)

        executeMv.visitInsn(Opcodes.RETURN)
        executeMv.visitMaxs(3, 1)
        executeMv.visitEnd()

        cw.visitEnd()
        val originalClassBytes = cw.toByteArray()

        // 2. Package class into real ZIP/JAR on disk
        ZipOutputStream(FileOutputStream(testJar)).use { zos ->
            zos.putNextEntry(ZipEntry("com/lagradost/testplugin/SyntheticPlugin.class"))
            zos.write(originalClassBytes)
            zos.closeEntry()
        }

        // 3. Execute PluginBytecodeTransformer on the real JAR file
        PluginBytecodeTransformer.transform(testJar)

        // 4. Verify the transformed bytecode inside the JAR
        assertTrue(testJar.exists(), "Transformed JAR file must exist on disk")
        assertTrue(testJar.length() > 0, "Transformed JAR must not be empty")

        ZipFile(testJar).use { zip ->
            val entry = zip.getEntry("com/lagradost/testplugin/SyntheticPlugin.class")
            assertNotNull(entry, "Class file must exist in transformed JAR")

            val transformedBytes = zip.getInputStream(entry).use { it.readBytes() }
            val reader = ClassReader(transformedBytes)
            val classNode = ClassNode()
            reader.accept(classNode, 0)

            // Assert @kotlin.Metadata annotation was stripped
            val hasKotlinMetadata = classNode.visibleAnnotations?.any { it.desc == "Lkotlin/Metadata;" } ?: false
            assertFalse(hasKotlinMetadata, "@kotlin.Metadata must be stripped to prevent Jackson reflection errors")

            // Assert @java.lang.Deprecated annotation was preserved
            val hasDeprecated = classNode.visibleAnnotations?.any { it.desc == "Ljava/lang/Deprecated;" } ?: false
            assertTrue(hasDeprecated, "Non-metadata annotations like @Deprecated must be preserved")

            // Assert method names: constructor_impl was renamed to constructor-impl
            val methodNames = classNode.methods.map { it.name }
            assertTrue(methodNames.contains("constructor-impl"), "constructor_impl must be renamed to constructor-impl")
            assertFalse(methodNames.contains("constructor_impl"), "Original constructor_impl must no longer exist")

            assertTrue(methodNames.contains("box-impl"), "box_impl must be renamed to box-impl")
            assertFalse(methodNames.contains("box_impl"), "Original box_impl must no longer exist")

            // Assert method instructions inside executeUiAndLogic
            val executeMethod = classNode.methods.first { it.name == "executeUiAndLogic" }
            val methodInsnNodes = executeMethod.instructions.filterIsInstance<MethodInsnNode>()

            // 1. Check call to constructor_impl -> constructor-impl
            val internalCall = methodInsnNodes.first { it.owner == "com/lagradost/testplugin/SyntheticPlugin" }
            assertEquals("constructor-impl", internalCall.name, "Call to constructor_impl must be rewritten to constructor-impl")

            // 2. Check that android.view.* and android.widget.* calls are neutralized to LinuxUiSink
            val neutralizedCalls = methodInsnNodes.filter { it.owner.contains("LinuxUiSink") }
            assertEquals(4, neutralizedCalls.size, "All 4 Android UI calls must be routed to LinuxUiSink")

            for (call in neutralizedCalls) {
                assertEquals(
                    "com/lagradost/runtime/shims/LinuxUiSink",
                    call.owner,
                    "Target owner must be LinuxUiSink"
                )
                assertEquals(
                    Opcodes.INVOKESTATIC,
                    call.opcode,
                    "Neutralized method invocation opcode must be INVOKESTATIC"
                )
                assertFalse(call.itf, "Neutralized call must not be an interface invocation")
            }

            // Verify no android.view or android.widget references remain in the call list
            val remainingAndroidCalls = methodInsnNodes.filter {
                it.owner.startsWith("android/view/") || it.owner.startsWith("android/widget/")
            }
            assertTrue(remainingAndroidCalls.isEmpty(), "No android.view or android.widget calls should remain")
        }
    }
}
