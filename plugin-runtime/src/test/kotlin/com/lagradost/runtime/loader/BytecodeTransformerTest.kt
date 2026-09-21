package com.lagradost.runtime.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BytecodeTransformerTest {

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
        assertEquals("already-impl", PluginBytecodeTransformer.fixMethodName("already-impl"))
        assertEquals("normalMethod", PluginBytecodeTransformer.fixMethodName("normalMethod"))
    }

    @Test
    fun testBytecodeTransformation(@TempDir tempDir: Path) {
        val testJar = tempDir.resolve("test-plugin.jar").toFile()

        // Create synthetic class bytecode
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/TestPlugin", null, "java/lang/Object", null)

        // 1. Add @kotlin.Metadata annotation (should be preserved for Jackson reflection)
        val av = cw.visitAnnotation("Lkotlin/Metadata;", true)
        av.visit("k", 1)
        av.visitEnd()

        // Also add another annotation (should be preserved)
        val av2 = cw.visitAnnotation("Ljava/lang/Deprecated;", true)
        av2.visitEnd()

        // 2. Add constructor_impl method definition
        val mvInit = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "constructor_impl", "(I)I", null, null)
        mvInit.visitCode()
        mvInit.visitVarInsn(Opcodes.ILOAD, 0)
        mvInit.visitInsn(Opcodes.IRETURN)
        mvInit.visitMaxs(1, 1)
        mvInit.visitEnd()

        // 3. Add run() method calling Android UI: View.setVisibility and TextView.setText
        val mvRun = cw.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null)
        mvRun.visitCode()

        // View.setVisibility(8)
        mvRun.visitInsn(Opcodes.ACONST_NULL) // dummy view
        mvRun.visitIntInsn(Opcodes.BIPUSH, 8)
        mvRun.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/view/View", "setVisibility", "(I)V", false)

        // TextView.setText("Hello")
        mvRun.visitInsn(Opcodes.ACONST_NULL) // dummy textview
        mvRun.visitLdcInsn("Hello")
        mvRun.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/widget/TextView", "setText", "(Ljava/lang/CharSequence;)V", false)

        // View.generateViewId() static call
        mvRun.visitMethodInsn(Opcodes.INVOKESTATIC, "android/view/View", "generateViewId", "()I", false)
        mvRun.visitInsn(Opcodes.POP)

        mvRun.visitInsn(Opcodes.RETURN)
        mvRun.visitMaxs(2, 1)
        mvRun.visitEnd()

        cw.visitEnd()
        val classBytes = cw.toByteArray()

        // Pack into testJar
        ZipOutputStream(FileOutputStream(testJar)).use { zos ->
            zos.putNextEntry(ZipEntry("com/example/TestPlugin.class"))
            zos.write(classBytes)
            zos.closeEntry()
        }

        // Run bytecode transformer
        PluginBytecodeTransformer.transform(testJar)

        // Inspect transformed class
        ZipFile(testJar).use { zip ->
            val entry = zip.getEntry("com/example/TestPlugin.class")
            assertNotNull(entry)

            val transformedBytes = zip.getInputStream(entry).use { it.readBytes() }
            val reader = ClassReader(transformedBytes)
            val classNode = ClassNode()
            reader.accept(classNode, 0)

            // Assert metadata annotation is preserved for Jackson KotlinModule
            val hasMetadata = classNode.visibleAnnotations?.any { it.desc == "Lkotlin/Metadata;" } ?: false
            assertTrue(hasMetadata, "@kotlin.Metadata annotation should be preserved")

            // Assert deprecated annotation is preserved
            val hasDeprecated = classNode.visibleAnnotations?.any { it.desc == "Ljava/lang/Deprecated;" } ?: false
            assertTrue(hasDeprecated, "@java.lang.Deprecated annotation should be preserved")

            // Assert constructor_impl was renamed to constructor-impl
            val hasConstructorImpl = classNode.methods.any { it.name == "constructor-impl" }
            assertTrue(hasConstructorImpl, "Method constructor_impl should be renamed to constructor-impl")

            val oldConstructorImpl = classNode.methods.any { it.name == "constructor_impl" }
            assertFalse(oldConstructorImpl, "Old method constructor_impl should not exist")

            // Assert UI calls are neutralized to LinuxUiSink
            val runMethod = classNode.methods.first { it.name == "run" }
            val methodCalls = runMethod.instructions.filterIsInstance<MethodInsnNode>()

            for (call in methodCalls) {
                assertEquals(
                    "com/lagradost/runtime/shims/LinuxUiSink",
                    call.owner,
                    "UI call to ${call.owner}.${call.name} should be redirected to LinuxUiSink"
                )
                assertEquals(Opcodes.INVOKESTATIC, call.opcode, "Neutralized call should be INVOKESTATIC")
            }
        }
    }
}
