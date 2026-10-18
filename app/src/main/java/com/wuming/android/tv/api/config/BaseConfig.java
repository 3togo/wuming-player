package com.wuming.android.tv.api.config;

import com.wuming.android.tv.App;
import com.wuming.android.tv.R;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Depot;
import com.wuming.android.tv.bean.Rule;
import com.wuming.android.tv.event.ConfigEvent;
import com.wuming.android.tv.impl.Callback;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Notify;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UrlUtil;
import android.os.Looper;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private final AtomicReference<Future<?>> futureRef = new AtomicReference<>();

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            if (config == null) config = defaultConfig();
            Server.get().start();
            load(config);
        } catch (Throwable e) {
            AppLog.e(getTag(), e);
        }
    }

    protected void postEvent() {
        ConfigEvent.common();
    }

    /**
     * Determine if synchronization is needed for the given URL.
     * Returns true when:
     * - sync flag is set (URLs match between parent and child config)
     * - config is null or has no URL (first load)
     * - the URL matches the current config URL (embedded config scenario)
     */
    public boolean needSync(String url) {
        return sync || config == null || BeanUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        if (config != null) return config;
        // On the main thread, never resolve defaultConfig() here: it may read Room
        // (forbidden since allowMainThreadQueries() was removed), and caching a
        // transient value would block the background config load from adopting the
        // resolved config via retainResolvedConfig. Callers needing only a display
        // value fall back to the in-memory default; the real config arrives through
        // the async load and refreshes the UI via events.
        if (isMainThread()) {
            return defaultConfig();
        }
        synchronized (this) {
            if (config == null) config = defaultConfig();
            return config;
        }
    }

    private static boolean isMainThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.authenticator().addAll(proxy);
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public void load(Callback callback) {
        int id = nextTaskId();
        Future<?> prev = futureRef.getAndSet(null);
        if (prev != null && !prev.isDone()) prev.cancel(true);
        futureRef.set(Task.submit(() -> loadConfig(id, config, callback)));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        try {
            // If config is null, fetch it from DB (this runs on background thread)
            Config effectiveConfig = (config != null) ? config : defaultConfig();
            // Retain the asynchronously resolved default. UI callers can inspect
            // this in-memory value without repeating the Room lookup on main.
            if (!retainResolvedConfig(id, config, effectiveConfig)) return;
            Server.get().start();
            OkHttp.cancel(getTag());
            load(effectiveConfig);
            if (taskId.get() != id) return;
            if (effectiveConfig.equals(this.config)) effectiveConfig.update();
            App.post(() -> Notify.show(effectiveConfig.getNotice()));
            App.post(callback::success);
        } catch (Throwable e) {
            AppLog.e(getTag(), e);
            if (isCanceled(e)) return;
            if (taskId.get() != id) return;
            Config currentConfig = this.config != null ? this.config : config;
            if (currentConfig == null || BeanUtils.isEmpty(currentConfig.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(Notify.getError(R.string.error_config_get, e)));
        } finally {
            if (taskId.get() == id) postEvent();
        }
    }

    synchronized int nextTaskId() {
        return taskId.incrementAndGet();
    }

    synchronized boolean retainResolvedConfig(int id, Config requested, Config resolved) {
        if (taskId.get() != id) return false;
        if (requested == null) {
            if (this.config == null) this.config = resolved;
            else if (this.config != resolved) return false;
        }
        return true;
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        if (e instanceof InterruptedIOException) return true;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = JsonParser.parseString(OkHttp.string(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    protected boolean checkJson(Config config, JsonObject object, int configType) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object, configType);
            return true;
        }
        return false;
    }

    private void parseDepot(Config config, JsonObject object, int configType) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, configType));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
    }

    protected void initCommonList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    protected abstract void setRules(List<Rule> rules);

    protected abstract void setAds(List<String> ads);
}
