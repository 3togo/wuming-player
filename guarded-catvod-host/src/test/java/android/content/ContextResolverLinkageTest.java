package android.content;

import android.app.Application;
import android.provider.Settings;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextResolverLinkageTest {
    @Test
    public void linksProviderContextAndBuildIdentityDescriptors() throws Exception {
        Path root = Files.createTempDirectory("context-resolver-linkage-");
        try {
            Path stub = root.resolve("android/content/Context.java");
            Files.createDirectories(stub.getParent());
            Files.writeString(stub, """
                package android.content;
                public class Context {
                    public ContentResolver getContentResolver() { return null; }
                }
                """);
            Path build = root.resolve("android/os/Build.java");
            Files.createDirectories(build.getParent());
            // Keep the compile-time field non-final so the consumer emits GETSTATIC.
            Files.writeString(build, """
                package android.os;
                public class Build {
                    public static String SERIAL, MANUFACTURER, PRODUCT, DEVICE, BOARD, HARDWARE, ID, CPU_ABI2;
                }
                """);
            Path provider = root.resolve("ProviderFixture.java");
            Files.writeString(provider, """
                import android.content.Context;
                import android.content.ContentResolver;
                public class ProviderFixture {
                    public static String serial() { return android.os.Build.SERIAL; }
                    public static String[] identity() {
                        return new String[] { android.os.Build.MANUFACTURER, android.os.Build.PRODUCT,
                            android.os.Build.DEVICE, android.os.Build.BOARD, android.os.Build.HARDWARE,
                            android.os.Build.ID, android.os.Build.CPU_ABI2 };
                    }
                    public static ContentResolver resolver(Context context) {
                        return context.getContentResolver();
                    }
                }
                """);
            String classpath = ContentResolver.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-classpath", classpath, "-d", root.toString(), stub.toString(), build.toString(), provider.toString()));
            try (URLClassLoader loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader())) {
                Class<?> fixture = loader.loadClass("ProviderFixture");
                assertEquals("unknown", fixture.getMethod("serial").invoke(null));
                String[] identity = (String[]) fixture.getMethod("identity").invoke(null);
                assertEquals(7, identity.length);
                for (String value : identity) assertNotNull(value);
                assertEquals("", identity[6]);
                var method = fixture.getMethod("resolver", Context.class);
                Context context = new Application(root.resolve("data").toFile());
                Object resolver = method.invoke(null, context);
                assertNotNull(resolver);
                assertSame(resolver, method.invoke(null, context.getApplicationContext()));
                assertNotSame(resolver, method.invoke(null, new Context(root.resolve("other").toFile())));
                var getter = Context.class.getMethod("getContentResolver");
                assertEquals(ContentResolver.class, getter.getReturnType());
                assertTrue(Modifier.isPublic(getter.getModifiers()));
                assertFalse(Modifier.isStatic(getter.getModifiers()));
                assertFalse(Modifier.isFinal(getter.getModifiers()));
                assertNull(Settings.Secure.getString((ContentResolver) resolver, Settings.Secure.ANDROID_ID));
                assertEquals(7, Settings.Global.getInt((ContentResolver) resolver, "missing", 7));
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
