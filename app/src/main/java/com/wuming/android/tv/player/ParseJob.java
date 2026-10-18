package com.wuming.android.tv.player;

import android.os.Looper;

import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.api.config.VodConfig;
import com.wuming.android.tv.api.loader.BaseLoader;
import com.wuming.android.tv.bean.BeanUtils;
import com.wuming.android.tv.bean.Parse;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.impl.ParseCallback;
import com.wuming.android.tv.server.Server;
import com.wuming.android.tv.ui.custom.CustomWebView;
import com.wuming.android.tv.utils.Task;
import com.wuming.android.tv.utils.UiTaskScope;
import com.wuming.android.tv.utils.UrlUtil;
import com.wuming.android.tv.utils.WebViewUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;
import com.wuming.android.tv.utils.AppLog;

public class ParseJob implements ParseCallback {
    private static final String TAG = "ParseJob";

    private final AtomicBoolean done = new AtomicBoolean();
    private final List<CustomWebView> webViews;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;
    private final ExecutorService candidates;
    private final Consumer<Runnable> post;
    private volatile Future<?> parseTask;
    private ScheduledFuture<?> timeoutTask;
    private volatile ParseCallback callback;
    private Parse parse;
    private UiTaskScope uiTasks;

    private ParseJob(ParseCallback callback) {
        this(callback, new UiTaskScope());
    }

    private ParseJob(ParseCallback callback, UiTaskScope scope) {
        this(callback, task -> scope.post(task, 0), Executors.newSingleThreadExecutor(),
                Executors.newFixedThreadPool(4));
        this.uiTasks = scope;
    }

    ParseJob(ParseCallback callback, Consumer<Runnable> post,
             ExecutorService executor, ExecutorService candidates) {
        this.executor = executor;
        this.candidates = candidates;
        this.post = post;
        this.webViews = new ArrayList<>();
        this.callback = callback;
    }

    public static ParseJob create(ParseCallback callback) {
        return new ParseJob(callback);
    }

    public ParseJob start(Result result, boolean useParse) {
        try {
            setParse(result, useParse);
            execute(result);
        } catch (RuntimeException error) {
            stop();
            throw error;
        }
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = VodConfig.get().getParse();
        if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        if (result.getPlayUrl().startsWith("parse:")) parse = VodConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parse == null || parse.isEmpty()) parse = Parse.get(0, result.getPlayUrl());
        parse = parse.copy();
        parse.setHeader(new HashMap<>(result.getHeader()));
        parse.setClick(getClick(result));
    }

    private String getClick(Result result) {
        String click = VodConfig.get().getSite(result.getKey()).getClick();
        if (BeanUtils.isNotEmpty(click)) return click;
        return result.getClick();
    }

    private synchronized void execute(Result result) {
        if (done.get()) return;
        parseTask = executor.submit(getTask(result));
        timeoutTask = Task.schedule(() -> {
            if (done.get()) return;
            Future<?> running = parseTask;
            if (running != null) running.cancel(true);
            onParseError();
        }, Constant.TIMEOUT_PARSE_DEF, TimeUnit.MILLISECONDS);
    }

    private Runnable getTask(Result result) {
        return () -> {
            try {
                doInBackground(result.getKey(), result.getUrl().v(), result.getFlag());
            } catch (Throwable e) {
                AppLog.e(TAG, e);
                onParseError();
            }
        };
    }

    private void doInBackground(String key, String webUrl, String flag) throws Throwable {
        switch (parse.getType()) {
            case 0:
                startWeb(key, parse, webUrl);
                break;
            case 1:
                jsonParse(parse, webUrl, true);
                break;
            case 2:
                jsonExtend(webUrl);
                break;
            case 3:
                jsonMix(webUrl, flag);
                break;
            case 4:
                superParse(webUrl, flag);
                break;
        }
    }

    private void jsonParse(Parse item, String webUrl, boolean fatal) throws Exception {
        Call call = OkHttp.newCall(item.getUrl() + webUrl, item.getHeader());
        calls.add(call);
        try {
            if (done.get()) return;
            try (Response res = call.execute()) {
                okhttp3.ResponseBody body = res.body();
                String bodyString = body != null ? body.string() : "";
                JsonObject object = Json.parse(bodyString).getAsJsonObject();
                String url = Json.safeString(object, "url");
                JsonObject data = object.getAsJsonObject("data");
                if (url.isEmpty()) url = Json.safeString(data, "url");
                Map<String, String> fallback = item.getHeader().isEmpty()
                        ? parse.getHeader() : item.getHeader();
                checkResult(getHeader(object, fallback), url, item.getName(), fatal);
            }
        } finally {
            calls.remove(call);
        }
    }

    private void jsonExtend(String webUrl) throws Throwable {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) if (item.getType() == 1) jxs.put(item.getName(), item.extUrl());
        checkResult(Result.fromObject(BaseLoader.get().jsonExt(parse.getUrl(), jxs, webUrl)));
    }

    private void jsonMix(String webUrl, String flag) throws Throwable {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) jxs.put(item.getName(), item.mixMap());
        checkResult(Result.fromObject(BaseLoader.get().jsonExtMix(flag, parse.getUrl(), parse.getName(), jxs, webUrl)));
    }

    private void superParse(String webUrl, String flag) throws Exception {
        List<Parse> json = VodConfig.get().getParses(1, flag);
        List<Parse> webs = VodConfig.get().getParses(0, flag);
        int count = json.size() + (webs.isEmpty() ? 0 : 1);
        CountDownLatch latch = new CountDownLatch(count);
        for (Parse item : json) {
            if (done.get()) return;
            candidates.execute(() -> jsonParse(latch, item, webUrl));
        }
        if (!webs.isEmpty()) startWeb(webs, webUrl);
        latch.await();
        onParseError();
    }

    private void jsonParse(CountDownLatch latch, Parse item, String webUrl) {
        try {
            jsonParse(item, webUrl, false);
        } catch (Exception e) {
            AppLog.e(TAG, e);
        } finally {
            latch.countDown();
        }
    }

    private void checkResult(Map<String, String> headers, String url, String from, boolean fatal) {
        if (hasAbsoluteUrl(url)) onParseSuccess(headers, url.trim(), from);
        else if (fatal) onParseError();
    }

    static boolean hasAbsoluteUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            String resource = value.trim();
            // Preserve the legacy URL|||duration***URL|||duration transport. Validate its first
            // resource here; MediaSourceFactory owns parsing the individual entries.
            int separator = resource.indexOf("|||");
            if (separator >= 0 && resource.contains("***")) resource = resource.substring(0, separator);
            URI uri = URI.create(resource);
            if (!uri.isAbsolute() || uri.getRawSchemeSpecificPart().isBlank()) return false;
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    || "rtsp".equalsIgnoreCase(scheme) || "rtmp".equalsIgnoreCase(scheme)) {
                return uri.getRawAuthority() != null && !uri.getRawAuthority().isBlank();
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void checkResult(Result result) {
        result.setHeader(parse.getHeader());
        if (result.getUrl().isEmpty()) onParseError();
        else if (result.needParse()) startWeb(result.getHeader(), UrlUtil.convert(result.getUrl().v()));
        else onParseSuccess(result.getHeader(), result.getUrl().v(), result.getJxFrom());
    }

    private void startWeb(List<Parse> items, String webUrl) {
        StringBuilder sb = new StringBuilder();
        for (Parse item : items) sb.append(item.getUrl()).append(";");
        startWeb(new HashMap<>(), Server.get().getAddress("/parse?jxs=" + Util.substring(sb.toString()) + "&url=" + webUrl));
    }

    private void startWeb(String key, Parse item, String webUrl) {
        startWeb(key, item.getName(), item.getHeader(), item.getUrl() + webUrl, item.getClick());
    }

    private void startWeb(Map<String, String> headers, String url) {
        startWeb("", "", headers, url, "");
    }

    private void startWeb(String key, String from, Map<String, String> headers, String url, String click) {
        if (!WebViewUtil.support()) {
            onParseError();
        } else {
            post.accept(() -> {
                if (done.get()) return;
                CustomWebView webView = CustomWebView.create(App.get()).start(key, from, headers, url, click, this, !url.contains("player/?url="));
                if (done.get()) webView.destroy();
                else webViews.add(webView);
            });
        }
    }

    static Map<String, String> getHeader(JsonObject object, Map<String, String> fallback) {
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            String key = entry.getKey();
            if (key.equalsIgnoreCase("ua")) key = HttpHeaders.USER_AGENT;
            if (key.equalsIgnoreCase(HttpHeaders.USER_AGENT)
                    || key.equalsIgnoreCase(HttpHeaders.REFERER)
                    || key.equalsIgnoreCase(HttpHeaders.COOKIE)) {
                headers.put(UrlUtil.fixHeader(key), entry.getValue().getAsString());
            }
        }
        return headers.isEmpty() ? new HashMap<>(fallback) : headers;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        complete(callback -> callback.onParseSuccess(headers, url, from));
    }

    @Override
    public void onParseError() {
        complete(ParseCallback::onParseError);
    }

    private void complete(Consumer<ParseCallback> result) {
        if (!done.compareAndSet(false, true)) return;
        post.accept(() -> {
            ParseCallback current = callback;
            stop();
            if (current != null) result.accept(current);
        });
    }

    private void stopWeb() {
        for (CustomWebView webView : webViews) webView.stop(false);
        for (CustomWebView webView : webViews) webView.destroy();
        webViews.clear();
    }

    public synchronized void stop() {
        done.set(true);
        callback = null;
        for (Call call : calls) call.cancel();
        calls.clear();

        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        if (parseTask != null) {
            parseTask.cancel(true);
            parseTask = null;
        }

        // Cancel transport and queued work without waiting for worker termination on the UI.
        executor.shutdownNow();
        candidates.shutdownNow();
        if (uiTasks != null) uiTasks.close();
        if (uiTasks == null && webViews.isEmpty()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) stopWeb();
        else App.post(this::stopWeb);
    }
}
