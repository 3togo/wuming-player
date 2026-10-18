package com.wuming.android.tv.api.parser;

import com.wuming.android.tv.bean.Channel;
import com.wuming.android.tv.bean.Group;
import com.wuming.android.tv.bean.Live;
import com.google.gson.Gson;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPInputStream;

/** Opt-in host comparison; never packaged into the application. */
public final class EpgImportBenchmark {
    private static volatile Live retained;
    private static volatile boolean sampling;
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String algorithm = args[0], encoding = args[2];
        File file = new File(args[1]);
        int warmups = Integer.parseInt(args[3]), samples = Integer.parseInt(args[4]);
        Method legacy = Class.forName("com.wuming.android.tv.api.parser.LegacyEpgParser")
                .getDeclaredMethod("readXml", Live.class, File.class);
        legacy.setAccessible(true);
        File expanded = new File(file.getParentFile(), file.getName() + ".legacy-expanded.xml");
        try {
            if (algorithm.equals("legacy") && encoding.equals("gzip-cache")) expand(file, expanded);
            for (int i = -warmups; i < samples; i++) {
                retained = null;
                System.gc();
                long beforeHeap = MEMORY.getHeapMemoryUsage().getUsed();
                AtomicLong peak = new AtomicLong(beforeHeap);
                sampling = true;
                Thread sampler = new Thread(() -> {
                    while (sampling) {
                        peak.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(), Math::max);
                        LockSupport.parkNanos(1_000_000);
                    }
                }, "epg-heap-sampler");
                sampler.start();
                var allocationBean = ManagementFactory.getThreadMXBean();
                com.sun.management.ThreadMXBean allocations = allocationBean instanceof com.sun.management.ThreadMXBean bean
                        && bean.isThreadAllocatedMemorySupported() ? bean : null;
                if (allocations != null) allocations.setThreadAllocatedMemoryEnabled(true);
                long allocated = allocations == null ? -1 : allocations.getThreadAllocatedBytes(Thread.currentThread().getId());
                long gcBefore = gcCount();
                long begin = System.nanoTime();
                long elapsed;
                Long allocatedBytes;
                try {
                    Live live = fixtureLive();
                    if (algorithm.equals("legacy")) {
                        File input = file;
                        if (encoding.startsWith("gzip")) {
                            if (encoding.equals("gzip-refresh")) expand(file, expanded);
                            input = expanded;
                        }
                        legacy.invoke(null, live, input);
                    } else EpgParser.readXml(live, file);
                    retained = live;
                    elapsed = System.nanoTime() - begin;
                    allocatedBytes = allocations == null ? null
                            : allocations.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated;
                } finally {
                    sampling = false;
                    sampler.join();
                }
                peak.accumulateAndGet(MEMORY.getHeapMemoryUsage().getUsed(), Math::max);
                long gcDuring = gcCount() - gcBefore;
                String fingerprint = fingerprint(retained);
                int count = retained.getGroups().get(0).getChannel().get(0).getDataList().stream()
                        .mapToInt(epg -> epg.getList().size()).sum();
                System.gc();
                long retainedHeap = MEMORY.getHeapMemoryUsage().getUsed();
                if (i >= 0) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("algorithm", algorithm);
                    result.put("encoding", encoding);
                    result.put("sample", i);
                    result.put("elapsedMs", elapsed / 1_000_000.0);
                    result.put("allocatedBytes", allocatedBytes);
                    result.put("heapBeforeBytes", beforeHeap);
                    result.put("sampledPeakHeapBytes", peak.get());
                    result.put("postGcHeapBytes", retainedHeap);
                    result.put("gcCollections", gcDuring);
                    result.put("retainedProgrammes", count);
                    result.put("fingerprint", fingerprint);
                    result.put("jvm", System.getProperty("java.runtime.version"));
                    System.out.println(new Gson().toJson(result));
                }
            }
        } finally {
            Files.deleteIfExists(expanded.toPath());
        }
    }

    private static Live fixtureLive() {
        Live live = new Live();
        Group group = new Group("benchmark");
        Channel channel = new Channel();
        channel.setTvgId("local");
        channel.setName("News");
        group.getChannel().add(channel);
        live.getGroups().add(group);
        return live;
    }

    private static void expand(File source, File target) throws IOException {
        try (InputStream in = new GZIPInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            in.transferTo(out);
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static String fingerprint(Live live) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Channel channel = live.getGroups().get(0).getChannel().get(0);
        digest.update(channel.getLogo().getBytes(StandardCharsets.UTF_8));
        var days = new ArrayList<>(channel.getDataList());
        days.sort(Comparator.comparing(epg -> epg.getDate()));
        for (var day : days) {
            digest.update(day.getDate().getBytes(StandardCharsets.UTF_8));
            for (var entry : day.getList()) {
                String value = entry.getTitle() + "\u0000" + entry.getStartTime() + "\u0000"
                        + entry.getEndTime() + "\u0000" + entry.getStart() + "\u0000" + entry.getEnd() + "\n";
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
