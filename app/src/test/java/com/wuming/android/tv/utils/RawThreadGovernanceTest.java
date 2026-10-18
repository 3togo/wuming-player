package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guards the app's dedicated threads: each one must carry a name and stay registered with the owner
 * that cancels or bounds it. Adding a raw thread means adding a registry entry in review, so the
 * list can never drift silently.
 */
public class RawThreadGovernanceTest {

    private static final Pattern CALL = Pattern.compile("\\b(?:new\\s+)?Thread\\s*\\(");
    private static final List<String> PRODUCTION_SOURCES = List.of(
            "src/main/java", "src/mobile/java", "src/leanback/java",
            "src/leanback_lite/java", "src/verification/java");

    /** Owner of each dedicated thread, keyed by the path relative to the app module. */
    private static final Map<String, Integer> REGISTERED = new TreeMap<>(Map.ofEntries(
            // QuickJS pipe streaming needs a blocking reader per pipe, not a shared pool slot.
            Map.entry("src/main/java/com/wuming/android/tv/api/provider/ProviderClient.java", 1),
            // Renderer capability profile cache refreshes on a dedicated background thread.
            Map.entry("src/main/java/com/wuming/android/tv/cast/RendererCapabilityProfiles.kt", 1),
            // The factory behind every Task executor and scheduler.
            Map.entry("src/main/java/com/wuming/android/tv/utils/Task.kt", 1),
            // One bounded, daemon IPv6 probe per explicit user request.
            Map.entry("src/main/java/com/wuming/android/tv/utils/NetworkChecker.kt", 1),
            // Three bounded daemon threads keep live line probes off the shared IO pool.
            Map.entry("src/main/java/com/wuming/android/tv/utils/LiveLineProber.kt", 1),
            // NanoHTTPD client handlers owned by the embedded server lifecycle.
            Map.entry("src/main/java/com/wuming/android/tv/server/Nano.java", 1),
            // Bounded remux preparation pool with an abort policy and core timeout.
            Map.entry("src/main/java/com/wuming/android/tv/server/process/CastRemuxCacheEngine.kt", 1),
            // Cast work drain thread named by its owning scheduler.
            Map.entry("src/main/java/com/wuming/android/tv/server/process/CastWorkScheduler.kt", 1),
            // Cache cleanup workers owned by the provider that starts and stops them.
            Map.entry("src/main/java/com/wuming/android/tv/cloud/opendrive/QuarkOpenDriveProvider.java", 1),
            // Parallel cast range fetch pool owned by the cast stream's own executor.
            Map.entry("src/main/java/com/wuming/android/tv/cloud/opendrive/QuarkParallelCastStream.java", 1),
            Map.entry("src/main/java/com/wuming/android/tv/cloud/opendrive/UcOpenDriveProvider.java", 1),
            Map.entry("src/main/java/com/wuming/android/tv/cloud/opendrive/TianyiOpenDriveProvider.java", 1),
            // DownloadManager executor owned for the process lifetime by the offline store.
            Map.entry("src/main/java/com/wuming/android/tv/player/offline/OfflinePlaybackStore.kt", 1)));

    @Test
    public void dedicatedThreadsStayRegisteredWithTheirOwner() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        callSites().forEach((file, calls) -> found.put(file, calls.size()));

        assertEquals("Raw thread list changed; review each thread and update the registry.",
                REGISTERED, found);
    }

    @Test
    public void everyDedicatedThreadCarriesAName() throws IOException {
        callSites().forEach((file, calls) -> {
            for (List<String> arguments : calls) {
                assertTrue(file + " must name its thread: new Thread(" + String.join(", ", arguments) + ")",
                        arguments.size() >= 2 && !arguments.get(1).isBlank());
            }
        });
    }

    @Test
    public void scannerRecognizesJavaAndKotlinConstructors() {
        assertEquals(List.of(List.of("() -> work()", "\"java-worker\"")),
                callsIn("new Thread(() -> work(), \"java-worker\")"));
        assertEquals(List.of(List.of("{ work() }", "\"kotlin-worker\"")),
                callsIn("Thread ({ work() }, \"kotlin-worker\")"));
        assertTrue(callsIn("Thread.currentThread(); workerThread()").isEmpty());
    }

    private static Map<String, List<List<String>>> callSites() throws IOException {
        Map<String, List<List<String>>> sites = new TreeMap<>();
        Path root = appRoot();
        for (String source : PRODUCTION_SOURCES) {
            Path directory = root.resolve(source);
            if (!Files.isDirectory(directory)) continue;
            try (Stream<Path> files = Files.walk(directory)) {
                List<Path> sourceFiles = files.filter(path -> path.getFileName().toString().endsWith(".java")
                                || path.getFileName().toString().endsWith(".kt"))
                        .sorted().toList();
                for (Path file : sourceFiles) {
                    List<List<String>> calls = callsIn(Files.readString(file));
                    if (!calls.isEmpty()) sites.put(root.relativize(file).toString().replace('\\', '/'), calls);
                }
            }
        }
        return sites;
    }

    private static List<List<String>> callsIn(String text) {
        List<List<String>> calls = new ArrayList<>();
        Matcher matcher = CALL.matcher(text);
        while (matcher.find()) {
            calls.add(argumentsOf(text, matcher.end()));
        }
        return calls;
    }

    /** Splits the argument list which starts right after {@link #CALL}. */
    private static List<String> argumentsOf(String text, int open) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 1;
        boolean inText = false;
        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inText) {
                current.append(character);
                if (character == '"' && text.charAt(index - 1) != '\\') inText = false;
                continue;
            }
            switch (character) {
                case '"' -> {
                    inText = true;
                    current.append(character);
                }
                case '(' -> {
                    depth++;
                    current.append(character);
                }
                case ')' -> {
                    depth--;
                    if (depth == 0) {
                        arguments.add(current.toString().trim());
                        return arguments;
                    }
                    current.append(character);
                }
                case ',' -> {
                    if (depth == 1) {
                        arguments.add(current.toString().trim());
                        current.setLength(0);
                    } else {
                        current.append(character);
                    }
                }
                default -> current.append(character);
            }
        }
        throw new IllegalStateException("Unbalanced " + CALL + " near " + text.substring(0, Math.min(80, text.length())));
    }

    private static Path appRoot() {
        Path start = Path.of("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            for (Path candidate : List.of(directory, directory.resolve("app"))) {
                if (Files.isRegularFile(candidate.resolve("build.gradle"))
                        && Files.isDirectory(candidate.resolve("src/main/java/com/wuming/android/tv"))) return candidate;
            }
        }
        throw new IllegalStateException("app module root not found from " + start);
    }
}
