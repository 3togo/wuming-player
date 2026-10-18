package com.wuming.android.tv.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class MigrationInstrumentedTest {

    private final String prefix = "smoke-migration-" + java.util.UUID.randomUUID() + "-";
    private final java.util.Set<String> names = new java.util.HashSet<>();
    private final java.util.List<SupportSQLiteDatabase> opened = new java.util.ArrayList<>();

    @org.junit.After public void removeOwnedDatabases() throws IOException {
        for (SupportSQLiteDatabase database : opened) if (database.isOpen()) database.close();
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : names) context.deleteDatabase(name);
    }

    private SupportSQLiteDatabase createDatabase(String name, int version) throws IOException {
        names.add(prefix + name);
        SupportSQLiteDatabase database = helper.createDatabase(prefix + name, version);
        opened.add(database);
        return database;
    }

    private SupportSQLiteDatabase migrate(String name, int version, boolean validate,
            androidx.room.migration.Migration... migrations) throws IOException {
        SupportSQLiteDatabase database = helper.runMigrationsAndValidate(prefix + name, version, validate, migrations);
        opened.add(database);
        return database;
    }

    private static final String DATABASE_NAME = "migration-test";

    @Rule
    @SuppressWarnings("deprecation")
    public MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    @Test
    public void cloudOwnershipSurvivesUpgradeToCurrentAndNewIndexesExist() throws IOException {
        SupportSQLiteDatabase database = createDatabase("migration-cloud-current", 40);
        database.execSQL("INSERT INTO CloudCacheRecord (provider, accountHash, remoteFolderId, remoteFileId, displayName, sourceSiteKey, sizeBytes, state, createdAt, updatedAt) VALUES ('alipan', 'account', 'folder', 'copy', 'video', 'site', 100, 0, 1, 1)");
        database.close();
        database = migrate("migration-cloud-current", AppDatabase.VERSION, true, Migrations.all());
        assertSingleInt(database, "SELECT COUNT(*) FROM CloudCacheRecord WHERE accountHash = 'account' AND remoteFileId = 'copy'", 1);
        assertTrue(hasIndex(database, "SitePerformance", "index_SitePerformance_performanceScore"));
        assertTrue(hasIndex(database, "SitePerformance", "index_SitePerformance_reliabilityScore"));
        assertTrue(hasIndex(database, "SitePerformance", "index_SitePerformance_avgSpeed"));
        database.close();
    }

    @Test
    public void migration38To39PreservesMetricsAndAddsFailureCount() throws IOException {
        SupportSQLiteDatabase database = createDatabase(DATABASE_NAME, 38);
        database.execSQL("INSERT INTO SitePerformance (siteKey, totalRequests, successCount, deadLinkCount, totalSpeed, speedSampleCount, lastSpeed, avgSpeed, lastSuccessTime, lastFailureTime, reliabilityScore, performanceScore, updatedAt) VALUES ('site', 3, 2, 1, 3000, 2, 2000, 1500, 10, 20, 0.66, 0.55, 30)");
        database.close();

        database = migrate(DATABASE_NAME, 39, true, Migrations.MIGRATION_38_39);
        try (Cursor cursor = database.query("SELECT failureCount, totalSpeed, avgSpeed FROM SitePerformance WHERE siteKey = 'site'")) {
            cursor.moveToFirst();
            assertEquals(0, cursor.getInt(0));
            assertEquals(3000.0, cursor.getDouble(1), 0.0);
            assertEquals(1500.0, cursor.getDouble(2), 0.0);
        }
    }

    @Test
    public void everyExportedSchemaMigratesToCurrentVersion() throws IOException {
        for (int version = 35; version < AppDatabase.VERSION; version++) {
            assertMigratesToCurrent(version, "migration-" + version, Migrations.all());
        }
    }

    @Test
    public void migration39To40AddsTrustedCloudTables() throws IOException {
        createDatabase("migration-cloud-39", 39).close();
        SupportSQLiteDatabase database = migrate(
                "migration-cloud-39", 40, true, Migrations.MIGRATION_39_40);
        database.execSQL("INSERT INTO CloudCacheRecord (provider, accountHash, remoteFolderId, remoteFileId, displayName, sourceSiteKey, sizeBytes, state, createdAt, updatedAt) VALUES ('quark', 'account', 'folder', 'file', 'video.mp4', 'site', 100, 0, 1, 1)");
        database.execSQL("INSERT INTO CloudAuditEvent (provider, operation, success, itemCount, bytes, message, createdAt) VALUES ('quark', 'record', 1, 1, 100, '', 1)");
        assertSingleInt(database,
                "SELECT COUNT(*) FROM CloudCacheRecord WHERE remoteFileId = 'file'", 1);
        assertSingleInt(database,
                "SELECT COUNT(*) FROM CloudAuditEvent WHERE operation = 'record'", 1);
    }

    @Test
    public void migration40To41AddsViewingRecords() throws IOException {
        createDatabase("migration-viewing-40", 40).close();
        SupportSQLiteDatabase database = migrate(
                "migration-viewing-40", 41, true, Migrations.MIGRATION_40_41);
        database.execSQL("INSERT INTO ViewingRecord (id, `key`, siteKey, vodId, vodName, startedAt, endedAt, lastWatchedAt, watchedDuration, mediaDuration, startPosition, endPosition, completed, cid) VALUES ('session', 'site@@@movie', 'site', 'movie', 'Movie', 1, 2, 2, 1000, 2000, 0, 1000, 0, 7)");
        assertSingleInt(database,
                "SELECT watchedDuration FROM ViewingRecord WHERE id = 'session'", 1000);
    }

    @Test
    public void migration41To42AddsProviderNeutralMediaCatalog() throws IOException {
        createDatabase("migration-media-41", 41).close();
        SupportSQLiteDatabase database = migrate(
                "migration-media-41", 42, true, Migrations.MIGRATION_41_42);
        database.execSQL("INSERT INTO MediaResource (uri, authority, kind, resourceId, title, manifestVersion, createdAt, updatedAt) VALUES ('opendrive://local/asset/movie', 'local', 'asset', 'movie', 'Movie', 1, 1, 1)");
        database.execSQL("INSERT INTO MediaSource (sourceId, resourceUri, adapterId, locatorVersion, locatorJson, credentialRef, fingerprint, priority, enabled, createdAt, updatedAt) VALUES ('source', 'opendrive://local/asset/movie', 'org.opendrive.http', 1, '{\"url\":\"https://example.test/movie.mp4\"}', '', 'sha256:0000000000000000000000000000000000000000000000000000000000000000', 0, 1, 1, 1)");
        assertSingleInt(database,
                "SELECT COUNT(*) FROM MediaSource WHERE resourceUri = 'opendrive://local/asset/movie'", 1);
    }

    @Test
    public void migration42To43AddsNullableMediaUriWithoutLosingHistory() throws IOException {
        SupportSQLiteDatabase database = createDatabase("migration-history-uri-42", 42);
        database.execSQL("INSERT INTO History (`key`, vodName, vodRemarks, episodeUrl, revSort, revPlay, createTime, opening, ending, position, duration, speed, scale, cid) VALUES ('site@@@movie', 'Movie', 'Episode 1', 'https://example.test/movie.mp4', 0, 0, 11, 0, 0, 100, 200, 1.0, 0, 7)");
        database.close();

        database = migrate("migration-history-uri-42", 43, true,
                Migrations.MIGRATION_42_43);
        try (Cursor cursor = database.query(
                "SELECT vodName, episodeUrl, mediaUri FROM History WHERE `key` = 'site@@@movie'")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("Movie", cursor.getString(0));
            assertEquals("https://example.test/movie.mp4", cursor.getString(1));
            assertEquals(null, cursor.getString(2));
        }
    }

    @Test
    public void migration43To44AddsCatalogLifecycleWithoutChangingIdentity() throws IOException {
        SupportSQLiteDatabase database = createDatabase("migration-catalog-lifecycle-43", 43);
        database.execSQL("INSERT INTO MediaResource (uri, authority, kind, resourceId, title, manifestVersion, createdAt, updatedAt) VALUES ('opendrive://local/asset/movie', 'local', 'asset', 'movie', 'Movie', 1, 10, 20)");
        database.close();

        database = migrate("migration-catalog-lifecycle-43", 44, true,
                Migrations.MIGRATION_43_44);
        try (Cursor cursor = database.query("SELECT uri, lastAccessedAt, pinned FROM MediaResource")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("opendrive://local/asset/movie", cursor.getString(0));
            assertEquals(20, cursor.getLong(1));
            assertEquals(0, cursor.getInt(2));
        }
    }

    @Test
    public void migration36To37AddsDanmakuWithoutLosingConfig() throws IOException {
        SupportSQLiteDatabase database = createDatabase("migration-config-36", 36);
        database.execSQL("INSERT INTO Config (type, time, url, name) VALUES (0, 123, 'https://example.test/config.json', 'Original')");
        database.close();

        database = migrate("migration-config-36", 37, true,
                Migrations.MIGRATION_36_37);
        try (Cursor cursor = database.query("SELECT time, name, danmaku FROM Config WHERE type = 0")) {
            cursor.moveToFirst();
            assertEquals(123, cursor.getLong(0));
            assertEquals("Original", cursor.getString(1));
            assertEquals(null, cursor.getString(2));
        }
        database.execSQL("INSERT INTO drive (name, type, configJson) VALUES ('Drive A', 1, '{}')");
        database.execSQL("INSERT INTO SearchHistory (keyword, timestamp, frequency) VALUES ('Movie', 10, 2)");
        assertSingleInt(database, "SELECT COUNT(*) FROM drive WHERE name = 'Drive A'", 1);
        assertSingleInt(database, "SELECT frequency FROM SearchHistory WHERE keyword = 'Movie'", 2);
    }

    @Test
    public void migration35To36PreservesRowsAndAddsQueryIndexes() throws IOException {
        SupportSQLiteDatabase database = createDatabase("migration-data-35", 35);
        database.execSQL("INSERT INTO Keep (`key`, vodName, createTime, type, cid) VALUES ('site@@@keep', 'Keep A', 10, 0, 7)");
        database.execSQL("INSERT INTO History (`key`, vodName, revSort, revPlay, createTime, opening, ending, position, duration, speed, scale, cid) VALUES ('site@@@history', 'History A', 0, 0, 11, 0, 0, 100, 200, 1.0, 0, 7)");
        database.close();

        database = migrate("migration-data-35", 36, true,
                Migrations.MIGRATION_35_36);
        assertSingleInt(database, "SELECT cid FROM Keep WHERE `key` = 'site@@@keep'", 7);
        assertSingleInt(database, "SELECT position FROM History WHERE `key` = 'site@@@history'", 100);
        assertTrue(hasIndex(database, "Keep", "index_Keep_cid_type"));
        assertTrue(hasIndex(database, "History", "index_History_cid_vodName"));
    }

    @Test
    public void migration37To38PreservesExistingDataAndCreatesMetricsTable() throws IOException {
        SupportSQLiteDatabase database = createDatabase("migration-data-37", 37);
        database.execSQL("INSERT INTO Config (type, time, url, name, danmaku) VALUES (0, 22, 'https://example.test/v37.json', 'Version 37', 'danmaku')");
        database.close();

        database = migrate("migration-data-37", 38, true,
                Migrations.MIGRATION_37_38);
        assertSingleInt(database, "SELECT time FROM Config WHERE name = 'Version 37'", 22);
        database.execSQL("INSERT INTO SitePerformance (siteKey, totalRequests, successCount, deadLinkCount, totalSpeed, speedSampleCount, lastSpeed, avgSpeed, lastSuccessTime, lastFailureTime, reliabilityScore, performanceScore, updatedAt) VALUES ('fixture', 1, 1, 0, 100, 1, 100, 100, 1, 0, 1.0, 1.0, 2)");
        assertSingleInt(database, "SELECT totalRequests FROM SitePerformance WHERE siteKey = 'fixture'", 1);
    }

    private void assertMigratesToCurrent(int startVersion, String name,
                                         androidx.room.migration.Migration... migrations) throws IOException {
        createDatabase(name, startVersion).close();
        migrate(name, AppDatabase.VERSION, true, migrations).close();
    }

    private static void assertSingleInt(SupportSQLiteDatabase database, String query, int expected) {
        try (Cursor cursor = database.query(query)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(expected, cursor.getInt(0));
        }
    }

    private static boolean hasIndex(SupportSQLiteDatabase database, String table, String expected) {
        try (Cursor cursor = database.query("PRAGMA index_list(`" + table + "`)")) {
            int name = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) if (expected.equals(cursor.getString(name))) return true;
            return false;
        }
    }
}
