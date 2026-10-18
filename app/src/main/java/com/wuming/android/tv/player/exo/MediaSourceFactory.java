package com.wuming.android.tv.player.exo;

import com.wuming.tv.core.playback.PlaybackTransportPlan;
import com.wuming.tv.core.playback.OpenDrivePlaybackHints;

import static androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.wuming.android.tv.App;
import com.wuming.android.tv.BuildConfig;
import com.wuming.android.tv.media.OpenDriveUriResolver;
import com.wuming.android.tv.player.offline.OfflinePlaybackStore;
import com.wuming.android.tv.utils.VideoNetworkPolicy;
import com.wuming.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.net.URI;
import java.util.Map;

import okhttp3.OkHttpClient;

public class MediaSourceFactory implements MediaSource.Factory {

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;
    private final PlaybackTransferTrace transferTrace;
    private final PlaybackHealthMonitor health;
    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;
    private ExtractorsFactory extractorsFactory;
    private DrmSessionManagerProvider drmSessionManagerProvider;
    private LoadErrorHandlingPolicy loadErrorHandlingPolicy;
    private static SimpleCache cache;

    public MediaSourceFactory() {
        this(new PlaybackHealthMonitor());
    }

    MediaSourceFactory(PlaybackHealthMonitor health) {
        this.health = health;
        transferTrace = new PlaybackTransferTrace();
        defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), getExtractorsFactory());
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        this.drmSessionManagerProvider = drmSessionManagerProvider;
        defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider);
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        this.loadErrorHandlingPolicy = loadErrorHandlingPolicy;
        defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        String url = mediaItem.requestMetadata.mediaUri != null ? mediaItem.requestMetadata.mediaUri.toString() : "";
        String mimeType = mediaItem.localConfiguration == null
                ? null : mediaItem.localConfiguration.mimeType;
        PlaybackTransportPlan plan = PlaybackTransportPlan.parse(
                url, mimeType, ExoUtil.extractHeaders(mediaItem));
        if (plan.kind() == PlaybackTransportPlan.Kind.HLS
                && !MimeTypes.APPLICATION_M3U8.equals(mimeType)) {
            // Provider locators can be resolved after process restart without passing through
            // OpenDriveSpider's JSON format field. The internal capability is authoritative and
            // prevents a signed extensionless playlist from being treated as progressive media.
            mediaItem = mediaItem.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build();
        }
        if (!plan.cacheKey().isEmpty()) {
            mediaItem = mediaItem.buildUpon().setCustomCacheKey(plan.cacheKey()).build();
        }
        if (url.contains("***") && url.contains("|||")) {
            return createConcatenatingMediaSource(mediaItem, url, plan);
        }

        DataSource.Factory source = buildDataSourceFactory(url, plan);
        // A live manifest is mutable. It must never read or write the runtime segment cache.
        // Explicit offline downloads retain their separate read-only manifest path.
        DataSource.Factory manifestSource = traced(source);
        if (plan.cachePolicy() == PlaybackTransportPlan.CachePolicy.WRITE_THROUGH) {
            manifestSource = OfflinePlaybackStore.readOnly(manifestSource);
        }
        boolean genericParallel = shouldUseGenericParallel(url, plan);
        if (plan.parallelRanges() || genericParallel) {
            // The parallel source emits one aggregate transfer trace. Attaching the generic
            // listener below it would emit start/end records for every sub-range and distort the
            // throughput being measured.
            String profile = genericParallel
                    ? OpenDrivePlaybackHints.PROFILE_GENERIC_HTTP : plan.parallelProfile();
            source = OpenDrivePlaybackHints.PROFILE_ALIPAN.equals(profile) ? traced(source)
                    : new ParallelRangeDataSource.Factory(source, plan.contentLength(), profile, health);
            android.util.Log.d("MediaSourceFactory", "Creating parallel range source profile="
                    + profile + ",length=" + plan.contentLength());
            if (genericParallel
                    && plan.cachePolicy() == PlaybackTransportPlan.CachePolicy.WRITE_THROUGH) {
                if (hasLiveCacheHeadroom()) {
                    source = getWriteThroughCacheDataSource(source);
                } else {
                    android.util.Log.w("MediaSourceFactory",
                            "Skipping live cache writes because storage is low");
                }
                source = OfflinePlaybackStore.readOnly(source);
            }
        } else {
            source = traced(source);
            if (plan.cachePolicy() == PlaybackTransportPlan.CachePolicy.WRITE_THROUGH) {
                if (hasLiveCacheHeadroom()) {
                    source = getWriteThroughCacheDataSource(source);
                } else {
                    android.util.Log.w("MediaSourceFactory",
                            "Skipping live cache writes because storage is low");
                }
                // Explicit downloads live in a non-evicting cache. Read it as the outer layer so
                // completed content plays without network while normal streaming writes only to
                // the bounded live LRU cache.
                source = OfflinePlaybackStore.readOnly(source);
            }
        }
        android.util.Log.d("MediaSourceFactory", "Creating " + plan.kind()
                + " media source, cache=" + plan.cachePolicy()
                + ",refresh=" + plan.refreshable());
        if (url.contains("[") && url.contains("]")) {
            android.util.Log.d("MediaSourceFactory", "IPv6 media URL passed to ExoPlayer");
        }
        // DefaultMediaSourceFactory owns sidecar-subtitle merging and clipping. Preserve those
        // wrappers; these less common adaptive items stream without the runtime segment cache.
        if (plan.adaptive() && (!mediaItem.localConfiguration.subtitleConfigurations.isEmpty()
                || !mediaItem.clippingConfiguration.equals(MediaItem.ClippingConfiguration.UNSET))) {
            DataSource.Factory compatible = plan.kind() == PlaybackTransportPlan.Kind.HLS
                    ? new CapabilityManifestDataSource.Factory(manifestSource, false) : manifestSource;
            return configuredFactory(compatible).createMediaSource(mediaItem);
        }
        MediaSource.Factory adaptive = AdaptiveSourceFactory.create(plan.kind(), source, manifestSource);
        return (adaptive == null ? configuredFactory(source) : configure(adaptive)).createMediaSource(mediaItem);
    }

    static DataSource.Factory buildDataSourceFactory(
            String url, PlaybackTransportPlan plan) {
        OkHttpClient client = plan.explicitCookie() || isBaiduPreview(url)
                || isExplicitCookieUcMedia(url, plan.networkHeaders())
                ? explicitCookiePlayerClient() : OkHttp.player();
        if (plan.parallelRanges() || shouldUseGenericParallel(url, plan)) {
            client = parallelRangeClient(client);
        }
        client = restricted(client);
        if (plan.parallelRanges() && OpenDrivePlaybackHints.PROFILE_ALIPAN.equals(plan.parallelProfile())) {
            return new AliAdaptiveRangeDataSource.Factory(client, plan.networkHeaders(), plan.contentLength());
        }
        OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(client);
        if (!plan.refreshable()) http.setDefaultRequestProperties(plan.networkHeaders());
        DataSource.Factory source = new DefaultDataSource.Factory(App.get(), http);
        if (plan.refreshable()) {
            source = new RefreshingDataSource.Factory(source, url, plan.networkHeaders(),
                    plan.expiresAtEpochMs(), plan.refreshUri(), OpenDriveResolverHolder.INSTANCE);
        }
        return source;
    }

    static OkHttpClient parallelRangeClient(OkHttpClient client) {
        // Parallel windows need independent connections. On the observed CDN, HTTP/2
        // multiplexing starves the leading range even while later ranges are buffered;
        // opening a replacement on that same connection does not recover throughput.
        return client.newBuilder().protocols(java.util.List.of(okhttp3.Protocol.HTTP_1_1)).build();
    }

    private static final class OpenDriveResolverHolder {
        private static final OpenDriveUriResolver INSTANCE = OpenDriveUriResolver.createDefault();
    }

    static boolean isBaiduPreview(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "pan.baidu.com".equalsIgnoreCase(uri.getHost())
                    && "/share/streaming".equals(uri.getPath());
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isExplicitCookieUcMedia(String value, Map<String, String> headers) {
        if (headers == null || !headers.containsKey("Cookie")) return false;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && ("drive.uc.cn".equalsIgnoreCase(host)
                    || host.toLowerCase(java.util.Locale.ROOT).endsWith(".drive.uc.cn"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean shouldUseGenericParallel(String value, PlaybackTransportPlan plan) {
        if (plan == null || plan.parallelRanges()
                || plan.kind() != PlaybackTransportPlan.Kind.PROGRESSIVE) return false;
        try {
            URI uri = URI.create(value == null ? "" : value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!UrlUtil.isHttpScheme(scheme)) {
                return false;
            }
            return host != null && !isLoopbackHost(host);
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(java.util.Locale.ROOT);
        if (value.length() > 2 && value.charAt(0) == '['
                && value.charAt(value.length() - 1) == ']') {
            value = value.substring(1, value.length() - 1);
        }
        return value.equals("localhost") || value.endsWith(".localhost")
                || value.startsWith("127.") || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1");
    }

    private static OkHttpClient explicitCookiePlayerClient() {
        return OkHttp.explicitCookiePlayer();
    }

    private static OkHttpClient restricted(OkHttpClient client) {
        return client.newBuilder().addInterceptor(chain -> {
            VideoNetworkPolicy.requireAllowed();
            return chain.proceed(chain.request());
        }).build();
    }

    private MediaSource createConcatenatingMediaSource(
            MediaItem mediaItem, String url, PlaybackTransportPlan plan) {
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
        for (String split : url.split("\\*\\*\\*")) {
            try {
                String[] info = split.split("\\|\\|\\|");
                if (info.length >= 2) {
                    // Concatenated resources share normal HTTP headers, but each entry owns its
                    // URI. A stable OpenDrive refresh handle cannot safely rewrite all entries to
                    // one target, so this compatibility path intentionally stays non-refreshing.
                    OkHttpClient client = plan.explicitCookie()
                            ? explicitCookiePlayerClient() : OkHttp.player();
                    OkHttpDataSource.Factory http = new OkHttpDataSource.Factory(restricted(client));
                    http.setDefaultRequestProperties(plan.networkHeaders());
                    DataSource.Factory source = traced(
                            new DefaultDataSource.Factory(App.get(), http));
                    builder.add(configuredFactory(source).createMediaSource(mediaItem.buildUpon()
                                    .setUri(Uri.parse(info[0])).build()),
                            Long.parseLong(info[1]));
                }
            } catch (NumberFormatException e) {
                // Skip malformed entries
            }
        }
        return builder.build();
    }

    private ExtractorsFactory getExtractorsFactory() {
        if (extractorsFactory == null) extractorsFactory = new DefaultExtractorsFactory().setTsExtractorFlags(FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS).setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
        return extractorsFactory;
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) dataSourceFactory = traced(
                new DefaultDataSource.Factory(App.get(), getHttpDataSourceFactory()));
        return dataSourceFactory;
    }

    private DataSource.Factory traced(DataSource.Factory factory) {
        if (!BuildConfig.DEBUG) return factory;
        return () -> {
            DataSource source = factory.createDataSource();
            source.addTransferListener(transferTrace);
            return source;
        };
    }

    private MediaSource.Factory configuredFactory(DataSource.Factory source) {
        return configure(new DefaultMediaSourceFactory(source, getExtractorsFactory()));
    }

    private MediaSource.Factory configure(MediaSource.Factory factory) {
        if (drmSessionManagerProvider != null) {
            factory.setDrmSessionManagerProvider(drmSessionManagerProvider);
        }
        if (loadErrorHandlingPolicy != null) {
            factory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
        }
        return factory;
    }

    private DataSource.Factory getWriteThroughCacheDataSource(
            DataSource.Factory upstreamFactory) {
        // SimpleCache scans its index on first use. Keep that I/O on Media3's loader thread rather
        // than the UI thread where the MediaSource is constructed.
        return () -> {
            try {
                return new CacheDataSource.Factory()
                        .setCache(getCache())
                        .setUpstreamDataSourceFactory(upstreamFactory)
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .createDataSource();
            } catch (RuntimeException error) {
                // IGNORE_CACHE_ON_ERROR handles read/write errors only after construction.
                // A locked or unavailable cache directory must not prevent network playback.
                android.util.Log.w("MediaSourceFactory", "Streaming without disk cache", error);
                return upstreamFactory.createDataSource();
            }
        };
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) httpDataSourceFactory = new OkHttpDataSource.Factory(OkHttp.player());
        return httpDataSourceFactory;
    }

    private static long getMaxCacheBytes() {
        // Use 10% of space above a 256 MiB reserve, min 32 MiB, max 2 GiB. The caller separately
        // disables writes when total headroom is too low.
        File cacheDir = Path.exo();
        File checkDir = cacheDir.exists() ? cacheDir : cacheDir.getParentFile();
        long availableBytes = checkDir != null ? checkDir.getUsableSpace() : 500L * 1024 * 1024;
        long writableBytes = Math.max(0L, availableBytes - 256L * 1024 * 1024);
        long cacheSize = writableBytes / 10;
        cacheSize = Math.max(cacheSize, 32L * 1024 * 1024);
        cacheSize = Math.min(cacheSize, 2L * 1024 * 1024 * 1024); // at most 2GB
        return cacheSize;
    }

    private static boolean hasLiveCacheHeadroom() {
        File cacheDir = Path.exo();
        File checkDir = cacheDir.exists() ? cacheDir : cacheDir.getParentFile();
        return checkDir != null && checkDir.getUsableSpace() > 384L * 1024 * 1024;
    }

    private static synchronized SimpleCache getCache() {
        if (cache == null) cache = new SimpleCache(Path.exo(), new LeastRecentlyUsedCacheEvictor(getMaxCacheBytes()), new StandaloneDatabaseProvider(App.get()));
        return cache;
    }
}
