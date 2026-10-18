package com.wuming.android.tv.utils;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Timeout;

public class DownloadTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void cancelDropsQueuedCallbacksAndPreservesCommittedFile() throws Exception {
        File file = folder.newFile("result");
        List<Runnable> work = new ArrayList<>();
        List<Runnable> main = new ArrayList<>();
        Recorder recorder = new Recorder();
        Download download = new Download("https://fixture.test", file, work::add, main::add, main::remove,
                (url, tag) -> new FakeCall("complete", 200));
        download.start(recorder);
        work.remove(0).run();
        Runnable dequeued = main.get(main.size() - 1);
        java.lang.reflect.Field current = com.wuming.tv.core.base.Download.class.getDeclaredField("current");
        current.setAccessible(true);
        Object run = current.get(download);
        download.cancel();
        dequeued.run();
        java.lang.reflect.Field callback = run.getClass().getDeclaredField("callback");
        callback.setAccessible(true);
        assertNull(callback.get(run));
        assertTrue(main.isEmpty());
        assertEquals(0, recorder.success + recorder.errors + recorder.progress);
        assertEquals("complete", Files.readString(file.toPath()));
    }

    @Test public void failedHttpDoesNotReplaceExistingDestination() throws Exception {
        File file = folder.newFile("result");
        Files.writeString(file.toPath(), "valid");
        List<Runnable> work = new ArrayList<>();
        List<Runnable> main = new ArrayList<>();
        Recorder recorder = new Recorder();
        Download download = new Download("https://fixture.test", file, work::add, main::add, main::remove,
                (url, tag) -> new FakeCall("error page", 503));
        download.start(recorder);
        work.remove(0).run();
        while (!main.isEmpty()) main.remove(0).run();
        assertEquals(1, recorder.errors);
        assertEquals(0, recorder.success);
        assertEquals("valid", Files.readString(file.toPath()));
        assertEquals(1, folder.getRoot().list().length);
    }

    @Test public void restartedRunCancelsOldCallAndCannotOverwriteNewFile() throws Exception {
        File file = folder.newFile("result");
        FakeCall old = new FakeCall("obsolete", 200);
        old.release = new CountDownLatch(1);
        FakeCall fresh = new FakeCall("fresh", 200);
        AtomicInteger calls = new AtomicInteger();
        List<Runnable> main = Collections.synchronizedList(new ArrayList<>());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Download download = new Download("https://fixture.test", file, workers, main::add, main::remove,
                (url, tag) -> calls.getAndIncrement() == 0 ? old : fresh);
        try {
            download.tag("shared").start(new Recorder());
            assertTrue(old.started.await(2, TimeUnit.SECONDS));
            download.start(new Recorder());
            assertTrue(old.isCanceled());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!"fresh".equals(Files.readString(file.toPath())) && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals("fresh", Files.readString(file.toPath()));
            old.release.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals("fresh", Files.readString(file.toPath()));
            assertEquals(1, folder.getRoot().list().length);
        } finally {
            old.release.countDown();
            download.cancel();
            workers.shutdownNow();
        }
    }

    @Test public void truncatedResponseNeverPublishesPartialFile() throws Exception {
        File file = folder.newFile("result");
        Files.writeString(file.toPath(), "valid");
        FakeCall call = new FakeCall("short", 200);
        call.length = 100;
        Download download = new Download("https://fixture.test", file, Runnable::run, Runnable::run,
                ignored -> {}, (url, tag) -> call);
        assertThrows(RuntimeException.class, download::get);
        assertEquals("valid", Files.readString(file.toPath()));
        assertEquals(1, folder.getRoot().list().length);
    }

    private static class Recorder implements Download.Callback {
        int success, errors, progress;
        @Override public void progress(int value) { progress++; }
        @Override public void error(String message) { errors++; }
        @Override public void success(File file) { success++; }
    }

    private static class FakeCall implements Call {
        final String text;
        final int code;
        final CountDownLatch started = new CountDownLatch(1);
        volatile CountDownLatch release;
        volatile boolean canceled;
        long length;
        FakeCall(String text, int code) { this.text = text; this.code = code; length = text.length(); }
        @Override public Request request() { return new Request.Builder().url("https://fixture.test").build(); }
        @Override public Response execute() throws IOException {
            started.countDown();
            if (release != null) {
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
            }
            return new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(ResponseBody.create(new Buffer().writeUtf8(text), null, length)).build();
        }
        @Override public void enqueue(Callback callback) { throw new UnsupportedOperationException(); }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isExecuted() { return started.getCount() == 0; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Timeout timeout() { return Timeout.NONE; }
        @Override public <T> T tag(kotlin.reflect.KClass<T> type) { return null; }
        @Override public <T> T tag(Class<? extends T> type) { return null; }
        @Override public <T> T tag(kotlin.reflect.KClass<T> type, kotlin.jvm.functions.Function0<? extends T> compute) { return compute.invoke(); }
        @Override public <T> T tag(Class<T> type, kotlin.jvm.functions.Function0<? extends T> compute) { return compute.invoke(); }
        @Override public Call clone() { return new FakeCall(text, code); }
    }
}
