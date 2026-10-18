package com.wuming.android.tv.server.process

import com.wuming.android.tv.server.process.CastWorkScheduler.Work
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.function.IntFunction
import kotlin.math.min
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody

/** Bounded, byte-exact staging for small cast remux jobs. Owns only its newly created file.  */
object CastSegmentDownloader {
    const val WORKERS: Int = 4
    const val BUFFER_BYTES: Int = 64 * 1024
    private val MAX_SEGMENTS = CastSegmentProbe.MAX_SEGMENTS
    private val SCHEDULER = CastWorkScheduler(WORKERS, BUFFER_BYTES, "cast-segment-stage")

    @Throws(IOException::class)
    @JvmStatic
    fun download(
        directory: File?,
        segmentSizes: LongArray,
        byteLimit: Long,
        calls: IntFunction<Call?>
    ): File = download(directory, segmentSizes, byteLimit, WORKERS, calls)

    @Throws(IOException::class)
    @JvmStatic
    fun download(
        directory: File?,
        segmentSizes: LongArray,
        byteLimit: Long,
        parallelism: Int,
        calls: IntFunction<Call?>
    ): File {
        if (parallelism <= 0 || parallelism > WORKERS) throw IOException("Invalid worker budget")
        if (segmentSizes.size == 0 || segmentSizes.size > MAX_SEGMENTS ||
            byteLimit <= 0
        ) {
            throw IOException(
                "Invalid cast staging budget"
            )
        }
        val sizes: LongArray = segmentSizes.clone()
        val starts: LongArray = LongArray(sizes.size)
        var total: Long = 0
        for (i: Int in sizes.indices) {
            if (sizes[i] <= 0 ||
                sizes[i] > byteLimit - total
            ) {
                throw IOException("Cast staging byte limit exceeded")
            }
            starts[i] = total
            total += sizes[i]
        }
        checkInterrupted()
        val file: File = File.createTempFile("cast-stage-", ".ts", directory)
        val scope: CastCallScope = CastCallScope()
        var job: CastWorkScheduler.Job? = null
        var complete: Boolean = false
        try {
            // Workers never open a path. Closing this shared handle prevents late writes or
            // recreation after a failed/cancelled request deletes the staging file.
            FileOutputStream(file).use { stream ->
                stream.getChannel().use { output ->
                    job = CastWorkScheduler.Job(
                        sizes.size,
                        parallelism,
                        Work { index: Int, buffer: ByteArray?, bytes: ByteBuffer? ->
                            scope.check()
                            CastSegmentDownloader.copy(
                                calls.apply(index)!!,
                                output,
                                starts[index],
                                sizes[index],
                                buffer!!,
                                bytes!!,
                                scope
                            )
                        },
                        Runnable { scope.close() }
                    )
                    SCHEDULER.submit(job)
                    job.await()
                    checkInterrupted()
                    if (output.size() != total) throw IOException("Incomplete cast staging file")
                }
            }
            complete = true
            return file
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            val failure: InterruptedIOException = InterruptedIOException("Cast staging interrupted")
            failure.initCause(error)
            throw failure
        } catch (error: RuntimeException) {
            throw IOException("Unable to schedule cast staging", error)
        } finally {
            scope.close()
            if (job != null) SCHEDULER.cancel(job)
            if (!complete) Files.deleteIfExists(file.toPath())
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    private fun copy(
        call: Call,
        output: FileChannel,
        position: Long,
        expected: Long,
        buffer: ByteArray,
        bytes: ByteBuffer,
        scope: CastCallScope
    ) {
        var position: Long = position
        scope.register(call)
        try {
            call.execute().use { response ->
                val body: ResponseBody = response.body
                if (!response.isSuccessful
                ) {
                    throw IOException("Cast segment HTTP " + response.code)
                }
                if (body.contentLength() >= 0 &&
                    body.contentLength() != expected
                ) {
                    throw IOException(
                        "Cast segment size changed since probe"
                    )
                }
                val input: InputStream = body.byteStream()
                var remaining: Long = expected
                while (remaining > 0) {
                    scope.check()
                    val count: Int =
                        input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("Cast segment ended before probed size")
                    if (count == 0) continue
                    bytes.clear()
                    bytes.limit(count)
                    while (bytes.hasRemaining()) {
                        scope.check()
                        position += output.write(bytes, position).toLong()
                    }
                    remaining -= count.toLong()
                }
                scope.check()
                // Content-Length is only a hint. Verify the actual EOF, including unknown lengths.
                if (input.read() != -1) throw IOException("Cast segment exceeds probed size")
            }
        } finally {
            scope.remove(call)
        }
    }

    @Throws(InterruptedIOException::class)
    @JvmStatic
    private fun checkInterrupted() {
        if (Thread.currentThread()
                .isInterrupted()
        ) {
            throw InterruptedIOException("Cast staging interrupted")
        }
    }
}
