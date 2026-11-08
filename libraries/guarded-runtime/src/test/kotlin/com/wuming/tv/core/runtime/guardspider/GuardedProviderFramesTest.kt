package com.wuming.tv.core.runtime.guardspider

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

class GuardedProviderFramesTest {
    @Test fun repairsMissingFramesForBranchesAndExceptionHandlersWithoutRewritingApprovedBytes() = fixture { root ->
        val original = Files.readAllBytes(root.resolve("FrameFixture.class"))
        URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { loader ->
            assertThrows(VerifyError::class.java) { Class.forName("FrameFixture", true, loader) }
        }
        GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { loader ->
            val type = Class.forName("FrameFixture", true, loader)
            val guarded = type.getMethod("guarded", Boolean::class.javaPrimitiveType)
            assertEquals(17, guarded.invoke(null, false))
            assertEquals(23, guarded.invoke(null, true))
        }
        assertArrayEquals(original, Files.readAllBytes(root.resolve("FrameFixture.class")))
    }

    @Test fun resolvesSiblingAndSelfHierarchiesWithoutInitializingUnusedClasses() = fixture { root ->
        System.clearProperty(POISON)
        try {
            repeat(2) {
                GuardedProviderClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { loader ->
                    val type = Class.forName("FrameFixture", true, loader)
                    assertEquals("FrameFixture\$Left", type.getMethod("choose", Boolean::class.javaPrimitiveType)
                        .invoke(null, true).javaClass.name)
                    assertEquals("FrameFixture\$Child", type.getMethod("self", Boolean::class.javaPrimitiveType)
                        .invoke(null, false).javaClass.name)
                    assertNull("Hierarchy resolution initialized an unused provider class", System.getProperty(POISON))
                }
            }
        } finally { System.clearProperty(POISON) }
    }

    private fun fixture(check: (Path) -> Unit) {
        val root = Files.createTempDirectory("provider-frames-")
        try {
            val source = root.resolve("FrameFixture.java")
            Files.writeString(source, """
                public class FrameFixture {
                    public static class Base {}
                    public static class Left extends Base {}
                    public static class Right extends Base {
                        static { System.setProperty("$POISON", "initialized"); }
                    }
                    public static class Child extends FrameFixture {}
                    public static Base choose(boolean first) {
                        Base value;
                        if (first) value = new Left(); else value = new Right();
                        return value;
                    }
                    public static FrameFixture self(boolean first) {
                        FrameFixture value;
                        if (first) value = new FrameFixture(); else value = new Child();
                        return value;
                    }
                    public static int guarded(boolean fail) {
                        try { if (fail) throw new IllegalArgumentException(); return 17; }
                        catch (IllegalArgumentException error) { return 23; }
                    }
                }
            """.trimIndent())
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-d", root.toString(), source.toString()))
            Files.list(root).use { paths -> paths.filter { it.toString().endsWith(".class") }.forEach { path ->
                val writer = ClassWriter(0)
                ClassReader(Files.readAllBytes(path)).accept(writer, ClassReader.SKIP_FRAMES)
                Files.write(path, writer.toByteArray())
            } }
            check(root)
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private companion object { const val POISON = "wuming.test.unusedProviderInitialized" }
}
