package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.Assert.*
import org.junit.Test

class GuardedDexPayloadTest {
    @Test
    fun extractionFindsNewJarOutput() = fixture { root ->
        val jar = archive(root, "config.jar")
        val extraction = GuardedSpiderExtraction(root, root, root, root, root, GuardedSpiderAbi.ARMEABI_V7A)
        assertEquals(jar, extraction.decryptedDex)
    }

    @Test
    fun acceptsRawDexAndArchivesUnderBothSupportedNames() = fixture { root ->
        for (name in listOf("config.db", "config.jar")) {
            val jar = archive(root, name)
            assertEquals(jar, GuardedDexPayload.find(root))
            Files.write(jar, "dex\n035\u0000fixture".toByteArray())
            assertEquals(jar, GuardedDexPayload.find(root))
            Files.delete(jar)
        }
    }

    @Test
    fun prefersLegacyOutputWhenBothAreValid() = fixture { root ->
        archive(root, "config.jar")
        val legacy = archive(root, "config.db")
        assertEquals(legacy, GuardedDexPayload.find(root))
    }

    @Test
    fun ignoresInvalidLegacyOutputWhenJarIsValid() = fixture { root ->
        val legacy = archive(root, "config.db", "NotDex.class")
        val modern = archive(root, "config.jar")
        for (invalid in listOf(byteArrayOf(), "dex".toByteArray(), "PKxxbroken".toByteArray())) {
            Files.write(legacy, invalid)
            assertEquals(modern, GuardedDexPayload.find(root))
        }
        archive(root, "config.db", "NotDex.class")
        assertEquals(modern, GuardedDexPayload.find(root))
    }

    @Test
    fun rejectsMissingDirectoriesMalformedFilesAndNonDexArchives() = fixture { root ->
        assertNull(GuardedDexPayload.find(root))
        val legacy = GuardedDexPayload.expectedOutput(root)
        Files.createDirectories(legacy)
        assertNull(GuardedDexPayload.find(root))
        Files.delete(legacy)
        archive(root, "config.db", "NotDex.class")
        assertNull(GuardedDexPayload.find(root))
        Files.write(legacy, "PKxxbroken".toByteArray())
        assertNull(GuardedDexPayload.find(root))
    }

    @Test
    fun doesNotScanUnrelatedPathsOrModifyPayloadBytes() = fixture { root ->
        val unrelated = archive(root, "other.jar")
        val bytes = Files.readAllBytes(unrelated)
        assertNull(GuardedDexPayload.find(root))
        val modern = archive(root, "config.jar", "CLASSES.DEX")
        val modernBytes = Files.readAllBytes(modern)
        assertEquals(modern, GuardedDexPayload.find(root))
        assertArrayEquals(bytes, Files.readAllBytes(unrelated))
        assertArrayEquals(modernBytes, Files.readAllBytes(modern))
    }

    @Test
    fun missingOutputRetainsExpectedLegacyPath() = fixture { root ->
        val extraction = GuardedSpiderExtraction(root, root, root, root, root, GuardedSpiderAbi.ARMEABI_V7A)
        assertEquals(root.resolve("code-cache/sharedb/config.db"), extraction.decryptedDex)
    }

    private fun archive(root: Path, name: String, entry: String = "classes.dex"): Path {
        val path = root.resolve("code-cache/sharedb/$name")
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            jar.putNextEntry(JarEntry(entry)); jar.write("dex\n035\u0000fixture".toByteArray()); jar.closeEntry()
        }
        return path
    }

    private fun fixture(check: (Path) -> Unit) {
        val root = Files.createTempDirectory("guarded-payload-test-")
        try { check(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
