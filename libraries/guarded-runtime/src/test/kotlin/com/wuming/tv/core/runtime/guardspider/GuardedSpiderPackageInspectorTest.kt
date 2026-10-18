package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class GuardedSpiderPackageInspectorTest {

    @Test
    fun verifiesAndExtractsBothArmArchitectures() {
        val artifact = packageWith(
            "classes.dex" to "dex\n035\u0000fixture".toByteArray(),
            "assets/ftyguard_v7.so" to byteArrayOf(7, 7),
            "assets/ftyguard_v8.so" to byteArrayOf(8, 8),
            "assets/ftyshinidie.guard" to byteArrayOf(1, 2, 3),
        )
        try {
            val report = GuardedSpiderPackageInspector.inspect(artifact, sha256(artifact))

            assertEquals(setOf(GuardedSpiderAbi.ARMEABI_V7A, GuardedSpiderAbi.ARM64_V8A), report.supportedAbis)
            GuardedSpiderPackageInspector.extract(artifact, report, GuardedSpiderAbi.ARMEABI_V7A).use { extraction ->
                assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(extraction.library))
                assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(extraction.guard))
                assertTrue(extraction.library.startsWith(extraction.root))
            }
        } finally {
            Files.deleteIfExists(artifact)
        }
    }

    @Test(expected = UnsupportedGuardedSpiderPackage::class)
    fun rejectsPackagesWithoutGuardPayload() {
        val artifact = packageWith(
            "classes.dex" to "dex\n035\u0000fixture".toByteArray(),
            "assets/ftyguard_v7.so" to byteArrayOf(7),
        )
        try {
            GuardedSpiderPackageInspector.inspect(artifact, sha256(artifact))
        } finally {
            Files.deleteIfExists(artifact)
        }
    }

    @Test(expected = UnsupportedGuardedSpiderPackage::class)
    fun rejectsUntrustedDigest() {
        val artifact = packageWith(
            "classes.dex" to "dex\n035\u0000fixture".toByteArray(),
            "assets/ftyguard_v7.so" to byteArrayOf(7),
            "assets/ftyshinidie.guard" to byteArrayOf(1),
        )
        try {
            GuardedSpiderPackageInspector.inspect(artifact, "0".repeat(64))
        } finally {
            Files.deleteIfExists(artifact)
        }
    }

    private fun packageWith(vararg entries: Pair<String, ByteArray>): Path {
        val path = Files.createTempFile("guarded-spider-test-", ".jar")
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            entries.forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        return path
    }

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
}
