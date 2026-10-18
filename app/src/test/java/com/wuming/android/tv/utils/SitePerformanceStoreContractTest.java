package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.SitePerformance;
import com.wuming.android.tv.db.SitePerformanceStore;
import com.wuming.tv.core.source.SitePersistencePolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Contract for {@link SitePerformanceStore} as seen through {@link SitePerformanceTracker}.
 *
 * <p>The tracker keeps its flush cadence and dirty-key accounting in {@code :libraries:source-core}
 * policies, so what is pinned here is the seam: which writes reach the store, when, and with which
 * object identity. The identity rule matters most — readers are handed the tracker's live records,
 * while the store must only ever receive detached snapshots.</p>
 */
public class SitePerformanceStoreContractTest {

    private static final String KEY = "site-a";

    private RecordingStore store;
    private RecordingExecutor io;
    private ScheduledExecutorService scheduler;
    private SitePerformanceTracker tracker;

    @Before
    public void setUp() {
        store = new RecordingStore();
        io = new RecordingExecutor();
        // The tracker only ever schedules with minute-scale delays, so nothing fires during a test.
        scheduler = Executors.newSingleThreadScheduledExecutor();
        tracker = new SitePerformanceTracker(store, io, scheduler, key -> null);
    }

    @After
    public void tearDown() {
        scheduler.shutdownNow();
    }

    /** The public API must not block on storage: a write is queued, not performed inline. */
    @Test
    public void recordingQueuesTheWriteInsteadOfPerformingItOnTheCallerThread() {
        tracker.recordSuccess(KEY, 1024L);
        assertEquals(List.of(), store.persistedSiteKeys());
        io.drain();
        assertEquals(List.of(KEY), store.persistedSiteKeys());
    }

    /**
     * The store receives a detached copy. If it got the live record, a later increment would edit
     * an already-written row's in-memory value and the cache would publish a mutating object.
     */
    @Test
    public void flushPersistsADetachedSnapshot() {
        tracker.recordSuccess(KEY, 1024L);
        io.drain();
        SitePerformance live = tracker.getPerformance(KEY);
        SitePerformance written = store.persisted.get(0);
        assertNotSame(live, written);
        assertEquals(1L, written.getTotalRequests());
        tracker.recordSuccess(KEY, 2048L);
        assertEquals("the earlier write must not observe a later increment",
                1L, written.getTotalRequests());
        assertEquals(2L, live.getTotalRequests());
    }

    /**
     * The hazard the boundaries document records: a flush lags the increments, so ranking has to
     * keep reading the live record. A read-through port would have broken exactly this.
     */
    @Test
    public void readersSeeIncrementsThatHaveNotBeenFlushed() {
        SitePerformance live = tracker.getPerformance(KEY);
        tracker.recordFailure(KEY);
        io.drain();
        assertSame("the tracker must keep handing out one live record per site",
                live, tracker.getPerformance(KEY));
        assertEquals(1L, live.getTotalRequests());
        assertEquals(1L, store.persisted.get(0).getFailureCount());
    }

    /** Only the first flush runs; the cadence policy has to defer the rest to the scheduler. */
    @Test
    public void aSecondRecordWithinTheFlushIntervalDoesNotReachTheStore() {
        tracker.recordSuccess(KEY, 1024L);
        io.drain();
        tracker.recordSuccess(KEY, 1024L);
        io.drain();
        assertEquals(List.of(KEY), store.persistedSiteKeys());
    }

    /** A rejected key is requeued for the retry budget, then abandoned instead of looping forever. */
    @Test
    public void aFailingStoreIsRetriedUpToTheBudgetAndThenDropped() {
        store.failure = new IllegalStateException("storage unavailable");
        tracker.recordSuccess(KEY, 1024L);
        io.drain();
        for (int attempt = 1; attempt < SitePersistencePolicy.MAX_RETRY_FAILURES; attempt++) {
            tracker.forceSave();
        io.drain();
        }
        assertEquals(SitePersistencePolicy.MAX_RETRY_FAILURES, store.persistAttempts);
        tracker.forceSave();
        io.drain();
        assertEquals("an exhausted key must stop being retried",
                SitePersistencePolicy.MAX_RETRY_FAILURES, store.persistAttempts);
    }

    /** A recovered store still sees the key, proving the requeue kept it dirty. */
    @Test
    public void aKeySurvivesOneFailureAndIsWrittenWhenTheStoreRecovers() {
        store.failure = new IllegalStateException("storage unavailable");
        tracker.recordSuccess(KEY, 1024L);
        io.drain();
        store.failure = null;
        tracker.forceSave();
        io.drain();
        assertEquals(2, store.persistAttempts);
        assertEquals(1, store.persisted.size());
    }

    /**
     * Eviction has to be synchronous and the row delete deferred, in that order: if both waited on
     * the executor, a reader could refill the cache from a row the clear had not removed yet.
     */
    @Test
    public void clearEvictsFirstAndDeletesTheRowOnTheStorageExecutor() {
        tracker.recordSuccess(KEY, 1024L);
        io.drain();
        tracker.clear(KEY);
        assertEquals(List.of(KEY), store.evicted);
        assertEquals(List.of(), store.deleted);
        io.drain();
        assertEquals(List.of(KEY), store.deleted);
    }

    @Test
    public void clearAllDropsEverythingAndLeavesNothingQueued() {
        tracker.recordSuccess(KEY, 1024L);
        tracker.recordSuccess("site-b", 1024L);
        io.drain();
        tracker.clearAll();
        assertEquals(1, store.evictAllAttempts);
        assertEquals(List.of(), store.deletedAll);
        io.drain();
        assertEquals(1, store.deletedAll.size());
        store.persisted.clear();
        tracker.forceSave();
        io.drain();
        assertEquals("a cleared tracker must not rewrite the old rows",
                List.of(), store.persistedSiteKeys());
    }

    /** A cleared key reloads from an empty store rather than resurrecting the dropped record. */
    @Test
    public void recordingAfterAClearStartsFromAnEmptyRecord() {
        tracker.recordSuccess(KEY, 4096L);
        io.drain();
        assertEquals(4096.0, store.persisted.get(0).getAvgSpeed(), 0.0);
        tracker.clear(KEY);
        io.drain();
        tracker.recordSuccess(KEY, 1024L);
        tracker.forceSave();
        io.drain();
        assertEquals(2, store.persisted.size());
        SitePerformance rewritten = store.persisted.get(1);
        assertEquals(1L, rewritten.getTotalRequests());
        assertEquals(1L, rewritten.getSpeedSampleCount());
        assertEquals("the discarded sample must not survive into the new record",
                1024.0, rewritten.getAvgSpeed(), 0.0);
    }

    @Test
    public void nothingIsWrittenForAnAbsentKeyOrWithoutRecordedActivity() {
        assertNull(tracker.getPerformance(null));
        assertNull(tracker.getPerformance(""));
        tracker.recordSuccess(null, 1024L);
        tracker.recordSuccess("", 1024L);
        tracker.recordFailure(null);
        tracker.recordDeadLink("");
        io.drain();
        tracker.forceSave();
        io.drain();
        assertEquals(0, store.persistAttempts);
        assertTrue(store.evicted.isEmpty());
    }

    @Test
    public void everyRecordingEntryPointReachesTheStoreOnce() {
        tracker.recordSuccess(KEY, 1024L);
        tracker.recordDeadLink(KEY);
        tracker.recordFailure(KEY);
        io.drain();
        assertEquals(1, store.persisted.size());
        SitePerformance written = store.persisted.get(0);
        assertEquals(3L, written.getTotalRequests());
        assertEquals(1L, written.getSuccessCount());
        assertEquals(1L, written.getDeadLinkCount());
        assertEquals(1L, written.getFailureCount());
    }

    @Test
    public void hydrationKeepsStoredCountersAndPendingObservations() {
        SitePerformance baseline = new SitePerformance(KEY);
        baseline.recordSuccess(4000);
        baseline.recordFailure();
        int[] reads = {0};
        tracker = new SitePerformanceTracker(store, io, scheduler, key -> {
            reads[0]++;
            return baseline.snapshot();
        });
        SitePerformance live = tracker.getPerformance(KEY);
        tracker.recordSuccess(KEY, 2000);
        tracker.recordDeadLink(KEY);
        assertEquals(0, reads[0]);
        assertEquals(2, live.getTotalRequests());
        io.drain();
        assertSame(live, tracker.getPerformance(KEY));
        assertEquals(1, reads[0]);
        assertEquals(4, live.getTotalRequests());
        assertEquals(2, live.getSuccessCount());
        assertEquals(1, live.getFailureCount());
        assertEquals(1, live.getDeadLinkCount());
        assertEquals(3000.0, live.getAvgSpeed(), 0.0);
        assertEquals(4, store.persisted.get(0).getTotalRequests());
    }

    @Test
    public void unavailableBaselineCannotOverwriteStoredCounters() {
        boolean[] fail = {true};
        tracker = new SitePerformanceTracker(store, io, scheduler, key -> {
            if (fail[0]) throw new IllegalStateException("unavailable");
            SitePerformance stored = new SitePerformance(KEY);
            stored.recordSuccess(1000);
            return stored;
        });
        tracker.recordFailure(KEY);
        io.drain();
        assertTrue(store.persisted.isEmpty());
        fail[0] = false;
        tracker.forceSave();
        io.drain();
        assertEquals(2, store.persisted.get(0).getTotalRequests());
    }

    @Test
    public void lateHydrationCannotResurrectClearedStatistics() {
        tracker = new SitePerformanceTracker(store, io, scheduler, key -> {
            tracker.clear(KEY);
            SitePerformance stored = new SitePerformance(KEY);
            stored.recordSuccess(1000);
            return stored;
        });
        tracker.recordFailure(KEY);
        io.drain();
        io.drain();
        assertTrue(store.persisted.isEmpty());
    }

    private static final class RecordingStore implements SitePerformanceStore {

        private final List<SitePerformance> persisted = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private final List<String> evicted = new ArrayList<>();
        private final List<String> deletedAll = new ArrayList<>();
        private int persistAttempts;
        private int evictAllAttempts;
        private Exception failure;

        @Override
        public void persist(SitePerformance snapshot) throws Exception {
            persistAttempts++;
            if (failure != null) throw failure;
            persisted.add(snapshot);
        }

        @Override
        public void deleteAll() throws Exception {
            if (failure != null) throw failure;
            deletedAll.add("all");
        }

        @Override
        public void delete(String siteKey) throws Exception {
            if (failure != null) throw failure;
            deleted.add(siteKey);
        }

        @Override
        public void evictAll() {
            evictAllAttempts++;
        }

        @Override
        public void evict(String siteKey) {
            evicted.add(siteKey);
        }

        private List<String> persistedSiteKeys() {
            return persisted.stream().map(SitePerformance::getSiteKey).toList();
        }
    }

    /** Holds deferred work until a test decides when it runs. */
    private static final class RecordingExecutor implements Executor {

        private final List<Runnable> pending = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        private void drain() {
            List<Runnable> due = new ArrayList<>(pending);
            pending.clear();
            due.forEach(Runnable::run);
        }
    }
}
