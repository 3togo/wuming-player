package com.wuming.tv.core.playback

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.IntFunction

/** Single-reader ordered stream. close() may concurrently cancel blocked opens and reads. */
class OrderedPrefetchStream @JvmOverloads constructor(
    private val count: Int,
    private val requests: IntFunction<Request>,
    private val budget: Budget = SHARED_BUDGET
) : InputStream() {
    interface Request {
        /** -1 for unknown length; otherwise premature EOF is a transfer failure. */
        val length: Long get() = -1
        @Throws(IOException::class) fun open(): InputStream
        /** Must also cancel an open() that has not returned yet. */
        fun cancel()
    }

    class Budget(val streams: Int) {
        init { require(streams >= 0) }
        internal val permits = Semaphore(streams)
    }

    private data class Block(val bytes: ByteArray? = null, val size: Int = 0, val error: IOException? = null)
    private class Slot(val request: Request) {
        val queue = ArrayBlockingQueue<Block>(QUEUED_BLOCKS)
    }

    private val lifecycle = Any()
    private val slots = ArrayDeque<Slot>()
    @Volatile private var closed = false
    private var started = false
    private var next = 0
    private var executor: ThreadPoolExecutor? = null
    private var buffers: ArrayBlockingQueue<ByteArray>? = null
    private var block: Block? = null
    private var cursor = 0
    private var serialRequest: Request? = null
    private var serialInput: InputStream? = null
    private var serialRemaining = -1L
    private val single = ByteArray(1)

    init { require(count >= 0) }

    private fun start() = synchronized(lifecycle) {
        if (started || closed) return@synchronized
        started = true
        if (count == 0 || !budget.permits.tryAcquire()) return@synchronized
        val pool = ArrayBlockingQueue<ByteArray>(BUFFER_COUNT)
        repeat(BUFFER_COUNT) { pool.add(ByteArray(BLOCK_BYTES)) }
        buffers = pool
        executor = object : ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(WORKERS), { work -> Thread(work, "media-segment-prefetch").apply { isDaemon = true } }) {
            // A cancelled upstream that ignores interruption retains its permit until it exits.
            override fun terminated() { budget.permits.release() }
        }
        schedule()
    }

    private fun schedule() = synchronized(lifecycle) {
        while (!closed && slots.size < WORKERS && next < count) {
            val slot = Slot(requests.apply(next++))
            require(slot.request.length >= -1)
            slots.addLast(slot)
            executor!!.execute { produce(slot) }
        }
    }

    private fun produce(slot: Slot) {
        try {
            checkOpen()
            slot.request.open().use { input ->
                var remaining = slot.request.length
                while (remaining != 0L) {
                    checkOpen()
                    val bytes = awaitBuffer()
                    var owned = true
                    try {
                        val limit = if (remaining < 0) bytes.size else minOf(bytes.size.toLong(), remaining).toInt()
                        val size = input.read(bytes, 0, limit)
                        if (size < 0) {
                            if (remaining > 0) throw EOFException("Segment ended before its declared length")
                            break
                        }
                        if (size == 0) continue
                        publish(slot, Block(bytes, size))
                        owned = false
                        if (remaining > 0) remaining -= size
                    } finally { if (owned) recycle(bytes) }
                }
            }
            publish(slot, Block())
        } catch (failure: Exception) {
            if (!closed) {
                val interrupted = Thread.interrupted()
                try { publish(slot, Block(error = asIo(failure))) } catch (_: IOException) { }
                finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        }
    }

    private fun awaitBuffer(): ByteArray {
        while (true) {
            checkOpen()
            try { buffers!!.poll(100, TimeUnit.MILLISECONDS)?.let { return it } }
            catch (failure: InterruptedException) { throw interrupted(failure) }
        }
    }

    private fun publish(slot: Slot, value: Block) {
        while (true) {
            checkOpen()
            try { if (slot.queue.offer(value, 100, TimeUnit.MILLISECONDS)) return }
            catch (failure: InterruptedException) { throw interrupted(failure) }
        }
    }

    private fun recycle(bytes: ByteArray) { if (!closed) buffers?.offer(bytes) }

    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > target.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (closed) return -1
        try {
            start()
            if (executor == null) return readSerial(target, offset, length)
            while (true) {
                checkOpen()
                val current = block
                if (current != null && cursor < current.size) {
                    val size = minOf(length, current.size - cursor)
                    current.bytes!!.copyInto(target, offset, cursor, cursor + size)
                    cursor += size
                    if (cursor == current.size) { recycle(current.bytes); block = null }
                    return size
                }
                val slot = synchronized(lifecycle) { slots.peekFirst() }
                if (slot == null) { close(); return -1 }
                val item = try { slot.queue.poll(100, TimeUnit.MILLISECONDS) }
                    catch (failure: InterruptedException) { throw interrupted(failure) }
                if (item == null) continue
                item.error?.let { throw it }
                if (item.bytes == null) {
                    synchronized(lifecycle) { slots.removeFirst() }
                    schedule()
                } else { block = item; cursor = 0 }
            }
        } catch (failure: Exception) {
            close()
            throw asIo(failure)
        }
    }

    /** Saturation falls back to one caller-owned request, not an unbounded worker queue. */
    private fun readSerial(target: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            checkOpen()
            if (serialInput == null) {
                val request = synchronized(lifecycle) {
                    checkOpen()
                    if (next == count) null else requests.apply(next++).also { serialRequest = it }
                }
                if (request == null) { close(); return -1 }
                require(request.length >= -1)
                val input = request.open()
                synchronized(lifecycle) {
                    if (closed) { input.close(); throw IOException("Stream closed") }
                    serialInput = input
                    serialRemaining = request.length
                }
            }
            val input = serialInput ?: throw IOException("Stream closed")
            val limit = if (serialRemaining < 0) length else minOf(length.toLong(), serialRemaining).toInt()
            val size = if (limit == 0) -1 else input.read(target, offset, limit)
            if (size < 0) {
                if (serialRemaining > 0) throw EOFException("Segment ended before its declared length")
                input.close()
                synchronized(lifecycle) { serialInput = null; serialRequest = null }
            } else if (size > 0) {
                if (serialRemaining > 0) serialRemaining -= size
                return size
            }
        }
    }

    override fun close() {
        val pending: List<Request>
        val input: InputStream?
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            pending = slots.map { it.request } + listOfNotNull(serialRequest)
            slots.forEach { it.queue.clear() }
            slots.clear()
            input = serialInput
            serialInput = null
            serialRequest = null
            block = null
            buffers?.clear()
        }
        // No network operation or cancellation occurs under the lifecycle monitor.
        pending.forEach { try { it.cancel() } catch (_: Exception) { } }
        try { input?.close() } catch (_: IOException) { }
        executor?.shutdownNow()
    }

    private fun checkOpen() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Segment transfer interrupted")
        if (closed) throw IOException("Stream closed")
    }

    private fun interrupted(failure: InterruptedException): IOException {
        Thread.currentThread().interrupt()
        return InterruptedIOException("Segment transfer interrupted").apply { initCause(failure) }
    }

    private fun asIo(failure: Exception): IOException = failure as? IOException ?: IOException("Segment transfer failed", failure)

    companion object {
        const val WORKERS = 3
        const val BLOCK_BYTES = 64 * 1024
        const val QUEUED_BLOCKS = 4
        const val BUFFER_COUNT = WORKERS * (QUEUED_BLOCKS + 1) + 1
        private val SHARED_BUDGET = Budget(2)
    }
}
