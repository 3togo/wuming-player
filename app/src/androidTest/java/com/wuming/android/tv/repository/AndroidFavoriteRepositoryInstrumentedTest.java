package com.wuming.android.tv.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.db.DataCache;
import com.wuming.tv.core.library.FavoriteRepository;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class AndroidFavoriteRepositoryInstrumentedTest {

    @Test
    public void deletingVideosPersistsAndPreservesLiveFavorites() {
        DataCache.awaitReady();
        FavoriteRepository<Keep> repository = AndroidFavoriteRepository.getInstance();
        int catalogId = 917_331;
        String suffix = UUID.randomUUID().toString();
        Keep video = keep("video@@@" + suffix, catalogId, 0);
        Keep live = keep("live@@@" + suffix, 0, 1);

        try {
            repository.save(video);
            repository.save(live);
            await(() -> AppDatabase.get().getKeepDao().find(catalogId, video.getKey()) != null);
            await(() -> AppDatabase.get().getKeepDao().find(live.getKey()) != null);

            repository.deleteVideo(catalogId, video.getKey());
            await(() -> AppDatabase.get().getKeepDao().find(catalogId, video.getKey()) == null);
            assertNull(AppDatabase.get().getKeepDao().find(catalogId, video.getKey()));
            assertNotNull(AppDatabase.get().getKeepDao().find(live.getKey()));

            repository.save(video);
            await(() -> AppDatabase.get().getKeepDao().find(catalogId, video.getKey()) != null);
            repository.deleteAllVideos();

            assertFalse(DataCache.get().getVodKeeps().stream()
                    .anyMatch(item -> item.getKey().equals(video.getKey())));
            assertTrue(DataCache.get().getLiveKeeps().stream()
                    .anyMatch(item -> item.getKey().equals(live.getKey())));
            await(() -> AppDatabase.get().getKeepDao().find(catalogId, video.getKey()) == null);
            assertNull(AppDatabase.get().getKeepDao().find(catalogId, video.getKey()));
            assertNotNull(AppDatabase.get().getKeepDao().find(live.getKey()));
        } finally {
            repository.deleteVideo(catalogId, video.getKey());
            repository.deleteLive(live.getKey());
        }
    }

    private static Keep keep(String key, int catalogId, int type) {
        Keep keep = new Keep();
        keep.setKey(key);
        keep.setCid(catalogId);
        keep.setType(type);
        keep.setVodName(key);
        keep.setCreateTime(System.currentTimeMillis());
        return keep;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5_000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25);
        }
        assertTrue("Timed out waiting for asynchronous persistence", condition.getAsBoolean());
    }
}
