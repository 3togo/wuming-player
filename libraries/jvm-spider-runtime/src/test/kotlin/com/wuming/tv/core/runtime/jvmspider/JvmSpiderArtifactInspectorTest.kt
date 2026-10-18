package com.wuming.tv.core.runtime.jvmspider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class JvmSpiderArtifactInspectorTest {

    @Test
    fun acceptsClassOnlyJarAndVerifiesSha256() {
        val path = Files.createTempFile("portable-spider-", ".jar")
        try {
            jar(path, mapOf("demo/Spider.class" to byteArrayOf(0x01, 0x02)))
            val first = JvmSpiderArtifactInspector.inspect(path)
            val verified = JvmSpiderArtifactInspector.inspect(path, first.sha256.uppercase())
            assertEquals(1, verified.classCount)
            assertEquals(2, verified.uncompressedBytes)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsDexNativeGuardAndAndroidReferences() {
        listOf("classes.dex", "assets/ftyguard_v8.so", "assets/payload.guard").forEach { forbidden ->
            val path = Files.createTempFile("blocked-spider-", ".jar")
            try {
                jar(path, mapOf("demo/Spider.class" to byteArrayOf(1), forbidden to byteArrayOf(2)))
                val failure = runCatching { JvmSpiderArtifactInspector.inspect(path) }.exceptionOrNull()
                assertTrue(failure is UnsupportedJvmSpiderArtifact)
            } finally {
                Files.deleteIfExists(path)
            }
        }

        val android = Files.createTempFile("android-spider-", ".jar")
        try {
            jar(android, mapOf("demo/Spider.class" to "prefix android/content/Context suffix".toByteArray()))
            val failure = runCatching { JvmSpiderArtifactInspector.inspect(android) }.exceptionOrNull()
            assertTrue(failure is UnsupportedJvmSpiderArtifact)
        } finally {
            Files.deleteIfExists(android)
        }
    }

    private fun jar(path: java.nio.file.Path, entries: Map<String, ByteArray>) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
