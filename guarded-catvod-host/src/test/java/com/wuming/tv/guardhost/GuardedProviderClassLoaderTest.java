package com.wuming.tv.guardhost;

import com.wuming.tv.core.runtime.guardspider.GuardedProviderClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.tools.ToolProvider;
import okhttp3.Headers;
import okhttp3.Request;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuardedProviderClassLoaderTest {
    @Test
    public void adaptsPrecompiledRequestAndMapHeadersWithoutRelaxingOtherHeaders() throws Exception {
        Path root = Files.createTempDirectory("provider-header-linkage-");
        try {
            Path source = root.resolve("ProviderFixture.java");
            Files.writeString(source, """
                import java.util.Map;
                import okhttp3.Headers;
                import okhttp3.Request;
                public class ProviderFixture {
                    public static String request(String name, String value) {
                        return new Request.Builder().url("https://fixture.example/")
                            .header(name, value).build().header(name);
                    }
                    public static String headers(String name, String value) {
                        return new Headers.Builder().add(name, value).set(name, value).build().get(name);
                    }
                    public static String map(Map<String, String> values) {
                        return Headers.of(values).get("User-Agent");
                    }
                }
                """);
            String classpath = Request.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                    + java.io.File.pathSeparator
                    + kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-classpath", classpath, "-d", root.toString(), source.toString()));
            byte[] original = Files.readAllBytes(root.resolve("ProviderFixture.class"));
            try (GuardedProviderClassLoader loader = new GuardedProviderClassLoader(
                    new URL[]{root.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> fixture = loader.loadClass("ProviderFixture");
                for (String method : new String[]{"request", "headers"}) {
                    assertEquals("TVBox Desktop", fixture.getMethod(method, String.class, String.class)
                            .invoke(null, "User-Agent", "১১TVBox Desktopौौ"));
                    InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                            () -> fixture.getMethod(method, String.class, String.class)
                                    .invoke(null, "Authorization", "秘密"));
                    assertTrue(failure.getCause() instanceof IllegalArgumentException);
                    assertThrows(InvocationTargetException.class,
                            () -> fixture.getMethod(method, String.class, String.class)
                                    .invoke(null, "User-Agent", "字\r\nX-Injected: yes"));
                }
                Map<String, String> values = Map.of("User-Agent", "১১TVBox Desktopौौ");
                assertEquals("TVBox Desktop", fixture.getMethod("map", Map.class).invoke(null, values));
                assertEquals("১১TVBox Desktopौौ", values.get("User-Agent"));
            }
            assertArrayEquals(original, Files.readAllBytes(root.resolve("ProviderFixture.class")));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
