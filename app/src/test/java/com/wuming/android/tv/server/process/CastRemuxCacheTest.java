package com.wuming.android.tv.server.process;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CastRemuxCacheTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private CastRemuxCache.Key key(String name) { return new CastRemuxCache.Key(name, Map.of()); }
    private File file(int size) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), new byte[size]);
        return file;
    }
    private void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
    private void waiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        assertEquals(Thread.State.WAITING, thread.getState());
    }
    private Thread request(CastRemuxCache cache, String name, CastRemuxCache.Builder builder,
                           AtomicReference<Object> result) {
        Thread thread = new Thread(() -> {
            try { result.set(cache.open(key(name), builder)); }
            catch (Exception error) { result.set(error); }
        });
        thread.start();
        return thread;
    }
    private void join(Thread thread) throws Exception { thread.join(5000); assertFalse(thread.isAlive()); }

    @Test public void headersAreImmutableAndPartOfIdentity() {
        assertTrue("The normalized key retains its JVM record contract", CastRemuxCache.Key.class.isRecord());
        Map<String,String> headers = new HashMap<>(); headers.put("Authorization", "first");
        CastRemuxCache.Key key = new CastRemuxCache.Key("url", headers);
        headers.put("Authorization", "second");
        assertEquals("first", key.headers().get("Authorization"));
        assertNotEquals(key, new CastRemuxCache.Key("url", headers));
        assertFalse(key.toString().contains("first"));
    }

    @Test public void leasesProtectFilesUntilResponseCloses() throws Exception {
        CastRemuxCache cache = new CastRemuxCache(8, 1);
        File first = file(8), rejected = file(8), next = file(8);
        CastRemuxCache.Lease lease = cache.open(key("first"), () -> first);
        var response = CastMediaRelay.serveLocalFile(lease, "video/mp4", "bytes=2-4");
        assertEquals("bytes 2-4/8", response.getHeader("Content-Range"));
        assertEquals(0, response.getData().read());
        assertNull(cache.open(key("blocked"), () -> rejected));
        assertTrue(first.exists());
        response.close(); lease.close();
        try (var acquired = cache.open(key("next"), () -> next)) { assertNotNull(acquired); }
        assertFalse(first.exists());
    }

    @Test public void oversizedAndFailedPreparationsCanRetry() throws Exception {
        CastRemuxCache cache = new CastRemuxCache(8, 2);
        assertNull(cache.open(key("large"), () -> file(9)));
        try { cache.open(key("error"), () -> { throw new java.io.IOException("test"); }); fail(); }
        catch (java.io.IOException expected) { assertEquals("test", expected.getMessage()); }
        // Other assets remain usable after rejection/failure.
        try (var lease = cache.open(key("good"), () -> file(8))) { assertNotNull(lease); }
    }

    @Test public void sharedPreparationSurvivesOneInterruptedWaiter() throws Exception {
        CastRemuxCache cache = new CastRemuxCache(8, 2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger builds = new AtomicInteger();
        CastRemuxCache.Builder builder = () -> { builds.incrementAndGet(); entered.countDown(); release.await(); return file(4); };
        AtomicReference<Object> a = new AtomicReference<>(), b = new AtomicReference<>();
        Thread first = request(cache, "same", builder, a); await(entered);
        Thread second = request(cache, "same", builder, b); waiting(second);
        first.interrupt(); join(first); release.countDown(); join(second);
        assertTrue(a.get() instanceof java.io.InterruptedIOException);
        assertTrue(b.get() instanceof CastRemuxCache.Lease);
        ((CastRemuxCache.Lease)b.get()).close();
        assertEquals(1, builds.get());
    }

    @Test public void independentAssetsProgressAndThirdFallsBack() throws Exception {
        CastRemuxCache cache = new CastRemuxCache(8, 2);
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        CastRemuxCache.Builder builder = () -> { entered.countDown(); release.await(); return file(4); };
        AtomicReference<Object> a = new AtomicReference<>(), b = new AtomicReference<>();
        Thread first = request(cache, "a", builder, a), second = request(cache, "b", builder, b);
        try { await(entered); assertNull(cache.open(key("third"), () -> { fail("Must not prepare"); return null; })); }
        finally { release.countDown(); }
        join(first); join(second);
        assertTrue(a.get() instanceof CastRemuxCache.Lease); assertTrue(b.get() instanceof CastRemuxCache.Lease);
        ((CastRemuxCache.Lease)a.get()).close(); ((CastRemuxCache.Lease)b.get()).close();
    }

    @Test public void abandonedBuilderCannotPublishLateFile() throws Exception {
        CastRemuxCache cache = new CastRemuxCache(8, 2);
        File late = file(4);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread waiter = request(cache, "late", () -> {
            entered.countDown();
            for (;;) { try { release.await(); break; } catch (InterruptedException ignored) {} }
            returned.countDown(); return late;
        }, result);
        await(entered); waiter.interrupt(); join(waiter);
        assertNull(cache.open(key("late"), () -> { fail("Abandoned slot must stay reserved"); return null; }));
        release.countDown(); await(returned);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (late.exists() && System.nanoTime() < deadline) Thread.yield();
        assertFalse(late.exists());
        assertTrue(result.get() instanceof java.io.InterruptedIOException);
    }
}
