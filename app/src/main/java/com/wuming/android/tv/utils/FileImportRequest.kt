package com.wuming.android.tv.utils

import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.App
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.function.Consumer
import java.util.function.Function

/** Adds imported-file ownership to the shared lifecycle request. */
@JvmSuppressWildcards
internal class FileImportRequest<T>(
    owner: LifecycleOwner,
    resolve: Callable<ImportedFile>,
    transform: Function<String, T>,
    success: Consumer<T>,
    failure: Runnable,
    worker: Executor,
    main: Executor,
    remove: Consumer<Runnable>,
) : AutoCloseable {
    class ImportedFile(
        @JvmField val file: File,
        @JvmField val owned: Boolean,
    ) {
        fun discard() {
            if (owned) file.delete()
        }
    }

    private data class ImportedValue<T>(
        val file: ImportedFile,
        val value: T,
    )

    private val request: UiRequest<ImportedValue<T>> =
        UiRequest(
            owner,
            Callable {
                val file = resolve.call()
                try {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Import cancelled")
                    ImportedValue(file, transform.apply(file.file.absolutePath))
                } catch (error: Exception) {
                    file.discard()
                    throw error
                }
            },
            Consumer { success.accept(it.value) },
            Consumer { failure.run() },
            Runnable {},
            Consumer { it.file.discard() },
            worker,
            main,
            remove,
        )
    constructor(
        owner: LifecycleOwner,
        resolve: Callable<ImportedFile>,
        transform: Function<String, T>,
        success: Consumer<T>,
        failure: Runnable,
    ) :
        this(
            owner,
            resolve,
            transform,
            success,
            failure,
            Task.largeExecutor(),
            App.mainThreadExecutor,
            Consumer { App.removeCallbacks(it) },
        )

    override fun close() {
        request.close()
    }
}
