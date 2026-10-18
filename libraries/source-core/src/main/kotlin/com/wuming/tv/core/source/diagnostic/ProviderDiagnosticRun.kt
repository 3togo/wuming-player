package com.wuming.tv.core.source.diagnostic

import java.util.concurrent.Future

@JvmSuppressWildcards
class ProviderDiagnosticRun(
    private val future: Future<List<ProbeResult>>,
    private val cancel: Runnable,
) {
    fun future(): Future<List<ProbeResult>> = future

    fun cancel() {
        cancel.run()
    }

    fun isDone(): Boolean = future.isDone
}
