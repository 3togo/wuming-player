package com.wuming.android.tv.server.process;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded, byte-exact staging for small cast remux jobs. Owns only its newly created file. */
final class FixedCastSegmentDownloader {
    static final int WORKERS = 4;
    static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAX_SEGMENTS = 10_000;
    // Also bound abandoned work if an upstream implementation is slow to honor cancellation.
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            WORKERS, WORKERS, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(WORKERS), task -> {
                Thread thread = new Thread(task, "cast-segment-stage");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    static { EXECUTOR.allowCoreThreadTimeOut(true); }

    private FixedCastSegmentDownloader() {}

    static File download(File directory, long[] segmentSizes, long byteLimit,
                         IntFunction<Call> calls) throws IOException {
        return download(directory, segmentSizes, byteLimit, WORKERS, calls);
    }

    static File download(File directory, long[] segmentSizes, long byteLimit,
                         int parallelism, IntFunction<Call> calls) throws IOException {
        if (parallelism <= 0 || parallelism > WORKERS) throw new IOException("Invalid worker budget");
        if (segmentSizes.length == 0 || segmentSizes.length > MAX_SEGMENTS || byteLimit <= 0)
            throw new IOException("Invalid cast staging budget");
        long[] sizes = segmentSizes.clone();
        long[] starts = new long[sizes.length];
        long total = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] <= 0 || sizes[i] > byteLimit - total)
                throw new IOException("Cast staging byte limit exceeded");
            starts[i] = total;
            total += sizes[i];
        }
        checkInterrupted();
        File file = File.createTempFile("cast-stage-", ".ts", directory);
        Scope scope = new Scope();
        List<Future<Void>> workers = new ArrayList<>();
        boolean complete = false;
        try {
            // Workers never open a path. Closing this shared handle prevents late writes or
            // recreation after a failed/cancelled request deletes the staging file.
            try (FileOutputStream stream = new FileOutputStream(file);
                 FileChannel output = stream.getChannel()) {
                AtomicInteger next = new AtomicInteger();
                ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(EXECUTOR);
                int workerCount = Math.min(parallelism, sizes.length);
                for (int i = 0; i < workerCount; i++) {
                    workers.add(completion.submit(() -> {
                        byte[] buffer = new byte[BUFFER_BYTES];
                        ByteBuffer bytes = ByteBuffer.wrap(buffer);
                        try {
                            int index;
                            while ((index = next.getAndIncrement()) < sizes.length) {
                                scope.check();
                                copy(calls.apply(index), output, starts[index], sizes[index], buffer, bytes, scope);
                            }
                            return null;
                        } catch (IOException | RuntimeException error) {
                            scope.close();
                            throw error;
                        }
                    }));
                }
                for (int i = 0; i < workerCount; i++) completion.take().get();
                checkInterrupted();
                if (output.size() != total) throw new IOException("Incomplete cast staging file");
            }
            complete = true;
            return file;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException("Cast staging interrupted");
            failure.initCause(error);
            throw failure;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException failure) throw failure;
            throw new IOException("Cast segment download failed", error.getCause());
        } catch (RuntimeException error) {
            throw new IOException("Unable to schedule cast staging", error);
        } finally {
            scope.close();
            for (Future<Void> worker : workers) worker.cancel(true);
            if (!complete) Files.deleteIfExists(file.toPath());
        }
    }

    private static void copy(Call call, FileChannel output, long position, long expected,
                             byte[] buffer, ByteBuffer bytes, Scope scope) throws IOException {
        scope.register(call);
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null)
                throw new IOException("Cast segment HTTP " + response.code());
            if (body.contentLength() >= 0 && body.contentLength() != expected)
                throw new IOException("Cast segment size changed since probe");
            InputStream input = body.byteStream();
            long remaining = expected;
            while (remaining > 0) {
                scope.check();
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new EOFException("Cast segment ended before probed size");
                if (count == 0) continue;
                bytes.clear();
                bytes.limit(count);
                while (bytes.hasRemaining()) {
                    scope.check();
                    position += output.write(bytes, position);
                }
                remaining -= count;
            }
            scope.check();
            // Content-Length is only a hint. Verify the actual EOF, including unknown lengths.
            if (input.read() != -1) throw new IOException("Cast segment exceeds probed size");
        } finally {
            scope.remove(call);
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cast staging interrupted");
    }

    private static final class Scope implements AutoCloseable {
        private final Set<Call> active = new HashSet<>();
        private boolean closed;
        synchronized void check() throws IOException {
            checkInterrupted();
            if (closed) throw new IOException("Cast staging cancelled");
        }
        synchronized void register(Call call) throws IOException {
            if (closed || Thread.currentThread().isInterrupted()) {
                call.cancel();
                throw new InterruptedIOException("Cast staging cancelled");
            }
            active.add(call);
        }
        synchronized void remove(Call call) { active.remove(call); }
        @Override public void close() {
            List<Call> calls;
            synchronized (this) {
                if (closed) return;
                closed = true;
                calls = new ArrayList<>(active);
            }
            // Cancel sockets here; only each reading worker closes its ResponseBody.
            for (Call call : calls) call.cancel();
        }
    }
}
