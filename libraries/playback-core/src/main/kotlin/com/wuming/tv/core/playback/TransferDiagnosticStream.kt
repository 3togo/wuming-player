package com.wuming.tv.core.playback

import java.io.InputStream
import java.util.function.Consumer
import java.util.function.LongSupplier

/** Counts bytes consumed by the downstream reader, not network delivery or decoder progress.
 * Reports on read completion (no watchdog thread); a blocked read is visible when it returns.
 */
class TransferDiagnosticStream @JvmOverloads constructor(
    private val source: InputStream,
    private val expected: Long,
    private val sink: Consumer<String>,
    private val clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000 }
) : InputStream() {
    private val started = clock.asLong
    private var lastReport = started
    private var bytes = 0L
    private var maxReadMs = 0L
    private var finished = false

    private fun report(event: String) {
        val elapsed = (clock.asLong - started).coerceAtLeast(0)
        try {
            sink.accept("event=$event bytes=$bytes expected=$expected elapsedMs=$elapsed " +
                "avgBytesPerSec=${bytes * 1000 / elapsed.coerceAtLeast(1)} maxReadMs=$maxReadMs")
        } catch (_: Exception) { /* Diagnostics cannot affect playback. */ }
        lastReport = clock.asLong
    }

    private inline fun measured(read: () -> Int, count: (Int) -> Int): Int {
        val start = clock.asLong
        try {
            val result = read()
            maxReadMs = maxOf(maxReadMs, clock.asLong - start)
            if (result < 0) finish(if (expected >= 0 && bytes < expected) "short_eof" else "eof")
            else {
                val n = count(result)
                val first = bytes == 0L && n > 0
                bytes += n
                if (first) report("first_bytes")
                if (expected >= 0 && bytes >= expected) finish("complete")
                else if (!finished && clock.asLong - lastReport >= 5000) report("progress")
            }
            return result
        } catch (error: Exception) {
            maxReadMs = maxOf(maxReadMs, clock.asLong - start)
            finish("read_error_${error.javaClass.simpleName}")
            throw error
        }
    }

    private fun finish(event: String) {
        if (!finished) { finished = true; report(event) }
    }

    override fun read(): Int = measured({ source.read() }, { 1 })
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        measured({ source.read(buffer, offset, length) }, { it })

    override fun close() {
        try { source.close() } finally { finish("closed") }
    }
}
