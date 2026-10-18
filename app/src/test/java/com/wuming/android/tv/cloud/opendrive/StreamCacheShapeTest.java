package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.cache.BoundedReferenceCache;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Characterizes the per-provider playback handle maps, which look alike by name and are not alike
 * in behavior. A2 put one bounded cache into {@code :libraries:cache-core}, and the temptation is
 * to route the {@code streams} maps through it as well; these tests record why that would change
 * eviction behavior instead of merely changing a type.
 *
 * <p>The three shapes are pinned on purpose: a handle here can be a live playback lease, a
 * provider-side quota accounting record, or nothing more than a remembered URL, and only one of
 * the three tolerates being dropped by a size limit.</p>
 */
public class StreamCacheShapeTest {

    private static final String PROVIDER_DIRECTORY =
            "src/main/java/com/wuming/android/tv/cloud/opendrive";

    /** Capacity is the decisive difference: a size limit here can drop a live playback handle. */
    @Test
    public void boundedCacheEvictsWhereAnUnboundedMapRetains() {
        BoundedReferenceCache<String, String> bounded = new BoundedReferenceCache<>(2);
        Map<String, String> unbounded = new ConcurrentHashMap<>();
        for (String key : List.of("a", "b", "c")) {
            bounded.put(key, key);
            unbounded.put(key, key);
        }
        assertEquals(2, bounded.size());
        assertNull("the bounded cache must discard an entry", bounded.get("a"));
        assertEquals("the live handle map must retain every entry", 3, unbounded.size());
        assertNotNull(unbounded.get("a"));
    }

    /** Reads reorder the bounded cache, so a sweep cannot assume the order it observed earlier. */
    @Test
    public void boundedCacheDiscardsByLastUseNotByAge() {
        BoundedReferenceCache<String, String> cache = new BoundedReferenceCache<>(2);
        cache.put("old", "old");
        cache.put("new", "new");
        cache.get("old");
        cache.put("third", "third");
        assertNotNull("reading an entry has to protect it from the next eviction", cache.get("old"));
        assertNull(cache.get("new"));
    }

    /**
     * Quark's reclaim pass removes a handle and then needs to know which provider-side copies it
     * is still protecting; Uc's pass reads every live handle without removing anything. Both walk
     * a live view, which the bounded cache deliberately does not expose.
     */
    @Test
    public void boundedCacheExposesNoIterationView() {
        List<String> declared = new ArrayList<>();
        List<String> views = new ArrayList<>();
        for (java.lang.reflect.Method method : BoundedReferenceCache.class.getMethods()) {
            declared.add(method.getName());
            if (List.of("entrySet", "values", "keySet", "iterator").contains(method.getName())) {
                views.add(method.getName());
            }
        }
        assertTrue("the probe must see the cache's own API, saw " + declared,
                declared.containsAll(List.of("get", "put", "removeEntriesIf", "size")));
        assertEquals("a bounded cache must not leak a mutable view of its contents",
                List.of(), views);
    }

    @Test
    public void quarkAndUcKeepTheirHandlesUnbounded() throws IOException {
        for (String provider : List.of("QuarkOpenDriveProvider", "UcOpenDriveProvider")) {
            String text = source(provider);
            assertTrue(provider + " declares streams as a live handle registry",
                    text.contains("ConcurrentHashMap<String, StreamRef> streams"));
            assertFalse(provider + " must not be switched to the bounded cache",
                    text.contains("streams = new OpenDriveMemoryCache")
                            || text.contains("streams = new BoundedReferenceCache"));
        }
    }

    /**
     * Only the idle-expiring provider may use the bounded cache, and only because its value type
     * carries no lease: losing one entry there costs a re-resolve, never a stranded copy.
     */
    @Test
    public void tianyiIsTheOnlyProviderWhoseStreamHandlesMayExpireFromACache() throws IOException {
        String tianyi = source("TianyiOpenDriveProvider");
        assertTrue(tianyi.contains("OpenDriveMemoryCache<String, StreamRef> streams"));

        for (String provider : List.of("QuarkOpenDriveProvider", "UcOpenDriveProvider")) {
            String text = source(provider);
            assertEquals(provider + " must not reuse Tianyi's expiry sweep", 0,
                    occurrences(text, "removeEntriesIf"));
        }
    }

    /**
     * The expiry rules disagree on whether a live reader matters. Quark refuses to drop a leased
     * handle, Tianyi has no lease concept, and Uc never ages a stream handle out at all.
     */
    @Test
    public void theExpiryRulesAreNotTheSameRule() throws IOException {
        String quark = source("QuarkOpenDriveProvider");
        assertTrue("Quark expires a stream only while no reader holds it",
                quark.contains("!entry.getValue().active() && entry.getValue().lastAccess() < cutoff"));
        assertTrue("Quark tracks readers on the handle", quark.contains("AtomicInteger active"));

        String tianyi = source("TianyiOpenDriveProvider");
        assertTrue("Tianyi expires purely on last access",
                tianyi.contains("removeEntriesIf((handle, stream) -> stream.lastAccess < cutoff)"));
        assertFalse("Tianyi's stream value carries no lease", tianyi.contains("AtomicInteger active"));

        String uc = source("UcOpenDriveProvider");
        assertFalse("Uc keeps stream handles until an explicit clear",
                uc.contains("cleanupStreams"));
        assertTrue("Uc's stream value is an immutable record with no access stamp",
                uc.contains("private record StreamRef("));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static String source(String provider) throws IOException {
        Path file = appRoot().resolve(PROVIDER_DIRECTORY).resolve(provider + ".java");
        assertTrue("missing provider source " + file, Files.isRegularFile(file));
        return Files.readString(file);
    }

    private static Path appRoot() {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (ownsProviderSources(directory)) return directory;
            Path nested = directory.resolve("app");
            if (ownsProviderSources(nested)) return nested;
        }
        throw new IllegalStateException("app module root not found");
    }

    private static boolean ownsProviderSources(Path candidate) {
        return Files.isDirectory(candidate.resolve(PROVIDER_DIRECTORY));
    }
}
