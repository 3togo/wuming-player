package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class GuardedSpiderPreparerTest {

    @Test
    fun preservesAndValidatesAbiRuntimeAssets() {
        val root = Files.createTempDirectory("guarded-preparer-assets-test-")
        val runtimeCalls = AtomicInteger()
        val report = GuardedSpiderPackageReport(
            sha256 = "b".repeat(64),
            compressedBytes = 10,
            dexEntry = "classes.dex",
            guardEntry = "assets/ftyshinidie.guard",
            libraries = mapOf(GuardedSpiderAbi.ARMEABI_V7A to "assets/ftyguard_v7.so"),
        )
        val preparer = GuardedSpiderPreparer(
            packageReport = report,
            producer = GuardedPayloadProducer { _, output -> Files.write(output, "payload".toByteArray()) },
            runtimeAssetsProducer = GuardedRuntimeAssetsProducer { _, library, guard ->
                runtimeCalls.incrementAndGet()
                Files.write(library, byteArrayOf(1, 2, 3))
                Files.write(guard, byteArrayOf(4, 5, 6))
            },
            converter = GuardedDexConverter { _, output -> writeClassJar(output, 1) },
            cacheRoot = root,
        )
        try {
            val first = preparer.prepare(GuardedSpiderAbi.ARMEABI_V7A)
            val second = preparer.prepare(GuardedSpiderAbi.ARMEABI_V7A)
            assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(requireNotNull(first.nativeLibrary)))
            assertArrayEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(requireNotNull(first.guardData)))
            assertTrue(second.reused)
            assertTrue(runtimeCalls.get() == 1)

            Files.write(requireNotNull(first.nativeLibrary), byteArrayOf(9))
            val rebuilt = preparer.prepare(GuardedSpiderAbi.ARMEABI_V7A)
            assertFalse(rebuilt.reused)
            assertTrue(runtimeCalls.get() == 2)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun reusesValidatedPreparationAndRebuildsCorruption() {
        val root = Files.createTempDirectory("guarded-preparer-test-")
        val producerCalls = AtomicInteger()
        val converterCalls = AtomicInteger()
        val report = GuardedSpiderPackageReport(
            sha256 = "a".repeat(64),
            compressedBytes = 10,
            dexEntry = "classes.dex",
            guardEntry = "assets/ftyshinidie.guard",
            libraries = mapOf(GuardedSpiderAbi.ARMEABI_V7A to "assets/ftyguard_v7.so"),
        )
        val preparer = GuardedSpiderPreparer(
            packageReport = report,
            producer = GuardedPayloadProducer { _, output ->
                producerCalls.incrementAndGet()
                Files.write(output, "payload".toByteArray())
            },
            converter = GuardedDexConverter { _, output ->
                converterCalls.incrementAndGet()
                writeClassJar(output, converterCalls.get())
            },
            cacheRoot = root,
        )
        try {
            val first = preparer.prepare(GuardedSpiderAbi.ARMEABI_V7A)
            val second = preparer.prepare(GuardedSpiderAbi.ARMEABI_V7A)

            assertFalse(first.reused)
            assertTrue(second.reused)
            assertTrue(first.artifactSha256 == second.artifactSha256)
            assertTrue(producerCalls.get() == 1)
            assertTrue(converterCalls.get() == 1)

            Files.write(first.path, "corrupt".toByteArray())
            val rebuilt = preparer.prepare(GuardedSpiderAbi.ARMEABI_V7A)

            assertFalse(rebuilt.reused)
            assertNotEquals(first.artifactSha256, rebuilt.artifactSha256)
            assertTrue(producerCalls.get() == 2)
            assertTrue(converterCalls.get() == 2)
        } finally {
            deleteTree(root)
        }
    }

    private fun writeClassJar(path: Path, generation: Int) {
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry("fixture/Spider.class"))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), generation.toByte()))
            jar.closeEntry()
        }
    }
}
