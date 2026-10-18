import shark.*;
import shark.HeapObject.HeapInstance;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline Android HPROF analysis; writes class counts and Shark GC-root leak traces. */
public final class HeapProfile {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: HeapProfile dump.hprof output-prefix");
        Path prefix = Path.of(args[1]);
        FilteringLeakingObjectFinder defaults = new FilteringLeakingObjectFinder(
                AndroidObjectInspectors.Companion.getAppLeakingObjectFilters());
        LeakingObjectFinder finder = graph -> {
            Map<String, long[]> counts = new TreeMap<>();
            int activities = 0, destroyed = 0;
            Iterator<HeapInstance> instances = graph.getInstances().iterator();
            while (instances.hasNext()) {
                HeapInstance instance = instances.next();
                long[] row = counts.computeIfAbsent(instance.getInstanceClassName(), key -> new long[3]);
                row[0]++;
                row[1] += instance.getByteSize();
                if (instance.instanceOf("android.app.Activity")) {
                    activities++;
                    HeapField field = instance.get("android.app.Activity", "mDestroyed");
                    if (field != null && Boolean.TRUE.equals(field.getValue().getAsBoolean())) {
                        destroyed++;
                        row[2]++;
                    }
                }
            }
            try (PrintWriter out = new PrintWriter(prefix + "-classes.tsv")) {
                out.println("class\tinstances\tshallow_instance_bytes\tdestroyed_activities");
                counts.forEach((name, row) -> out.println(name + "\t" + row[0] + "\t" + row[1] + "\t" + row[2]));
            } catch (IOException error) { throw new UncheckedIOException(error); }
            Set<Long> suspects = defaults.findLeakingObjectIds(graph);
            System.out.printf("objects=%d instances=%d object_arrays=%d primitive_arrays=%d activities=%d destroyed_activities=%d leak_candidates=%d%n",
                    graph.getObjectCount(), graph.getInstanceCount(), graph.getObjectArrayCount(),
                    graph.getPrimitiveArrayCount(), activities, destroyed, suspects.size());
            return suspects;
        };
        HeapAnalyzer analyzer = new HeapAnalyzer(step -> System.err.println("Shark: " + step));
        HeapAnalysis result = analyzer.analyze(new File(args[0]), finder,
                AndroidReferenceMatchers.getAppDefaults(), true,
                AndroidObjectInspectors.Companion.getAppDefaults(), graph -> Collections.emptyMap(), null);
        Files.writeString(Path.of(prefix + "-analysis.txt"), result.toString());
        if (!(result instanceof HeapAnalysisSuccess success)) {
            System.err.println(result);
            throw new IllegalStateException("Heap analysis failed; see saved report");
        }
        System.out.printf("application_leak_groups=%d library_leak_groups=%d unreachable_candidates=%d analysis_ms=%d%n",
                success.getApplicationLeaks().size(), success.getLibraryLeaks().size(),
                success.getUnreachableObjects().size(), success.getAnalysisDurationMillis());
        for (Leak leak : success.getApplicationLeaks()) {
            System.out.printf("APP %s traces=%d retained_bytes=%s retained_objects=%s%n", leak.getShortDescription(),
                    leak.getLeakTraces().size(), leak.getTotalRetainedHeapByteSize(), leak.getTotalRetainedObjectCount());
        }
        for (Leak leak : success.getLibraryLeaks()) {
            System.out.printf("LIBRARY %s traces=%d retained_bytes=%s%n", leak.getShortDescription(),
                    leak.getLeakTraces().size(), leak.getTotalRetainedHeapByteSize());
        }
    }
}
