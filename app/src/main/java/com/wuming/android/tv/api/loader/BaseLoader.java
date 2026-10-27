package com.wuming.android.tv.api.loader;

import com.wuming.android.tv.ProductCore;
import com.wuming.android.tv.api.config.LiveConfig;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.provider.ProviderClient;
import com.wuming.android.tv.api.provider.RemoteSpider;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.cloud.opendrive.OpenDriveSpider;
import com.wuming.android.tv.player.extractor.YoutubeSpider;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.DexClassLoader;

public class BaseLoader {

    private final JarLoader jarLoader;
    private final PyLoader pyLoader;
    private final JsLoader jsLoader;
    private final Set<String> interactiveCspKeys;

    private BaseLoader() {
        jarLoader = new JarLoader();
        pyLoader = new PyLoader();
        jsLoader = new JsLoader();
        interactiveCspKeys = ConcurrentHashMap.newKeySet();
    }

    public static BaseLoader get() {
        return Loader.INSTANCE;
    }

    private static boolean isJs(String api) {
        return api.contains(".js");
    }

    private static boolean isPy(String api) {
        return api.contains(".py");
    }

    private static boolean isCsp(String api) {
        return api.startsWith("csp_");
    }

    private static boolean isBuiltin(String api) {
        return api.startsWith("builtin_");
    }

    private static boolean isolateCspProviders() {
        /*
         * REGRESSION GUARDRAIL:
         * Do not unify this branch merely to "simplify" provider loading.
         * Mobile isolation contains routine provider crashes. The 32-bit
         * Leanback provider subprocess repeatedly crashes in merge.Gc.run
         * before returning home/search results, so Leanback must load locally.
         * Validate both flavors using section 7 of the regression playbook
         * before changing this routing.
         */
        return ProductCore.getDeviceType() == 1;
    }

    private final ConcurrentHashMap<String, Spider> builtinSpiders = new ConcurrentHashMap<>();

    private Spider getBuiltinSpider(String key, String api, String ext) {
        String name = api.substring("builtin_".length());
        String spKey = "builtin_" + key;
        Spider existing = builtinSpiders.get(spKey);
        if (existing != null) return existing;
        try {
            Spider spider = createBuiltinSpider(name);
            if (spider instanceof SpiderNull) return spider;
            spider.siteKey = key;
            spider.init(com.wuming.android.tv.App.get(), ext);
            builtinSpiders.put(spKey, spider);
            return spider;
        } catch (Exception e) {
            return new SpiderNull();
        }
    }

    static Spider createBuiltinSpider(String name) {
        return switch (name) {
            case "libvio" -> new com.wuming.android.tv.catalog.LibvioSpider();
            case "youtube" -> new YoutubeSpider();
            case "opendrive" -> new OpenDriveSpider();
            default -> new SpiderNull();
        };
    }

    public void clear() {
        builtinSpiders.values().forEach(Spider::destroy);
        builtinSpiders.clear();
        jarLoader.clear();
        interactiveCspKeys.clear();
        pyLoader.clear();
        jsLoader.clear();
        ProviderClient.get().clearHealth();
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        if (com.wuming.tv.core.libvio.LibvioSiteSpec.matches(api)) return getBuiltinSpider(key, "builtin_libvio", ext);
        if (isPy(api)) return pyLoader.getSpider(key, api, ext);
        else if (isJs(api)) return jsLoader.getSpider(key, api, ext, jar);
        else if (isCsp(api)) {
            // Once a provider opens interactive UI, keep its subsequent home and
            // category calls on that same local instance. Otherwise mobile would
            // read stale login/enable state from the isolated provider process.
            if (interactiveCspKeys.contains(key)) return getInteractiveSpider(key, api, ext, jar);
            return isolateCspProviders()
                    ? new RemoteSpider(key, api, ext, jar)
                    : jarLoader.getSpider(key, api, ext, jar);
        }
        else if (isBuiltin(api)) return getBuiltinSpider(key, api, ext);
        else return new SpiderNull();
    }

    /**
     * REGRESSION GUARDRAIL:
     * Do not replace this with getSpider(...) in playerContent. Cloud-drive QR
     * login requires a UI-capable process, and subsequent proxy calls must use
     * this same local provider instance.
     *
     * Player calls from external CSP providers can be interactive (cloud-drive
     * QR login, verification, etc.). Those providers need the app process,
     * where an Activity and the normal UI hooks exist. Browsing/search remains
     * in the isolated provider process so a faulty provider cannot take down
     * the UI during routine navigation.
     */
    public Spider getInteractiveSpider(String key, String api, String ext, String jar) {
        if (com.wuming.tv.core.libvio.LibvioSiteSpec.matches(api) || !isCsp(api)) return getSpider(key, api, ext, jar);
        interactiveCspKeys.add(key);
        jarLoader.setRecent(com.github.catvod.utils.Util.md5(jar));
        return jarLoader.getSpider(key, api, ext, jar);
    }

    public boolean isInteractive(String key) {
        return interactiveCspKeys.contains(key);
    }

    /**
     * Returns true when this build ships TV's bundled native Python runtime.
     * Python providers run through {@code libwuming_python.so}; Chaquopy is gone and
     * {@code -PnativePythonEnabled=false} omits the runtime assets and libraries.
     */
    public boolean isPythonAvailable() {
        try {
            com.wuming.android.tv.App.get().getAssets().open("python/runtime.json").close();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public Spider getSpider(String key) {
        Site site = VodConfig.get().getSite(key);
        Live live = LiveConfig.get().getLive(key);
        if (!site.isEmpty()) return site.spider();
        if (!live.isEmpty()) return live.spider();
        return new SpiderNull();
    }

    public void setRecent(String key, String api, String jar) {
        if (isJs(api)) jsLoader.setRecent(key);
        else if (isPy(api)) pyLoader.setRecent(key);
        else if (isCsp(api)) {
            if (isolateCspProviders()) ProviderClient.get().setRecent(jar);
            else jarLoader.setRecent(com.github.catvod.utils.Util.md5(jar));
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        String siteKey = params.get("siteKey");
        if (siteKey != null) {
            Site site = VodConfig.get().getSite(siteKey);
            // Keep authenticated QR/proxy traffic on the interactive instance.
            // Routing it back to RemoteSpider loses the login session.
            if (interactiveCspKeys.contains(site.getKey())) {
                Object[] result = getInteractiveSpider(site.getKey(), site.getApi(), site.getExt(), site.getJar()).proxy(params);
                if (result != null) return result;
            }
            return getSpider(siteKey).proxy(params);
        }
        if ("js".equals(params.get("do"))) return jsLoader.proxy(params);
        if ("py".equals(params.get("do"))) return pyLoader.proxy(params);
        if (!isolateCspProviders()) return jarLoader.proxy(params);
        if (!interactiveCspKeys.isEmpty()) {
            Object[] result = jarLoader.proxy(params);
            if (result != null) return result;
        }
        return ProviderClient.get().proxy(params);
    }

    public void parseJar(String jar, boolean recent) {
        if (BeanUtils.isEmpty(jar)) return;
        if (isolateCspProviders()) {
            ProviderClient.get().preload(jar);
            if (recent) ProviderClient.get().setRecent(jar);
        } else {
            String key = com.github.catvod.utils.Util.md5(jar);
            jarLoader.parseJar(key, jar);
            if (recent) jarLoader.setRecent(key);
        }
    }

    public DexClassLoader dex(String jar) {
        return jarLoader.dex(jar);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        return isolateCspProviders()
                ? ProviderClient.get().jsonExt(key, jxs, url)
                : jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        return isolateCspProviders()
                ? ProviderClient.get().jsonExtMix(flag, key, name, jxs, url)
                : jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    private static class Loader {
        static volatile BaseLoader INSTANCE = new BaseLoader();
    }
}