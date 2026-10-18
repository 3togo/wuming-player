package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class GuardedSpiderDiagnosticInstrumenterTest {

    @Test
    fun instrumentsSeparateContentAddressedArtifactAndReusesIt() {
        val root = Files.createTempDirectory("guarded-diagnostic-test-")
        try {
            val sourcePath = root.resolve("prepared.jar")
            writeFixtureJar(sourcePath)
            val sourceSha = sha256(sourcePath)
            val sourceBytes = Files.readAllBytes(sourcePath)
            val source = PreparedGuardedSpiderArtifact(
                path = sourcePath,
                abi = GuardedSpiderAbi.ARMEABI_V7A,
                packageSha256 = "a".repeat(64),
                artifactSha256 = sourceSha,
                classCount = 1,
                reused = false,
            )
            val instrumenter = GuardedSpiderDiagnosticInstrumenter(root.resolve("cache"))

            val first = instrumenter.instrument(source, PROVIDER_CLASS)
            val second = instrumenter.instrument(source, PROVIDER_CLASS)

            assertFalse(first.reused)
            assertTrue(second.reused)
            assertNotEquals(source.path, first.path)
            assertNotEquals(sourceSha, first.artifactSha256)
            assertEquals(first.artifactSha256, second.artifactSha256)
            assertTrue(sourceBytes.contentEquals(Files.readAllBytes(sourcePath)))
            assertEquals(1, diagnosticCalls(first.path))
            assertEquals(1, providerStateCalls(first.path))
        } finally {
            deleteTree(root)
        }
    }

    private fun diagnosticCalls(path: Path): Int = JarFile(path.toFile()).use { jar ->
        val bytes = jar.getInputStream(jar.getJarEntry(PROVIDER_ENTRY)).use { it.readAllBytes() }
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        node.methods.single { it.name == "detailContent" }.instructions.asSequence().count {
            it is MethodInsnNode &&
                it.owner == "com/fongmi/tv/guardhost/GuardedProviderDiagnostics" &&
                it.name == "report"
        }
    }

    private fun providerStateCalls(path: Path): Int = JarFile(path.toFile()).use { jar ->
        val bytes = jar.getInputStream(jar.getJarEntry(PROVIDER_ENTRY)).use { it.readAllBytes() }
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        node.methods.single { it.name == "detailContent" }.instructions.asSequence().count {
            it is MethodInsnNode &&
                it.owner == "com/fongmi/tv/guardhost/GuardedProviderDiagnostics" &&
                it.name == "reportProviderState"
        }
    }

    private fun writeFixtureJar(path: Path) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry(PROVIDER_ENTRY))
            jar.write(fixtureClass())
            jar.closeEntry()
        }
    }

    private fun fixtureClass(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PROVIDER_INTERNAL, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(
            Opcodes.ACC_PUBLIC,
            "detailContent",
            "(Ljava/util/List;)Ljava/lang/String;",
            null,
            null,
        ).apply {
            val start = Label()
            val end = Label()
            val handler = Label()
            visitTryCatchBlock(start, end, handler, "java/lang/Exception")
            visitCode()
            visitLabel(start)
            visitTypeInsn(Opcodes.NEW, "java/lang/Exception")
            visitInsn(Opcodes.DUP)
            visitLdcInsn("hidden")
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Exception", "<init>", "(Ljava/lang/String;)V", false)
            visitInsn(Opcodes.ATHROW)
            visitLabel(end)
            visitLabel(handler)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitLdcInsn("")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private companion object {
        const val PROVIDER_CLASS = "com.github.catvod.spider.FixtureGuard"
        const val PROVIDER_INTERNAL = "com/github/catvod/spider/FixtureGuard"
        const val PROVIDER_ENTRY = "$PROVIDER_INTERNAL.class"
    }
}
