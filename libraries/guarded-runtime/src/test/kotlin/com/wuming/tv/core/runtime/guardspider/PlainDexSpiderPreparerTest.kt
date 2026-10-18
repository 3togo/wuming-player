package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainDexSpiderPreparerTest {

    @Test
    fun convertsOnlyOnceAndReusesThePreparedJar() {
        val packageJar = tempJar("classes.dex" to "dex\n035\u0000fixture".toByteArray())
        val conversions = AtomicInteger()
        val cache = Files.createTempDirectory("plain-dex-cache-")
        val converter = GuardedDexConverter { _, output -> conversions.incrementAndGet(); classJar(output) }
        val expected = sha256(packageJar)

        val first = PlainDexSpiderPreparer.prepare(packageJar, expected, converter, cache)
        val second = PlainDexSpiderPreparer.prepare(packageJar, expected, converter, cache)

        assertEquals(1, conversions.get())
        assertEquals(1, first.classCount)
        assertEquals(first.artifactSha256, second.artifactSha256)
        assertFalse(first.reused)
        assertTrue(second.reused)
        assertEquals(null, first.nativeLibrary)
        assertEquals(null, first.guardData)
    }

    @Test
    fun rejectsPackagesThatAreNotPlainSingleDexSpiders() {
        val expected = "0".repeat(64)
        val withoutDex = tempJar("demo/Spider.class" to byteArrayOf(1))
        val guarded = tempJar(
            "classes.dex" to "dex\n035\u0000".toByteArray(),
            "assets/ftyshinidie.guard" to byteArrayOf(1),
        )
        val native = tempJar(
            "classes.dex" to "dex\n035\u0000".toByteArray(),
            "lib/armeabi-v7a/libftyguard_v7.so" to byteArrayOf(2),
        )
        val cache = Files.createTempDirectory("plain-dex-cache-")
        val converter = GuardedDexConverter { _, output -> classJar(output) }

        listOf(withoutDex, guarded, native).forEach { artifact ->
            assertThrows(IllegalArgumentException::class.java) {
                PlainDexSpiderPreparer.prepare(artifact, sha256(artifact), converter, cache)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlainDexSpiderPreparer.prepare(tempJar("classes.dex" to byteArrayOf(1)), expected, converter, cache)
        }
    }

    private fun tempJar(vararg entries: Pair<String, ByteArray>): Path {
        val path = Files.createTempFile("plain-dex-spider-", ".jar")
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, content) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(content)
                jar.closeEntry()
            }
        }
        return path
    }

    private fun classJar(target: Path) {
        target.parent?.let(Files::createDirectories)
        JarOutputStream(Files.newOutputStream(target)).use { jar ->
            jar.putNextEntry(JarEntry("com/github/catvod/spider/Demo.class"))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            jar.closeEntry()
        }
    }
}
