package com.wuming.tv.core.catalog

import com.wuming.tv.core.playback.DramaVideoResolver
import org.junit.Assert.*
import org.junit.Test

class DramaPlaybackCatalogSourceExecutorTest {
    @Test fun recognizedDramaPlaybackDoesNotInvokeTheVendorLoginGate() {
        var providerCalls = 0
        val delegate = object : CatalogSourceExecutor {
            override val source = CatalogSource("fixture", "Fixture")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String): CatalogDetail = error("unused")
            override fun play(flag: String, id: String): CatalogPlayback { providerCalls++; return CatalogPlayback() }
        }
        var nativeCalls = 0
        val resolver = DramaVideoResolver { nativeCalls++; error("fixture native failure") }
        val wrapper = DramaPlaybackCatalogSourceExecutor(delegate, "csp_AiDj", resolver)
        assertThrows(IllegalStateException::class.java) { wrapper.play("AI", "11/22") }
        assertEquals(1, nativeCalls)
        assertEquals(0, providerCalls)
        assertNull(wrapper.prewarm("AI", "11/22"))
        assertEquals(PlaybackPreparationSafety.UNKNOWN, wrapper.playbackPreparationSafety("AI", "11/22"))
        assertEquals(1, nativeCalls)
        assertEquals(0, providerCalls)
        wrapper.play("unrelated", "https://example/video")
        assertEquals(1, providerCalls)
    }
}
