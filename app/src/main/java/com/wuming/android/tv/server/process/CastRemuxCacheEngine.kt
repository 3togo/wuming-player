package com.wuming.android.tv.server.process

import com.wuming.android.tv.utils.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded per-asset preparation and leases for immutable, exclusively owned remux files. */
internal class CastRemuxCacheEngine(private val byteLimit: Long, private val entryLimit: Int) {
    private val cached = LinkedHashMap<CastRemuxCache.Key, Entry>(8, 0.75f, true)
    private val preparing = LinkedHashMap<CastRemuxCache.Key, Job>()
    private var cachedBytes = 0L

    init { require(byteLimit > 0 && entryLimit > 0) { "Invalid remux cache budget" } }

    @Throws(IOException::class)
    fun open(key: CastRemuxCache.Key, builder: CastRemuxCache.Builder): CastRemuxCache.Lease? {
        if (Thread.currentThread().isInterrupted) throw interrupted(null)
        val job: Job
        var start = false
        synchronized(this) {
            val existing = cached[key]
            if (existing != null) return lease(existing)
            val pending = preparing[key]
            if (pending == null) {
                if (preparing.size >= 2) return null
                job = Job(key, builder)
                preparing[key] = job
                start = true
            } else {
                if (pending.abandoned) return null
                job = pending
            }
            job.waiters++
        }
        try {
            if (start) dispatch(job)
            val entry = job.result.get()
            synchronized(this) {
                if (Thread.currentThread().isInterrupted) throw interrupted(null)
                return if (entry == null) null else lease(entry)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted(error)
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is IOException) throw cause
            throw IOException("Cast preparation failed", cause)
        } catch (error: CancellationException) {
            throw IOException("Cast preparation cancelled", error)
        } finally { detach(job) }
    }

    private fun dispatch(job: Job) {
        try {
            EXECUTOR.execute {
                try { job.task.run() }
                finally {
                    // Future cancellation can finish before its worker and cleanup exit.
                    synchronized(this) { preparing.remove(job.key, job) }
                }
            }
        } catch (error: RuntimeException) {
            synchronized(this) { preparing.remove(job.key, job) }
            job.result.completeExceptionally(error)
        }
    }

    private fun prepare(job: Job, builder: CastRemuxCache.Builder) {
        var candidate: File? = null
        try {
            candidate = builder.build()
            synchronized(this) {
                if (job.abandoned || Thread.currentThread().isInterrupted || candidate == null) {
                    job.result.complete(null)
                    return
                }
                val file = candidate
                val bytes = file.length()
                if (!file.isFile || bytes <= 0 || bytes > byteLimit || !makeRoom(bytes)) {
                    job.result.complete(null)
                    return
                }
                val entry = Entry(file, bytes)
                entry.reservations = job.waiters
                job.entry = entry
                cached[job.key] = entry
                cachedBytes += bytes
                candidate = null
                job.result.complete(entry)
            }
        } catch (error: Throwable) { job.result.completeExceptionally(error) }
        finally {
            candidate?.let { if (!it.delete() && it.exists()) AppLog.w("CastRemuxCache", "Unable to remove rejected remux file") }
        }
    }

    @Synchronized
    private fun detach(job: Job) {
        job.waiters--
        job.entry?.let { it.reservations-- }
        if (job.waiters == 0 && !job.result.isDone) {
            job.abandoned = true
            job.result.cancel(false)
            job.task.cancel(true)
        }
    }

    /** Opens the handle under the cache lock before eviction can inspect it. */
    private fun lease(entry: Entry): CastRemuxCache.Lease {
        val input = try { FileInputStream(entry.file) }
        catch (error: IOException) {
            if (!entry.file.exists() && entry.readers == 0 && entry.reservations == 0) {
                cached.values.remove(entry)
                cachedBytes -= entry.bytes
            }
            throw error
        }
        entry.readers++
        return CastRemuxCache.Lease(input, entry.bytes, Runnable { synchronized(this) { entry.readers-- } })
    }

    /** Failed deletions remain charged to the budget; pinned entries are never evicted. */
    private fun makeRoom(incoming: Long): Boolean {
        var reclaimable = 0L
        var removable = 0
        for (entry in cached.values) if (!entry.pinned()) { reclaimable += entry.bytes; removable++ }
        if (incoming > byteLimit - (cachedBytes - reclaimable) || cached.size - removable >= entryLimit) return false
        val entries = cached.values.iterator()
        while ((incoming > byteLimit - cachedBytes || cached.size >= entryLimit) && entries.hasNext()) {
            val entry = entries.next()
            if (entry.pinned()) continue
            if (entry.file.delete() || !entry.file.exists()) {
                cachedBytes -= entry.bytes
                entries.remove()
            }
        }
        return incoming <= byteLimit - cachedBytes && cached.size < entryLimit
    }

    private class Entry(val file: File, val bytes: Long) {
        var readers = 0
        var reservations = 0
        fun pinned(): Boolean = readers != 0 || reservations != 0
    }

    private inner class Job(val key: CastRemuxCache.Key, builder: CastRemuxCache.Builder) {
        val result = CompletableFuture<Entry?>()
        val task = FutureTask<Void?>({ prepare(this, builder); null })
        var waiters = 0
        var abandoned = false
        var entry: Entry? = null
    }

    companion object {
        private val EXECUTOR = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2), { task ->
            Thread(task, "cast-remux-prepare").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

        private fun interrupted(cause: Throwable?): InterruptedIOException =
            InterruptedIOException("Interrupted waiting for cast preparation").apply { if (cause != null) initCause(cause) }
    }
}

/** Idempotent release also runs when closing the underlying file handle fails. */
open class CastRemuxLease(input: FileInputStream, private val length: Long, private val release: Runnable) : FilterInputStream(input) {
    private val closed = AtomicBoolean()
    fun length(): Long = length

    @Throws(IOException::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { super.close() } finally { release.run() }
    }
}
