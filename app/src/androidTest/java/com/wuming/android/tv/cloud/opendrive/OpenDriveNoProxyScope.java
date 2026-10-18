package com.wuming.android.tv.cloud.opendrive;

import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.catvod.net.OkHttp;
import java.lang.reflect.Field;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;

/** Test-only direct routing for provider API calls and the actual player; restored on exit. */
final class OpenDriveNoProxyScope implements AutoCloseable {
    private record Saved(Field field, Object owner, OkHttpClient client, ConnectionPool pool) { }
    private final List<Saved> saved = new ArrayList<>();
    private final AtomicInteger direct = new AtomicInteger();
    private final AtomicInteger proxied = new AtomicInteger();

    OpenDriveNoProxyScope() throws Exception {
        try {
            for (String name : List.of("client", "explicitCookieClient")) {
                var method = OpenDriveHttp.class.getDeclaredMethod(name);
                method.setAccessible(true);
                replace(OpenDriveHttp.class, null, name, (OkHttpClient) method.invoke(null));
            }
            replace(OkHttp.class, OkHttp.get(), "player", OkHttp.player());
        } catch (Exception error) { close(); throw error; }
    }

    private void replace(Class<?> type, Object owner, String name, OkHttpClient base) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        var pool = new ConnectionPool();
        var client = base.newBuilder().proxy(Proxy.NO_PROXY).connectionPool(pool)
                .eventListener(new EventListener() {
                    @Override public void connectionAcquired(Call call, Connection connection) {
                        if (connection.route().proxy().type() == Proxy.Type.DIRECT) direct.incrementAndGet();
                        else proxied.incrementAndGet();
                    }
                }).build();
        saved.add(new Saved(field, owner, base, pool));
        field.set(owner, client);
    }

    @Override public void close() throws Exception {
        for (int i = saved.size() - 1; i >= 0; i--) {
            Saved value = saved.get(i);
            value.field().set(value.owner(), value.client());
            value.pool().evictAll();
        }
        saved.clear();
        Bundle status = new Bundle();
        status.putInt("directConnections", direct.get());
        status.putInt("proxiedConnections", proxied.get());
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
