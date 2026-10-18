package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.Keep;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoFavoritesControllerTest {
    @Test public void togglePersistsAndNotifiesWhileCheckOnlyUpdatesIcon() {
        Fixture f = new Fixture();
        f.controller.toggle(candidate("one"));
        f.drain();
        assertEquals(List.of("true:true"), f.results);
        f.controller.check("one");
        f.drain();
        assertEquals(List.of("true:true", "true:false"), f.results);
        f.controller.toggle(candidate("one"));
        f.drain();
        assertNull(f.store.find("one"));
        assertEquals("false:true", f.results.get(2));
    }

    @Test public void outOfOrderChecksOnlyDeliverNewestRequest() {
        Fixture f = new Fixture();
        f.controller.check("one");
        f.controller.toggle(candidate("one"));
        f.work.remove(1).run();
        f.work.remove(0).run();
        f.drain();
        assertEquals(List.of("true:true"), f.results);
    }

    @Test public void navigatingAwaySuppressesResultAlreadyQueuedForDisplay() {
        Fixture f = new Fixture();
        f.controller.check("one");
        f.work.remove(0).run();
        f.key = "two";
        f.drain();
        assertTrue(f.results.isEmpty());
    }

    @Test public void closingReleasesHostButAllowsRequestedWriteToFinish() throws Exception {
        Fixture f = new Fixture();
        f.controller.toggle(candidate("one"));
        f.controller.close();
        f.controller.close();
        f.controller.check("one");
        assertEquals(1, f.work.size());
        f.drain();
        assertEquals("one", f.store.find("one").getKey());
        assertTrue(f.results.isEmpty());
        java.lang.reflect.Field host = VideoFavoritesController.class.getDeclaredField("host");
        host.setAccessible(true);
        assertNull(host.get(f.controller));
    }

    @Test public void metadataUpdateChangesExistingFavoriteWithoutCreatingOne() {
        Fixture f = new Fixture();
        f.store.save(candidate("one"));
        f.controller.update("one", "renamed", "new-picture");
        f.controller.update("missing", "ignored", "ignored");
        f.drain();
        assertEquals("renamed", f.store.find("one").getVodName());
        assertEquals("new-picture", f.store.find("one").getVodPic());
        assertNull(f.store.find("missing"));
        assertTrue(f.results.isEmpty());
    }

    private static Keep candidate(String key) {
        Keep keep = new Keep();
        keep.setKey(key);
        return keep;
    }

    private static class Store implements VideoFavoritesController.Store {
        final Map<String, Keep> values = new HashMap<>();
        @Override public Keep find(String key) { return values.get(key); }
        @Override public void save(Keep keep) { values.put(keep.getKey(), keep); }
        @Override public void delete(Keep keep) { values.remove(keep.getKey()); }
    }

    private static class Fixture implements VideoFavoritesController.Host {
        String key = "one";
        final Store store = new Store();
        final List<Runnable> work = new ArrayList<>();
        final List<Runnable> main = new ArrayList<>();
        final List<String> results = new ArrayList<>();
        final VideoFavoritesController controller = new VideoFavoritesController(this, store, work::add, main::add);
        @Override public String activeKey() { return key; }
        @Override public void showFavorite(boolean kept, boolean notify) { results.add(kept + ":" + notify); }
        void drain() {
            while (!work.isEmpty()) work.remove(0).run();
            while (!main.isEmpty()) main.remove(0).run();
        }
    }
}
