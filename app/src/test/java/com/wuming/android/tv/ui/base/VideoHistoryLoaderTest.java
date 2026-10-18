package com.wuming.android.tv.ui.base;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Vod;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class VideoHistoryLoaderTest {
    @Test public void restoresMissingHistoryEntirelyOnWorker() {
        Fixture f = new Fixture();
        f.load();
        assertEquals(0, f.store.lookups);
        assertEquals(0, f.store.restores);
        f.work.remove(0).run();
        assertEquals(1, f.store.lookups);
        assertEquals(1, f.store.restores);
        assertNull(f.result);
        f.main.remove(0).run();
        assertFalse(f.existing);
        assertEquals("one", f.result.getKey());
        assertEquals(7, f.result.getCid());
        assertEquals("Movie", f.result.getVodName());
        assertEquals("Episode 3", f.result.getVodRemarks());
        assertEquals("requested", f.mark);
    }

    @Test public void existingHistoryBypassesEpisodeSearch() {
        Fixture f = new Fixture();
        f.store.saved = new History();
        f.load();
        f.drain();
        assertSame(f.store.saved, f.result);
        assertTrue(f.existing);
        assertEquals(0, f.store.restores);
    }

    @Test public void restartRemovesAlreadyQueuedDeliveryEvenForSameVideo() {
        Fixture f = new Fixture();
        f.load();
        f.work.remove(0).run();
        Runnable stale = f.main.get(0);
        f.load();
        assertTrue(f.main.isEmpty());
        stale.run();
        assertNull(f.result);
        f.drain();
        assertNotNull(f.result);
    }

    @Test public void navigationRejectsResultAtActualUiDelivery() {
        Fixture f = new Fixture();
        f.load();
        f.work.remove(0).run();
        f.key = "two";
        f.main.remove(0).run();
        assertNull(f.result);
    }

    @Test public void closeCancelsQueuedReadAndReleasesHost() throws Exception {
        Fixture f = new Fixture();
        f.load();
        f.loader.close();
        f.loader.close();
        f.load();
        f.drain();
        assertEquals(0, f.store.lookups);
        assertNull(f.result);
        java.lang.reflect.Field host = VideoHistoryLoader.class.getDeclaredField("host");
        host.setAccessible(true);
        assertNull(host.get(f.loader));
    }

    @Test public void closeRemovesQueuedUiResult() {
        Fixture f = new Fixture();
        f.load();
        f.work.remove(0).run();
        Runnable stale = f.main.get(0);
        f.loader.close();
        assertTrue(f.main.isEmpty());
        stale.run();
        assertNull(f.result);
    }

    @Test public void lookupAndEpisodeFailuresStillDeliverUsableDefault() {
        Fixture f = new Fixture();
        f.store.fail = true;
        f.load();
        f.drain();
        assertNotNull(f.result);
        assertEquals("one", f.result.getKey());
        assertFalse(f.existing);
        assertEquals(2, f.errors.size());
    }

    @Test public void interruptedReadCannotPublishAfterCloseEvenIfStoreIgnoresCancellation() throws Exception {
        Fixture f = new Fixture();
        f.store.entered = new CountDownLatch(1);
        f.store.release = new CountDownLatch(1);
        f.load();
        Thread thread = new Thread(f.work.remove(0));
        thread.start();
        try {
            assertTrue(f.store.entered.await(5, TimeUnit.SECONDS));
            f.loader.close();
        } finally {
            f.store.release.countDown();
            thread.join(5000);
        }
        assertFalse(thread.isAlive());
        assertTrue(f.main.isEmpty());
        assertNull(f.result);
    }

    private static class Store implements VideoHistoryLoader.Store {
        int lookups;
        int restores;
        boolean fail;
        History saved;
        CountDownLatch entered;
        CountDownLatch release;
        @Override public History find(String key) {
            lookups++;
            if (entered != null) {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { done = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { }
                }
            }
            if (fail) throw new IllegalStateException("Unavailable database");
            return saved;
        }
        @Override public void restoreEpisode(History history, Vod item) {
            restores++;
            if (fail) throw new IllegalStateException("Unavailable episodes");
            history.setVodRemarks("Episode 3");
        }
    }

    private static class Fixture implements VideoHistoryLoader.Host {
        String key = "one";
        String mark;
        History result;
        boolean existing;
        final List<RuntimeException> errors = new ArrayList<>();
        final Store store = new Store();
        final List<Runnable> work = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();
        final VideoHistoryLoader loader = new VideoHistoryLoader(this, store, work::add, main::add, main::remove, errors::add);
        @Override public String activeKey() { return key; }
        @Override public void restored(String key, String mark, Vod item, History history, boolean existing) {
            this.mark = mark;
            this.result = history;
            this.existing = existing;
        }
        void load() {
            // HTML decoding belongs to Android; this fixture tests restoration ownership.
            Vod item = new Vod() { @Override public String getName() { return "Movie"; } };
            loader.load(key, 7, "requested", item);
        }
        void drain() {
            while (!work.isEmpty()) work.remove(0).run();
            while (!main.isEmpty()) main.remove(0).run();
        }
    }
}
