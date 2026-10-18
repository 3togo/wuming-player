package com.wuming.android.tv.media.catalog;

import com.wuming.tv.core.media.catalog.MediaManifest;

import com.wuming.tv.core.media.catalog.MediaCatalogStore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.bean.History;
import com.wuming.tv.core.media.DecodedMedia;
import com.wuming.android.tv.media.DirectHttpInputDecoder;
import com.google.gson.Gson;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RoomMediaCatalogInstrumentedTest {

    private AppDatabase database;
    private RoomMediaCatalog catalog;
    private DirectHttpInputDecoder decoder;

    @Before
    public void setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),
                        AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        catalog = new RoomMediaCatalog(database, new Gson());
        decoder = new DirectHttpInputDecoder();
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void persistentSourceRoundTripsAndImportIsIdempotent() {
        DecodedMedia media = decoder.decode("https://cdn.example/movie.mp4").media();

        MediaCatalogStore.SaveResult first = catalog.save(media);
        MediaCatalogStore.SaveResult second = catalog.save(media);
        MediaManifest manifest = catalog.find(first.uri()).orElseThrow();

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.uri(), second.uri());
        assertEquals("https://cdn.example/movie.mp4",
                manifest.sources().get(0).locator().get("url"));
    }

    @Test
    public void transientSourceCannotLeakIntoCatalog() {
        DecodedMedia signed = decoder.decode(
                "https://cdn.example/movie.mp4?token=secret").media();

        assertThrows(IllegalArgumentException.class, () -> catalog.save(signed));
    }

    @Test
    public void pruneDeletesOnlyOldUnreferencedUnpinnedResourcesAndCascadesSources() {
        MediaCatalogStore.SaveResult garbage = catalog.save(
                decoder.decode("https://cdn.example/garbage.mp4").media());
        MediaCatalogStore.SaveResult historyRef = catalog.save(
                decoder.decode("https://cdn.example/history.mp4").media());
        MediaCatalogStore.SaveResult pinned = catalog.save(
                decoder.decode("https://cdn.example/pinned.mp4").media());
        long old = System.currentTimeMillis()
                - java.util.concurrent.TimeUnit.DAYS.toMillis(100);
        database.getOpenHelper().getWritableDatabase().execSQL(
                "UPDATE MediaResource SET lastAccessedAt = ?", new Object[]{old});
        catalog.pin(pinned.uri());
        History history = new History();
        history.setKey("site@@@history");
        history.setCid(1);
        history.setCreateTime(1);
        history.setMediaUri(historyRef.uri().toString());
        database.getHistoryDao().insertOrUpdate(history);

        assertEquals(1, catalog.prune());
        assertTrue(catalog.find(garbage.uri()).isEmpty());
        assertTrue(catalog.find(historyRef.uri()).isPresent());
        assertTrue(catalog.find(pinned.uri()).isPresent());
        assertTrue(database.getMediaCatalogDao().findEnabledSources(
                garbage.uri().toString()).isEmpty());
    }
}
