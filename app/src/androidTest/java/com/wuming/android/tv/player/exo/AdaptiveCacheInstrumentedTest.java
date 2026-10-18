package com.wuming.android.tv.player.exo;

import static org.junit.Assert.*;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AdaptiveCacheInstrumentedTest {
    @Test public void playlistsRefreshWhileSegmentsAndExplicitDownloadsStayCached() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger segmentRequests = new AtomicInteger();
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                if (session.getUri().equals("/extensionless")) return newFixedLengthResponse(
                        Response.Status.OK, "application/vnd.apple.mpegurl",
                        "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:" + sequence.incrementAndGet() + "\n");
                segmentRequests.incrementAndGet();
                return newFixedLengthResponse("fixed segment bytes");
            }
        };
        File dir = new File(ApplicationProvider.getApplicationContext().getCacheDir(), "adaptive-cache-" + UUID.randomUUID());
        assertTrue(dir.mkdirs());
        android.database.sqlite.SQLiteDatabase index = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(new File(dir, "index.db"), null);
        DatabaseProvider database = new DatabaseProvider() {
            @Override public android.database.sqlite.SQLiteDatabase getReadableDatabase() { return index; }
            @Override public android.database.sqlite.SQLiteDatabase getWritableDatabase() { return index; }
        };
        SimpleCache runtime = null, offline = null;
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        try {
            runtime = new SimpleCache(new File(dir, "runtime"), new NoOpCacheEvictor(), database);
            offline = new SimpleCache(new File(dir, "offline"), new NoOpCacheEvictor(), database);
            DataSource.Factory network = new DefaultHttpDataSource.Factory();
            DataSource.Factory cached = new CacheDataSource.Factory().setCache(runtime).setUpstreamDataSourceFactory(network);
            String manifest = "http://127.0.0.1:" + server.getListeningPort() + "/extensionless";
            String segment = "http://127.0.0.1:" + server.getListeningPort() + "/segment.ts";
            String stale = read(cached.createDataSource(), manifest);
            assertEquals(stale, read(cached.createDataSource(), manifest));
            assertEquals("Old cache masks playlist updates", 1, sequence.get());
            var routing = AdaptiveSourceFactory.hlsDataSources(cached, network);
            String first = read(routing.createDataSource(C.DATA_TYPE_MANIFEST), manifest);
            String next = read(routing.createDataSource(C.DATA_TYPE_MANIFEST), manifest);
            assertNotEquals(stale, first);
            assertNotEquals(first, next);
            assertEquals(3, sequence.get());
            assertEquals(read(routing.createDataSource(C.DATA_TYPE_MEDIA), segment),
                    read(routing.createDataSource(C.DATA_TYPE_MEDIA), segment));
            assertEquals("Video bytes remain cached", 1, segmentRequests.get());
            DataSource.Factory download = new CacheDataSource.Factory().setCache(offline).setUpstreamDataSourceFactory(network);
            String saved = read(download.createDataSource(), manifest);
            DataSource.Factory offlineOnly = new CacheDataSource.Factory().setCache(offline).setCacheWriteDataSinkFactory(null);
            androidx.media3.common.MediaItem subtitled = new androidx.media3.common.MediaItem.Builder()
                    .setUri(manifest).setRequestMetadata(new androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setMediaUri(Uri.parse(manifest)).build())
                    .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    .setSubtitleConfigurations(java.util.List.of(new androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse("http://127.0.0.1/subtitle.vtt")).setMimeType("text/vtt").build())).build();
            assertTrue("Default subtitle merging must survive adaptive routing",
                    new MediaSourceFactory().createMediaSource(subtitled) instanceof androidx.media3.exoplayer.source.MergingMediaSource);
            server.stop();
            var offlineRouting = AdaptiveSourceFactory.hlsDataSources(cached, offlineOnly);
            assertEquals("Explicit download manifest remains available offline", saved,
                    read(offlineRouting.createDataSource(C.DATA_TYPE_MANIFEST), manifest));
        } finally {
            server.stop();
            if (runtime != null) runtime.release();
            if (offline != null) offline.release();
            index.close();
            if (dir.exists()) try (var paths = Files.walk(dir.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String read(DataSource source, String url) throws Exception {
        try {
            source.open(new DataSpec(Uri.parse(url)));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int count; (count = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT;) bytes.write(buffer, 0, count);
            return bytes.toString(StandardCharsets.UTF_8.name());
        } finally { source.close(); }
    }
}
