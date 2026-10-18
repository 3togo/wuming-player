package com.wuming.android.tv.api.loader;

import android.content.Context;

import com.wuming.android.tv.App;
import com.wuming.android.tv.utils.Download;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.tv.core.source.SpiderDigest;
import com.wuming.tv.core.source.SpiderDigestKind;
import com.wuming.tv.core.source.SpiderLocation;
import com.wuming.tv.core.source.SpiderReference;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Hex;

public class JarLoader {
    private static final String TAG = "JarLoader";

    private static final int MAX_LOADERS = 10;
    private static final int MAX_SPIDERS = 50;

    private final ConcurrentHashMap<String, DexClassLoader> loaders;
    private final ConcurrentHashMap<String, Method> methods;
    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile String recent;

    public JarLoader() {
        loaders = new ConcurrentHashMap<>();
        methods = new ConcurrentHashMap<>();
        spiders = new ConcurrentHashMap<>();
    }

    public synchronized void clear() {
        // Do not destroy spiders here. Provider calls run on background threads and
        // may still be using an instance after it has been handed to SiteApi.
        // Dropping the cache references lets a later request rebuild safely without
        // invalidating an in-flight native provider.
        loaders.clear();
        methods.clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    private void evictLoaders() {
        if (loaders.size() <= MAX_LOADERS) return;
        String oldest = null;
        for (String key : loaders.keySet()) {
            if (!key.equals(recent)) {
                oldest = key;
                break;
            }
        }
        if (oldest != null) {
            loaders.remove(oldest);
            methods.remove(oldest);
            // Drop cache references only; an in-flight request may still own one.
            evictSpidersForLoader(oldest);
        }
    }

    private void evictSpidersForLoader(String loaderKey) {
        spiders.keySet().removeIf(key -> key.startsWith(loaderKey + "|"));
    }

    private void evictSpiders() {
        if (spiders.size() <= MAX_SPIDERS) return;
        String oldest = null;
        for (String key : spiders.keySet()) {
            oldest = key;
            break;
        }
        if (oldest != null) {
            // Removal must not destroy a spider which SiteApi may still be using.
            spiders.remove(oldest);
        }
    }

    private boolean invokeInit(DexClassLoader loader, File file) {
        try {
            initializeProvider(loader, App.get());
            return true;
        } catch (Throwable e) {
            try {
                if (e instanceof IllegalStateException
                        && "Provider Init.loader() is null after initialization".equals(e.getMessage())
                        && GuardedProviderRecovery.recover(loader, App.get(), file)) {
                    AppLog.i(TAG, "Recovered verified guarded provider payload with read-only code");
                    return true;
                }
            } catch (Throwable recoveryError) {
                e.addSuppressed(recoveryError);
            }
            AppLog.e(TAG, e);
            return false;
        }
    }

    static void initializeProvider(ClassLoader loader, Context context) throws ReflectiveOperationException {
        Class<?> init;
        try {
            init = loader.loadClass("com.github.catvod.spider.Init");
        } catch (ClassNotFoundException optional) {
            return; // Older providers do not expose an initialization hook.
        }
        init.getMethod("init", Context.class).invoke(null, context);

        // Guarded jars can swallow native initialization errors and return from
        // init() with a null nested loader. Calling their spider constructor or
        // proxy then aborts in JNI, beyond the protection of a Java catch block.
        // Validate the optional public loader contract before publishing the jar.
        Method accessor;
        try {
            accessor = init.getMethod("loader");
        } catch (NoSuchMethodException optional) {
            return;
        }
        if (Modifier.isStatic(accessor.getModifiers())
                && ClassLoader.class.isAssignableFrom(accessor.getReturnType())
                && accessor.invoke(null) == null) {
            throw new IllegalStateException("Provider Init.loader() is null after initialization");
        }
    }

    private void invokeProxy(String key, DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            Method method = clz.getMethod("proxy", Map.class);
            methods.put(key, method);
        } catch (Throwable e) {
            AppLog.e(TAG, e);
        }
    }

    private DexClassLoader loadInternal(String key, File file) {
        try {
            if (Thread.currentThread().isInterrupted()) return null;
            if (file == null || !Path.exists(file) || !file.setReadOnly()) return null;
            String cachePath = Path.jar().getAbsolutePath();
            DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.get().getClassLoader());
            // A provider whose Init hook failed is not safe to enter. In
            // particular, native providers may otherwise receive a null internal
            // class loader and abort the whole process instead of throwing.
            if (!invokeInit(loader, file)) return null;
            invokeProxy(key, loader);
            return loader;
        } catch (Throwable e) {
            AppLog.e(TAG, e);
            clearInvalidCache(file);
            return null;
        }
    }

    private void clearInvalidCache(File file) {
        // A jar that failed to dex is unusable. Drop it when it lives in the jar
        // cache so the next request re-downloads instead of reusing the corrupt
        // copy. Never delete files outside the cache (e.g. file:// provider jars).
        try {
            if (file == null || file.getParentFile() == null) return;
            if (file.getParentFile().equals(Path.jar())) Path.clear(file);
        } catch (Exception ignored) {
            // File may be in use or already deleted; no action needed.
        }
    }

    public synchronized void parseJar(String key, String jar) {
        if (jar == null || jar.isEmpty()) return;
        if (loaders.containsKey(key)) return;
        if (jar.startsWith("assets")) jar = UrlUtil.convert(jar);
        final String jarUrl = jar;

        DexClassLoader loader = null;
        try {
            SpiderReference reference = SpiderLocation.parse(jarUrl);
            if (reference == null) return;
            String jarPath = reference.getLocation();
            SpiderDigest expected = resolveDigest(reference.getDigest());
            ProviderJarCache cache = new ProviderJarCache(Path.jar(), Util.md5(jarUrl));

            if (UrlUtil.isHttp(jarPath)) {
                loader = loadRemote(key, jarPath, expected, cache);
            } else if (jarPath.startsWith("file")) {
                File local = Path.local(jarPath);
                if (cache.accepts(local, expected)) loader = loadInternal(key, local);
            }
        } catch (Exception e) {
            AppLog.e(TAG, e);
        }

        if (loader != null) {
            loaders.putIfAbsent(key, loader);
            evictLoaders();
        }
    }

    private DexClassLoader loadRemote(String key, String location, SpiderDigest expected, ProviderJarCache cache) {
        DexClassLoader loader = null;
        File candidate = Path.jar(location);
        try {
            if (expected != null && expected.getKind() == SpiderDigestKind.MD5 &&
                    Util.equals(location, expected.getValue()) && cache.accepts(candidate, expected)) {
                loader = loadInternal(key, candidate);
            } else {
                candidate = Download.create(location, candidate).get();
                if (cache.accepts(candidate, expected)) loader = loadInternal(key, candidate);
                else AppLog.w(TAG, "Downloaded provider JAR failed integrity verification");
            }
            if (loader != null) {
                try { cache.promote(candidate, expected); }
                catch (Exception backupError) { AppLog.e(TAG, backupError); }
                return loader;
            }
        } catch (Exception error) {
            AppLog.e(TAG, error);
        }
        File previous = cache.recover(expected);
        if (previous == null) return null;
        AppLog.i(TAG, "Trying initialized provider JAR cache after candidate failure");
        return loadInternal(key, previous);
    }

    private static SpiderDigest resolveDigest(SpiderDigest digest) {
        if (digest == null || !digest.isRemote()) return digest;
        return new SpiderDigest(digest.getKind(), OkHttp.string(digest.getValue()).trim());
    }

    public synchronized DexClassLoader dex(String jar) {
        try {
            String jaKey = Util.md5(jar);
            parseJar(jaKey, jar);
            return loaders.get(jaKey);
        } catch (Throwable e) {
            AppLog.e(TAG, e);
            return null;
        }
    }

    static String spiderKey(String jarKey, String key, String api, String ext) {
        return jarKey + "|" + key + "|" + api + "|" + digest(ext);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return Hex.encodeLower(bytes);
        } catch (Exception e) {
            // SHA-256 is mandatory on Android. Keep a deterministic fallback for
            // unusual runtimes instead of losing provider cache isolation.
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    public synchronized Spider getSpider(String key, String api, String ext, String jar) {
        if (jar == null || jar.isEmpty()) return new SpiderNull();
        String jaKey = Util.md5(jar);
        String spKey = spiderKey(jaKey, key, api, ext);
        Spider existing = spiders.get(spKey);
        if (existing != null) return existing;
        try {
            parseJar(jaKey, jar);
            DexClassLoader loader = loaders.get(jaKey);
            if (loader == null) return new SpiderNull();
            if (api == null || !api.startsWith("csp_") || api.length() == 4) return new SpiderNull();
            Spider spider = createSpider(loader, App.get(), key, api, ext);
            spiders.put(spKey, spider);
            evictSpiders();
            return spider;
        } catch (Throwable e) {
            AppLog.e(TAG, e);
            return new SpiderNull();
        }
    }

    // Keep construction and initialization in one boundary: returning a wrapper
    // alone does not prove its implementation exists or is usable.
    static Spider createSpider(ClassLoader loader, Context context, String key, String api, String ext)
            throws Exception {
        Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + api.substring(4))
                .getDeclaredConstructor().newInstance();
        spider.siteKey = com.wuming.tv.core.source.packages.PackageSourceIdentity.original(key);
        spider.init(context, ext);
        return spider;
    }

    private synchronized DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    public synchronized JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Json" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
        return (JSONObject) method.invoke(null, jxs, url);
    }

    public synchronized JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Class<?> clz = requireRecentLoader().loadClass("com.github.catvod.parser.Mix" + key);
        Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
        return (JSONObject) method.invoke(null, jxs, name, flag, url);
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        Method method = recent != null ? methods.get(recent) : null;
        Object[] result = proxyInvoke(method, params);
        if (result != null) return result;
        return tryOthers(params);
    }

    private Object[] tryOthers(Map<String, String> p) {
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            if (entry.getKey().equals(recent)) continue;
            Object[] result = proxyInvoke(entry.getValue(), p);
            if (result != null) return result;
        }
        return null;
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            AppLog.e(TAG, e);
            return null;
        }
    }
}
