package com.wuming.android.tv.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.bean.Site;
import com.wuming.tv.core.library.FavoriteRepository;
import com.wuming.tv.core.library.HistoryRepository;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserLibraryTest {

    @Test
    public void historiesAreFilteredAndSortedNewestFirstRegardlessOfRankOrInsertionOrder() {
        FakeHistories histories = new FakeHistories();
        History low = history("one@@@low", "Title", 1_000, 20);
        History high = history("one@@@high", "Title", 1_000, 20);
        History unavailable = history("two@@@other", "Title", 1_000, 20);
        History middle = history("one@@@middle", "Title", 1_000, 20);
        History hidden = history("one@@@hidden", "Title", 1_000, 20);
        low.setCreateTime(300);
        high.setCreateTime(100);
        middle.setCreateTime(200);
        unavailable.setCreateTime(500);
        hidden.setCreateTime(400);
        histories.items.addAll(List.of(middle, unavailable, high, hidden, low));
        Site site = new Site();
        site.setKey("one");
        UserLibrary library = library(histories, new FakeFavorites(), List.of(site),
                Map.of(low.getKey(), 1, high.getKey(), 9, unavailable.getKey(), 10,
                        hidden.getKey(), -1));

        assertEquals(List.of(low, middle, high), library.histories());
    }

    @Test
    public void mergeCopiesPreferencesDeletesOldItemAndSavesIncoming() {
        FakeHistories histories = new FakeHistories();
        History existing = history("one@@@old", "Title", 100_000, 7);
        existing.setOpening(3_000);
        existing.setEnding(4_000);
        existing.setSpeed(1.5f);
        histories.items.add(existing);
        History incoming = history("one@@@new", "Title", 100_001, 7);
        UserLibrary library = library(histories, new FakeFavorites(), List.of(), Map.of());

        assertSame(incoming, library.mergeAndSave(incoming));

        assertEquals(3_000, incoming.getOpening());
        assertEquals(4_000, incoming.getEnding());
        assertEquals(1.5f, incoming.getSpeed(), 0.0f);
        assertEquals("7:one@@@old", histories.deleted.get(0));
        assertSame(incoming, histories.saved);
        assertEquals(12_345, incoming.getUpdateTime());
    }

    @Test
    public void favoriteWorkflowUsesRepositoryWithoutEntityPersistenceMethods() {
        FakeFavorites favorites = new FakeFavorites();
        Keep keep = new Keep();
        keep.setKey("one@@@favorite");
        keep.setCid(7);
        UserLibrary library = library(new FakeHistories(), favorites, List.of(), Map.of());

        library.saveFavorite(keep);
        assertSame(keep, favorites.saved);
        assertSame(keep, library.deleteFavorite(keep));
        assertEquals("7:one@@@favorite", favorites.deleted);
    }

    private static UserLibrary library(
            FakeHistories histories,
            FakeFavorites favorites,
            List<Site> sites,
            Map<String, Integer> ranks) {
        return new UserLibrary(histories, favorites, () -> 7, () -> sites,
                key -> ranks.getOrDefault(key, 0), () -> 12_345L);
    }

    private static History history(String key, String name, long duration, int catalogId) {
        History history = new History();
        history.setKey(key);
        history.setVodName(name);
        history.setDuration(duration);
        history.setCid(catalogId);
        return history;
    }

    private static final class FakeHistories implements HistoryRepository<History> {
        private final List<History> items = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private History saved;

        @Override public List<History> recent(long cutoffTimeMillis) { return new ArrayList<>(items); }
        @Override public List<History> recent(int catalogId, long cutoffTimeMillis) {
            return items.stream().filter(item -> item.getCid() == catalogId).toList();
        }
        @Override public History findByKey(String key) {
            return items.stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(null);
        }
        @Override public List<History> findByName(String name) {
            return items.stream().filter(item -> name.equals(item.getVodName())).toList();
        }
        @Override public void save(History item) { saved = item; }
        @Override public void delete(int catalogId, String key) { deleted.add(catalogId + ":" + key); }
        @Override public void deleteCatalog(int catalogId) { }
    }

    private static final class FakeFavorites implements FavoriteRepository<Keep> {
        private final Map<String, Keep> items = new HashMap<>();
        private Keep saved;
        private String deleted;

        @Override public Keep find(int catalogId, String key) { return items.get(key); }
        @Override public boolean exists(String key) { return items.containsKey(key); }
        @Override public List<Keep> videoFavorites() { return new ArrayList<>(items.values()); }
        @Override public List<Keep> liveFavorites() { return List.of(); }
        @Override public void save(Keep item) { saved = item; items.put(item.getKey(), item); }
        @Override public void deleteVideo(int catalogId, String key) { deleted = catalogId + ":" + key; }
        @Override public void deleteLive(String key) { }
        @Override public void deleteVideoCatalog(int catalogId) { }
        @Override public void deleteAllVideos() { }
    }
}
