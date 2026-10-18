import com.wuming.tv.core.playback.OrderedPrefetchStream;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Host-only synthetic per-segment latency; no CDN, decoder or device-speed claim. */
public final class CastStreamingBenchmark {
    private static final int SEGMENTS = 18, BYTES = 256 * 1024;
    public static void main(String[] args) throws Exception {
        for (int sample = -1; sample < 3; sample++) {
            // Alternate order; exclude the first pair as warmup.
            for (int n = 0; n < 2; n++) {
                boolean parallel = (n + sample + 1) % 2 == 1;
                var active = new AtomicInteger();
                var peak = new AtomicInteger();
                var opened = new AtomicInteger();
                long start = System.nanoTime(), first = 0, total = 0;
                var digest = MessageDigest.getInstance("SHA-256");
                try (var stream = new OrderedPrefetchStream(SEGMENTS, index -> new OrderedPrefetchStream.Request() {
                    private final AtomicBoolean finished = new AtomicBoolean();
                    private boolean started;
                    @Override public long getLength() { return BYTES; }
                    @Override public InputStream open() throws IOException {
                        started = true;
                        opened.incrementAndGet();
                        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                        try { delay(40); } catch (IOException failure) { cancel(); throw failure; }
                        return new InputStream() {
                            int left = BYTES;
                            @Override public int read() { throw new UnsupportedOperationException(); }
                            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                if (left == 0) return -1;
                                delay(2);
                                int count = Math.min(left, length);
                                Arrays.fill(bytes, offset, offset + count, (byte) (index + 1));
                                left -= count;
                                return count;
                            }
                            @Override public void close() { cancel(); }
                        };
                    }
                    @Override public void cancel() {
                        if (started && finished.compareAndSet(false, true)) active.decrementAndGet();
                    }
                }, new OrderedPrefetchStream.Budget(parallel ? 1 : 0))) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = stream.read(buffer)) >= 0;) {
                        if (first == 0) first = System.nanoTime() - start;
                        digest.update(buffer, 0, count);
                        total += count;
                    }
                }
                long elapsed = System.nanoTime() - start;
                var expected = MessageDigest.getInstance("SHA-256");
                byte[] block = new byte[64 * 1024];
                for (int i = 0; i < SEGMENTS; i++) {
                    Arrays.fill(block, (byte) (i + 1));
                    for (int b = 0; b < BYTES; b += block.length) expected.update(block);
                }
                if (total != (long) SEGMENTS * BYTES || !Arrays.equals(expected.digest(), digest.digest()))
                    throw new AssertionError("Byte order/length mismatch");
                if (active.get() != 0 || opened.get() != SEGMENTS || peak.get() > (parallel ? 3 : 1))
                    throw new AssertionError("Request lifecycle/budget mismatch");
                if (sample >= 0) System.out.printf(Locale.ROOT,
                        "{\"sample\":%d,\"mode\":\"%s\",\"elapsedMs\":%.3f,\"firstByteMs\":%.3f,\"bytes\":%d,\"peakRequests\":%d}%n",
                        sample, parallel ? "prefetch" : "sequential", elapsed / 1e6, first / 1e6, total, peak.get());
            }
        }
    }
    private static void delay(long millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
    }
}
