package com.wuming.android.tv.api.loader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Build;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.wuming.android.tv.App;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.InvocationTargetException;

import dalvik.system.DexClassLoader;

/** Opt-in loader/home diagnostic using a staged jar; no playback or account mutations. */
@RunWith(AndroidJUnit4.class)
public class NativeProviderLoaderInstrumentedTest {
    @Test
    public void guardedProviderInitializesBeforeSpiderConstruction() throws Exception {
        Assume.assumeTrue("Requires an explicitly staged provider fixture",
                "true".equals(InstrumentationRegistry.getArguments().getString("native_loader_probe")));
        File directory = new File(App.get().getCacheDir(), "native-loader-probe");
        File jar = new File(directory, "provider.jar");
        assertTrue("Stage cache/native-loader-probe/provider.jar first", jar.isFile());
        assertTrue(jar.setReadOnly());
        DexClassLoader loader = new DexClassLoader(jar.getPath(), directory.getPath(),
                directory.getPath(), App.get().getClassLoader());
        Class<?> init = loader.loadClass("com.github.catvod.spider.Init");
        Log.i("NativeLoaderProbe", "sdk=" + Build.VERSION.SDK_INT + " abi=" + Build.CPU_ABI);
        try {
            init.getMethod("init", android.content.Context.class).invoke(null, App.get());
            if (init.getMethod("loader").invoke(null) == null
                    && "true".equals(InstrumentationRegistry.getArguments().getString("native_loader_recovery"))) {
                assertTrue("Verified payload recovery must succeed", GuardedProviderRecovery.recover(loader, App.get(), jar));
            }
            Object nested = init.getMethod("loader").invoke(null);
            Log.i("NativeLoaderProbe", "nestedLoader=" + (nested == null ? "null" : nested.getClass().getName()));
            assertNotNull("Native provider initialization returned a null nested loader", nested);
            String provider = InstrumentationRegistry.getArguments()
                    .getString("native_loader_spider", "DouDouGuard");
            assertTrue(provider.matches("[A-Za-z0-9_]+"));
            com.github.catvod.crawler.Spider spider = (com.github.catvod.crawler.Spider) loader.loadClass("com.github.catvod.spider." + provider)
                    .getDeclaredConstructor().newInstance();
            assertNotNull(spider);
            Log.i("NativeLoaderProbe", provider + " constructed successfully");
            spider.init(App.get(), "");
            String home = spider.homeContent(false);
            org.json.JSONObject result = new org.json.JSONObject(home);
            assertTrue("Provider must return a home response", result.has("class") || result.has("list"));
            Log.i("NativeLoaderProbe", provider + " init and homeContent returned valid JSON");
        } catch (InvocationTargetException failure) {
            Log.e("NativeLoaderProbe", "Provider initialization threw", failure.getCause());
            throw failure;
        }
    }
}
