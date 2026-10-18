package com.wuming.android.tv.player.danmaku;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Locale;

/** Actual bounded input buffering only; excludes Android parser and rendering. */
public final class LoaderBenchmark {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.wuming.android.tv.player.danmaku." + args[0]);
        Method read = type.getDeclaredMethod("readBounded", java.io.InputStream.class);
        read.setAccessible(true);
        byte[] input = new byte[Integer.parseInt(args[1])];
        for (int i = 0; i < input.length; i++) input[i] = (byte) (i * 17);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int sample = -3; sample < 5; sample++) {
            Object loader = type.getConstructor().newInstance();
            ByteArrayInputStream stream = new ByteArrayInputStream(input);
            System.gc();
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long start = System.nanoTime();
            byte[] output = (byte[]) read.invoke(loader, stream);
            long elapsed = System.nanoTime() - start;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
            if (!java.util.Arrays.equals(input, output)) throw new AssertionError("Output mismatch");
            if (sample >= 0) System.out.printf(Locale.ROOT,
                    "{\"sample\":%d,\"elapsedMs\":%.6f,\"allocatedBytes\":%d,\"retainedPayloadBytes\":%d}%n",
                    sample, elapsed / 1e6, allocated, output.length);
        }
    }
}
