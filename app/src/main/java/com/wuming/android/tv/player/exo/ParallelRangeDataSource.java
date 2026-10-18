package com.wuming.android.tv.player.exo;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.wuming.android.tv.BuildConfig;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;
import com.wuming.tv.core.playback.RangeProbeOptimizer;
import com.wuming.tv.core.playback.RangeTuningProfile;
import com.wuming.android.tv.utils.AppLog;
import com.wuming.android.tv.utils.NetworkEnvironmentMonitor;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes one progressive file while fetching each bounded window through parallel ranges.
 *
 * <p>Each profile is backed by device evidence or the provider implementation being matched.
 * UC PDS follows CatVod DownloadMT with {@code availableProcessors * 2} requests. Quark follows
 * established Quark download clients: thirty-two 128 KiB parts are actively drained in parallel and
 * exposed to the player in file order, with rolling prefetch before the current batch drains.
 * Aliyun uses the independently measured four-request, 4 MiB default.
 * Every range-safe profile validates Content-Range and falls back to sequential playback when a
 * server rejects or corrupts a bounded range. The provider-supplied object size clamps the final
 * range to EOF.</p>
 */
@UnstableApi
final class ParallelRangeDataSource implements DataSource {

    static final long UC_WINDOW_BYTES = 16L * 1024L * 1024L;
    static final long BAIDU_PART_BYTES = 4L * 1024L * 1024L;
    static final int BAIDU_PARALLELISM = 3;
    static final long BAIDU_WINDOW_BYTES = BAIDU_PART_BYTES * BAIDU_PARALLELISM;
    static final long GENERIC_PART_BYTES = 2L * 1024L * 1024L;
    static final int GENERIC_PARALLELISM = 5;
    static final long GENERIC_WINDOW_BYTES = GENERIC_PART_BYTES * GENERIC_PARALLELISM;
    private static final long GENERIC_STARTUP_BYTES = 4L * 1024L * 1024L;
    private static final long GENERIC_STARTUP_PART_BYTES = 256L * 1024L;
    // Live Quark playback is throttled per connection. Keep each ordered part small enough that a
    // slow individual stream cannot hold the player behind already-buffered later ranges. A live
    // 16-way comparison stayed below this 4K file's bitrate; 32-way playback sustained the stream.
    static final long QUARK_PART_BYTES = 128L * 1024L;
    static final int QUARK_PARALLELISM = 32;
    static final long QUARK_WINDOW_BYTES = QUARK_PART_BYTES * QUARK_PARALLELISM;
    static final long ALIPAN_WINDOW_BYTES = 4L * 1024L * 1024L;
    static final long ALIPAN_MIN_WINDOW_BYTES = 2L * 1024L * 1024L;
    static final long ALIPAN_MAX_WINDOW_BYTES = 8L * 1024L * 1024L;
    private static final int RANGE_REFRESH_LOSS_PERCENT = 50;
    private static final long RANGE_REFRESH_SAMPLE_NANOS = 1_500_000_000L;
    private static final int MAX_RANGE_REFRESHES_PER_WINDOW = 4;
    private static final int URGENT_START_RANGES = 4;
    private static final long RANGE_PROGRESS_TIMEOUT_MILLIS = 6_000L;
    private static final long RANGE_HEDGE_TIMEOUT_MILLIS = 10_000L;
    private static final String TAG = "ParallelRange";
    private static final long LOG_INTERVAL_MILLIS = 3_000L;
    private static final boolean TRACE_ENABLED = BuildConfig.DEBUG;

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;
        private final long contentLength;
        private final String profile;
        private final RangePolicy policy;
        private final PlaybackHealthMonitor health;

        Factory(DataSource.Factory upstreamFactory, long contentLength, String profile) {
            this(upstreamFactory, contentLength, profile, new PlaybackHealthMonitor());
        }

        Factory(DataSource.Factory upstreamFactory, long contentLength, String profile,
                PlaybackHealthMonitor health) {
            this.upstreamFactory = upstreamFactory;
            this.health = health;
            this.contentLength = Math.max(0L, contentLength);
            this.policy = RangePolicy.forProfile(profile);
            if (policy == null) {
                throw new IllegalArgumentException("Unknown parallel range profile");
            }
            this.profile = profile;
        }

        @Override
        public DataSource createDataSource() {
            return new ParallelRangeDataSource(
                    upstreamFactory, contentLength, profile, policy, health);
        }
    }

    /** Bounded policies for known providers and for generic HTTP after an exact range probe. */
    static final class RangePolicy {
        private final long minimumWindowBytes;
        private final long initialWindowBytes;
        private final long maximumWindowBytes;
        private final int minimumParallelism;
        private final int initialParallelism;
        private final int maximumParallelism;
        private final long minimumRangeBytes;
        private final long maximumRangeBytes;
        private final boolean rangeTuning;
        private final boolean adaptiveWindow;
        private final boolean strictContentRange;
        private final boolean refreshSlowRanges;
        private final boolean sequentialFallback;
        private final boolean prefetchRanges;

        private RangePolicy(long minimumWindowBytes, long initialWindowBytes,
                            long maximumWindowBytes, int minimumParallelism,
                            int initialParallelism, int maximumParallelism,
                            long minimumRangeBytes, long maximumRangeBytes,
                            boolean rangeTuning, boolean adaptiveWindow,
                            boolean strictContentRange, boolean refreshSlowRanges,
                            boolean sequentialFallback, boolean prefetchRanges) {
            if (minimumWindowBytes <= 0L || initialWindowBytes < minimumWindowBytes
                    || maximumWindowBytes < initialWindowBytes || minimumParallelism <= 0
                    || initialParallelism < minimumParallelism
                    || maximumParallelism < initialParallelism || minimumRangeBytes <= 0L
                    || maximumRangeBytes < minimumRangeBytes) {
                throw new IllegalArgumentException("Invalid parallel range policy");
            }
            this.minimumWindowBytes = minimumWindowBytes;
            this.initialWindowBytes = initialWindowBytes;
            this.maximumWindowBytes = maximumWindowBytes;
            this.minimumParallelism = minimumParallelism;
            this.initialParallelism = initialParallelism;
            this.maximumParallelism = maximumParallelism;
            this.minimumRangeBytes = minimumRangeBytes;
            this.maximumRangeBytes = maximumRangeBytes;
            this.rangeTuning = rangeTuning;
            this.adaptiveWindow = adaptiveWindow;
            this.strictContentRange = strictContentRange;
            this.refreshSlowRanges = refreshSlowRanges;
            this.sequentialFallback = sequentialFallback;
            this.prefetchRanges = prefetchRanges;
        }

        static RangePolicy forProfile(String profile) {
            int cpuParallelism = Math.max(
                    1, Runtime.getRuntime().availableProcessors() * 2);
            if (OpenDrivePlaybackHints.PROFILE_QUARK_ORIGINAL.equals(profile)) {
                return tunableBuffered(1024L * 1024L, QUARK_WINDOW_BYTES,
                        16L * 1024L * 1024L, 8, QUARK_PARALLELISM,
                        64L * 1024L, 512L * 1024L);
            }
            if (OpenDrivePlaybackHints.PROFILE_UC_PDS.equals(profile)) {
                // Opening many response bodies without reading them is not parallel download.
                // Buffer the bounded 16 MiB window so every UC connection actively transfers.
                int ranges = Math.min(cpuParallelism, 16);
                long partBytes = UC_WINDOW_BYTES / ranges;
                return tunableBuffered(2L * 1024L * 1024L, UC_WINDOW_BYTES,
                        UC_WINDOW_BYTES, Math.min(2, ranges), ranges,
                        Math.min(256L * 1024L, partBytes),
                        Math.max(4L * 1024L * 1024L, partBytes));
            }
            if (OpenDrivePlaybackHints.PROFILE_BAIDU_ORIGINAL.equals(profile)) {
                // Baidu CDN nodes are known to accept bounded ranges up to about 5 MiB. Keep
                // three 4 MiB pieces in flight and fall back if exact Content-Range validation
                // fails on a different node or account tier.
                return tunableBuffered(1024L * 1024L, BAIDU_WINDOW_BYTES,
                        20L * 1024L * 1024L, 1, 5,
                        1024L * 1024L, 5L * 1024L * 1024L,
                        BAIDU_PARALLELISM);
            }
            if (OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP.equals(profile)) {
                return tunableBuffered(1024L * 1024L, GENERIC_WINDOW_BYTES,
                        16L * 1024L * 1024L, 1, 8,
                        512L * 1024L, 4L * 1024L * 1024L,
                        GENERIC_PARALLELISM);
            }
            if (OpenDrivePlaybackHints.PROFILE_ALIPAN.equals(profile)) {
                return adaptive(ALIPAN_MIN_WINDOW_BYTES, ALIPAN_WINDOW_BYTES,
                        ALIPAN_MAX_WINDOW_BYTES, 4);
            }
            return null;
        }

        static RangePolicy fixed(long windowBytes, int parallelism) {
            long rangeBytes = Math.max(1L, windowBytes / parallelism);
            return new RangePolicy(windowBytes, windowBytes, windowBytes,
                    parallelism, parallelism, parallelism, rangeBytes, rangeBytes,
                    false, false, false, false, false, false);
        }

        static RangePolicy buffered(long windowBytes, int parallelism) {
            long rangeBytes = Math.max(1L, windowBytes / parallelism);
            return new RangePolicy(windowBytes, windowBytes, windowBytes,
                    parallelism, parallelism, parallelism, rangeBytes, rangeBytes,
                    false, false, true, false, true, true);
        }

        private static RangePolicy tunableBuffered(long minimumWindowBytes,
                                                   long initialWindowBytes,
                                                   long maximumWindowBytes,
                                                   int minimumParallelism,
                                                   int maximumParallelism,
                                                   long minimumRangeBytes,
                                                   long maximumRangeBytes) {
            return tunableBuffered(minimumWindowBytes, initialWindowBytes, maximumWindowBytes,
                    minimumParallelism, maximumParallelism, minimumRangeBytes,
                    maximumRangeBytes, maximumParallelism);
        }

        private static RangePolicy tunableBuffered(long minimumWindowBytes,
                                                   long initialWindowBytes,
                                                   long maximumWindowBytes,
                                                   int minimumParallelism,
                                                   int maximumParallelism,
                                                   long minimumRangeBytes,
                                                   long maximumRangeBytes,
                                                   int initialParallelism) {
            return new RangePolicy(minimumWindowBytes, initialWindowBytes, maximumWindowBytes,
                    minimumParallelism, initialParallelism, maximumParallelism, minimumRangeBytes,
                    maximumRangeBytes, true, false, true, true, true, true);
        }

        private static RangePolicy adaptive(long minimumWindowBytes,
                                            long initialWindowBytes,
                                            long maximumWindowBytes, int parallelism) {
            return new RangePolicy(minimumWindowBytes, initialWindowBytes,
                    maximumWindowBytes, parallelism, parallelism, parallelism,
                    Math.max(1L, minimumWindowBytes / parallelism),
                    Math.max(1L, maximumWindowBytes / parallelism),
                    false, true, true, true, true, false);
        }

        boolean adaptive() {
            return adaptiveWindow;
        }

        boolean rangeTuning() {
            return rangeTuning;
        }

        long minimumWindowBytes() {
            return minimumWindowBytes;
        }

        long initialWindowBytes() {
            return initialWindowBytes;
        }

        long maximumWindowBytes() {
            return maximumWindowBytes;
        }

        int parallelism() {
            return initialParallelism;
        }

        int maximumParallelism() {
            return maximumParallelism;
        }

        int minimumParallelism() {
            return minimumParallelism;
        }

        long minimumRangeBytes() {
            return minimumRangeBytes;
        }

        long maximumRangeBytes() {
            return maximumRangeBytes;
        }

        boolean strictContentRange() {
            return strictContentRange;
        }

        boolean refreshSlowRanges() {
            return refreshSlowRanges;
        }

        boolean sequentialFallback() {
            return sequentialFallback;
        }

        boolean prefetchRanges() {
            return prefetchRanges;
        }
    }

    private final DataSource.Factory upstreamFactory;
    private final RangePolicy policy;
    private final long initialWindowBytes;
    private final long minimumWindowBytes;
    private final long maximumWindowBytes;
    private final int maximumParallelism;
    private final int initialParallelism;
    private final long configuredContentLength;
    private final String profile;
    private final boolean strictContentRange;
    private final boolean refreshSlowRanges;
    private final boolean sequentialFallback;
    private final boolean prefetchRanges;
    private final List<TransferListener> listeners = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();
    private final ConcurrentLinkedQueue<NetworkBatchSample> networkBatchSamples =
            new ConcurrentLinkedQueue<>();
    private DataSource single;
    private ExecutorService executor;
    private ExecutorService hedgeExecutor;
    private DataSpec original;
    private boolean parallel;
    private int partIndex;
    private long position;
    private long bytesRemaining;
    private long startedAt;
    private long lastLogAt;
    private long totalBytesRead;
    private long intervalBytesRead;
    private long tuningBytesRead;
    private long tuningStartedNanos;
    private long windowBytes;
    private int parallelism;
    private long activeWindowLength;
    private long activeWindowReadNanos;
    private boolean activeWindowFull;
    private long activePartBytesRead;
    private long activePartReadNanos;
    private long learnedRangeBps;
    private Part slowReplacement;
    private long slowReplacementPosition;
    private long slowReplacementStartedNanos;
    private boolean rangeRefreshPending;
    private boolean rangeFailureRecorded;
    private int rangeRefreshes;
    private boolean startupRampPending;
    private final AdaptiveWindowController adaptiveWindow;
    private final PlaybackHealthMonitor health;
    private RangeTuningStore.Session tuningSession;
    private RangeProbeOptimizer rangeOptimizer;
    private long tuningNetworkGeneration;
    private long tuningContentBytes;

    ParallelRangeDataSource(DataSource.Factory upstreamFactory, long chunkBytes,
                            int parallelism, long contentLength, String profile) {
        this(upstreamFactory, contentLength, profile,
                RangePolicy.fixed(chunkBytes, parallelism));
    }

    ParallelRangeDataSource(DataSource.Factory upstreamFactory, long contentLength,
                            String profile, RangePolicy policy) {
        this(upstreamFactory, contentLength, profile, policy, new PlaybackHealthMonitor());
    }

    private ParallelRangeDataSource(DataSource.Factory upstreamFactory, long contentLength,
                            String profile, RangePolicy policy, PlaybackHealthMonitor health) {
        this.upstreamFactory = upstreamFactory;
        this.health = health;
        this.policy = policy;
        this.initialWindowBytes = policy.initialWindowBytes();
        this.minimumWindowBytes = policy.minimumWindowBytes();
        this.maximumWindowBytes = policy.maximumWindowBytes();
        this.windowBytes = initialWindowBytes;
        this.maximumParallelism = policy.maximumParallelism();
        this.initialParallelism = policy.parallelism();
        this.parallelism = initialParallelism;
        this.configuredContentLength = Math.max(0L, contentLength);
        this.profile = profile == null ? "test" : profile;
        this.strictContentRange = policy.strictContentRange();
        this.refreshSlowRanges = policy.refreshSlowRanges();
        this.sequentialFallback = policy.sequentialFallback();
        this.prefetchRanges = policy.prefetchRanges();
        this.adaptiveWindow = policy.adaptive()
                ? new AdaptiveWindowController(policy.minimumWindowBytes(),
                policy.initialWindowBytes(), policy.maximumWindowBytes()) : null;
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        if (single != null) single.addTransferListener(listener);
        for (Part part : parts) part.addTransferListener(listener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        close();
        long declared = declaredContentLength(dataSpec.httpRequestHeaders);
        long total = declared > 0L ? declared : configuredContentLength;
        original = withoutInternalHeaders(dataSpec);
        if (total <= 0L && OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP.equals(profile)
                && dataSpec.length == C.LENGTH_UNSET) {
            long probeStartedNanos = System.nanoTime();
            total = probeContentLength(original);
            if (TRACE_ENABLED) AppLog.i(TAG, "event=generic-range-probe,elapsedMs="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - probeStartedNanos)
                    + ",rangeSupported=" + (total > 0L));
        }
        applyLearnedSetting(original.uri, total);
        tuningContentBytes = total;
        position = dataSpec.position;
        bytesRemaining = dataSpec.length == C.LENGTH_UNSET && total > position
                ? total - position : dataSpec.length;
        parallel = total > position && bytesRemaining > 0L
                && (!OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP.equals(profile)
                || bytesRemaining >= GENERIC_WINDOW_BYTES);
        if (!parallel) return openSingle(original);
        tuningStartedNanos = System.nanoTime();
        if (TRACE_ENABLED) startedAt = lastLogAt = SystemClock.elapsedRealtime();
        executor = Executors.newFixedThreadPool(maximumParallelism);
        hedgeExecutor = Executors.newFixedThreadPool(2);
        startupRampPending = prefetchRanges;
        try {
            openWindow();
        } catch (IOException error) {
            if (!sequentialFallback || isCancellation(error)) throw error;
            return openSequentialFallback(error);
        }
        if (TRACE_ENABLED) trace("open");
        return bytesRemaining;
    }

    private void applyLearnedSetting(Uri uri, long contentBytes) {
        tuningNetworkGeneration = NetworkEnvironmentMonitor.generation();
        parallelism = initialParallelism;
        windowBytes = initialWindowBytes;
        rangeOptimizer = null;
        if (adaptiveWindow != null) adaptiveWindow.reset();
        tuningSession = RangeTuningStore.session(profile, "playback", uri, contentBytes);
        RangeTuningProfile learned = tuningSession.load();
        learnedRangeBps = learned == null ? 0L
                : learned.observedBps() / Math.max(1, learned.ranges());
        if (policy.rangeTuning()) {
            RangeProbeOptimizer.Setting defaults = new RangeProbeOptimizer.Setting(
                    initialParallelism, Math.max(1L, initialWindowBytes / initialParallelism));
            RangeProbeOptimizer.Bounds bounds = new RangeProbeOptimizer.Bounds(
                    policy.minimumParallelism(), maximumParallelism,
                    policy.minimumRangeBytes(), policy.maximumRangeBytes(),
                    minimumWindowBytes, maximumWindowBytes);
            rangeOptimizer = new RangeProbeOptimizer(defaults, bounds, learned);
            applyRangeSetting(rangeOptimizer.setting());
            if (learned != null) logLoadedSetting(learned);
            return;
        }
        if (learned == null) return;
        parallelism = Math.max(1, Math.min(maximumParallelism, learned.ranges()));
        long learnedWindow;
        try {
            learnedWindow = Math.multiplyExact(learned.rangeBytes(), parallelism);
        } catch (ArithmeticException overflow) {
            learnedWindow = maximumWindowBytes;
        }
        windowBytes = Math.max(minimumWindowBytes,
                Math.min(maximumWindowBytes, learnedWindow));
        if (adaptiveWindow != null) adaptiveWindow.restore(windowBytes);
        logLoadedSetting(learned);
    }

    private void applyRangeSetting(RangeProbeOptimizer.Setting setting) {
        parallelism = setting.ranges();
        windowBytes = setting.windowBytes();
    }

    private void logLoadedSetting(RangeTuningProfile learned) {
        AppLog.i(TAG, "event=tuning-load,profile=" + profile
                + ",ranges=" + parallelism + ",rangeBytes="
                + Math.max(1L, windowBytes / parallelism)
                + ",confidence=" + learned.confidence()
                + ",observedBps=" + learned.observedBps());
    }

    /** A one-byte exact range proves both range support and total length before fan-out. */
    private long probeContentLength(DataSpec dataSpec) throws IOException {
        DataSource probe = createUpstream();
        try {
            DataSpec request = dataSpec.buildUpon().setLength(1L).build();
            probe.open(request);
            return contentRangeTotal(probe.getResponseHeaders(), dataSpec.position, 1L);
        } catch (IOException error) {
            if (isCancellation(error)) throw error;
            AppLog.i(TAG, "Generic range probe failed; using sequential playback");
            return 0L;
        } catch (RuntimeException error) {
            AppLog.i(TAG, "Generic range probe failed; using sequential playback");
            return 0L;
        } finally {
            try {
                probe.close();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private long openSingle(DataSpec dataSpec) throws IOException {
        single = createUpstream();
        try {
            long opened = single.open(dataSpec);
            bytesRemaining = dataSpec.length == C.LENGTH_UNSET ? opened : dataSpec.length;
            return opened;
        } catch (IOException | RuntimeException error) {
            try {
                single.close();
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            single = null;
            throw error;
        }
    }

    private void openWindow() throws IOException {
        refreshTuningAfterNetworkChange();
        processNetworkBatchSamples(true);
        finishWindowSample();
        try {
            closeParts();
        } catch (IOException error) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            throw error;
        }
        if (adaptiveWindow != null) windowBytes = adaptiveWindow.windowBytes();
        long windowLength = nextWindowLength(position, bytesRemaining);
        int count = (int) Math.min((long) parallelism, windowLength);
        long base = windowLength / count;
        long remainder = windowLength % count;
        long nextPosition = position;
        boolean urgentStart = startupRampPending;
        startupRampPending = false;
        NetworkBatch batch = rangeOptimizer == null || urgentStart || isStartupRange(position) ? null
                : new NetworkBatch(count, networkBatchSamples);
        if (prefetchRanges) {
            openBufferedWindow(windowLength, count, base, remainder,
                    nextPosition, batch, urgentStart);
            return;
        }
        List<Future<Part>> futures = new ArrayList<>(count);
        ConcurrentLinkedQueue<Part> pending = new ConcurrentLinkedQueue<>();
        AtomicBoolean accepting = new AtomicBoolean(true);
        try {
            for (int index = 0; index < count; index++) {
                long length = base + (index < remainder ? 1L : 0L);
                long partPosition = nextPosition;
                nextPosition += length;
                futures.add(executor.submit(() -> openTrackedPart(
                        partPosition, length, pending, accepting, batch)));
            }
            for (Future<Part> future : futures) {
                Part part = future.get();
                if (pending.remove(part)) parts.add(part);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure =
                    new InterruptedIOException("Interrupted while opening parallel ranges");
            failure.initCause(error);
            abortWindow(futures, pending, accepting, failure);
            throw failure;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            IOException failure = cause instanceof IOException io ? io
                    : new IOException("Unable to open parallel range", cause);
            abortWindow(futures, pending, accepting, failure);
            throw failure;
        } catch (RuntimeException error) {
            IOException failure = new IOException("Unable to schedule parallel ranges", error);
            abortWindow(futures, pending, accepting, failure);
            throw failure;
        }
        partIndex = 0;
        activeWindowLength = windowLength;
        activeWindowReadNanos = 0L;
        activeWindowFull = windowLength == windowBytes;
        resetPartSample();
        rangeRefreshes = 0;
    }

    private void refreshTuningAfterNetworkChange() {
        long generation = NetworkEnvironmentMonitor.generation();
        if (generation == tuningNetworkGeneration || original == null) return;
        // Samples and candidates straddling two networks are not comparable. The persisted
        // known-good profile remains untouched; the next window starts from the new environment.
        networkBatchSamples.clear();
        tuningBytesRead = 0L;
        tuningStartedNanos = System.nanoTime();
        rangeFailureRecorded = false;
        applyLearnedSetting(original.uri, tuningContentBytes);
        AppLog.i(TAG, "event=tuning-network-change,profile=" + profile
                + ",generation=" + generation + ",ranges=" + parallelism
                + ",rangeBytes=" + Math.max(1L, windowBytes / parallelism));
    }

    private void openBufferedWindow(long windowLength, int count, long base,
                                    long remainder, long nextPosition,
                                    NetworkBatch batch, boolean urgentStart) throws IOException {
        List<Part> opened = new ArrayList<>(count);
        AtomicBoolean rampStarted = new AtomicBoolean(!urgentStart);
        int urgentCount = urgentStart ? urgentRangeCount(count) : count;
        Runnable startRemainder = () -> {
            if (!rampStarted.compareAndSet(false, true)) return;
            for (int index = urgentCount; index < opened.size(); index++) {
                opened.get(index).startBuffering(executor);
            }
            AppLog.i(TAG, "event=urgent-ramp,profile=" + profile
                    + ",urgentRanges=" + urgentCount + ",targetRanges=" + count);
        };
        try {
            for (int index = 0; index < count; index++) {
                long length = base + (index < remainder ? 1L : 0L);
                long partPosition = nextPosition;
                nextPosition += length;
                opened.add(bufferedPart(partPosition, length, batch,
                        urgentStart && index == 0 ? startRemainder : null));
            }
            parts.addAll(opened);
            for (int index = 0; index < urgentCount; index++) {
                opened.get(index).startBuffering(executor);
            }
        } catch (RuntimeException error) {
            IOException failure = new IOException("Unable to schedule buffered ranges", error);
            for (Part part : opened) {
                try {
                    part.close();
                } catch (IOException cleanupError) {
                    failure.addSuppressed(cleanupError);
                }
            }
            parts.clear();
            throw failure;
        }
        partIndex = 0;
        activeWindowLength = windowLength;
        activeWindowReadNanos = 0L;
        activeWindowFull = !urgentStart && windowLength == windowBytes;
        resetPartSample();
        rangeRefreshes = 0;
        if (urgentStart) {
            AppLog.i(TAG, "event=urgent-start,profile=" + profile
                    + ",urgentRanges=" + urgentCount + ",targetRanges=" + count);
        }
    }

    static int urgentRangeCount(int targetRanges) {
        return Math.max(1, Math.min(URGENT_START_RANGES, targetRanges));
    }

    private boolean isStartupRange(long rangePosition) {
        return OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP.equals(profile)
                && rangePosition - original.position < GENERIC_STARTUP_BYTES;
    }

    private long nextWindowLength(long rangePosition, long remaining) {
        long length = Math.min(windowBytes, remaining);
        if (!isStartupRange(rangePosition)) return length;
        // Large ordered ranges strand useful prefetched bytes behind a slow first connection.
        // Use short ranges while discovering MP4 tracks (and after a seek), then resume the
        // learned bulk-transfer setting. Clamp the boundary so startup samples stay separate.
        return Math.min(length, Math.min(GENERIC_STARTUP_PART_BYTES * parallelism,
                GENERIC_STARTUP_BYTES - (rangePosition - original.position)));
    }

    private void finishWindowSample() {
        if (adaptiveWindow == null || !activeWindowFull || activeWindowReadNanos <= 0L
                || activeWindowLength <= 0L || parts.isEmpty()
                || partIndex < parts.size()) return;
        long bps = bytesPerSecond(activeWindowLength, activeWindowReadNanos);
        long previous = adaptiveWindow.windowBytes();
        AdaptiveWindowController.Action action = adaptiveWindow.record(bps);
        windowBytes = adaptiveWindow.windowBytes();
        activeWindowLength = 0L;
        activeWindowReadNanos = 0L;
        activeWindowFull = false;
        if (action == AdaptiveWindowController.Action.NONE) return;
        AppLog.i(TAG, "event=window-" + action.name().toLowerCase(java.util.Locale.ROOT)
                + ",profile=" + profile + ",fromBytes=" + previous
                + ",toBytes=" + windowBytes + ",activeBps=" + bps
                + ",baselineBps=" + adaptiveWindow.baselineBps());
    }

    private void processNetworkBatchSamples(boolean transferActive) {
        if (!transferActive) {
            // Media3 closes and reopens the same source for seeks and extractor metadata reads.
            // Closing cancels pending range workers, so their late samples are not evidence that
            // the known-good setting failed and must never mutate its persisted profile.
            networkBatchSamples.clear();
            return;
        }
        if (rangeOptimizer == null) {
            networkBatchSamples.clear();
            return;
        }
        NetworkBatchSample sample;
        while ((sample = networkBatchSamples.poll()) != null) {
            if (sample.failed()) {
                if (!rangeFailureRecorded) {
                    tuningSession.saveOrClear(rangeOptimizer.fail(System.currentTimeMillis()));
                    applyRangeSetting(rangeOptimizer.bestSetting());
                    rangeFailureRecorded = true;
                    AppLog.i(TAG, "event=tuning-failure-rollback,profile=" + profile
                            + ",ranges=" + parallelism + ",rangeBytes="
                            + Math.max(1L, windowBytes / parallelism));
                }
                continue;
            }
            long bps = bytesPerSecond(sample.bytes(), sample.elapsedNanos());
            PlaybackHealthMonitor.Snapshot health = this.health.snapshot(
                    SystemClock.elapsedRealtime());
            RangeProbeOptimizer.Decision decision = rangeOptimizer.recordBatch(
                    new RangeProbeOptimizer.Observation(bps, sample.firstByteNanos(),
                            health.marginPermille(), health.rebufferId()),
                    System.currentTimeMillis());
            if (decision.profile() != null) tuningSession.save(decision.profile());
            if (decision.action() == RangeProbeOptimizer.Action.NONE) continue;
            applyRangeSetting(decision.setting());
            AppLog.i(TAG, "event=tuning-"
                    + decision.action().name().toLowerCase(java.util.Locale.ROOT)
                    + ",profile=" + profile + ",ranges=" + parallelism
                    + ",rangeBytes=" + Math.max(1L, windowBytes / parallelism)
                    + ",batchBps=" + bps);
        }
    }

    static boolean shouldRefreshRange(long baselineBps, long bytes, long elapsedNanos) {
        if (baselineBps <= 0L || bytes <= 0L || elapsedNanos < RANGE_REFRESH_SAMPLE_NANOS) {
            return false;
        }
        return bytesPerSecond(bytes, elapsedNanos) < baselineBps
                - baselineBps * RANGE_REFRESH_LOSS_PERCENT / 100L;
    }

    private static long bytesPerSecond(long bytes, long elapsedNanos) {
        if (bytes <= 0L || elapsedNanos <= 0L) return 0L;
        return bytes * 1_000_000_000L / elapsedNanos;
    }

    private Part openTrackedPart(long partPosition, long length,
                                 ConcurrentLinkedQueue<Part> pending,
                                 AtomicBoolean accepting, NetworkBatch batch) throws IOException {
        Part part;
        try {
            part = openPart(partPosition, length, batch);
        } catch (IOException | RuntimeException error) {
            if (batch != null) batch.failure();
            throw error;
        }
        pending.add(part);
        if (!accepting.get() && pending.remove(part)) {
            part.close();
            throw new IOException("Parallel range was cancelled while opening");
        }
        return part;
    }

    private void abortWindow(List<Future<Part>> futures,
                             ConcurrentLinkedQueue<Part> pending,
                             AtomicBoolean accepting, IOException failure) {
        accepting.set(false);
        futures.forEach(future -> future.cancel(true));
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        try {
            closeParts();
        } catch (IOException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        Part part;
        while ((part = pending.poll()) != null) {
            try {
                part.close();
            } catch (IOException cleanupError) {
                failure.addSuppressed(cleanupError);
            }
        }
    }

    private void appendBufferedWindowIfNeeded() throws IOException {
        processNetworkBatchSamples(true);
        if (!prefetchRanges || partIndex >= parts.size()
                || parts.size() - partIndex > parallelism / 2) return;
        long pendingBytes = 0L;
        for (int index = partIndex; index < parts.size(); index++) {
            pendingBytes += parts.get(index).remaining;
        }
        long unscheduledBytes = bytesRemaining - pendingBytes;
        if (unscheduledBytes <= 0L) return;
        long nextPosition = position + pendingBytes;
        long batchLength = nextWindowLength(nextPosition, unscheduledBytes);
        int count = (int) Math.min((long) parallelism, batchLength);
        long base = batchLength / count;
        long remainder = batchLength % count;
        NetworkBatch batch = rangeOptimizer == null || isStartupRange(nextPosition) ? null
                : new NetworkBatch(count, networkBatchSamples);
        List<Future<Part>> futures = new ArrayList<>(count);
        List<Part> opened = new ArrayList<>(count);
        ConcurrentLinkedQueue<Part> pending = new ConcurrentLinkedQueue<>();
        AtomicBoolean accepting = new AtomicBoolean(true);
        try {
            for (int index = 0; index < count; index++) {
                long length = base + (index < remainder ? 1L : 0L);
                long rangePosition = nextPosition;
                nextPosition += length;
                futures.add(executor.submit(() -> openTrackedPart(
                        rangePosition, length, pending, accepting, batch)));
            }
            for (Future<Part> future : futures) {
                Part part = future.get();
                if (pending.remove(part)) opened.add(part);
            }
            for (Part part : opened) part.startBuffering(executor);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure =
                    new InterruptedIOException("Interrupted while prefetching parallel ranges");
            failure.initCause(error);
            abortBufferedAppend(futures, opened, pending, accepting, failure);
            throw failure;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            IOException failure = cause instanceof IOException io ? io
                    : new IOException("Unable to prefetch parallel range", cause);
            abortBufferedAppend(futures, opened, pending, accepting, failure);
            throw failure;
        } catch (RuntimeException error) {
            IOException failure = new IOException("Unable to schedule parallel prefetch", error);
            abortBufferedAppend(futures, opened, pending, accepting, failure);
            throw failure;
        }
        if (partIndex > 0) {
            parts.subList(0, partIndex).clear();
            partIndex = 0;
        }
        parts.addAll(opened);
        // Rolling prefetch extends the same open source for the whole movie. Give each
        // successful batch its recovery budget instead of exhausting it once per movie.
        rangeRefreshes = 0;
        AppLog.i(TAG, "event=rolling-prefetch,profile=" + profile
                + ",position=" + (nextPosition - batchLength)
                + ",bytes=" + batchLength + ",parts=" + count);
    }

    private void abortBufferedAppend(List<Future<Part>> futures, List<Part> opened,
                                     ConcurrentLinkedQueue<Part> pending,
                                     AtomicBoolean accepting, IOException failure) {
        accepting.set(false);
        futures.forEach(future -> future.cancel(true));
        for (Part part : opened) {
            try {
                part.close();
            } catch (IOException cleanupError) {
                failure.addSuppressed(cleanupError);
            }
        }
        Part part;
        while ((part = pending.poll()) != null) {
            try {
                part.close();
            } catch (IOException cleanupError) {
                failure.addSuppressed(cleanupError);
            }
        }
    }

    private Part openPart(long partPosition, long length) throws IOException {
        return openPart(partPosition, length, null);
    }

    private Part openPart(long partPosition, long length, NetworkBatch batch) throws IOException {
        DataSource source = openRangeSource(partPosition, length);
        return new Part(source, length, prefetchRanges, batch, null);
    }

    private Part bufferedPart(long partPosition, long length, NetworkBatch batch,
                              Runnable firstProgress) {
        return new Part(() -> openRangeSource(partPosition, length), length,
                true, batch, firstProgress);
    }

    private DataSource openRangeSource(long partPosition, long length) throws IOException {
        DataSource source = createUpstream();
        try {
            DataSpec spec = original.buildUpon().setPosition(partPosition).setLength(length).build();
            long opened = source.open(spec);
            if (opened != C.LENGTH_UNSET && opened < length) {
                throw new EOFException("Parallel range shorter than requested");
            }
            if (strictContentRange && !matchesContentRange(
                    source.getResponseHeaders(), partPosition, length)) {
                throw new IOException("Parallel range response metadata mismatch");
            }
            return source;
        } catch (IOException | RuntimeException error) {
            try {
                source.close();
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private DataSource createUpstream() {
        DataSource source = upstreamFactory.createDataSource();
        for (TransferListener listener : listeners) source.addTransferListener(listener);
        return source;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT;
        if (!parallel) return readSingle(buffer, offset, length);
        while (true) {
            if (partIndex >= parts.size()) {
                try {
                    openWindow();
                } catch (IOException error) {
                    if (!sequentialFallback || isCancellation(error)) throw error;
                    openSequentialFallback(error);
                    return readSingle(buffer, offset, length);
                }
            }
            refreshCurrentRangeIfNeeded();
            Part part = parts.get(partIndex);
            int allowed = (int) Math.min((long) length,
                    Math.min(bytesRemaining, part.remaining));
            long readStartedAt = SystemClock.elapsedRealtimeNanos();
            int read;
            try {
                read = part.read(buffer, offset, allowed);
                if (read == C.RESULT_END_OF_INPUT && part.remaining != 0L) {
                    throw new EOFException("Parallel range ended early");
                }
            } catch (RangeStalledException stalled) {
                if (slowReplacement != null) {
                    refreshCurrentRangeIfNeeded();
                    if (parts.get(partIndex) != part) continue;
                }
                if (refreshSlowRanges && rangeRefreshes < MAX_RANGE_REFRESHES_PER_WINDOW
                        && hedgeCurrentRange(stalled)) {
                    continue;
                }
                if (!sequentialFallback) throw stalled;
                openSequentialFallback(stalled);
                return readSingle(buffer, offset, length);
            } catch (IOException error) {
                if (!sequentialFallback || isCancellation(error)) throw error;
                openSequentialFallback(error);
                return readSingle(buffer, offset, length);
            }
            long readNanos = Math.max(1L,
                    SystemClock.elapsedRealtimeNanos() - readStartedAt);
            if (read == C.RESULT_END_OF_INPUT) {
                advancePart();
                continue;
            }
            part.remaining -= read;
            position += read;
            bytesRemaining -= read;
            activeWindowReadNanos += readNanos;
            activePartBytesRead += read;
            activePartReadNanos += readNanos;
            if (refreshSlowRanges && rangeRefreshes < MAX_RANGE_REFRESHES_PER_WINDOW
                    && part.remaining > 0L && slowReplacement == null
                    && shouldRefreshRange(refreshBaselineBps(),
                    activePartBytesRead, activePartReadNanos)) {
                rangeRefreshPending = true;
            }
            totalBytesRead += read;
            tuningBytesRead += read;
            if (TRACE_ENABLED) intervalBytesRead += read;
            if (TRACE_ENABLED
                    && SystemClock.elapsedRealtime() - lastLogAt >= LOG_INTERVAL_MILLIS) {
                trace("progress");
            }
            if (part.remaining == 0L) advancePart();
            return read;
        }
    }

    private int readSingle(byte[] buffer, int offset, int length) throws IOException {
        int allowed = bytesRemaining == C.LENGTH_UNSET ? length
                : (int) Math.min((long) length, bytesRemaining);
        int read = single.read(buffer, offset, allowed);
        if (read > 0 && bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
        return read;
    }

    private long refreshBaselineBps() {
        if (adaptiveWindow != null) return adaptiveWindow.baselineBps();
        // Before a learned sample exists, recover generic streams delivering less than
        // 64 KiB/s for the sample interval. Limit attempts with the normal window budget.
        return OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP.equals(profile)
                ? Math.max(128L * 1024L, learnedRangeBps) : learnedRangeBps;
    }

    private void refreshCurrentRangeIfNeeded() throws IOException {
        if (slowReplacement != null) {
            long consumed = position - slowReplacementPosition;
            if (slowReplacement.alignBufferedPosition(consumed)) {
                Part previous = parts.set(partIndex, slowReplacement);
                slowReplacement = null;
                previous.close();
                resetPartSample();
                AppLog.i(TAG, "event=slow-range-recovered,profile=" + profile
                        + ",position=" + position);
            } else if (slowReplacement.failedOrClosed()
                    || System.nanoTime() - slowReplacementStartedNanos
                    >= TimeUnit.MILLISECONDS.toNanos(RANGE_HEDGE_TIMEOUT_MILLIS)) {
                closeSlowReplacement();
                resetPartSample();
            }
        }
        if (!rangeRefreshPending || partIndex >= parts.size()) return;
        rangeRefreshPending = false;
        Part previous = parts.get(partIndex);
        if (previous.remaining <= 0L) return;
        if (prefetchRanges && hedgeExecutor != null) {
            if (slowReplacement != null) return;
            slowReplacementPosition = position;
            slowReplacementStartedNanos = System.nanoTime();
            slowReplacement = bufferedPart(position, previous.remaining, null, null);
            slowReplacement.startBuffering(hedgeExecutor);
            rangeRefreshes++;
            resetPartSample();
            AppLog.i(TAG, "event=slow-range-hedge,profile=" + profile
                    + ",position=" + position + ",remaining=" + previous.remaining
                    + ",baselineBps=" + refreshBaselineBps());
            return;
        }
        try {
            Part replacement = openPart(position, previous.remaining);
            replacement.startBuffering(hedgeExecutor != null ? hedgeExecutor : executor);
            parts.set(partIndex, replacement);
            rangeRefreshes++;
            resetPartSample();
            try {
                previous.close();
            } catch (IOException cleanupError) {
                AppLog.w(TAG, "Unable to close degraded parallel range", cleanupError);
            }
            AppLog.i(TAG, "event=range-refresh,profile=" + profile
                    + ",position=" + position + ",remaining=" + replacement.remaining
                    + ",baselineBps=" + refreshBaselineBps());
        } catch (IOException refreshError) {
            // The existing exact range is still open, so a failed optimization must not interrupt
            // otherwise valid playback. The next completed window can still shrink the range size.
            resetPartSample();
            AppLog.w(TAG, "Unable to refresh degraded parallel range", refreshError);
        }
    }

    private boolean hedgeCurrentRange(RangeStalledException stalled) throws IOException {
        closeSlowReplacement();
        if (partIndex >= parts.size() || hedgeExecutor == null) return false;
        Part previous = parts.get(partIndex);
        if (previous.remaining <= 0L) return false;
        Part replacement = bufferedPart(position, previous.remaining, null, null);
        CompletableFuture<Part> winner = firstReadable(previous, replacement);
        replacement.startBuffering(hedgeExecutor);
        try {
            Part selected = winner.get(RANGE_HEDGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            rangeRefreshes++;
            resetPartSample();
            if (selected == previous) {
                replacement.close();
                AppLog.i(TAG, "event=range-hedge-original,profile=" + profile
                        + ",position=" + position + ",firstByte=" + stalled.firstByte);
                return true;
            }
            parts.set(partIndex, replacement);
            previous.close();
            AppLog.i(TAG, "event=range-hedge-replacement,profile=" + profile
                    + ",position=" + position + ",remaining=" + replacement.remaining
                    + ",firstByte=" + stalled.firstByte);
            return true;
        } catch (TimeoutException | ExecutionException error) {
            replacement.close();
            AppLog.w(TAG, "Buffered range hedge did not recover", error);
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            replacement.close();
            InterruptedIOException failure =
                    new InterruptedIOException("Interrupted while hedging buffered range");
            failure.initCause(error);
            throw failure;
        }
    }

    private static CompletableFuture<Part> firstReadable(Part first, Part second) {
        CompletableFuture<Part> winner = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        observeReadable(first, winner, failures);
        observeReadable(second, winner, failures);
        return winner;
    }

    private static void observeReadable(Part part, CompletableFuture<Part> winner,
                                        AtomicInteger failures) {
        part.progressFuture().whenComplete((ignored, error) -> {
            if (error == null && part.hasReadableData()) winner.complete(part);
            else if (failures.incrementAndGet() == 2) {
                winner.completeExceptionally(error == null
                        ? new IOException("Range hedge completed without data") : error);
            }
        });
    }

    private long openSequentialFallback(IOException cause) throws IOException {
        AppLog.w(TAG, "Parallel range failed for profile=" + profile
                + "; using sequential playback", cause);
        closeParts();
        if (executor != null) executor.shutdownNow();
        executor = null;
        parallel = false;
        if (tuningSession != null) {
            if (rangeOptimizer != null) {
                if (!rangeFailureRecorded) {
                    tuningSession.saveOrClear(rangeOptimizer.fail(System.currentTimeMillis()));
                    rangeFailureRecorded = true;
                }
            } else {
                tuningSession.failure();
            }
        }
        DataSpec fallback = original.buildUpon().setPosition(position)
                .setLength(bytesRemaining).build();
        return openSingle(fallback);
    }

    static boolean isCancellation(IOException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof InterruptedException
                    || cause instanceof InterruptedIOException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    static boolean matchesContentRange(Map<String, List<String>> headers,
                                       long start, long length) {
        return contentRangeTotal(headers, start, length) > 0L;
    }

    static long contentRangeTotal(Map<String, List<String>> headers,
                                  long start, long length) {
        if (headers == null || length <= 0L) return 0L;
        String value = null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!"Content-Range".equalsIgnoreCase(entry.getKey())
                    || entry.getValue() == null || entry.getValue().isEmpty()) continue;
            value = entry.getValue().get(0);
            break;
        }
        if (value == null || !value.startsWith("bytes ")) return 0L;
        int dash = value.indexOf('-', 6);
        int slash = value.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) return 0L;
        try {
            long actualStart = Long.parseLong(value.substring(6, dash));
            long actualEnd = Long.parseLong(value.substring(dash + 1, slash));
            long total = Long.parseLong(value.substring(slash + 1));
            long requestedEnd = Math.addExact(start, length - 1L);
            return actualStart == start && actualEnd == requestedEnd
                    && total > actualEnd ? total : 0L;
        } catch (NumberFormatException | ArithmeticException ignored) {
            return 0L;
        }
    }

    private void advancePart() throws IOException {
        closeSlowReplacement();
        Part part = parts.get(partIndex++);
        part.close();
        resetPartSample();
        try {
            appendBufferedWindowIfNeeded();
        } catch (IOException error) {
            if (!sequentialFallback || isCancellation(error)) throw error;
            openSequentialFallback(error);
        }
    }

    private void resetPartSample() {
        activePartBytesRead = 0L;
        activePartReadNanos = 0L;
        rangeRefreshPending = false;
    }

    @Nullable
    @Override
    public Uri getUri() {
        if (single != null) return single.getUri();
        return partIndex < parts.size() ? parts.get(partIndex).uri() : null;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        if (single != null) return single.getResponseHeaders();
        return parts.isEmpty() ? Collections.emptyMap() : parts.get(0).responseHeaders();
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        if (TRACE_ENABLED && parallel && startedAt != 0L) trace("close");
        processNetworkBatchSamples(false);
        if (rangeOptimizer != null && tuningSession != null && parallel) {
            RangeTuningProfile learned = rangeOptimizer.finish(System.currentTimeMillis());
            if (learned != null) tuningSession.save(learned);
        } else {
            recordLearnedSetting();
        }
        if (single != null) {
            try {
                single.close();
            } catch (IOException error) {
                failure = error;
            }
        }
        try {
            closeParts();
        } catch (IOException error) {
            if (failure == null) failure = error;
        }
        if (executor != null) executor.shutdownNow();
        if (hedgeExecutor != null) hedgeExecutor.shutdownNow();
        single = null;
        executor = null;
        hedgeExecutor = null;
        original = null;
        parallel = false;
        partIndex = 0;
        position = 0L;
        bytesRemaining = C.LENGTH_UNSET;
        startedAt = 0L;
        lastLogAt = 0L;
        totalBytesRead = 0L;
        intervalBytesRead = 0L;
        tuningBytesRead = 0L;
        tuningStartedNanos = 0L;
        windowBytes = initialWindowBytes;
        parallelism = initialParallelism;
        activeWindowLength = 0L;
        activeWindowReadNanos = 0L;
        activeWindowFull = false;
        resetPartSample();
        rangeRefreshes = 0;
        startupRampPending = false;
        rangeFailureRecorded = false;
        networkBatchSamples.clear();
        tuningSession = null;
        rangeOptimizer = null;
        tuningNetworkGeneration = 0L;
        tuningContentBytes = 0L;
        if (adaptiveWindow != null) adaptiveWindow.reset();
        if (failure != null) throw failure;
    }

    private void recordLearnedSetting() {
        if (tuningSession == null || !parallel || tuningBytesRead < 1024L * 1024L
                || tuningStartedNanos <= 0L) return;
        long elapsedNanos = Math.max(1L, System.nanoTime() - tuningStartedNanos);
        if (elapsedNanos < 500_000_000L) return;
        long observedBps = bytesPerSecond(tuningBytesRead, elapsedNanos);
        tuningSession.success(parallelism, Math.max(1L, windowBytes / parallelism),
                observedBps);
    }

    private void trace(String event) {
        long now = SystemClock.elapsedRealtime();
        long elapsed = Math.max(1L, now - startedAt);
        long intervalElapsed = Math.max(1L, now - lastLogAt);
        AppLog.i(TAG, "event=" + event
                + ",parallelism=" + parallelism
                + ",profile=" + profile
                + ",windowBytes=" + windowBytes
                + ",position=" + position
                + ",remaining=" + bytesRemaining
                + ",bytes=" + totalBytesRead
                + ",elapsedMs=" + elapsed
                + ",avgBps=" + totalBytesRead * 1_000L / elapsed
                + ",intervalBytes=" + intervalBytesRead
                + ",intervalMs=" + intervalElapsed
                + ",intervalBps=" + intervalBytesRead * 1_000L / intervalElapsed);
        intervalBytesRead = 0L;
        lastLogAt = now;
    }

    private void closeParts() throws IOException {
        closeSlowReplacement();
        IOException failure = null;
        for (Part part : parts) {
            try {
                part.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
            }
        }
        parts.clear();
        partIndex = 0;
        if (failure != null) throw failure;
    }

    private void closeSlowReplacement() throws IOException {
        Part pending = slowReplacement;
        slowReplacement = null;
        if (pending != null) pending.close();
    }

    private static DataSpec withoutInternalHeaders(DataSpec dataSpec) {
        String found = null;
        for (String key : dataSpec.httpRequestHeaders.keySet()) {
            if (OpenDrivePlaybackHints.CONTENT_LENGTH.equalsIgnoreCase(key)
                    || OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE.equalsIgnoreCase(key)) {
                found = key;
                break;
            }
        }
        if (found == null) return dataSpec;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(dataSpec.httpRequestHeaders);
        headers.entrySet().removeIf(entry ->
                OpenDrivePlaybackHints.CONTENT_LENGTH.equalsIgnoreCase(entry.getKey())
                        || OpenDrivePlaybackHints.PARALLEL_RANGE_PROFILE.equalsIgnoreCase(
                        entry.getKey()));
        return dataSpec.buildUpon().setHttpRequestHeaders(headers).build();
    }

    static long declaredContentLength(Map<String, String> headers) {
        if (headers == null) return 0L;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!OpenDrivePlaybackHints.CONTENT_LENGTH.equalsIgnoreCase(entry.getKey())) continue;
            try {
                return Math.max(0L, Long.parseLong(entry.getValue()));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    @FunctionalInterface
    private interface SourceOpener {
        DataSource open() throws IOException;
    }

    private static final class Part {
        private final SourceOpener opener;
        private final byte[] buffer;
        private final NetworkBatch networkBatch;
        private final Runnable firstProgress;
        private DataSource source;
        private long remaining;
        private Future<?> bufferTask;
        private int bufferedBytes;
        private int bufferPosition;
        private IOException bufferFailure;
        private boolean bufferComplete;
        private boolean closed;
        private boolean sourceClosed;
        private boolean firstProgressSent;
        private long taskStartedNanos;
        private long lastProgressNanos;
        private CompletableFuture<Void> nextProgress;

        private Part(DataSource source, long remaining, boolean prefetch,
                     NetworkBatch networkBatch, Runnable firstProgress) {
            this(null, source, remaining, prefetch, networkBatch, firstProgress);
        }

        private Part(SourceOpener opener, long remaining, boolean prefetch,
                     NetworkBatch networkBatch, Runnable firstProgress) {
            this(opener, null, remaining, prefetch, networkBatch, firstProgress);
        }

        private Part(SourceOpener opener, DataSource source, long remaining, boolean prefetch,
                     NetworkBatch networkBatch, Runnable firstProgress) {
            this.opener = opener;
            this.source = source;
            this.remaining = remaining;
            this.networkBatch = networkBatch;
            this.firstProgress = firstProgress;
            if (prefetch && remaining > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Buffered range is too large");
            }
            this.buffer = prefetch ? new byte[(int) remaining] : null;
        }

        private synchronized void addTransferListener(TransferListener listener) {
            if (source != null) source.addTransferListener(listener);
        }

        private synchronized Uri uri() {
            return source == null ? null : source.getUri();
        }

        private synchronized Map<String, List<String>> responseHeaders() {
            return source == null ? Collections.emptyMap() : source.getResponseHeaders();
        }

        private synchronized boolean hasReadableData() {
            return bufferPosition < bufferedBytes;
        }

        private synchronized boolean alignBufferedPosition(long consumed) {
            if (closed || bufferFailure != null || consumed < 0L || consumed >= bufferedBytes) {
                return false;
            }
            remaining -= consumed - bufferPosition;
            bufferPosition = (int) consumed;
            return true;
        }

        private synchronized boolean failedOrClosed() {
            return closed || bufferFailure != null;
        }

        private synchronized CompletableFuture<Void> progressFuture() {
            if (hasReadableData()) return CompletableFuture.completedFuture(null);
            if (bufferFailure != null) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(bufferFailure);
                return failed;
            }
            if (closed) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new InterruptedIOException("Parallel range was closed"));
                return failed;
            }
            if (nextProgress == null) nextProgress = new CompletableFuture<>();
            return nextProgress;
        }

        private synchronized void startBuffering(ExecutorService executor) {
            if (buffer == null || bufferTask != null || closed) return;
            taskStartedNanos = lastProgressNanos = System.nanoTime();
            try {
                bufferTask = executor.submit(this::fillBuffer);
            } catch (RuntimeException error) {
                setFailure(new IOException("Unable to start buffered range", error));
            }
        }

        private void fillBuffer() {
            try {
                DataSource local = source;
                if (local == null) {
                    local = opener.open();
                    synchronized (this) {
                        if (closed) {
                            try {
                                local.close();
                            } catch (IOException ignored) {
                            }
                            return;
                        }
                        source = local;
                    }
                }
                while (true) {
                    int writeOffset;
                    synchronized (this) {
                        if (closed) return;
                        writeOffset = bufferedBytes;
                        if (writeOffset == buffer.length) {
                            bufferComplete = true;
                            if (networkBatch != null) {
                                networkBatch.success(buffer.length,
                                        Math.max(1L, System.nanoTime() - taskStartedNanos));
                            }
                            notifyAll();
                            return;
                        }
                    }
                    int read = local.read(buffer, writeOffset, buffer.length - writeOffset);
                    if (read == C.RESULT_END_OF_INPUT) {
                        throw new EOFException("Parallel range ended early");
                    }
                    if (read == 0) continue;
                    CompletableFuture<Void> progress;
                    Runnable callback = null;
                    synchronized (this) {
                        bufferedBytes += read;
                        lastProgressNanos = System.nanoTime();
                        progress = nextProgress;
                        nextProgress = null;
                        if (!firstProgressSent) {
                            firstProgressSent = true;
                            if (networkBatch != null) {
                                networkBatch.firstProgress(Math.max(1L,
                                        lastProgressNanos - taskStartedNanos));
                            }
                            callback = firstProgress;
                        }
                        notifyAll();
                    }
                    if (progress != null) progress.complete(null);
                    if (callback != null) {
                        try {
                            callback.run();
                        } catch (RuntimeException error) {
                            AppLog.w(TAG, "Unable to ramp buffered ranges", error);
                        }
                    }
                }
            } catch (IOException | RuntimeException error) {
                synchronized (this) {
                    if (closed) return;
                }
                setFailure(error instanceof IOException io ? io
                        : new IOException("Unable to buffer parallel range", error));
            } finally {
                closeBufferedSource();
            }
        }

        private void setFailure(IOException failure) {
            CompletableFuture<Void> progress;
            synchronized (this) {
                if (closed || bufferFailure != null) return;
                bufferFailure = failure;
                bufferComplete = true;
                if (networkBatch != null) networkBatch.failure();
                progress = nextProgress;
                notifyAll();
            }
            if (progress != null) progress.completeExceptionally(failure);
        }

        private void closeBufferedSource() {
            DataSource local;
            synchronized (this) {
                if (sourceClosed || source == null) return;
                sourceClosed = true;
                local = source;
            }
            try {
                // OkHttp/Okio response bodies must be closed by the same worker that is reading
                // them. Closing here avoids racing ResponseBody.close() against source.read(),
                // which Okio reports as "Unbalanced enter/exit" during Media3 seek cancellation.
                local.close();
            } catch (IOException | RuntimeException error) {
                AppLog.w(TAG, "Unable to close buffered parallel range", error);
            }
        }

        private int read(byte[] target, int offset, int length) throws IOException {
            if (buffer == null) {
                DataSource local;
                synchronized (this) {
                    local = source;
                }
                return local.read(target, offset, length);
            }
            synchronized (this) {
                while (bufferPosition >= bufferedBytes && bufferFailure == null
                        && !bufferComplete && !closed) {
                    long progressAt = lastProgressNanos > 0L
                            ? lastProgressNanos : taskStartedNanos;
                    long remainingNanos = TimeUnit.MILLISECONDS.toNanos(
                            RANGE_PROGRESS_TIMEOUT_MILLIS)
                            - Math.max(0L, System.nanoTime() - progressAt);
                    if (remainingNanos <= 0L) {
                        throw new RangeStalledException(bufferedBytes == 0);
                    }
                    try {
                        long millis = Math.max(1L,
                                TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                        wait(millis);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        InterruptedIOException failure =
                                new InterruptedIOException("Interrupted while reading parallel range");
                        failure.initCause(error);
                        throw failure;
                    }
                }
                if (bufferPosition < bufferedBytes) {
                    int count = Math.min(length, bufferedBytes - bufferPosition);
                    System.arraycopy(buffer, bufferPosition, target, offset, count);
                    bufferPosition += count;
                    return count;
                }
                if (bufferFailure != null) throw bufferFailure;
                if (closed) throw new InterruptedIOException("Parallel range was closed");
                return C.RESULT_END_OF_INPUT;
            }
        }

        private void close() throws IOException {
            Future<?> task;
            DataSource local;
            CompletableFuture<Void> progress;
            boolean closeDirectly;
            synchronized (this) {
                if (closed) return;
                closed = true;
                task = bufferTask;
                local = source;
                closeDirectly = buffer == null || task == null;
                progress = nextProgress;
                notifyAll();
            }
            if (progress != null) progress.completeExceptionally(
                    new InterruptedIOException("Parallel range was closed"));
            if (task != null) task.cancel(true);
            if (closeDirectly && local != null) {
                synchronized (this) {
                    if (sourceClosed) return;
                    sourceClosed = true;
                }
                local.close();
            }
        }
    }

    private static final class RangeStalledException extends IOException {
        private final boolean firstByte;

        private RangeStalledException(boolean firstByte) {
            super(firstByte ? "Parallel range first byte timed out"
                    : "Parallel range stopped making progress");
            this.firstByte = firstByte;
        }
    }

    record NetworkBatchSample(long bytes, long elapsedNanos,
                              long firstByteNanos, long maxPartNanos,
                              boolean failed) {
        NetworkBatchSample(long bytes, long elapsedNanos, boolean failed) {
            this(bytes, elapsedNanos, 0L, elapsedNanos, failed);
        }
    }

    static final class NetworkBatch {
        private final int expectedParts;
        private final ConcurrentLinkedQueue<NetworkBatchSample> samples;
        private final long startedNanos = System.nanoTime();
        private final AtomicInteger completedParts = new AtomicInteger();
        private final AtomicLong completedBytes = new AtomicLong();
        private final AtomicLong maxFirstByteNanos = new AtomicLong();
        private final AtomicLong maxPartNanos = new AtomicLong();
        private final AtomicBoolean emitted = new AtomicBoolean();

        NetworkBatch(int expectedParts,
                     ConcurrentLinkedQueue<NetworkBatchSample> samples) {
            if (expectedParts <= 0) throw new IllegalArgumentException("No range parts");
            this.expectedParts = expectedParts;
            this.samples = samples;
        }

        void firstProgress(long elapsedNanos) {
            maxFirstByteNanos.accumulateAndGet(Math.max(1L, elapsedNanos), Math::max);
        }

        void success(long bytes) {
            success(bytes, Math.max(1L, System.nanoTime() - startedNanos));
        }

        void success(long bytes, long elapsedNanos) {
            if (emitted.get()) return;
            completedBytes.addAndGet(Math.max(0L, bytes));
            maxPartNanos.accumulateAndGet(Math.max(1L, elapsedNanos), Math::max);
            if (completedParts.incrementAndGet() != expectedParts
                    || !emitted.compareAndSet(false, true)) return;
            samples.add(new NetworkBatchSample(completedBytes.get(),
                    Math.max(1L, System.nanoTime() - startedNanos),
                    maxFirstByteNanos.get(), maxPartNanos.get(), false));
        }

        void failure() {
            if (!emitted.compareAndSet(false, true)) return;
            samples.add(new NetworkBatchSample(completedBytes.get(),
                    Math.max(1L, System.nanoTime() - startedNanos),
                    maxFirstByteNanos.get(), maxPartNanos.get(), true));
        }
    }
}
