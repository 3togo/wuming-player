package com.wuming.tv.core.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteRangesTest {

    @Test
    fun openEndedAndClosedRequestRangesParse() {
        val open = ByteRanges.parseRequest("bytes=1024-")!!
        assertEquals(1024L, open.start)
        assertNull(open.endInclusive)
        val closed = ByteRanges.parseRequest("  Bytes=0-499  ")!!
        assertEquals(0L, closed.start)
        assertEquals(499L, closed.endInclusive)
    }

    @Test
    fun malformedRequestRangesAreRejected() {
        assertNull(ByteRanges.parseRequest(null))
        assertNull(ByteRanges.parseRequest(""))
        assertNull(ByteRanges.parseRequest("bytes=abc-"))
        assertNull(ByteRanges.parseRequest("bytes=500-100"))
        assertNull(ByteRanges.parseRequest("0-499"))
        assertNull(ByteRanges.parseRequest("bytes=-500"))
    }

    @Test
    fun suffixRequestsResolveToACount() {
        assertEquals(500L, ByteRanges.parseSuffixRequest("bytes=-500"))
        assertNull(ByteRanges.parseSuffixRequest("bytes=0-499"))
        assertNull(ByteRanges.parseSuffixRequest("bytes=-"))
    }

    @Test
    fun responseTotalsMayBeUnknown() {
        val known = ByteRanges.parseResponse("bytes 0-1023/4096")!!
        assertEquals(0L, known.start)
        assertEquals(1023L, known.endInclusive)
        assertEquals(4096L, known.total)
        val unknown = ByteRanges.parseResponse("bytes 0-1023/*")!!
        assertNull(unknown.total)
    }

    @Test
    fun malformedResponsesAreRejected() {
        assertNull(ByteRanges.parseResponse(null))
        assertNull(ByteRanges.parseResponse("bytes 0-1023"))
        assertNull(ByteRanges.parseResponse("bytes 500-100/4096"))
        assertNull(ByteRanges.parseResponse("bytes 0-1023/0"))
        assertNull(ByteRanges.parseResponse("bytes 0-1023/abc"))
    }

    @Test
    fun renderedHeaderUsesAsteriskForAnUnknownTotal() {
        assertEquals("bytes 0-1023/4096", ByteRanges.responseHeader(0, 1023, 4096))
        assertEquals("bytes 0-1023/*", ByteRanges.responseHeader(0, 1023, null))
    }
}
