package com.wuming.android.tv.api.provider;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import com.wuming.android.tv.App;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.api.loader.JarLoader;
import com.wuming.android.tv.utils.Task;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public final class ProviderService extends Service {

    private static final Type STRING_MAP = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Type NESTED_STRING_MAP = new TypeToken<LinkedHashMap<String, HashMap<String, String>>>() {}.getType();

    private final JarLoader loader = new JarLoader();
    private final IBinder bridge = new android.os.Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code != ProviderProtocol.TRANSACTION_CALL) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(ProviderProtocol.DESCRIPTOR);
            Bundle request = data.readBundle(ProviderService.class.getClassLoader());
            if (request == null) {
                reply.writeException(new IllegalArgumentException("Missing provider request"));
                return true;
            }
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                reply.writeNoException();
                reply.writeInt(1);
                pipe[0].writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                Task.executor().execute(() -> handle(request, pipe[1]));
                closeQuietly(pipe[0]);
            } catch (Throwable e) {
                reply.writeException(new IllegalStateException("Unable to create provider response", e));
            }
            return true;
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return bridge;
    }

    @Override
    public void onDestroy() {
        loader.clear();
        super.onDestroy();
    }

    private void handle(Bundle request, ParcelFileDescriptor outputDescriptor) {
        try (DataOutputStream output = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(outputDescriptor))) {
            try {
                int proxyPort = request.getInt(ProviderProtocol.PROXY_PORT, -1);
                if (proxyPort > 0) Proxy.set(proxyPort);
                int operation = request.getInt(ProviderProtocol.OP, -1);
                if (operation == ProviderProtocol.CRASH_FOR_TEST && BuildConfig.DEBUG) {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    return;
                }
                if (operation == ProviderProtocol.PROXY) {
                    writeProxy(output, request);
                } else {
                    writeString(output, executeString(operation, request));
                }
            } catch (Throwable e) {
                // A proxy body may already be streaming. In that case closing the
                // pipe is the only unambiguous failure signal to the caller.
                if (output.size() == 0) {
                    output.writeByte(ProviderProtocol.RESULT_ERROR);
                    writeValue(output, safeMessage(e));
                }
            }
        } catch (Throwable ignored) {
            closeQuietly(outputDescriptor);
        }
    }

    private String executeString(int operation, Bundle request) throws Throwable {
        if (operation == ProviderProtocol.PING) return "pong";
        if (operation == ProviderProtocol.TLS_POLICY) {
            OkHttp.applyLegacyTlsPolicy(request.getBoolean(ProviderProtocol.ENABLED));
            return "ok";
        }
        String jar = request.getString(ProviderProtocol.JAR, "");
        if (operation == ProviderProtocol.PRELOAD) {
            requireLoader(jar);
            return "ok";
        }
        if (operation == ProviderProtocol.JSON_EXT) return jsonExt(request, jar).toString();
        if (operation == ProviderProtocol.JSON_EXT_MIX) return jsonExtMix(request, jar).toString();

        Spider spider = spider(request);
        ArrayList<String> args = request.getStringArrayList(ProviderProtocol.ARGS);
        List<String> values = args == null ? List.of() : args;
        return switch (operation) {
            case ProviderProtocol.HOME -> spider.homeContent(request.getBoolean("filter", true));
            case ProviderProtocol.HOME_VIDEO -> spider.homeVideoContent();
            case ProviderProtocol.CATEGORY -> spider.categoryContent(value(values, 0), value(values, 1), request.getBoolean("filter"), stringMap(request.getBundle(ProviderProtocol.PARAMS)));
            case ProviderProtocol.DETAIL -> spider.detailContent(defaultList(request.getStringArrayList(ProviderProtocol.IDS)));
            case ProviderProtocol.SEARCH -> spider.searchContent(value(values, 0), request.getBoolean("quick"));
            case ProviderProtocol.SEARCH_PAGE -> spider.searchContent(value(values, 0), request.getBoolean("quick"), value(values, 1));
            case ProviderProtocol.PLAYER -> spider.playerContent(value(values, 0), value(values, 1), defaultList(request.getStringArrayList(ProviderProtocol.FLAGS)));
            case ProviderProtocol.LIVE -> spider.liveContent(value(values, 0));
            case ProviderProtocol.MANUAL_VIDEO -> String.valueOf(spider.manualVideoCheck());
            case ProviderProtocol.VIDEO_FORMAT -> String.valueOf(spider.isVideoFormat(value(values, 0)));
            case ProviderProtocol.ACTION -> spider.action(value(values, 0));
            default -> throw new IllegalArgumentException("Unknown provider operation: " + operation);
        };
    }

    private Spider spider(Bundle request) throws Exception {
        String jar = request.getString(ProviderProtocol.JAR, "");
        requireLoader(jar);
        loader.setRecent(Util.md5(jar));
        Spider spider = loader.getSpider(
                request.getString(ProviderProtocol.KEY, ""),
                request.getString(ProviderProtocol.API, ""),
                request.getString(ProviderProtocol.EXT, ""),
                jar);
        if (spider instanceof SpiderNull) throw new IllegalStateException("Provider could not be initialized");
        return spider;
    }

    private void requireLoader(String jar) {
        if (jar == null || jar.isEmpty() || loader.dex(jar) == null) {
            throw new IllegalStateException("Provider jar could not be loaded");
        }
        loader.setRecent(Util.md5(jar));
    }

    private JSONObject jsonExt(Bundle request, String jar) throws Throwable {
        requireLoader(jar);
        LinkedHashMap<String, String> jxs = App.gson().fromJson(request.getString(ProviderProtocol.JSON, "{}"), STRING_MAP);
        ArrayList<String> args = request.getStringArrayList(ProviderProtocol.ARGS);
        return loader.jsonExt(request.getString(ProviderProtocol.KEY, ""), jxs, value(args, 0));
    }

    private JSONObject jsonExtMix(Bundle request, String jar) throws Throwable {
        requireLoader(jar);
        LinkedHashMap<String, HashMap<String, String>> jxs = App.gson().fromJson(request.getString(ProviderProtocol.JSON, "{}"), NESTED_STRING_MAP);
        ArrayList<String> args = request.getStringArrayList(ProviderProtocol.ARGS);
        return loader.jsonExtMix(value(args, 0), request.getString(ProviderProtocol.KEY, ""), value(args, 1), jxs, value(args, 2));
    }

    private void writeString(DataOutputStream output, String result) throws Exception {
        output.writeByte(ProviderProtocol.RESULT_OK);
        writeValue(output, result == null ? "" : result);
    }

    @SuppressWarnings("unchecked")
    private void writeProxy(DataOutputStream output, Bundle request) throws Exception {
        Map<String, String> params = stringMap(request.getBundle(ProviderProtocol.PARAMS));
        Object[] result;
        if (!request.getString(ProviderProtocol.KEY, "").isEmpty()) {
            result = spider(request).proxy(params);
        } else {
            requireLoader(request.getString(ProviderProtocol.JAR, ""));
            result = loader.proxy(params);
        }
        if (result == null || result.length == 0) throw new IllegalStateException("Provider proxy returned no data");

        int status;
        String mime;
        InputStream stream;
        Map<String, String> headers = new HashMap<>();
        if (result[0] instanceof NanoHTTPD.Response response) {
            status = response.getStatus().getRequestStatus();
            mime = response.getMimeType();
            stream = response.getData();
        } else {
            if (result.length < 3) throw new IllegalStateException("Provider proxy response is incomplete");
            status = (Integer) result[0];
            mime = (String) result[1];
            stream = (InputStream) result[2];
            if (result.length > 3 && result[3] instanceof Map) headers.putAll((Map<String, String>) result[3]);
        }
        if (stream == null) throw new IllegalStateException("Provider proxy returned an empty stream");

        output.writeByte(ProviderProtocol.RESULT_OK);
        output.writeInt(status);
        writeValue(output, mime == null ? "application/octet-stream" : mime);
        writeValue(output, App.gson().toJson(headers));
        output.flush();
        try (stream) {
            stream.transferTo(output);
        }
    }

    private static HashMap<String, String> stringMap(Bundle bundle) {
        HashMap<String, String> result = new HashMap<>();
        if (bundle == null) return result;
        for (String key : bundle.keySet()) result.put(key, bundle.getString(key, ""));
        return result;
    }

    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String value(List<String> values, int index) {
        return values != null && index < values.size() ? values.get(index) : "";
    }

    private static void writeValue(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return "Provider error: " + (message == null || message.isEmpty() ? error.getClass().getSimpleName() : message);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
