package com.wuming.android.tv.server.process;

import com.google.gson.Gson;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import okhttp3.*;
import okio.Okio;

/** Host-only synthetic transport; neither real media nor an Android remux benchmark. */
public final class CastStageBenchmark {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final Object LEGACY_LOCK = new Object();
    private static final AtomicInteger active = new AtomicInteger(), peakCalls = new AtomicInteger();
    private static final com.sun.management.ThreadMXBean ALLOC =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        String algorithm = args[0];
        File directory = new File(args[1]);
        int segments = Integer.parseInt(args[2]), size = Integer.parseInt(args[3]);
        int delay = Integer.parseInt(args[4]), jobs = Integer.parseInt(args[5]);
        int warmups = Integer.parseInt(args[6]), samples = Integer.parseInt(args[7]);
        long[] sizes = new long[segments]; Arrays.fill(sizes, size);
        ALLOC.setThreadAllocatedMemoryEnabled(true);
        ExecutorService callers = Executors.newFixedThreadPool(jobs);
        try {
            for (int sample = -warmups; sample < samples; sample++) {
                System.gc();
                long heapBefore = MEMORY.getHeapMemoryUsage().getUsed();
                AtomicLong peakHeap = new AtomicLong(heapBefore), peakDisk = new AtomicLong();
                AtomicBoolean sampling = new AtomicBoolean(true);
                Thread sampler = new Thread(() -> {
                    while (sampling.get()) {
                        peakHeap.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(), Math::max);
                        peakDisk.accumulateAndGet(diskBytes(directory), Math::max);
                        LockSupport.parkNanos(1_000_000);
                    }
                }, "cast-profile-sampler");
                // Include persistent sampler allocations equally in both modes.
                sampler.start();
                peakCalls.set(0);
                long allocatedBefore = allocated(), gcBefore = gcCount(), begin = System.nanoTime();
                List<Future<File>> futures = new ArrayList<>();
                List<File> outputs = new ArrayList<>();
                long elapsed, allocatedBytes;
                try {
                    for (int job = 0; job < jobs; job++) futures.add(callers.submit(() -> {
                        if (algorithm.equals("legacy")) {
                            // The production baseline holds MP4_LOCK across staging/remux.
                            synchronized (LEGACY_LOCK) {
                                return LegacyCastStage.run(directory, sizes, i -> call(i, size, delay));
                            }
                        }
                        if (algorithm.equals("bounded")) return FixedCastSegmentDownloader.download(
                                directory, sizes, 32L << 20, 2, i -> call(i, size, delay));
                        return CastSegmentDownloader.download(directory, sizes, 32L << 20,
                                i -> call(i, size, delay));
                    }));
                    for (Future<File> future : futures) outputs.add(future.get(30, TimeUnit.SECONDS));
                    elapsed = System.nanoTime() - begin;
                    allocatedBytes = allocated() - allocatedBefore;
                    peakHeap.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(), Math::max);
                    peakDisk.accumulateAndGet(diskBytes(directory), Math::max);
                } finally {
                    sampling.set(false); sampler.join();
                }
                long gcDuring = gcCount() - gcBefore;
                Set<String> fingerprints = new HashSet<>();
                for (File output : outputs) {
                    if (output.length() != (long)segments * size) throw new IOException("Output length mismatch");
                    fingerprints.add(fingerprint(output));
                    Files.delete(output.toPath());
                }
                if (fingerprints.size() != 1 || diskBytes(directory) != 0 || active.get() != 0)
                    throw new IllegalStateException("Output/cleanup mismatch");
                if (sample >= 0) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("algorithm", algorithm); row.put("sample", sample);
                    row.put("elapsedMs", elapsed / 1e6); row.put("allocatedBytes", allocatedBytes);
                    row.put("heapBeforeBytes", heapBefore); row.put("sampledPeakHeapBytes", peakHeap.get());
                    row.put("sampledPeakDiskBytes", peakDisk.get()); row.put("gcCollections", gcDuring);
                    row.put("peakCalls", peakCalls.get()); row.put("fingerprint", fingerprints.iterator().next());
                    row.put("outputBytes", (long)segments * size * jobs);
                    row.put("jvm", System.getProperty("java.runtime.version"));
                    System.out.println(new Gson().toJson(row));
                }
            }
        } finally { callers.shutdownNow(); }
    }

    private static long allocated() {
        // Workers/callers stay alive throughout a sample. Sampler and infrastructure are included.
        long sum = 0;
        for (long value : ALLOC.getThreadAllocatedBytes(ALLOC.getAllThreadIds())) if (value > 0) sum += value;
        return sum;
    }
    private static long diskBytes(File directory) {
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("Missing fixture directory");
        long sum = 0; for (File file : files) sum += file.length(); return sum;
    }
    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }
    private static String fingerprint(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static Call call(int index, int size, int delay) {
        AtomicBoolean cancelled = new AtomicBoolean();
        return (Call) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "execute" -> {
                    peakCalls.accumulateAndGet(active.incrementAndGet(), Math::max);
                    InputStream stream = new InputStream() {
                        int position; boolean started, closed;
                        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                            if (!started) {
                                started = true;
                                try { if (delay > 0) Thread.sleep(delay); }
                                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                            }
                            if (cancelled.get()) throw new InterruptedIOException();
                            if (position == size) return -1;
                            int n = Math.min(Math.min(count, 8192), size - position);
                            Arrays.fill(bytes, offset, offset + n, (byte)(index * 31));
                            position += n; return n;
                        }
                        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
                        @Override public void close() { if (!closed) { closed = true; active.decrementAndGet(); } }
                    };
                    yield new Response.Builder().request(new Request.Builder().url("https://fixture.invalid/" + index).build())
                            .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(ResponseBody.create(Okio.buffer(Okio.source(stream)), null, size)).build();
                }
                case "cancel" -> { cancelled.set(true); yield null; }
                case "isCanceled" -> cancelled.get();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
}
