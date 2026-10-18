package com.wuming.android.tv.server.process

import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Fair segment-boundary assignments; two jobs include abandoned work still in progress.  */
class CastWorkScheduler(
    private val workers: Int,
    private val bufferBytes: Int,
    private val threadName: String
) {
    private val jobs: MutableList<Job> = ArrayList<Job>(2)
    private var started = false

    @Synchronized
    @Throws(IOException::class)
    fun submit(job: Job?) {
        if (jobs.size >= 2) throw IOException("Cast work capacity exhausted")
        jobs.add(job!!)
        if (!started) {
            started = true
            for (i: Int in 0..<workers) {
                val worker: Thread = Thread(Runnable { this.work() }, threadName)
                worker.setDaemon(true)
                worker.start()
            }
        }
        signalWorkers()
    }

    @Synchronized
    fun cancel(job: Job) {
        job.cancelled = true
        if (job.active == 0) jobs.remove(job)
        signalWorkers()
    }

    @Synchronized
    private fun claim(): Assignment {
        while (true) {
            var selected: Job? = null
            for (job: Job in jobs) {
                if (job.cancelled || job.next == job.count ||
                    job.active >= job.parallelism
                ) {
                    continue
                }
                if (selected == null || job.active < selected.active) selected = job
            }
            if (selected != null) {
                // Rotate ties; a new/less-active asset gets the next available worker.
                jobs.remove(selected)
                jobs.add(selected)
                selected.active++
                return Assignment(selected, selected.next++)
            }
            try {
                awaitWork()
            } catch (ignored: InterruptedException) {
                /* Workers have process lifetime. */
            }
        }
    }

    // Kotlin Any does not expose JVM monitor wait/notify; callers hold this monitor.
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun signalWorkers() = (this as java.lang.Object).notifyAll()

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun awaitWork() = (this as java.lang.Object).wait()

    @JvmRecord
    private data class Assignment(val job: Job?, val index: Int)

    private fun work() {
        val buffer: ByteArray = ByteArray(bufferBytes)
        val bytes: ByteBuffer = ByteBuffer.wrap(buffer)
        while (true) runAssignment(claim(), buffer, bytes)
    }

    // A separate frame releases the completed job before claim() waits for more work.
    private fun runAssignment(assignment: Assignment, buffer: ByteArray, bytes: ByteBuffer) {
        val job = assignment.job!!
        var failure: Throwable? = null
        try {
            job.work.run(assignment.index, buffer, bytes)
        } catch (error: Throwable) {
            failure = error
            // Mark failed before making any more assignments. Cancel outside the lock.
            synchronized(this) { job.cancelled = true }
            job.cancel.run()
        } finally {
            synchronized(this) {
                job.active--
                if (failure != null) job.result.completeExceptionally(failure)
                if (job.active == 0 && (job.cancelled || job.next == job.count)) {
                    jobs.remove(job)
                    if (!job.cancelled) job.result.complete(null)
                }
                signalWorkers()
            }
            // Do not let an upstream's interrupt state poison the next asset.
            Thread.interrupted()
        }
    }

    fun interface Work {
        @Throws(Exception::class)
        fun run(index: Int, buffer: ByteArray?, bytes: ByteBuffer?)
    }

    class Job(
        @JvmField val count: Int,
        @JvmField val parallelism: Int,
        @JvmField val work: Work,
        @JvmField val cancel: Runnable
    ) {
        @JvmField val result: CompletableFuture<Void?> = CompletableFuture<Void?>()

        @JvmField var next: Int = 0

        @JvmField var active: Int = 0

        @JvmField var cancelled: Boolean = false

        @Throws(IOException::class, InterruptedException::class)
        fun await() {
            try {
                result.get()
            } catch (error: ExecutionException) {
                val failure = error.cause
                if (failure is IOException) throw failure
                throw IOException("Cast work failed", error.cause)
            }
        }
    }
}
