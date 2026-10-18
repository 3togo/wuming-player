package com.wuming.android.tv.server.process;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import fi.iki.elonen.NanoHTTPD;
import static org.junit.Assert.*;

/** Fixture-only native extraction/muxing; no cast renderer or provider is contacted. */
@RunWith(AndroidJUnit4.class)
public class CastRemuxInstrumentedTest {
    @Test(timeout = 60000)
    public void stagedSegmentsRemuxWithVideoAudioAndContinuousSamples() throws Exception {
        byte[][] segments = new byte[3][];
        long[] sizes = new long[3];
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = 0; i < segments.length; i++) {
            try (var stream = InstrumentationRegistry.getInstrumentation().getContext()
                    .getAssets().open("cast-remux/segment-0" + i + ".ts")) {
                segments[i] = stream.readAllBytes();
            }
            sizes[i] = segments[i].length;
            expected.write(segments[i]);
        }
        AtomicInteger authenticated = new AtomicInteger();
        NanoHTTPD server = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession session) {
                if (!"fixture".equals(session.getHeaders().get("x-cast-fixture")))
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "missing header");
                int index = Integer.parseInt(session.getUri().substring(1));
                authenticated.incrementAndGet();
                if (index == 0) try { Thread.sleep(50); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return newFixedLengthResponse(Response.Status.OK, "video/mp2t",
                        new ByteArrayInputStream(segments[index]), segments[index].length);
            }
        };
        File directory = newDirectory();
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            File staged = CastSegmentDownloader.download(directory, sizes, 32L * 1024 * 1024,
                    index -> RelayHttpClient.upstreamCall("http://127.0.0.1:" + server.getListeningPort()
                            + "/" + index, Map.of("X-Cast-Fixture", "fixture"), null, null));
            assertArrayEquals(expected.toByteArray(), Files.readAllBytes(staged.toPath()));
            assertEquals(3, authenticated.get());
            File output = new File(directory, "fixture.mp4");
            assertTrue("Platform remux rejected generated H.264/AAC", remux(staged, output));
            assertTrue(output.length() > 0);
            Map<String, Integer> sourceSamples = samples(staged, false);
            Map<String, Integer> outputSamples = samples(output, true);
            assertEquals(sourceSamples, outputSamples);
            assertEquals(Integer.valueOf(30), outputSamples.get("video/avc"));
            assertTrue(outputSamples.get("audio/mp4a-latm") > 100);
        } finally {
            server.stop();
            deleteOwnedDirectory(directory);
        }
    }

    @Test(timeout = 30000)
    public void malformedTransportStreamFailsRemux() throws Exception {
        File directory = newDirectory();
        try {
            File input = new File(directory, "invalid.ts");
            Files.write(input.toPath(), new byte[188]);
            assertFalse(remux(input, new File(directory, "invalid.mp4")));
        } finally { deleteOwnedDirectory(directory); }
    }

    private static boolean remux(File input, File output) throws Exception {
        return TsToMp4Remuxer.remuxToMp4(input, output);
    }

    private static Map<String, Integer> samples(File file, boolean verifyTimeline) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals(2, extractor.getTrackCount());
            Map<String, Integer> counts = new HashMap<>();
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if ("video/avc".equals(mime)) {
                    assertEquals(160, format.getInteger(MediaFormat.KEY_WIDTH));
                    assertEquals(96, format.getInteger(MediaFormat.KEY_HEIGHT));
                }
                extractor.selectTrack(track);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                int count = 0;
                long first = -1, previous = -1;
                while (extractor.getSampleTrackIndex() >= 0) {
                    buffer.clear();
                    assertTrue(extractor.readSampleData(buffer, 0) > 0);
                    long pts = extractor.getSampleTime();
                    if (first < 0) first = pts;
                    if (verifyTimeline) assertTrue("Non-increasing " + mime + " timestamp", pts > previous);
                    previous = pts;
                    count++;
                    if (!extractor.advance()) break;
                }
                if (verifyTimeline) assertTrue("Collapsed " + mime + " timeline",
                        previous - first > 2_800_000 && previous - first < 3_200_000);
                counts.put(mime, count);
                extractor.unselectTrack(track);
            }
            return counts;
        } finally { extractor.release(); }
    }

    private static File newDirectory() throws Exception {
        return Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "cast-remux-test-").toFile();
    }

    private static void deleteOwnedDirectory(File directory) throws Exception {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(directory.toPath());
    }
}
