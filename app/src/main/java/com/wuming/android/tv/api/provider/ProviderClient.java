package com.wuming.android.tv.api.provider;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.wuming.android.tv.App;
import com.wuming.android.tv.Constant;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.Task;
import com.wuming.tv.core.source.ProviderHealthPolicy;
import com.github.catvod.Proxy;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class ProviderClient {

    // Older leanback firmware may keep the main thread busy for 8-10 seconds
    // during activity and AutoSize initialization. ServiceConnection callbacks
    // are delivered there on pre-Android 10 devices, so allow enough headroom.
    private static final long CONNECT_TIMEOUT_MS = 20_000L;
    private static final long CONTENT_TIMEOUT_MS = Constant.TIMEOUT_VOD + 5_000L;
    private static final int MAX_PIPE_THREADS = 8;
    private static final int MAX_QUEUED_PIPE_TASKS = 64;
    private static final ExecutorService PIPE_EXECUTOR = createPipeExecutor();

    private final Object connectionLock = new Object();
    private final ProviderHealthPolicy health = new ProviderHealthPolicy();
    private volatile CountDownLatch connectionLatch = new CountDownLatch(1);
    private volatile IBinder binder;
    private volatile boolean binding;
    private volatile String recentJar = "";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (connectionLock) {
                binder = service;
                binding = false;
                connectionLatch.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            resetConnection();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            try {
                App.get().unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
            resetConnection();
            startBinding();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            resetConnection();
        }
    };

    private ProviderClient() {
    }

    private static ExecutorService createPipeExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                MAX_PIPE_THREADS, MAX_PIPE_THREADS, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_PIPE_TASKS), new PipeThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public static ProviderClient get() {
        return Loader.INSTANCE;
    }

    public void preload(String jar) {
        if (jar == null || jar.isEmpty()) return;
        recentJar = jar;
        startBinding();
        if (Looper.myLooper() == Looper.getMainLooper()) return;
        try {
            callString(request(ProviderProtocol.PRELOAD, "", "", "", jar), CONTENT_TIMEOUT_MS);
        } catch (ProviderException e) {
            AppLog.e("ProviderClient", "Unable to preload provider", e);
        }
    }

    public void setRecent(String jar) {
        if (jar != null) recentJar = jar;
        startBinding();
    }

    public void clearHealth() {
        health.clear();
    }

    /**
     * Refreshes network clients in the isolated provider process. This is
     * asynchronous so a settings tap never waits for service binding.
     */
    public void applyLegacyTlsPolicy(boolean enabled) {
        startBinding();
        try {
            Task.execute(() -> {
                Bundle request = request(ProviderProtocol.TLS_POLICY, "", "", "", "");
                request.putBoolean(ProviderProtocol.ENABLED, enabled);
                try {
                    callString(request, CONNECT_TIMEOUT_MS);
                } catch (ProviderException e) {
                    AppLog.w("ProviderClient", "Unable to apply TLS policy in provider process", e);
                }
            });
        } catch (RuntimeException error) {
            AppLog.w("ProviderClient", "Unable to queue TLS policy update", error);
        }
    }

    String callString(Bundle request, long timeoutMs) throws ProviderException {
        String healthKey = healthKey(request);
        ensureHealthy(healthKey);
        ParcelFileDescriptor descriptor;
        try {
            descriptor = transact(request);
        } catch (ProviderException e) {
            health.failure(healthKey, System.currentTimeMillis());
            throw e;
        }
        Future<String> future;
        try {
            future = PIPE_EXECUTOR.submit(() -> readString(descriptor));
        } catch (RejectedExecutionException error) {
            closeQuietly(descriptor);
            throw new ProviderException("Provider is busy", error);
        }
        try {
            String result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            health.success(healthKey);
            return result;
        } catch (TimeoutException e) {
            closeQuietly(descriptor);
            future.cancel(true);
            throw failed(healthKey, "Provider timed out", e);
        } catch (InterruptedException e) {
            closeQuietly(descriptor);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderException("Provider request interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ProviderException providerException) {
                health.failure(healthKey, System.currentTimeMillis());
                throw providerException;
            }
            throw failed(healthKey, "Provider process failed", cause);
        }
    }

    boolean callBoolean(Bundle request, long timeoutMs) throws ProviderException {
        return Boolean.parseBoolean(callString(request, timeoutMs));
    }

    boolean isConnected() {
        IBinder current = binder;
        return current != null && current.isBinderAlive();
    }

    Object[] callProxy(Bundle request) throws ProviderException {
        String healthKey = healthKey(request);
        ensureHealthy(healthKey);
        ParcelFileDescriptor descriptor;
        try {
            descriptor = transact(request);
        } catch (ProviderException e) {
            health.failure(healthKey, System.currentTimeMillis());
            throw e;
        }
        Future<Object[]> future;
        try {
            future = PIPE_EXECUTOR.submit(() -> readProxy(descriptor));
        } catch (RejectedExecutionException error) {
            closeQuietly(descriptor);
            throw new ProviderException("Provider proxy is busy", error);
        }
        try {
            Object[] result = future.get(CONTENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            health.success(healthKey);
            return result;
        } catch (TimeoutException e) {
            closeQuietly(descriptor);
            future.cancel(true);
            throw failed(healthKey, "Provider proxy timed out", e);
        } catch (InterruptedException e) {
            closeQuietly(descriptor);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderException("Provider proxy interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ProviderException providerException) {
                health.failure(healthKey, System.currentTimeMillis());
                throw providerException;
            }
            throw failed(healthKey, "Provider proxy process failed", cause);
        }
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        Bundle request = request(ProviderProtocol.JSON_EXT, key, "", "", recentJar);
        request.putString(ProviderProtocol.JSON, App.gson().toJson(jxs));
        request.putStringArrayList(ProviderProtocol.ARGS, new ArrayList<>(java.util.List.of(url)));
        return new JSONObject(callString(request, CONTENT_TIMEOUT_MS));
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        Bundle request = request(ProviderProtocol.JSON_EXT_MIX, key, "", "", recentJar);
        request.putString(ProviderProtocol.JSON, App.gson().toJson(jxs));
        request.putStringArrayList(ProviderProtocol.ARGS, new ArrayList<>(java.util.List.of(flag, name, url)));
        return new JSONObject(callString(request, CONTENT_TIMEOUT_MS));
    }

    public Object[] proxy(Map<String, String> params) throws ProviderException {
        Bundle request = request(ProviderProtocol.PROXY, "", "", "", recentJar);
        request.putBundle(ProviderProtocol.PARAMS, toBundle(params));
        return callProxy(request);
    }

    public boolean ping() {
        try {
            return "pong".equals(callString(request(ProviderProtocol.PING, "", "", "", ""), CONNECT_TIMEOUT_MS));
        } catch (ProviderException e) {
            return false;
        }
    }

    public void crashForTest() throws ProviderException {
        if (!com.wuming.android.tv.BuildConfig.DEBUG) throw new ProviderException("Test crash is disabled");
        callString(request(ProviderProtocol.CRASH_FOR_TEST, "", "", "", ""), CONNECT_TIMEOUT_MS);
    }

    static Bundle request(int operation, String key, String api, String ext, String jar) {
        Bundle request = new Bundle();
        request.putInt(ProviderProtocol.OP, operation);
        request.putString(ProviderProtocol.KEY, key);
        request.putString(ProviderProtocol.API, api);
        request.putString(ProviderProtocol.EXT, ext);
        request.putString(ProviderProtocol.JAR, jar);
        request.putInt(ProviderProtocol.PROXY_PORT, Proxy.getPort());
        return request;
    }

    static Bundle toBundle(Map<String, String> values) {
        Bundle bundle = new Bundle();
        if (values != null) values.forEach(bundle::putString);
        return bundle;
    }

    private ParcelFileDescriptor transact(Bundle request) throws ProviderException {
        IBinder target = awaitBinder();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ProviderProtocol.DESCRIPTOR);
            data.writeBundle(request);
            if (!target.transact(ProviderProtocol.TRANSACTION_CALL, data, reply, 0)) {
                throw new ProviderException("Provider service rejected request");
            }
            reply.readException();
            if (reply.readInt() == 0) throw new ProviderException("Provider service returned no response");
            return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
        } catch (DeadObjectException e) {
            resetConnection();
            throw new ProviderException("Provider process stopped unexpectedly", e);
        } catch (RemoteException | RuntimeException e) {
            throw new ProviderException("Provider IPC failed", e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private IBinder awaitBinder() throws ProviderException {
        startBinding();
        IBinder current = binder;
        if (current != null && current.isBinderAlive()) return current;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new ProviderException("Provider is still starting");
        }
        CountDownLatch latch = connectionLatch;
        try {
            if (!latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new ProviderException("Timed out connecting to provider process");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Provider connection interrupted", e);
        }
        current = binder;
        if (current == null || !current.isBinderAlive()) throw new ProviderException("Provider process is unavailable");
        return current;
    }

    private void startBinding() {
        synchronized (connectionLock) {
            if (binder != null && binder.isBinderAlive()) return;
            if (binding) return;
            binding = true;
            connectionLatch = new CountDownLatch(1);
            Intent intent = new Intent(App.get(), ProviderService.class);
            boolean result;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                result = App.get().bindService(intent, Context.BIND_AUTO_CREATE, PIPE_EXECUTOR, connection);
            } else {
                result = App.get().bindService(intent, connection, Context.BIND_AUTO_CREATE);
            }
            if (!result) {
                binding = false;
                connectionLatch.countDown();
            }
        }
    }

    private void resetConnection() {
        synchronized (connectionLock) {
            binder = null;
            binding = false;
            connectionLatch.countDown();
            connectionLatch = new CountDownLatch(1);
        }
    }

    private void ensureHealthy(String key) throws ProviderException {
        long wait = health.retryAfter(key, System.currentTimeMillis());
        if (wait > 0) throw new ProviderException("Provider temporarily disabled; retry in " + Math.max(1, TimeUnit.MILLISECONDS.toSeconds(wait)) + "s");
    }

    private ProviderException failed(String key, String message, Throwable cause) {
        health.failure(key, System.currentTimeMillis());
        return new ProviderException(message, cause);
    }

    private static String healthKey(Bundle request) {
        String key = request.getString(ProviderProtocol.KEY, "");
        if (!key.isEmpty()) return key;
        return Integer.toHexString(request.getString(ProviderProtocol.JAR, "").hashCode());
    }

    private static String readString(ParcelFileDescriptor descriptor) throws ProviderException {
        try (DataInputStream input = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(descriptor))) {
            int status = input.readUnsignedByte();
            String value = readValue(input);
            if (status == ProviderProtocol.RESULT_ERROR) throw new ProviderException(value);
            return value;
        } catch (EOFException e) {
            throw new ProviderException("Provider process terminated during request", e);
        } catch (IOException e) {
            throw new ProviderException("Unable to read provider response", e);
        }
    }

    private static Object[] readProxy(ParcelFileDescriptor descriptor) throws ProviderException {
        DataInputStream input = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(descriptor));
        try {
            int result = input.readUnsignedByte();
            if (result == ProviderProtocol.RESULT_ERROR) {
                String message = readValue(input);
                input.close();
                throw new ProviderException(message);
            }
            int status = input.readInt();
            String mime = readValue(input);
            String headersJson = readValue(input);
            @SuppressWarnings("unchecked")
            Map<String, String> headers = App.gson().fromJson(headersJson, Map.class);
            return new Object[]{status, mime, input, headers == null ? new HashMap<>() : headers};
        } catch (EOFException e) {
            closeQuietly(input);
            throw new ProviderException("Provider process terminated during proxy request", e);
        } catch (IOException e) {
            closeQuietly(input);
            throw new ProviderException("Unable to read provider proxy response", e);
        }
    }

    private static String readValue(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("Invalid provider response length: " + length);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Loader {
        static final ProviderClient INSTANCE = new ProviderClient();
    }

    private static final class PipeThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Provider-pipe-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
