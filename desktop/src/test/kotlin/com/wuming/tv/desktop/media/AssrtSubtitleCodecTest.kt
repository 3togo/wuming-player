package com.wuming.tv.desktop.media

import com.wuming.tv.core.media.subtitle.AssrtSubtitleCodec
import com.wuming.tv.core.media.subtitle.SubtitleCandidate
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Desktop consumes the shared grammar without Android or CatVod dependencies. */
class AssrtSubtitleCodecTest {
    @Test fun resolvesSearchCandidateThroughSharedCodec() {
        val page = AssrtSubtitleCodec.parseSearch("""{"sub":{"subs":[{"id":42,"videoname":"Example","subtype":"srt"}]}}""", 1)
        val resolved = AssrtSubtitleCodec.parseDetail("""{"sub":{"subs":[{"filename":"Example.srt","url":"https://example.test/sub.srt"}]}}""", page.candidates.single())
        assertEquals("42", resolved.id)
        assertEquals("srt", resolved.format)
        assertFalse(resolved.archive)
    }

    @Test fun unsafeDownloadsAreRejectedOnDesktopToo() {
        val candidate = SubtitleCandidate("assrt", "42", "Example", "", "", null, false, 0)
        assertThrows(IOException::class.java) {
            AssrtSubtitleCodec.parseDetail("""{"sub":{"subs":[{"filename":"Example.srt","url":"file:///tmp/sub.srt"}]}}""", candidate)
        }
    }
}
