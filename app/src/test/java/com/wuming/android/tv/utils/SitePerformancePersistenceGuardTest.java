package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps {@link SitePerformanceTracker} free of direct persistence writes and keeps
 * {@link SitePerformanceStore} a write-only port.
 *
 * <p>Both halves are deliberate. The tracker's flush is a batched, retried, clear-race-sensitive
 * sequence, and the only reason it is testable at all is that writes arrive at one seam. The port
 * stays write-only because readers must be handed the tracker's live records — a flush lags the
 * increments it stores, so a read-through port would make ranking blind to recent activity.</p>
 */
public class SitePerformancePersistenceGuardTest {

    private static final String MAIN_SOURCE = "src/main/java/com/wuming/android/tv";

    /**
     * The row mutations and cache invalidations the tracker used to perform inline. The
     * read-through cache fill stays allowed: it warms memory from a row it just read and changes no
     * stored state, which is the whole distinction this port is drawn on.
     */
    private static final List<String> WRITE_CALLS = List.of(
            "getSitePerformanceDao().insertOrUpdate",
            "getSitePerformanceDao().deleteAll",
            "getSitePerformanceDao().deleteBySiteKey",
            "clearSitePerformance",
            "removeSitePerformance");

    /** Every operation the write-only port is allowed to declare. */
    private static final List<String> WRITE_PREFIXES = List.of("persist", "delete", "evict");

    @Test
    public void theTrackerWritesToStorageOnlyThroughThePort() throws IOException {
        String text = trackerSource();
        for (String write : WRITE_CALLS) {
            assertFalse("SitePerformanceTracker must not call " + write
                    + "; route it through SitePerformanceStore", text.contains(write));
        }
        assertTrue("the tracker needs an injected store", text.contains("store.persist("));
    }

    @Test
    public void theReadThroughLoadStaysInTheTracker() throws IOException {
        String text = trackerSource();
        assertTrue("the read path must keep handing live records to readers",
                text.contains("SitePerformanceRepository::loadStored"));
    }

    /** A port that grows a read method has reversed the decision recorded in its javadoc. */
    @Test
    public void thePortDeclaresNothingButWrites() throws IOException {
        List<String> names = new ArrayList<>();
        for (java.lang.reflect.Method method : com.wuming.android.tv.db.SitePerformanceStore.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        assertEquals("the port has exactly the five operations the tracker needs",
                5, names.size());
        for (String name : names) {
            assertTrue("SitePerformanceStore is write-only, so " + name + " is not allowed",
                    WRITE_PREFIXES.stream().anyMatch(name::startsWith));
        }
    }

    /** The Room adapter is the single place that knows how site performance is stored. */
    @Test
    public void onlyTheRoomAdapterReachesRoomForSitePerformance() throws IOException {
        String adapter = source("db/RoomSitePerformanceStore.kt");
        assertTrue(adapter.contains(".getSitePerformanceDao()"));
        assertTrue(adapter.contains("DataCache.isReady()"));
    }

    private static String trackerSource() throws IOException {
        Path stem = appRoot().resolve(MAIN_SOURCE).resolve("utils/SitePerformanceTracker");
        List<Path> candidates = List.of(Path.of(stem + ".java"), Path.of(stem + ".kt"));
        List<Path> existing = candidates.stream().filter(Files::isRegularFile).toList();
        assertEquals("exactly one Java/Kotlin tracker implementation must exist", 1, existing.size());
        return Files.readString(existing.get(0));
    }

    private static String source(String relativePath) throws IOException {
        Path file = appRoot().resolve(MAIN_SOURCE).resolve(relativePath);
        assertTrue("missing source " + file, Files.isRegularFile(file));
        return Files.readString(file);
    }

    private static Path appRoot() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isDirectory(directory.resolve(MAIN_SOURCE + "/utils"))) return directory;
            Path nested = directory.resolve("app");
            if (Files.isDirectory(nested.resolve(MAIN_SOURCE + "/utils"))) return nested;
        }
        throw new IllegalStateException("app module root not found");
    }
}
