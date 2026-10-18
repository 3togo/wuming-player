package com.wuming.tv.core.playback.hls;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Keep this Java caller: Kotlin callers cannot pass a null varargs array directly. */
public class HlsPipelineJavaContractTest {
    @Test
    public void nullableArrayAndJvmVarargsAreBothSupported() throws Exception {
        assertTrue(HlsPipeline.class.getMethod("of", HlsTransform[].class).isVarArgs());
        assertEquals("#EXTM3U", HlsPipeline.of((HlsTransform[]) null)
                .transform("#EXTM3U", null).getContent());
        assertEquals("#EXTM3U", new HlsPipeline(null)
                .transform("#EXTM3U", null).getContent());
        assertEquals("#EXTM3U", HlsPipeline.Companion.of((HlsTransform[]) null)
                .transform("#EXTM3U", null).getContent());
    }

    @Test
    public void factoryFiltersNullElementsAndCopiesTheArray() {
        HlsTransform transform = (content, context) ->
                HlsTransformationResult.unchanged(content + "\nkept.ts");
        HlsTransform[] input = {null, transform, null};
        HlsPipeline pipeline = HlsPipeline.of(input);
        input[1] = null;
        assertEquals("#EXTM3U\nkept.ts", pipeline.transform("#EXTM3U", null).getContent());
        assertEquals("#EXTM3U", HlsPipeline.of((HlsTransform) null)
                .transform("#EXTM3U", null).getContent());
    }

    @Test
    public void listConstructorStillCopiesAndRejectsNullElements() {
        HlsTransform transform = (content, context) -> HlsTransformationResult.unchanged(content + "\nkept.ts");
        ArrayList<HlsTransform> input = new ArrayList<>();
        input.add(transform);
        HlsPipeline pipeline = new HlsPipeline(input);
        input.clear();
        assertEquals("#EXTM3U\nkept.ts", pipeline.transform("#EXTM3U", null).getContent());
        assertThrows(NullPointerException.class,
                () -> new HlsPipeline(Arrays.asList((HlsTransform) null)));
    }
}
