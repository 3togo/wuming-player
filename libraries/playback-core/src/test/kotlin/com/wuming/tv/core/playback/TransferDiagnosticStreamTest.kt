package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class TransferDiagnosticStreamTest {
    @Test fun countsBytesWithoutChangingContentAndCompletesOnce() {
        val events = mutableListOf<String>()
        val data = ByteArray(1027) { it.toByte() }
        val stream = TransferDiagnosticStream(ByteArrayInputStream(data), data.size.toLong(), { events.add(it) })
        assertArrayEquals(data, stream.readBytes())
        stream.close()
        assertEquals(1, events.count { it.contains("event=complete ") })
        assertTrue(events.last().contains("bytes=1027"))
        assertEquals(1, events.count { it.contains("event=first_bytes ") })
    }

    @Test fun preservesExceptionAndReportsShortEofAndEarlyCloseSeparately() {
        val events = mutableListOf<String>()
        TransferDiagnosticStream(ByteArrayInputStream(byteArrayOf(1)), 2, { events.add(it) }).use { it.readBytes() }
        assertTrue(events.last().contains("event=short_eof "))
        events.clear()
        TransferDiagnosticStream(ByteArrayInputStream(byteArrayOf(1)), 2, { events.add(it) }).close()
        assertTrue(events.single().contains("event=closed bytes=0"))
        val failure = IOException("signed-url-secret")
        val stream = TransferDiagnosticStream(object : InputStream() {
            override fun read(): Int = throw failure
        }, -1, { events.add(it) })
        try { stream.read(); fail() } catch (error: IOException) { assertSame(failure, error) }
        assertTrue(events.last().contains("read_error_IOException"))
        assertFalse(events.joinToString().contains("signed-url-secret"))
    }

    @Test fun reportsReadDelayAndThrottlesProgressWithMonotonicClock() {
        val time = AtomicLong()
        val events = mutableListOf<String>()
        val source = object : InputStream() {
            override fun read(): Int { time.addAndGet(1000); return 7 }
        }
        val stream = TransferDiagnosticStream(source, -1, { events.add(it) }, { time.get() })
        repeat(6) { assertEquals(7, stream.read()) }
        assertEquals(2, events.size)
        assertTrue(events.last().contains("event=progress bytes=6"))
        assertTrue(events.last().contains("maxReadMs=1000"))
    }

    @Test fun failingDiagnosticsCannotBreakTransferOrClose() {
        var closed = false
        val source = object : ByteArrayInputStream(byteArrayOf(42)) {
            override fun close() { closed = true; super.close() }
        }
        TransferDiagnosticStream(source, 1, { throw IllegalStateException() }).use {
            assertEquals(42, it.read())
            assertEquals(-1, it.read())
        }
        assertTrue(closed)
    }
}
