package com.wuming.android.tv.cloud.opendrive;

import static com.wuming.android.tv.cloud.opendrive.QuarkOpenDriveProvider.translatedContentRange;

import com.wuming.android.tv.cloud.opendrive.QuarkOpenDriveProvider.LogicalRange;
import com.wuming.android.tv.cloud.opendrive.QuarkOpenDriveProvider.PlaybackConnection;
import com.wuming.android.tv.cloud.opendrive.QuarkOpenDriveProvider.StreamRef;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.wuming.tv.core.playback.RangeProbeOptimizer;
import com.wuming.tv.core.playback.RangeTuningProfile;
import com.wuming.android.tv.player.exo.RangeTuningStore;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reassembles bounded Quark ranges into the one ordered stream expected by a DLNA renderer.
 * The renderer remains unaware of the upstream connection fan-out and can seek normally.
 * Extracted from {@link QuarkOpenDriveProvider}; behaviour is unchanged.
 */
final class QuarkParallelCastStream extends InputStream {

    private static final long CAST_TRACE_INTERVAL_MILLIS = 3_000L;
    private static final long CAST_PROGRESS_TIMEOUT_MILLIS = 6_000L;

    /** Opens one Quark playback connection for a media-offset-translated byte range. */
    interface ConnectionOpener {
        PlaybackConnection open(StreamRef stream, String range) throws Exception;
    }

    private final ConnectionOpener opener;
    private final StreamRef stream;
    private final long endExclusive;
    private final int maximumParallelism;
    private final ExecutorService executor;
    private final RangeTuningStore.Session tuningSession;
    private final RangeProbeOptimizer rangeOptimizer;
    private final ConcurrentLinkedQueue<PendingCastRange> pending =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CastNetworkSample> tuningSamples =
            new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<RangeProbeOptimizer.Setting, CastRangeAccumulator>
            tuningAccumulators = new ConcurrentHashMap<>();
    private final Set<PlaybackConnection> activeConnections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean released = new AtomicBoolean();
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean closed;
    private final byte[] oneByte = new byte[1];
    private boolean tuningFailure;
    private int partBytes;
    private int parallelism;
    private long nextPosition;
    private long position;
    private byte[] current;
    private int currentOffset;
    private Response sequentialResponse;
    private InputStream sequentialInput;
    private long lastTraceAt = startedAt;
    private long totalBytesRead;
    private long intervalBytesRead;

    QuarkParallelCastStream(ConnectionOpener opener, StreamRef stream, long start, long length,
                            int partBytes, int parallelism) {
        if (opener == null || stream == null || start < 0L || length <= 0L
                || partBytes <= 0 || parallelism <= 0) {
            throw new IllegalArgumentException("Invalid parallel cast stream");
        }
        this.opener = opener;
        this.stream = stream;
        this.position = start;
        this.nextPosition = start;
        this.endExclusive = Math.addExact(start, length);
        this.maximumParallelism = parallelism;
        RangeProbeOptimizer.Setting defaults =
                new RangeProbeOptimizer.Setting(parallelism, partBytes);
        int minimumRanges = Math.max(1, parallelism / 4);
        long minimumPartBytes = Math.max(4L * 1024L, partBytes / 2L);
        long maximumPartBytes = Math.max(partBytes, partBytes * 4L);
        RangeProbeOptimizer.Bounds bounds = new RangeProbeOptimizer.Bounds(
                minimumRanges, parallelism, minimumPartBytes, maximumPartBytes,
                minimumPartBytes * minimumRanges,
                maximumPartBytes * parallelism);
        this.tuningSession = RangeTuningStore.session(
                OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL, "cast",
                stream.url(), length);
        this.rangeOptimizer = new RangeProbeOptimizer(
                defaults, bounds, tuningSession.load());
        applyCastSetting(rangeOptimizer.setting());
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(maximumParallelism + 2, runnable -> {
            Thread thread = new Thread(runnable,
                    "QuarkCastRange-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        scheduleRanges();
    }

    @Override
    public int read() throws IOException {
        int count = read(oneByte, 0, 1);
        return count < 0 ? -1 : oneByte[0] & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (target == null) throw new NullPointerException("target");
        if (offset < 0 || length < 0 || length > target.length - offset) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) return 0;
        if (closed) throw new InterruptedIOException("Parallel cast stream was closed");
        if (position >= endExclusive) return -1;
        if (sequentialInput != null) return readSequential(target, offset, length);
        while (current == null || currentOffset >= current.length) {
            PendingCastRange range = pending.poll();
            if (range == null) return -1;
            try {
                current = awaitCastRange(range);
                currentOffset = 0;
                processCastTuningSamples();
                scheduleRanges();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                InterruptedIOException failure =
                        new InterruptedIOException("Interrupted while buffering Quark cast");
                failure.initCause(error);
                throw failure;
            } catch (ExecutionException error) {
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Parallel cast stream was closed");
                }
                startSequentialFallback(asIOException(error.getCause()));
                return readSequential(target, offset, length);
            } catch (TimeoutException error) {
                startSequentialFallback(new IOException(
                        "Quark cast range stopped making progress", error));
                return readSequential(target, offset, length);
            }
        }
        int count = Math.min(length, current.length - currentOffset);
        System.arraycopy(current, currentOffset, target, offset, count);
        currentOffset += count;
        recordRead(count);
        return count;
    }

    private void scheduleRanges() {
        while (!closed && pending.size() < parallelism && nextPosition < endExclusive) {
            long rangeStart = nextPosition;
            int rangeLength = (int) Math.min((long) partBytes,
                    endExclusive - rangeStart);
            nextPosition += rangeLength;
            RangeProbeOptimizer.Setting setting = new RangeProbeOptimizer.Setting(
                    parallelism, partBytes);
            AtomicBoolean sampleGate = new AtomicBoolean();
            pending.add(new PendingCastRange(rangeStart, rangeLength, setting,
                    sampleGate, submitCastAttempt(rangeStart, rangeLength,
                    setting, sampleGate)));
        }
    }

    private CastAttempt submitCastAttempt(long start, int length,
                                          RangeProbeOptimizer.Setting setting,
                                          AtomicBoolean sampleGate) {
        CastProgress progress = new CastProgress();
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
            try {
                return readRange(start, length, setting, sampleGate, progress);
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, executor);
        return new CastAttempt(future, progress);
    }

    private byte[] awaitCastRange(PendingCastRange range)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return awaitProgressing(range.primary());
        } catch (TimeoutException stalled) {
            CastAttempt hedge = submitCastAttempt(range.start(), range.length(),
                    range.setting(), range.sampleGate());
            OpenDriveLog.providerDebug("quark", "cast-range-hedge",
                    "start=" + range.start() + " length=" + range.length());
            CompletableFuture<byte[]> winner = firstSuccessful(
                    range.primary().future(), hedge.future());
            try {
                while (true) {
                    long waitNanos = Math.max(1L, Math.max(
                            range.primary().progress().remainingNanos(),
                            hedge.progress().remainingNanos()));
                    if (range.primary().progress().stalled()
                            && hedge.progress().stalled()) throw stalled;
                    try {
                        byte[] result = winner.get(waitNanos, TimeUnit.NANOSECONDS);
                        range.primary().future().cancel(true);
                        hedge.future().cancel(true);
                        return result;
                    } catch (TimeoutException ignored) {
                        // Re-evaluate both progress clocks. A slow but advancing request remains
                        // valid; only two simultaneously stalled attempts trigger fallback.
                    }
                }
            } finally {
                if (!winner.isDone() || winner.isCompletedExceptionally()) {
                    range.primary().future().cancel(true);
                    hedge.future().cancel(true);
                }
            }
        }
    }

    private byte[] awaitProgressing(CastAttempt attempt)
            throws InterruptedException, ExecutionException, TimeoutException {
        while (true) {
            long waitNanos = attempt.progress().remainingNanos();
            if (waitNanos <= 0L) throw new TimeoutException("Cast range stalled");
            try {
                return attempt.future().get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException error) {
                if (attempt.progress().stalled()) throw error;
            }
        }
    }

    private CompletableFuture<byte[]> firstSuccessful(CompletableFuture<byte[]> first,
                                                      CompletableFuture<byte[]> second) {
        CompletableFuture<byte[]> winner = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        first.whenComplete((value, error) -> completeCastWinner(
                winner, failures, value, error));
        second.whenComplete((value, error) -> completeCastWinner(
                winner, failures, value, error));
        return winner;
    }

    private void completeCastWinner(CompletableFuture<byte[]> winner,
                                    AtomicInteger failures, byte[] value,
                                    Throwable error) {
        if (error == null) winner.complete(value);
        else if (failures.incrementAndGet() == 2) winner.completeExceptionally(error);
    }

    private byte[] readRange(long start, int length,
                             RangeProbeOptimizer.Setting setting,
                             AtomicBoolean sampleGate,
                             CastProgress progress) throws IOException {
        String range = "bytes=" + start + '-' + (start + length - 1L);
        PlaybackConnection connection = null;
        Response response = null;
        long startedNanos = System.nanoTime();
        try {
            connection = opener.open(stream, range);
            response = connection.response();
            activeConnections.add(connection);
            if (closed) {
                connection.cancel();
                throw new InterruptedIOException("Parallel cast stream was closed");
            }
            ResponseBody body = response.body();
            LogicalRange actual = LogicalRange.parse(translatedContentRange(
                    response.header("Content-Range"), stream.mediaOffset()));
            if (!response.isSuccessful() || body == null || actual == null
                    || actual.start() != start || actual.length() != length) {
                throw new IOException("Quark cast range response mismatch");
            }
            byte[] result = new byte[length];
            InputStream input = body.byteStream();
            int read = 0;
            while (read < length) {
                int count = input.read(result, read, length - read);
                if (count < 0) throw new EOFException("Quark cast range ended early");
                if (count > 0) {
                    read += count;
                    progress.touch();
                }
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Parallel cast stream was closed");
                }
            }
            if (sampleGate.compareAndSet(false, true)) {
                recordCastNetworkRange(setting, length, startedNanos, System.nanoTime());
            }
            return result;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Unable to fetch Quark cast range", error);
        } finally {
            if (connection != null) {
                activeConnections.remove(connection);
                if (response != null) response.close();
            }
        }
    }

    private void startSequentialFallback(IOException cause) throws IOException {
        OpenDriveLog.providerWarn("quark", "cast-fallback",
                "position=" + position + " error=" + cause.getMessage());
        tuningSession.saveOrClear(rangeOptimizer.fail(System.currentTimeMillis()));
        tuningFailure = true;
        applyCastSetting(rangeOptimizer.bestSetting());
        stopWorkers();
        try {
            sequentialResponse = opener.open(stream, "bytes=" + position + '-').response();
            ResponseBody body = sequentialResponse.body();
            LogicalRange actual = LogicalRange.parse(translatedContentRange(
                    sequentialResponse.header("Content-Range"), stream.mediaOffset()));
            if (!sequentialResponse.isSuccessful() || body == null || actual == null
                    || actual.start() != position) {
                throw new IOException("Sequential Quark cast fallback response mismatch");
            }
            sequentialInput = body.byteStream();
        } catch (IOException error) {
            error.addSuppressed(cause);
            throw error;
        } catch (Exception error) {
            IOException failure = new IOException(
                    "Unable to open sequential Quark cast fallback", error);
            failure.addSuppressed(cause);
            throw failure;
        }
    }

    private int readSequential(byte[] target, int offset, int length) throws IOException {
        int allowed = (int) Math.min((long) length, endExclusive - position);
        int count = sequentialInput.read(target, offset, allowed);
        if (count > 0) recordRead(count);
        return count;
    }

    private void recordRead(int count) {
        position += count;
        totalBytesRead += count;
        intervalBytesRead += count;
        long now = System.currentTimeMillis();
        if (now - lastTraceAt < CAST_TRACE_INTERVAL_MILLIS) return;
        long elapsed = Math.max(1L, now - startedAt);
        long interval = Math.max(1L, now - lastTraceAt);
        OpenDriveLog.providerDebug("quark", "cast-progress",
                "position=" + position + " bytes=" + totalBytesRead
                        + " avgBps=" + (totalBytesRead * 1_000L / elapsed)
                        + " intervalBps=" + (intervalBytesRead * 1_000L / interval));
        intervalBytesRead = 0L;
        lastTraceAt = now;
    }

    private void recordCastNetworkRange(RangeProbeOptimizer.Setting setting,
                                        long bytes, long startedNanos,
                                        long completedNanos) {
        CastRangeAccumulator accumulator = tuningAccumulators.computeIfAbsent(
                setting, ignored -> new CastRangeAccumulator(setting.ranges()));
        long sampleBps = accumulator.record(bytes, startedNanos, completedNanos);
        if (sampleBps > 0L) {
            tuningAccumulators.remove(setting, accumulator);
            tuningSamples.add(new CastNetworkSample(setting, sampleBps));
        }
    }

    private void processCastTuningSamples() {
        CastNetworkSample sample;
        while ((sample = tuningSamples.poll()) != null) {
            if (!sample.setting().equals(rangeOptimizer.setting())) continue;
            RangeProbeOptimizer.Decision decision = rangeOptimizer.recordBatch(
                    sample.bytesPerSecond(), System.currentTimeMillis());
            if (decision.profile() != null) tuningSession.save(decision.profile());
            if (decision.action() == RangeProbeOptimizer.Action.NONE) continue;
            applyCastSetting(decision.setting());
            OpenDriveLog.providerDebug("quark", "cast-tuning-" +
                            decision.action().name().toLowerCase(Locale.ROOT),
                    "ranges=" + parallelism + " rangeBytes=" + partBytes
                            + " bps=" + sample.bytesPerSecond());
        }
    }

    private void applyCastSetting(RangeProbeOptimizer.Setting setting) {
        tuningAccumulators.clear();
        parallelism = setting.ranges();
        partBytes = Math.toIntExact(setting.rangeBytes());
    }

    private IOException asIOException(Throwable error) {
        while ((error instanceof CompletionException
                || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error instanceof IOException io ? io
                : new IOException("Unable to buffer Quark cast range", error);
    }

    private void stopWorkers() {
        pending.forEach(range -> range.primary().future().cancel(true));
        pending.clear();
        executor.shutdownNow();
        // OkHttp Call.cancel() is safe while another thread is reading. Closing its
        // Response from here can race Okio's active socket timeout and corrupt its
        // enter/exit state; each worker closes its own response in readRange's finally.
        for (PlaybackConnection connection : activeConnections) connection.cancel();
    }

    @Override
    public void close() throws IOException {
        if (!released.compareAndSet(false, true)) return;
        processCastTuningSamples();
        if (!tuningFailure) {
            RangeTuningProfile learned = rangeOptimizer.finish(System.currentTimeMillis());
            if (learned != null) tuningSession.save(learned);
        }
        closed = true;
        stopWorkers();
        try {
            if (sequentialResponse != null) sequentialResponse.close();
        } finally {
            stream.close();
            OpenDriveLog.providerDebug("quark", "cast-close",
                    "position=" + position + " bytes=" + totalBytesRead);
        }
    }

    private record CastNetworkSample(RangeProbeOptimizer.Setting setting,
                                     long bytesPerSecond) {
    }

    private record PendingCastRange(long start, int length,
                                    RangeProbeOptimizer.Setting setting,
                                    AtomicBoolean sampleGate,
                                    CastAttempt primary) {
    }

    private record CastAttempt(CompletableFuture<byte[]> future,
                               CastProgress progress) {
    }

    static final class CastProgress {
        private final AtomicLong lastProgressNanos = new AtomicLong(System.nanoTime());

        void touch() {
            lastProgressNanos.set(System.nanoTime());
        }

        long remainingNanos() {
            long elapsed = Math.max(0L, System.nanoTime() - lastProgressNanos.get());
            return TimeUnit.MILLISECONDS.toNanos(CAST_PROGRESS_TIMEOUT_MILLIS) - elapsed;
        }

        boolean stalled() {
            return remainingNanos() <= 0L;
        }
    }

    static final class CastRangeAccumulator {
        private final int requiredRanges;
        private int samples;
        private long aggregateBytes;
        private long firstStartedNanos = Long.MAX_VALUE;
        private long lastCompletedNanos;

        CastRangeAccumulator(int requiredRanges) {
            this.requiredRanges = requiredRanges;
        }

        synchronized long record(long bytes, long startedNanos, long completedNanos) {
            if (bytes <= 0L || startedNanos <= 0L || completedNanos <= startedNanos) return 0L;
            aggregateBytes = aggregateBytes > Long.MAX_VALUE - bytes
                    ? Long.MAX_VALUE : aggregateBytes + bytes;
            firstStartedNanos = Math.min(firstStartedNanos, startedNanos);
            lastCompletedNanos = Math.max(lastCompletedNanos, completedNanos);
            if (++samples < requiredRanges) return 0L;
            long elapsedNanos = Math.max(1L, lastCompletedNanos - firstStartedNanos);
            long result = aggregateBytes > Long.MAX_VALUE / 1_000_000_000L
                    ? Long.MAX_VALUE : aggregateBytes * 1_000_000_000L / elapsedNanos;
            aggregateBytes = 0L;
            samples = 0;
            firstStartedNanos = Long.MAX_VALUE;
            lastCompletedNanos = 0L;
            return result;
        }
    }
}
