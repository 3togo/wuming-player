package com.wuming.android.tv.dlna;

import static org.junit.Assert.*;

import com.wuming.tv.core.upnp.OkHttpStreamClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okio.Buffer;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OkHttpStreamClientContractTest {
    private ExecutorService executor;
    private OkHttpStreamClient.Configuration configuration;
    private OkHttpStreamClient client;

    @Before public void create() {
        executor = Executors.newSingleThreadExecutor();
        configuration = new OkHttpStreamClient.Configuration(executor);
        client = new OkHttpStreamClient(configuration);
    }

    @After public void close() {
        client.stop();
        executor.shutdownNow();
    }

    @Test public void requiredBodiesHeadersAndUserAgentKeepWireShape() throws Exception {
        for (String method : new String[]{"POST", "PUT", "NOTIFY"}) {
            var message = new StreamRequestMessage(new UpnpRequest(UpnpRequest.Method.POST, URI.create("http://127.0.0.1:9/control")) {
                @Override public String getHttpMethodName() { return method; }
            });
            message.getHeaders().put("X-Test", Arrays.asList("one", null, "two"));
            var request = client.createRequest(message).request();
            assertNotNull(request.body());
            assertEquals(0L, request.body().contentLength());
            assertEquals(Arrays.asList("one", "two"), request.headers("X-Test"));
            assertEquals(configuration.getUserAgentValue(1, 0), request.header("User-Agent"));
        }
        var get = message(UpnpRequest.Method.GET);
        get.getHeaders().add("User-Agent", "custom");
        assertNull(client.createRequest(get).request().body());
        assertEquals("custom", client.createRequest(get).request().header("User-Agent"));
        var post = message(UpnpRequest.Method.POST);
        post.setBody("payload");
        post.getHeaders().add("Content-Type", "text/plain; charset=utf-8");
        var body = client.createRequest(post).request().body();
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        assertEquals("payload", buffer.readUtf8());
        assertEquals("text/plain; charset=utf-8", body.contentType().toString());
    }

    @Test public void cancellationTimeoutAndShutdownKeepOwnership() throws Exception {
        assertSame(configuration, client.getConfiguration());
        var field = OkHttpStreamClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        OkHttpClient http = (OkHttpClient) field.get(client);
        assertEquals((configuration.getTimeoutSeconds() + 5) * 1000, http.connectTimeoutMillis());
        assertEquals(http.connectTimeoutMillis(), http.readTimeoutMillis());
        assertNull(http.proxy()); // Android retains system proxy selection.
        Call call = client.createRequest(message(UpnpRequest.Method.GET));
        client.abort(call);
        assertTrue(call.isCanceled());
        client.stop();
        assertTrue(http.dispatcher().executorService().isShutdown());
        assertFalse(executor.isShutdown());
        assertFalse(client.logExecutionException(null));
    }

    @Test public void responsesKeepStatusHeadersSizeBehaviorAndCloseTheirBody() throws Exception {
        byte[] content = new byte[1_048_577];
        Arrays.fill(content, (byte) 'a');
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var source = okio.Okio.buffer(new okio.ForwardingSource(new Buffer().write(content)) {
            @Override public void close() throws java.io.IOException {
                closed.set(true);
                super.close();
            }
        });
        okhttp3.ResponseBody body = new okhttp3.ResponseBody() {
            @Override public okhttp3.MediaType contentType() { return okhttp3.MediaType.get("text/plain; charset=utf-8"); }
            @Override public long contentLength() { return content.length; }
            @Override public okio.BufferedSource source() { return source; }
        };
        var message = message(UpnpRequest.Method.GET);
        var response = new okhttp3.Response.Builder()
                .request(client.createRequest(message).request())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(201).message("Created")
                .addHeader("X-Value", "one").addHeader("X-Value", "two")
                .addHeader("Content-Type", "text/plain; charset=utf-8").body(body).build();
        Call call = (Call) java.lang.reflect.Proxy.newProxyInstance(Call.class.getClassLoader(),
                new Class<?>[]{Call.class}, (proxy, method, args) -> {
                    if (method.getName().equals("execute")) return response;
                    throw new AssertionError(method.getName());
                });
        var result = client.createCallable(message, call).call();
        assertEquals(201, result.getOperation().getStatusCode());
        assertEquals(Arrays.asList("one", "two"), result.getHeaders().get("X-Value"));
        assertEquals(new String(content, StandardCharsets.UTF_8), result.getBodyString());
        assertTrue(closed.get());
    }

    private static StreamRequestMessage message(UpnpRequest.Method method) {
        return new StreamRequestMessage(method, URI.create("http://127.0.0.1:9/control"));
    }
}
