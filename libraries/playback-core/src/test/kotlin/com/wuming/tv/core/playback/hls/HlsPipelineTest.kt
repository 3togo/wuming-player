package com.wuming.tv.core.playback.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPipelineTest {

    @Test
    fun `nullable collections arrays content and context retain java contract`() {
        val playlist = "#EXTM3U\nsegment.ts"
        for (pipeline in listOf(HlsPipeline(null), HlsPipeline.of(), HlsPipeline.of())) {
            assertEquals(playlist, pipeline.transform(playlist, null).content)
            assertEquals("", pipeline.transform(null, null).content)
            assertEquals(HlsConfidence.NONE, pipeline.transform(null, null).confidence)
        }
        val pipeline = HlsPipeline.of(HlsTransform { content, _ ->
            HlsTransformationResult.unchanged(content!!)
        })
        assertEquals(playlist, pipeline.transform(playlist, null).content)
    }

    @Test
    fun `copies inputs and rejects null elements`() {
        val transform = HlsTransform { content, _ -> HlsTransformationResult.unchanged(content + "\nchanged.ts") }
        val transforms = mutableListOf<HlsTransform>()
        transforms.add(transform)
        val fromList = HlsPipeline(transforms)
        transforms.clear()
        val fromArray = HlsPipeline.of(transform)
        assertEquals("#EXTM3U\nchanged.ts", fromList.transform("#EXTM3U", null).content)
        assertEquals("#EXTM3U\nchanged.ts", fromArray.transform("#EXTM3U", null).content)
    }

    @Test
    fun `null and invalid outputs retain last valid content and continue`() {
        val pipeline = HlsPipeline.of(
            HlsTransform { content, _ -> HlsTransformationResult.unchanged(content + "\nfirst.ts") },
            HlsTransform { _, _ -> HlsTransformationResult("", emptyList(), emptyList(), HlsConfidence.NONE) },
            HlsTransform { _, _ -> HlsTransformationResult.unchanged("invalid") },
            HlsTransform { content, _ -> HlsTransformationResult.unchanged(content + "\nlast.ts") },
        )
        val result = pipeline.transform("#EXTM3U", null)
        assertEquals("#EXTM3U\nfirst.ts\nlast.ts", result.content)
        assertEquals(HlsConfidence.LOW, result.confidence)
        assertEquals(2, result.warnings.size)
        assertTrue(result.warnings.all { it.endsWith(" returned an invalid playlist; previous content retained") })
    }

    @Test
    fun `composes variant selection and uri rewriting`() {
        val playlist = "#EXTM3U\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"../key.bin\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080\nhigh.m3u8"
        val pipeline = HlsPipeline.of(
            HighestQualityVariantTransform(),
            UriRewriteTransform(),
        )
        val result = pipeline.transform(
            playlist,
            HlsTransformContext("https://media.example/path/master.m3u8?token=secret", { "relay:$it" }, emptyMap()),
        )
        assertTrue(result.changed)
        assertFalse(result.content.contains("low.m3u8"))
        assertTrue(result.content.contains("relay:https://media.example/path/high.m3u8"))
        assertTrue(result.content.contains("URI=\"relay:https://media.example/key.bin\""))
        assertEquals(HlsConfidence.HIGH, result.confidence)
    }

    @Test
    fun `rejects non playlist input without running transforms`() {
        val result = HlsPipeline.of(UriRewriteTransform())
            .transform("not hls", HlsTransformContext.of("https://example.com/a.m3u8"))
        assertEquals(HlsConfidence.NONE, result.confidence)
        assertFalse(result.changed)
    }

    @Test
    fun `of filters null transforms from vararg`() {
        val transform = HlsTransform { content, _ -> HlsTransformationResult.unchanged(content + "\nkept.ts") }
        val pipeline = HlsPipeline.of(transform, null, null)
        val result = pipeline.transform("#EXTM3U", null)
        assertEquals("#EXTM3U\nkept.ts", result.content)
    }

    @Test
    fun `of with all null transforms behaves like empty pipeline`() {
        val pipeline = HlsPipeline.of(null, null, null)
        val playlist = "#EXTM3U\nsegment.ts"
        assertEquals(playlist, pipeline.transform(playlist, null).content)
    }

    @Test
    fun `of with no args creates empty pipeline`() {
        val pipeline = HlsPipeline.of()
        val playlist = "#EXTM3U\nsegment.ts"
        assertEquals(playlist, pipeline.transform(playlist, null).content)
        assertEquals("", pipeline.transform(null, null).content)
    }

    @Test
    fun `pipeline constructed from list is independent of source list mutations`() {
        val transform = HlsTransform { content, _ -> HlsTransformationResult.unchanged(content + "\nx.ts") }
        val transforms = mutableListOf(transform)
        val pipeline = HlsPipeline(transforms)
        transforms.clear()
        transforms.add(HlsTransform { _, _ -> HlsTransformationResult.unchanged("unexpected") })
        assertEquals("#EXTM3U\nx.ts", pipeline.transform("#EXTM3U", null).content)
    }
}
