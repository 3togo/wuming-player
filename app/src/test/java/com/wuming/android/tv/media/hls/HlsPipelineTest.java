package com.wuming.android.tv.media.hls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNull;

import com.wuming.tv.core.playback.hls.HlsConfidence;
import com.wuming.tv.core.playback.hls.HlsPipeline;
import com.wuming.tv.core.playback.hls.HlsTransform;
import com.wuming.tv.core.playback.hls.HlsTransformContext;
import com.wuming.tv.core.playback.hls.HlsTransformationResult;
import com.wuming.tv.core.playback.hls.HighestQualityVariantTransform;
import com.wuming.tv.core.playback.hls.UriRewriteTransform;

import org.junit.Test;

import java.util.Map;

public class HlsPipelineTest {

    @Test
    public void nullableCollectionsArraysContentAndContextRetainJavaContract() throws Exception {
        String playlist = "#EXTM3U\nsegment.ts";
        for (HlsPipeline pipeline : java.util.List.of(new HlsPipeline(null),
                HlsPipeline.of(), HlsPipeline.of((HlsTransform[]) null))) {
            assertEquals(playlist, pipeline.transform(playlist, null).getContent());
            assertEquals("", pipeline.transform(null, null).getContent());
            assertEquals(HlsConfidence.NONE, pipeline.transform(null, null).getConfidence());
    }

        assertTrue(HlsPipeline.class.getMethod("of", HlsTransform[].class).isVarArgs());
        HlsPipeline pipeline = HlsPipeline.of((content, context) -> {
            assertNull(context);
            return HlsTransformationResult.unchanged(content);
        });
        assertEquals(playlist, pipeline.transform(playlist, null).getContent());
    }

    @Test
    public void copiesInputsAndRejectsNullElements() {
        HlsTransform transform = (content, context) -> HlsTransformationResult.unchanged(content + "\nchanged.ts");
        java.util.List<HlsTransform> transforms = new java.util.ArrayList<>();
        transforms.add(transform);
        HlsPipeline fromList = new HlsPipeline(transforms);
        transforms.clear();
        HlsTransform[] array = {transform};
        HlsPipeline fromArray = HlsPipeline.of(array);
        array[0] = null;
        assertEquals("#EXTM3U\nchanged.ts", fromList.transform("#EXTM3U", null).getContent());
        assertEquals("#EXTM3U\nchanged.ts", fromArray.transform("#EXTM3U", null).getContent());
        assertThrows(NullPointerException.class, () -> new HlsPipeline(java.util.Arrays.asList((HlsTransform) null)));
        HlsPipeline filtered = HlsPipeline.of((HlsTransform) null);
        assertEquals("#EXTM3U", filtered.transform("#EXTM3U", null).getContent());
    }

    @Test
    public void nullAndInvalidOutputsRetainLastValidContentAndContinue() {
        HlsPipeline pipeline = HlsPipeline.of(
                (content, context) -> HlsTransformationResult.unchanged(content + "\nfirst.ts"),
                (content, context) -> null,
                (content, context) -> HlsTransformationResult.unchanged("invalid"),
                (content, context) -> HlsTransformationResult.unchanged(content + "\nlast.ts"));
        HlsTransformationResult result = pipeline.transform("#EXTM3U", null);
        assertEquals("#EXTM3U\nfirst.ts\nlast.ts", result.getContent());
        assertEquals(HlsConfidence.LOW, result.getConfidence());
        assertEquals(2, result.getWarnings().size());
        assertTrue(result.getWarnings().stream().allMatch(w -> w.endsWith(
                " returned an invalid playlist; previous content retained")));
        assertThrows(UnsupportedOperationException.class, () -> result.getWarnings().add("changed"));
    }

    @Test
    public void errorsAreNotSwallowedAsRecoverableTransformFailures() {
        HlsPipeline pipeline = HlsPipeline.of((content, context) -> { throw new AssertionError("fatal"); });
        assertThrows(AssertionError.class, () -> pipeline.transform("#EXTM3U", null));
    }

    @Test
    public void composesVariantSelectionAndUriRewriting() {
        String playlist = "#EXTM3U\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nhigh.m3u8";
        HlsPipeline pipeline = HlsPipeline.of(
                new HighestQualityVariantTransform(), new UriRewriteTransform());

        HlsTransformationResult result = pipeline.transform(playlist,
                new HlsTransformContext("https://media.example/path/master.m3u8?token=secret",
                        value -> "relay:" + value, Map.of()));

        assertTrue(result.getChanged());
        assertFalse(result.getContent().contains("low.m3u8"));
        assertTrue(result.getContent().contains("relay:https://media.example/path/high.m3u8"));
        assertTrue(result.getContent().contains("URI=\"relay:https://media.example/key.bin\""));
        assertEquals(HlsConfidence.HIGH, result.getConfidence());
    }

    @Test
    public void retainsLastValidPlaylistWhenTransformFails() {
        String playlist = "#EXTM3U\n#EXTINF:5,\nsegment.ts";
        HlsPipeline pipeline = HlsPipeline.of(
                (content, context) -> { throw new IllegalStateException("broken"); },
                new UriRewriteTransform());

        HlsTransformationResult result = pipeline.transform(playlist,
                HlsTransformContext.of("https://example.com/live/index.m3u8"));

        assertTrue(result.getContent().contains("https://example.com/live/segment.ts"));
        assertFalse(result.getWarnings().isEmpty());
        assertEquals(HlsConfidence.LOW, result.getConfidence());
    }

    @Test
    public void rejectsNonPlaylistInputWithoutRunningTransforms() {
        HlsTransformationResult result = HlsPipeline.of(new UriRewriteTransform())
                .transform("not hls", HlsTransformContext.of("https://example.com/a.m3u8"));

        assertEquals(HlsConfidence.NONE, result.getConfidence());
        assertFalse(result.getChanged());
    }
}
