package com.wuming.tv.core.runtime.guardspider

import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Dex2JarShortArrayRepairTest {
    @Test
    fun restoresDecodedTableWhenGeneratedCopyIsReversed() = fixture(reversed = true) { root ->
        URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { original ->
            assertArrayEquals(shortArrayOf(0, 0), table(original))
        }
        val bytes = Files.readAllBytes(root.resolve("ArrayFixture.class"))
        GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { repaired ->
            assertArrayEquals(shortArrayOf(1, 2), table(repaired))
        }
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("ArrayFixture.class")))
    }

    @Test
    fun preservesCorrectlyOrderedGeneratedCopies() = fixture(reversed = false) { root ->
        GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use {
            assertArrayEquals(shortArrayOf(1, 2), table(it))
        }
    }

    @Test
    fun leavesOrdinaryArrayCopiesAlone() = fixture(reversed = true, generated = false) { root ->
        GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use {
            assertArrayEquals(shortArrayOf(0, 0), table(it))
        }
    }

    @Test
    fun doesNotGuessWhenEncodedLengthDiffers() = fixture(reversed = true, hex = "0100") { root ->
        GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use {
            assertArrayEquals(shortArrayOf(0, 0), table(it))
        }
    }

    private fun table(loader: ClassLoader) = loader.loadClass("ArrayFixture").getField("TABLE").get(null) as ShortArray

    private fun fixture(
        reversed: Boolean,
        generated: Boolean = true,
        hex: String = "01000200",
        check: (java.nio.file.Path) -> Unit,
    ) {
        val root = Files.createTempDirectory("dex2jar-array-test-")
        try {
            val decoder = if (generated) "${'$'}d2j${'$'}hex${'$'}fixture${'$'}decode_S" else "decode"
            val decoded = "$decoder(\"$hex\")"
            val source = root.resolve("ArrayFixture.java")
            Files.writeString(source, """
                public class ArrayFixture {
                    public static final short[] TABLE;
                    static {
                        short[] target = new short[2];
                        System.arraycopy(${if (reversed) "target, 0, $decoded" else "$decoded, 0, target"}, 0, 2);
                        TABLE = target;
                    }
                    private static short[] $decoder(String value) { return new short[] {1, 2}; }
                }
            """.trimIndent())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", root.toString(), source.toString()))
            check(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
