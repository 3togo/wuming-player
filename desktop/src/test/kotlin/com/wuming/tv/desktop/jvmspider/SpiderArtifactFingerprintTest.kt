package com.wuming.tv.desktop.jvmspider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class SpiderArtifactFingerprintTest {

    @Test
    fun unknownPayloadCannotBeMistakenForPlainDex() {
        for (payload in listOf("woshinidie.bin", "wexshinidie.guard", "FishGuard-v7.so")) {
            val artifact = Files.createTempFile("unknown-payload-", ".png")
            try {
                JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
                    for (name in listOf("classes.dex", "assets/$payload")) {
                        jar.putNextEntry(JarEntry(name)); jar.write(byteArrayOf(1)); jar.closeEntry()
                    }
                }
                assertEquals(SpiderArtifactKind.UNKNOWN, SpiderArtifactFingerprinter.inspect(artifact).kind)
            } finally { Files.deleteIfExists(artifact) }
        }
    }

    @Test
    fun fingerprintsGuardedPackageWithoutPreparingOrExecutingIt() {
        val artifact = Files.createTempFile("spider-fingerprint-", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(artifact)).use { jar ->
                listOf(
                    "classes.dex" to "dex\n035\u0000".toByteArray(),
                    "assets/ftyshinidie.guard" to byteArrayOf(1),
                    "assets/ftyguard_v7.so" to byteArrayOf(2),
                ).forEach { (name, content) ->
                    jar.putNextEntry(JarEntry(name))
                    jar.write(content)
                    jar.closeEntry()
                }
            }

            val result = SpiderArtifactFingerprinter.inspect(artifact)

            assertEquals(SpiderArtifactKind.GUARDED_DEX_PACKAGE, result.kind)
            assertEquals(listOf("armeabi-v7a"), result.supportedAbis)
            assertTrue(result.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(result.bytes > 0)
        } finally {
            Files.deleteIfExists(artifact)
        }
    }
}
