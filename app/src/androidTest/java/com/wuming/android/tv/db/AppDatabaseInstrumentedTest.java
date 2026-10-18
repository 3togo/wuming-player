package com.wuming.android.tv.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.bean.History;
import com.wuming.android.tv.bean.Keep;
import com.wuming.android.tv.bean.Config;
import com.wuming.android.tv.bean.Device;
import com.wuming.android.tv.bean.Drive;
import com.wuming.android.tv.bean.Live;
import com.wuming.android.tv.bean.Site;
import com.wuming.android.tv.bean.SitePerformance;
import com.wuming.android.tv.bean.Track;
import com.wuming.android.tv.bean.ViewingRecord;
import com.wuming.android.tv.cloud.CloudAuditEvent;
import com.wuming.android.tv.cloud.CloudCacheRecord;
import com.wuming.android.tv.cloud.CloudStorageProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppDatabaseInstrumentedTest {

    private AppDatabase database;

    @Before
    public void createDatabase() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void closeDatabase() {
        database.close();
    }

    @Test
    public void favoritesCanBeInsertedUpdatedFilteredAndDeleted() {
        Keep keep = new Keep();
        keep.setKey("site@@@vod-1");
        keep.setCid(7);
        keep.setType(0);
        keep.setVodName("Original");
        keep.setCreateTime(100);

        database.getKeepDao().insertOrUpdate(keep);
        assertEquals("Original", database.getKeepDao().find(7, keep.getKey()).getVodName());
        assertEquals(1, database.getKeepDao().getVod().size());
        assertEquals(0, database.getKeepDao().getLive().size());

        keep.setVodName("Updated");
        database.getKeepDao().insertOrUpdate(keep);
        assertEquals("Updated", database.getKeepDao().find(7, keep.getKey()).getVodName());

        database.getKeepDao().delete(7, keep.getKey());
        assertNull(database.getKeepDao().find(7, keep.getKey()));
    }

    @Test
    public void historyQueriesRespectConfigAndRecencyOrdering() {
        History older = history("site@@@old", "Series", 7, 100);
        History newer = history("site@@@new", "Series", 7, 200);
        database.getHistoryDao().insertOrUpdate(older);
        database.getHistoryDao().insertOrUpdate(newer);

        assertEquals("site@@@new", database.getHistoryDao().find(7, 0).get(0).getKey());
        assertEquals(2, database.getHistoryDao().findByName(7, "Series").size());

        database.getHistoryDao().delete(7, older.getKey());
        assertNull(database.getHistoryDao().findByKey(older.getKey()));
        assertNotNull(database.getHistoryDao().findByKey(newer.getKey()));
    }

    @Test
    public void viewingRecordsKeepSeparateSessionsAndWatchDuration() {
        ViewingRecord first = new ViewingRecord();
        first.setKey("site@@@movie");
        first.setCid(7);
        first.setLastWatchedAt(100);
        first.setWatchedDuration(20_000);
        ViewingRecord second = new ViewingRecord();
        second.setKey("site@@@movie");
        second.setCid(7);
        second.setLastWatchedAt(200);
        second.setWatchedDuration(40_000);

        database.getViewingRecordDao().insertOrUpdate(first);
        database.getViewingRecordDao().insertOrUpdate(second);

        assertEquals(2, database.getViewingRecordDao().findRecent(10).size());
        assertEquals(40_000, database.getViewingRecordDao().findRecent(10).get(0).getWatchedDuration());
        database.getViewingRecordDao().delete(7);
        assertEquals(0, database.getViewingRecordDao().findRecent(10).size());
    }

    @Test
    public void searchHistoryIncrementsFrequencyInsteadOfDuplicatingRows() {
        database.getSearchHistoryDao().insertOrUpdate("matrix");
        database.getSearchHistoryDao().insertOrUpdate("matrix");

        SearchHistory history = database.getSearchHistoryDao().findByKeyword("matrix");
        assertNotNull(history);
        assertEquals(2, history.getFrequency());
        assertEquals(1, database.getSearchHistoryDao().getRecent(10).size());
    }

    @Test
    public void sitePerformanceMetricsPersistAndSort() {
        database.getSitePerformanceDao().recordSuccess("fast", 10_000_000);
        database.getSitePerformanceDao().recordFailure("slow");

        SitePerformance fast = database.getSitePerformanceDao().findBySiteKey("fast");
        assertEquals(1, fast.getSuccessCount());
        assertEquals("fast", database.getSitePerformanceDao().findAllOrderByPerformance().get(0).getSiteKey());
    }

    @Test
    public void configurationSelectionUsesTypeAndMostRecentTimestamp() {
        Config older = new Config();
        older.setType(0);
        older.setUrl("https://older.example/config.json");
        older.setName("Older");
        older.setTime(100);
        Config newer = new Config();
        newer.setType(0);
        newer.setUrl("https://newer.example/config.json");
        newer.setName("Newer");
        newer.setTime(200);

        database.getConfigDao().insertOrUpdate(older);
        database.getConfigDao().insertOrUpdate(newer);

        assertEquals("Newer", database.getConfigDao().findOne(0).getName());
        assertEquals(2, database.getConfigDao().findByType(0).size());
        database.getConfigDao().delete(newer.getUrl(), newer.getType());
        assertNull(database.getConfigDao().find(newer.getUrl(), newer.getType()));
    }

    @Test
    public void synchronizedDevicesUpdateInsteadOfDuplicating() {
        Device device = new Device();
        device.setUuid("device-1");
        device.setName("Living Room");
        device.setIp("http://192.168.1.10:9978");
        device.setType(0);

        database.getDeviceDao().insertOrUpdate(device);
        Device stored = database.getDeviceDao().findAll().get(0);
        stored.setName("Living Room TV");
        database.getDeviceDao().insertOrUpdate(stored);

        assertEquals(1, database.getDeviceDao().findAll().size());
        assertEquals("Living Room TV", database.getDeviceDao().findAll().get(0).getName());
        database.getDeviceDao().delete();
        assertEquals(0, database.getDeviceDao().findAll().size());
    }

    @Test
    public void liveSourcesUpdateByNameAndCanBeLookedUp() {
        Live live = new Live("News", "https://example.test/one.m3u");
        live.setKeep("CCTV-1");
        database.getLiveDao().insertOrUpdate(live);

        Live updated = new Live("News", "https://example.test/two.m3u");
        updated.setKeep("CCTV-2");
        database.getLiveDao().insertOrUpdate(updated);

        assertEquals(1, database.getLiveDao().findAll().size());
        assertEquals("CCTV-2", database.getLiveDao().find("News").getKeep());
    }

    @Test
    public void drivesSupportInsertUpdateDeleteAndClear() {
        Drive drive = new Drive();
        drive.setName("NAS");
        drive.setType(1);
        drive.setConfigJson("{\"host\":\"one\"}");
        long id = database.getDriveDao().insert(drive);

        Drive stored = database.getDriveDao().getById((int) id);
        stored.setName("NAS Updated");
        database.getDriveDao().update(stored);
        assertEquals("NAS Updated", database.getDriveDao().getById((int) id).getName());

        database.getDriveDao().delete((int) id);
        assertNull(database.getDriveDao().getById((int) id));

        database.getDriveDao().insert(drive);
        database.getDriveDao().clear();
        assertEquals(0, database.getDriveDao().getAll().size());
    }

    @Test
    public void trustedCloudRecordsAndAuditEventsPersistSeparately() {
        CloudCacheRecord record = CloudCacheRecord.create(
                CloudStorageProvider.QUARK, "account", "folder", "file",
                "video.mp4", "site", 1024);
        database.getCloudCacheDao().insert(record);
        database.getCloudAuditDao().insert(CloudAuditEvent.create(
                CloudStorageProvider.QUARK, "record", true, 1, 1024, ""));

        assertEquals(1, database.getCloudCacheDao().active("quark").size());
        assertEquals(1, database.getCloudAuditDao().recent("quark", 10).size());
        database.getCloudCacheDao().updateState(
                "quark", "account", "file", CloudCacheRecord.DELETED, 2);
        assertEquals(0, database.getCloudCacheDao().active("quark").size());
    }

    @Test
    public void tracksReplaceSameKeyAndTypeWithoutAffectingOtherTracks() {
        Track audio = new Track(1, "Chinese", "aac").key("episode-1");
        audio.setSelected(true);
        database.getTrackDao().insert(audio);

        Track replacement = new Track(1, "English", "eac3").key("episode-1");
        database.getTrackDao().insert(replacement);
        database.getTrackDao().insert(new Track(2, "English CC", "vtt").key("episode-1"));

        assertEquals(2, database.getTrackDao().find("episode-1").size());
        assertEquals("English", database.getTrackDao().find("episode-1").stream()
                .filter(item -> item.getType() == 1).findFirst().orElseThrow().getName());
        database.getTrackDao().delete("episode-1");
        assertEquals(0, database.getTrackDao().find("episode-1").size());
    }

    @Test
    public void sitesUpdateByKeyWithoutDuplicatingRows() {
        Site site = Site.get("provider", "Original");
        site.setSearchable(1);
        database.getSiteDao().insertOrUpdate(site);

        Site replacement = Site.get("provider", "Updated");
        replacement.setSearchable(0);
        replacement.setChangeable(1);
        database.getSiteDao().insertOrUpdate(replacement);

        assertEquals(1, database.getSiteDao().findAll().size());
        Site stored = database.getSiteDao().findAll().get(0);
        assertEquals(0, stored.getSearchable().intValue());
        assertEquals(1, stored.getChangeable().intValue());
    }

    private static History history(String key, String name, int cid, long time) {
        History history = new History();
        history.setKey(key);
        history.setVodName(name);
        history.setCid(cid);
        history.setCreateTime(time);
        return history;
    }
}
