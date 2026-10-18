package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.db.AppDatabase;
import com.wuming.android.tv.media.catalog.MediaResourceEntity;
import com.wuming.android.tv.media.catalog.MediaSourceEntity;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class BackupInstrumentedTest {

    private AppDatabase database;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void portableRestorePreservesLocalCloudOwnershipAndAudit() {
        com.wuming.android.tv.cloud.CloudCacheRecord record = new com.wuming.android.tv.cloud.CloudCacheRecord();
        record.provider = "alipan";
        record.accountHash = "local-account";
        record.remoteFolderId = "owned-folder";
        record.remoteFileId = "owned-copy";
        database.getCloudCacheDao().insert(record);
        com.wuming.android.tv.cloud.CloudAuditEvent event = new com.wuming.android.tv.cloud.CloudAuditEvent();
        event.provider = "alipan";
        event.operation = "tracked-copy";
        database.getCloudAuditDao().insert(event);
        Backup backup = new Backup();
        backup.setSite(List.of(site("restored", 1)));
        backup.restore(database);
        assertEquals(1, database.getCloudCacheDao().active("alipan", "local-account", "owned-folder").size());
        assertEquals(0, database.getCloudCacheDao().active("alipan", "other-account", "owned-folder").size());
        assertEquals(1, database.getCloudAuditDao().recent("alipan", 10).size());
        assertEquals("restored", database.getSiteDao().findAll().get(0).getKey());
    }

    @Test
    public void backupJsonRoundTripRestoresEverySupportedTable() {
        Backup backup = new Backup();
        backup.setSite(List.of(site("site-a", 0)));
        backup.setLive(List.of(live("Live A", "CCTV-1")));
        backup.setKeep(List.of(keep("site-a@@@vod-a", "Movie A")));
        backup.setConfig(List.of(config("https://example.test/config.json", "Config A")));
        backup.setHistory(List.of(history("site-a@@@vod-a", "Movie A")));
        MediaResourceEntity resource = new MediaResourceEntity();
        resource.uri = "opendrive://local/asset/movie-a";
        resource.authority = "local";
        resource.kind = "asset";
        resource.resourceId = "movie-a";
        resource.title = "Movie A";
        resource.createdAt = resource.updatedAt = resource.lastAccessedAt = 1;
        MediaSourceEntity source = new MediaSourceEntity();
        source.sourceId = "source-a";
        source.resourceUri = resource.uri;
        source.adapterId = "org.opendrive.http";
        source.locatorVersion = 1;
        source.locatorJson = "{\"url\":\"https://example.test/movie-a.mp4\"}";
        source.fingerprint = "sha256:0000000000000000000000000000000000000000000000000000000000000000";
        source.createdAt = source.updatedAt = 1;
        backup.setMediaResource(List.of(resource));
        backup.setMediaSource(List.of(source));

        Site stale = site("stale", 1);
        database.getSiteDao().insertOrUpdate(stale);

        Backup.objectFrom(backup.toString()).restore(database);

        assertEquals(1, database.getSiteDao().findAll().size());
        assertEquals("site-a", database.getSiteDao().findAll().get(0).getKey());
        assertEquals("CCTV-1", database.getLiveDao().find("Live A").getKeep());
        assertEquals("Movie A", database.getKeepDao().getVod().get(0).getVodName());
        assertEquals("Config A", database.getConfigDao().findOne(0).getName());
        History restoredHistory = database.getHistoryDao().findByKey("site-a@@@vod-a");
        assertEquals("Movie A", restoredHistory.getVodName());
        assertEquals("opendrive://local/asset/movie-a", restoredHistory.getMediaUri());
        assertEquals("https://example.test/movie-a.mp4", restoredHistory.getEpisodeUrl());
        assertEquals("Movie A", database.getMediaCatalogDao().findResource(
                "opendrive://local/asset/movie-a").title);
        assertEquals(1, database.getMediaCatalogDao().findEnabledSources(
                "opendrive://local/asset/movie-a").size());
    }

    @Test
    public void malformedAndNullBackupsProduceSafeEmptyObjects() {
        Backup malformed = Backup.objectFrom("{not-json");
        Backup nullJson = Backup.objectFrom("null");

        assertTrue(malformed.getSite().isEmpty());
        assertTrue(malformed.getConfig().isEmpty());
        assertTrue(nullJson.getHistory().isEmpty());
        assertTrue(nullJson.getPrefers().isEmpty());
    }

    @Test
    public void preferencesRoundTripEverySupportedPrimitiveType() {
        String prefix = "backup_test_";
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(prefix + "string", "value");
        values.put(prefix + "boolean", true);
        values.put(prefix + "integer", 42);
        values.put(prefix + "long", 4_000_000_000L);
        values.put(prefix + "float", 1.25f);
        try {
            Backup backup = new Backup();
            backup.setPrefers(values);
            Backup.objectFrom(backup.toString()).restore(database);

            Map<String, ?> restored = Prefers.getPrefers().getAll();
            assertEquals("value", restored.get(prefix + "string"));
            assertEquals(true, restored.get(prefix + "boolean"));
            assertEquals(42, restored.get(prefix + "integer"));
            assertEquals(4_000_000_000L, restored.get(prefix + "long"));
            assertEquals(1.25f, (Float) restored.get(prefix + "float"), 0.0f);
        } finally {
            android.content.SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            values.keySet().forEach(editor::remove);
            editor.apply();
        }
    }

    @Test
    public void remoteAccessCredentialsAreNeitherExportedNorRestored() {
        String serverKey = "remote_server_token";
        String pairedKey = "remote_paired_tokens";
        String existingServerToken = "existing-secret";
        try {
            Prefers.put(serverKey, existingServerToken);
            Prefers.put(pairedKey, "paired-secret");

            Backup captured = Backup.create(database);
            assertFalse(captured.getPrefers().containsKey(serverKey));
            assertFalse(captured.getPrefers().containsKey(pairedKey));

            Backup imported = new Backup();
            imported.setPrefers(Map.of(serverKey, "imported-secret", pairedKey, "imported-pairing"));
            imported.restore(database);

            assertEquals(existingServerToken, Prefers.getString(serverKey));
            assertEquals("paired-secret", Prefers.getString(pairedKey));
        } finally {
            Prefers.remove(serverKey);
            Prefers.remove(pairedKey);
        }
    }

    @Test
    public void createCapturesAllAndPartialRestoreClearsUnsupportedStaleRows() {
        database.getSiteDao().insertOrUpdate(site("created-site", 1));
        database.getLiveDao().insertOrUpdate(live("Created Live", "CCTV-2"));
        database.getKeepDao().insertOrUpdate(keep("created@@@vod", "Created Keep"));
        database.getConfigDao().insertOrUpdate(config("https://example.test/created.json", "Created Config"));
        database.getHistoryDao().insertOrUpdate(history("created@@@vod", "Created History"));
        MediaResourceEntity resource = new MediaResourceEntity();
        resource.uri = "opendrive://local/asset/created";
        resource.authority = "local";
        resource.kind = "asset";
        resource.resourceId = "created";
        resource.createdAt = resource.updatedAt = resource.lastAccessedAt = 1;
        database.getMediaCatalogDao().insertResource(resource);

        Backup captured = Backup.create(database);
        assertEquals(1, captured.getSite().size());
        assertEquals(1, captured.getLive().size());
        assertEquals(1, captured.getKeep().size());
        assertEquals(1, captured.getConfig().size());
        assertEquals(1, captured.getHistory().size());
        assertEquals(1, captured.getMediaResource().size());

        Backup partial = Backup.objectFrom("{\"site\":[{\"key\":\"partial\",\"name\":\"Partial\"}]} ");
        partial.restore(database);
        assertEquals(1, database.getSiteDao().findAll().size());
        assertEquals("partial", database.getSiteDao().findAll().get(0).getKey());
        assertTrue(database.getLiveDao().findAll().isEmpty());
        assertTrue(database.getKeepDao().findAll().isEmpty());
        assertTrue(database.getConfigDao().findAll().isEmpty());
        assertTrue(database.getHistoryDao().findAll().isEmpty());
        assertTrue(database.getMediaCatalogDao().findAllResources().isEmpty());
    }

    @Test
    public void largeBackupPayloadRoundTripsWithoutDroppingEntries() {
        List<Site> sites = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) sites.add(site("site-" + index, index % 2));
        Backup backup = new Backup();
        backup.setSite(sites);

        Backup decoded = Backup.objectFrom(backup.toString());
        assertEquals(1_000, decoded.getSite().size());
        assertEquals("site-0", decoded.getSite().get(0).getKey());
        assertEquals("site-999", decoded.getSite().get(999).getKey());
    }

    @Test
    public void failedRestoreRollsBackDatabaseAndDoesNotApplyPreferences() {
        String preferenceKey = "backup_atomicity_test_" + java.util.UUID.randomUUID();
        database.getSiteDao().insertOrUpdate(site("existing", 1));
        database.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER backup_restore_failure BEFORE INSERT ON Site " +
                        "WHEN NEW.key = 'invalid' BEGIN SELECT RAISE(ABORT, 'forced restore failure'); END");
        Backup invalid = new Backup();
        invalid.setSite(List.of(site("invalid", 1)));
        invalid.setPrefers(Map.of(preferenceKey, "must-not-apply"));
        Prefers.remove(preferenceKey);

        try {
            assertThrows(RuntimeException.class, () -> invalid.restore(database));
            assertEquals(1, database.getSiteDao().findAll().size());
            assertEquals("existing", database.getSiteDao().findAll().get(0).getKey());
            assertFalse(Prefers.getPrefers().contains(preferenceKey));
        } finally {
            database.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER IF EXISTS backup_restore_failure");
            Prefers.remove(preferenceKey);
        }
    }

    private static Site site(String key, int searchable) {
        Site site = Site.get(key, key);
        site.setSearchable(searchable);
        return site;
    }

    private static Live live(String name, String keep) {
        Live live = new Live(name, "https://example.test/live.m3u");
        live.setKeep(keep);
        return live;
    }

    private static Keep keep(String key, String name) {
        Keep keep = new Keep();
        keep.setKey(key);
        keep.setVodName(name);
        keep.setCid(1);
        keep.setType(0);
        keep.setCreateTime(1);
        return keep;
    }

    private static Config config(String url, String name) {
        Config config = new Config();
        config.setUrl(url);
        config.setName(name);
        config.setType(0);
        config.setTime(1);
        return config;
    }

    private static History history(String key, String name) {
        History history = new History();
        history.setKey(key);
        history.setVodName(name);
        history.setCid(1);
        history.setCreateTime(1);
        history.setVodRemarks("Episode 1");
        history.setEpisodeUrl("https://example.test/movie-a.mp4");
        history.setMediaUri("opendrive://local/asset/movie-a");
        return history;
    }
}
