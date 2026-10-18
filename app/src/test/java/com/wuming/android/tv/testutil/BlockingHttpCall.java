package com.wuming.android.tv.testutil;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Timeout;

/** A response that can deliberately ignore interruption to exercise late-completion races. */
public final class BlockingHttpCall implements Call {
    public final CountDownLatch started = new CountDownLatch(1);
    public volatile CountDownLatch release;
    public volatile boolean cancelled;
    public final String body;
    public int code = 200;

    public BlockingHttpCall(String body) { this.body = body; }
    @Override public Request request() { return new Request.Builder().url("https://fixture.test").build(); }
    @Override public Response execute() throws IOException {
        started.countDown();
        if (release != null) {
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        }
        return new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(new Buffer().writeUtf8(body), null, body.length())).build();
    }
    @Override public void enqueue(Callback callback) { throw new UnsupportedOperationException(); }
    @Override public void cancel() { cancelled = true; }
    @Override public boolean isExecuted() { return started.getCount() == 0; }
    @Override public boolean isCanceled() { return cancelled; }
    @Override public Timeout timeout() { return Timeout.NONE; }
    @Override public <T> T tag(kotlin.reflect.KClass<T> type) { return null; }
    @Override public <T> T tag(Class<? extends T> type) { return null; }
    @Override public <T> T tag(kotlin.reflect.KClass<T> type, kotlin.jvm.functions.Function0<? extends T> compute) { return compute.invoke(); }
    @Override public <T> T tag(Class<T> type, kotlin.jvm.functions.Function0<? extends T> compute) { return compute.invoke(); }
    @Override public Call clone() { return new BlockingHttpCall(body); }
}
