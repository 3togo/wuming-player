package com.wuming.tv.core.media.subtitle

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AssrtSubtitleCodecTest {
    private val source = SubtitleCandidate("assrt", "42", "Example", "English", "", null, false, 55)

    @Test fun searchSkipsInvalidEntriesAndKeepsPageAndScorePolicy() {
        val json = """{"status":0,"sub":{"subs":[null,7,{},
            {"id":42,"videoname":"Example","subtype":"Subrip","vote_score":999,"lang":{"desc":"English"}},
            {"id":"43","native_name":"Other","subtype":"SSA","vote_score":-9}]}}"""
        val page = AssrtSubtitleCodec.parseSearch(json, 3)
        assertEquals(3, page.page)
        assertEquals(3, page.totalPages)
        assertEquals(listOf("42", "43"), page.candidates.map { it.id })
        assertEquals(listOf(100, 0), page.candidates.map { it.matchScore })
        assertEquals(listOf("srt", "ssa"), page.candidates.map { it.format })
        assertNull(page.candidates[0].downloadUri)
    }

    @Test fun fullPageSuggestsOneMorePage() {
        val entries = (1..15).joinToString(",") { """{"id":$it}""" }
        assertEquals(5, AssrtSubtitleCodec.parseSearch("""{"sub":{"subs":[$entries]}}""", 4).totalPages)
    }

    @Test fun directFilesWinOverArchivesAndPreserveSignedUri() {
        val result = AssrtSubtitleCodec.parseDetail(detail(""""filename":"archive.zip","url":"https://example.test/archive.zip",
            "filelist":[{"f":"ignore.txt","url":"https://example.test/txt"},
            {"f":"Example.SRT","url":"https://example.test/sub.srt?sign=fixture"}]"""), source)
        assertEquals("srt", result.format)
        assertFalse(result.archive)
        assertEquals("sign=fixture", result.downloadUri!!.query)
        assertEquals(source.language, result.language)
        assertEquals(source.matchScore, result.matchScore)
    }

    @Test fun fallsBackToDirectFileThenZip() {
        val direct = AssrtSubtitleCodec.parseDetail(detail(""""filename":"Example.vtt","url":"HTTP://example.test/sub.vtt""""), source)
        assertEquals("vtt", direct.format)
        assertFalse(direct.archive)
        val archive = AssrtSubtitleCodec.parseDetail(detail(""""filename":"Example.ZIP","url":"https://example.test/sub.zip""""), source)
        assertTrue(archive.archive)
        assertEquals("zip", archive.format)
    }

    @Test fun rejectsUnsafeOrMalformedDownloadUris() {
        for (url in listOf("file:///tmp/sub.srt", "javascript:alert(1)", "https://bad host/sub.srt")) {
            assertThrows(IOException::class.java) {
                AssrtSubtitleCodec.parseDetail(detail(""""filename":"Example.srt","url":"$url""""), source)
            }
        }
    }

    @Test fun rejectsApiErrorsMalformedJsonAndMissingFiles() {
        for (json in listOf("[]", "{", "{\"status\":403}")) {
            assertThrows(IOException::class.java) { AssrtSubtitleCodec.parseSearch(json, 1) }
        }
        for (json in listOf("{}", detail(""""filename":"Example.rar","url":"https://example.test/sub.rar""""))) {
            assertThrows(IOException::class.java) { AssrtSubtitleCodec.parseDetail(json, source) }
        }
    }

    private fun detail(fields: String): String = """{"status":0,"sub":{"subs":[{$fields}]}}"""
}
