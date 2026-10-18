package com.wuming.android.tv.api.loader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class JarLoaderTest {

    @Test
    public void spiderKeyIsStableForSameProviderConfiguration() {
        assertEquals(
                JarLoader.spiderKey("jar", "site", "csp_Test", "{\"token\":\"one\"}"),
                JarLoader.spiderKey("jar", "site", "csp_Test", "{\"token\":\"one\"}"));
    }

    @Test
    public void spiderKeyChangesWithEveryProviderIdentityComponent() {
        String base = JarLoader.spiderKey("jar", "site", "csp_Test", "{\"token\":\"one\"}");

        assertNotEquals(base, JarLoader.spiderKey("otherJar", "site", "csp_Test", "{\"token\":\"one\"}"));
        assertNotEquals(base, JarLoader.spiderKey("jar", "otherSite", "csp_Test", "{\"token\":\"one\"}"));
        assertNotEquals(base, JarLoader.spiderKey("jar", "site", "csp_Other", "{\"token\":\"one\"}"));
        assertNotEquals(base, JarLoader.spiderKey("jar", "site", "csp_Test", "{\"token\":\"two\"}"));
    }

    private static ClassLoader provider(Class<?> init) {
        return new ClassLoader(JarLoaderTest.class.getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.github.catvod.spider.Init")) {
                    if (init == null) throw new ClassNotFoundException(name);
                    return init;
                }
                return super.loadClass(name);
            }
        };
    }

    public static class LegacyInit {
        public static void init(android.content.Context context) {}
    }

    public static class ReadyInit {
        private static ClassLoader nested;
        public static void init(android.content.Context context) {
            nested = ReadyInit.class.getClassLoader();
        }
        public static ClassLoader loader() { return nested; }
    }

    public static class BrokenInit extends LegacyInit {
        public static ClassLoader loader() { return null; }
    }

    public static class ThrowingInit {
        public static void init(android.content.Context context) {
            throw new IllegalStateException("initialization failed");
        }
    }

    @Test
    public void missingOptionalInitRemainsSupported() throws Exception {
        JarLoader.initializeProvider(provider(null), null);
    }

    @Test
    public void legacyInitWithoutNestedLoaderRemainsSupported() throws Exception {
        JarLoader.initializeProvider(provider(LegacyInit.class), null);
    }

    @Test
    public void nestedLoaderIsCheckedAfterInitialization() throws Exception {
        ReadyInit.nested = null;
        JarLoader.initializeProvider(provider(ReadyInit.class), null);
    }

    @Test(expected = IllegalStateException.class)
    public void silentNativeInitializationFailureCannotPublishProvider() throws Exception {
        JarLoader.initializeProvider(provider(BrokenInit.class), null);
    }

    @Test(expected = java.lang.reflect.InvocationTargetException.class)
    public void thrownInitializationFailureCannotPublishProvider() throws Exception {
        JarLoader.initializeProvider(provider(ThrowingInit.class), null);
    }

    private static ClassLoader spiderClass(Class<?> implementation) {
        return new ClassLoader(JarLoaderTest.class.getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("com.github.catvod.spider.Fixture")) {
                    if (implementation == null) throw new ClassNotFoundException(name);
                    return implementation;
                }
                return super.loadClass(name);
            }
        };
    }

    public static class WorkingSpider extends com.github.catvod.crawler.Spider {
        private String initializedKey;
        private String extension;
        @Override
        public void init(android.content.Context context, String ext) {
            initializedKey = siteKey;
            extension = ext;
        }
        @Override
        public String homeContent(boolean filter) {
            return initializedKey + ":" + extension;
        }
    }

    public static class EmptyDelegateSpider extends com.github.catvod.crawler.Spider {
        private com.github.catvod.crawler.Spider delegate;
        @Override
        public void init(android.content.Context context, String ext) throws Exception {
            delegate.init(context, ext); // Reproduces the constructor-only false positive.
        }
    }

    public static class ConstructorFailureSpider extends com.github.catvod.crawler.Spider {
        public ConstructorFailureSpider() { throw new IllegalStateException("fixture failure"); }
    }

    @Test
    public void initializedProviderCanServeWithItsConfiguredIdentity() throws Exception {
        com.github.catvod.crawler.Spider spider = JarLoader.createSpider(
                spiderClass(WorkingSpider.class), null, "fixture-site", "csp_Fixture", "fixture-options");
        assertEquals("fixture-site:fixture-options", spider.homeContent(false));
    }

    @Test
    public void packageHostIdentityDoesNotChangeProviderKey() throws Exception {
        String key = com.wuming.tv.core.source.packages.PackageSourceIdentity.scoped("a".repeat(32), "push_agent");
        com.github.catvod.crawler.Spider spider = JarLoader.createSpider(
                spiderClass(WorkingSpider.class), null, key, "csp_Fixture", "options");
        assertEquals("push_agent:options", spider.homeContent(false));
    }

    @Test(expected = NullPointerException.class)
    public void wrapperWithMissingDelegateIsNotReturnedAsReady() throws Exception {
        JarLoader.createSpider(spiderClass(EmptyDelegateSpider.class), null, "site", "csp_Fixture", "");
    }

    @Test(expected = ClassNotFoundException.class)
    public void missingImplementationFailsBeforePublication() throws Exception {
        JarLoader.createSpider(spiderClass(null), null, "site", "csp_Fixture", "");
    }

    @Test(expected = java.lang.reflect.InvocationTargetException.class)
    public void constructorFailureFailsBeforePublication() throws Exception {
        JarLoader.createSpider(spiderClass(ConstructorFailureSpider.class), null, "site", "csp_Fixture", "");
    }

    @Test(expected = ClassCastException.class)
    public void implementationMustMatchHostSpiderContract() throws Exception {
        JarLoader.createSpider(spiderClass(Object.class), null, "site", "csp_Fixture", "");
    }
}
