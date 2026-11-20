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
import com.wuming.tv.core.base.BoundedResponse;
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
import okhttp3.HttpUrl;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private Future<?> future;
    private int synchronousLoad;
    private int explicitRequest;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract PreparedLoad prepare(Config config, int request) throws Throwable;

    protected abstract boolean isLoaded();

    protected record PreparedLoad(Config config, Runnable publish, Runnable cleanup, Runnable preload) implements AutoCloseable {
        PreparedLoad(Config config, Runnable publish) {
            this(config, publish, () -> {}, () -> {});
        }

        PreparedLoad(Config config, Runnable publish, Runnable cleanup) {
            this(config, publish, cleanup, () -> {});
        }

        @Override
        public void close() {
            cleanup.run();
        }
    }

    public void ensureLoaded() {
        int id;
        Config requested;
        synchronized (this) {
            if (isLoaded() || synchronousLoad != 0 || future != null && !future.isDone()) return;
            requested = config;
            id = nextTaskId();
            synchronousLoad = id;
        }
        try {
            loadConfig(id, requested, null);
        } finally {
            synchronized (this) {
                if (synchronousLoad == id) synchronousLoad = 0;
            }
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
        requestLoad(config, callback, false);
    }

    protected final void requestLoad(Config requested, Callback callback) {
        requestLoad(requested, callback, true);
    }

    private void requestLoad(Config requested, Callback callback, boolean explicit) {
        final int id;
        final int previous;
        synchronized (this) {
            previous = currentTaskId();
            id = nextTaskId();
            explicitRequest = explicit ? id : 0;
            if (future != null) future.cancel(true);
        }
        OkHttp.cancel(requestTag(previous));
        callback.start();
        if (!isCurrent(id)) return;
        Future<?> submitted = Task.submit(() -> loadConfig(id, requested, callback));
        synchronized (this) {
            if (isCurrent(id)) future = submitted;
            else submitted.cancel(true);
        }
    }

    private void loadConfig(int id, Config requested, Callback callback) {
        try {
            if (!isCurrent(id)) return;
            Config effective = requested != null ? requested : defaultConfig();
            if (!isCurrent(id)) return;
            Server.get().start();
            try (PreparedLoad prepared = prepare(effective, id)) {
                if (!commitPrepared(id, prepared, callback != null)) return;
                if (isCurrent(id)) prepared.preload().run();
                if (callback != null) {
                    postCurrent(id, () -> {
                        Notify.show(prepared.config().getNotice());
                        if (isCurrent(id)) callback.success();
                    });
                }
            }
        } catch (Throwable e) {
            AppLog.e(getTag(), e);
            if (callback != null && !isCanceled(e)) {
                postCurrent(id, () -> callback.error(requested == null && config == null
                        || requested != null && BeanUtils.isEmpty(requested.getUrl())
                        ? "" : Notify.getError(R.string.error_config_get, e)));
            }
        } finally {
            synchronized (this) {
                if (explicitRequest == id) explicitRequest = 0;
            }
            if (callback != null) postCurrent(id, this::postEvent);
        }
    }

    private void postCurrent(int id, Runnable action) {
        App.post(() -> {
            // Request creation can be reentrant during callbacks. Check each delivery.
            if (isCurrent(id)) action.run();
        });
    }

    synchronized int nextTaskId() {
        return taskId.incrementAndGet();
    }

    protected final int currentTaskId() {
        return taskId.get();
    }

    protected final String requestTag(int id) {
        return getTag() + ":" + id;
    }

    protected final boolean isCurrent(int id) {
        return taskId.get() == id;
    }

    protected final synchronized boolean acceptsEmbedded(int id, String url) {
        return explicitRequest == 0 && isCurrent(id) && needSync(url);
    }

    synchronized boolean commitPrepared(int id, PreparedLoad prepared, boolean save) {
        if (!isCurrent(id)) return false;
        Config next = prepared.config();
        if (save) next.ensurePersistedIdentity();
        prepared.publish().run();
        config = next;
        if (save) next.update();
        return true;
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        if (e instanceof InterruptedIOException) return true;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key, String tag) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString(), tag);
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString(), tag));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url, String tag) {
        try {
            try (okhttp3.Response response = OkHttp.newCall(UrlUtil.convert(url), tag).execute()) {
                if (!response.isSuccessful() || response.body() == null) return new JsonArray();
                JsonElement parsed = JsonParser.parseString(BoundedResponse.text(response.body(), BoundedResponse.CONFIG_BYTES));
                return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
            }
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    protected record SourceDocument(Config config, String text, JsonObject json, long fetchMillis) {}

    protected SourceDocument readSource(Config requested, int type, boolean requireObject, int id) throws Throwable {
        var visited = new HashSet<String>();
        Config candidate = requested;
        long started = System.currentTimeMillis();
        for (int hop = 0; hop <= 8; hop++) {
            if (!isCurrent(id) || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String url = UrlUtil.convert(candidate.getUrl());
            HttpUrl normalized = HttpUrl.parse(url);
            String identity = normalized == null ? url : normalized.newBuilder().fragment(null).build().toString();
            if (!visited.add(identity)) {
                throw new IllegalArgumentException("Configuration warehouse contains a cycle");
            }
            String text;
            try {
                text = requireObject ? com.wuming.android.tv.api.Decoder.getJsonObject(url, requestTag(id))
                        : com.wuming.android.tv.api.Decoder.getJson(url, requestTag(id));
            } catch (Exception failure) {
                if (Thread.currentThread().isInterrupted() || isCanceled(failure)
                        || failure instanceof BoundedResponse.LimitExceeded) throw failure;
                text = candidate.getJson();
                if (text.isEmpty()) throw failure;
                AppLog.w(getTag(), "Network fetch failed; using cached config JSON");
            }
            JsonObject object = Json.isObj(text) ? JsonParser.parseString(text).getAsJsonObject() : null;
            if (object == null) {
                if (requireObject) throw new IllegalArgumentException("Configuration must be a JSON object");
                return new SourceDocument(candidate, text, null, System.currentTimeMillis() - started);
            }
            if (object.has("msg")) throw new IllegalArgumentException(object.get("msg").getAsString());
            if (!object.has("urls")) return new SourceDocument(candidate, text, object, System.currentTimeMillis() - started);
            if (hop == 8) break;
            JsonElement entries = object.get("urls");
            if (!entries.isJsonArray()) throw new IllegalArgumentException("Configuration warehouse urls must be an array");
            List<Depot> items = Depot.arrayFrom(entries.toString());
            if (items.isEmpty() || items.get(0) == null || BeanUtils.isEmpty(items.get(0).getUrl())) {
                throw new IllegalArgumentException("Configuration warehouse has no first child");
            }
            Depot child = items.get(0);
            candidate = Config.prepareSource(child.getUrl(), child.getName(), type);
        }
        throw new IllegalArgumentException("Configuration warehouse exceeds eight hops");
    }

    protected Runnable prepareCommonList(JsonObject object, String tag) {
        List<Header> headers = Header.arrayFrom(fetchArray(object, "headers", tag));
        List<Proxy> proxies = Proxy.arrayFrom(fetchArray(object, "proxy", tag));
        List<Rule> rules = Rule.arrayFrom(fetchArray(object, "rules", tag));
        List<String> hosts = Json.safeListString(object, "hosts");
        List<String> ads = Json.safeListString(object, "ads");
        return () -> {
            setHeaders(headers);
            setProxy(proxies);
            setRules(rules);
            setHosts(hosts);
            setAds(ads);
        };
    }

    protected abstract void setRules(List<Rule> rules);

    protected abstract void setAds(List<String> ads);
}
