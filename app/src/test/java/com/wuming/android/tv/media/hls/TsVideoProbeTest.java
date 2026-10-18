package com.wuming.android.tv.media.hls;

import static org.junit.Assert.*;

import com.wuming.tv.core.playback.HlsResolutionAdFilter;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assume;
import org.junit.Test;

public class TsVideoProbeTest {
    // Codec parameter sets only, extracted from the investigated stream. No video frames.
    private static final String PROGRAMME = "AAABZ2QAH6zZQFAFuwFqAgICgAAAAwCAAAAZB4wYywAAAAFo74/L";
    private static final String AD_30 = "AAABZ2QAMqzZgHgCJ+XAWoCAgKAAAAMAIAAAB4HjBjNAAAAAAWjpePLIsA==";
    private static final String AD_25 = "AAABZ2QAMqzZgHgCJ+XAWoCAgKAAAAMAIAAABkHjBjNAAAAAAWjpePLIsA==";

    @Test public void readsProgrammeAndBothAdFormatsWithRawUnnormalizedPts() {
        var programme = TsVideoProbe.read(sample(PROGRAMME, 1674.84));
        assertNotNull(programme);
        assertEquals(1280, programme.width());
        assertEquals(720, programme.height());
        assertEquals(1674.84, programme.ptsSeconds(), 0.00002);
        for (String sps : List.of(AD_25, AD_30)) {
            var ad = TsVideoProbe.read(sample(sps, 1.48));
            assertNotNull(ad);
            assertEquals(1920, ad.width());
            assertEquals(1080, ad.height());
            assertEquals(1.48, ad.ptsSeconds(), 0.00002);
        }
    }

    @Test public void rejectsMissingTablesNonAvcTruncatedOrScrambledData() {
        byte[] bytes = sample(PROGRAMME, 20);
        assertNull(TsVideoProbe.read(Arrays.copyOfRange(bytes, 188, bytes.length)));
        bytes[17] = 0x24; // PMT stream_type: HEVC is not handled by this probe.
        assertNull(TsVideoProbe.read(bytes));
        bytes = sample(PROGRAMME, 20);
        bytes[191] |= (byte) 0x80;
        assertNull(TsVideoProbe.read(bytes));
        assertNull(TsVideoProbe.read(new byte[20]));
        assertNull(TsVideoProbe.read(new byte[TsVideoProbe.MAX_BYTES + 1]));
        assertNull(TsVideoProbe.read(null));
    }

    @Test public void joinsSpsAcrossTransportPackets() {
        byte[] pes = pes(PROGRAMME, 123);
        byte[] extended = new byte[pes.length + 155];
        System.arraycopy(pes, 0, extended, 0, 14);
        Arrays.fill(extended, 14, 169, (byte) 0x55);
        System.arraycopy(pes, 14, extended, 169, pes.length - 14);
        byte[] input = concat(pmt(), packet(256, true, Arrays.copyOf(extended, 184)),
                packet(256, false, Arrays.copyOfRange(extended, 184, extended.length)));
        assertEquals(1280, TsVideoProbe.read(input).width());
    }

    @Test public void preservesAll33TimestampBitsAndRejectsMissingMarkers() {
        double seconds = ((1L << 33) - 1) / 90000.0;
        var video = TsVideoProbe.read(sample(PROGRAMME, seconds));
        assertNotNull(video);
        assertEquals(seconds, video.ptsSeconds(), 0.000001);
        for (int marker : new int[]{9, 11, 13}) {
            byte[] bytes = sample(PROGRAMME, seconds);
            bytes[188 + 4 + marker] &= (byte) 0xfe;
            assertNull(TsVideoProbe.read(bytes));
        }
    }

    @Test public void keepsFallbackForIncompleteSpsAndMalformedAdaptation() {
        assertNull(TsVideoProbe.read(sample("AAABZ2QAH6zZQFAFuwFqAgICgAAAAwCAAAAZB4wYyw==", 20)));
        byte[] bytes = sample(PROGRAMME, 20);
        bytes[191] |= 0x20;
        bytes[192] = (byte) 0xff;
        assertNull(TsVideoProbe.read(bytes));
    }

    @Test public void downloadedEpisodeRemovesOnlyBothConfirmedAdsAndProviderRemainder() throws Exception {
        String directory = System.getenv("TV_AD_FIXTURE_DIR");
        Assume.assumeTrue("Optional local investigation fixture", directory != null);
        Path root = Path.of(directory);
        Map<String, Path> files = new HashMap<>();
        try (var paths = Files.list(root.resolve("segments"))) {
            paths.forEach(p -> files.put(p.getFileName().toString().substring(17), p));
        }
        String original = Files.readString(root.resolve("original.m3u8"));
        String provider = Files.readString(root.resolve("provider.m3u8"));
        String base = URI.create(provider.lines().filter(s -> !s.isBlank() && !s.startsWith("#")).findFirst().orElseThrow()).resolve(".").toString();
        HlsResolutionAdFilter.Probe probe = url -> {
            String name = URI.create(url).getPath();
            try (var in = Files.newInputStream(files.get(name.substring(name.lastIndexOf('/') + 1)))) {
                return TsVideoProbe.read(in.readNBytes(TsVideoProbe.MAX_BYTES));
            }
        };
        var a = HlsResolutionAdFilter.filter(base, original, probe, (b, r) -> URI.create(b).resolve(r).toString());
        var b = HlsResolutionAdFilter.filter(base, provider, probe, (u, r) -> URI.create(u).resolve(r).toString());
        assertEquals(2, a.removedBlocks());
        assertEquals(9, a.removedSegments());
        assertEquals(1, b.removedBlocks());
        assertEquals(4, b.removedSegments());
        List<String> aSegments = segmentLines(a.content());
        assertEquals(673, aSegments.size());
        assertEquals(aSegments, segmentLines(b.content()));
        Set<String> ads = Set.of("7a44fc5d99d608a213c0db62c99e9a32.ts", "5c3e146a9438c4f24e10f9c2e5214454.ts",
                "ac2cb7c46b94a36dd459ad670a40aa2f.ts", "5f09e09b35e777959b7809ae1c28c234.ts", "93722d231784fcc9804419ce682b3de7.ts",
                "b1e5bc40ce179edfbea51db7989f0db4.ts", "5c56905b2c36872918f667c83c7ab998.ts",
                "86902a2dd36100263d5aa2d565ed4d7d.ts", "a298651c01cda83347d532aa86a5e49c.ts");
        List<String> expected = segmentLines(original).stream().filter(s -> !ads.contains(s))
                .map(s -> URI.create(base).resolve(s).toString()).toList();
        assertEquals(expected, aSegments);
    }

    private static List<String> segmentLines(String content) {
        return content.lines().filter(s -> !s.isBlank() && !s.startsWith("#")).toList();
    }

    private static byte[] sample(String sps, double pts) { return concat(pmt(), packet(256, true, pes(sps, pts))); }

    private static byte[] pmt() {
        return packet(4096, true, new byte[]{0, 2, (byte) 0xb0, 18, 0, 1, (byte) 0xc1, 0, 0,
                (byte) 0xe1, 0, (byte) 0xf0, 0, 0x1b, (byte) 0xe1, 0, (byte) 0xf0, 0, 0, 0, 0, 0});
    }

    private static byte[] pes(String sps, double seconds) {
        long pts = Math.round(seconds * 90000);
        byte[] header = {0, 0, 1, (byte) 0xe0, 0, 0, (byte) 0x80, (byte) 0x80, 5,
                (byte) (0x21 | ((pts >> 29) & 14)), (byte) (pts >> 22),
                (byte) (((pts >> 14) & 254) | 1), (byte) (pts >> 7), (byte) ((pts << 1) | 1)};
        return concat(header, Base64.getDecoder().decode(sps));
    }

    private static byte[] packet(int pid, boolean start, byte[] payload) {
        byte[] data = new byte[188];
        Arrays.fill(data, (byte) 0xff);
        data[0] = 0x47;
        data[1] = (byte) ((pid >> 8) | (start ? 0x40 : 0));
        data[2] = (byte) pid;
        data[3] = 0x10;
        System.arraycopy(payload, 0, data, 4, payload.length);
        return data;
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.write(array, 0, array.length);
        return out.toByteArray();
    }
}
