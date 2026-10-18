package com.wuming.android.tv.api.debug

import java.util.concurrent.CompletableFuture

/** Lifecycle boundary for load/reload/invoke/cancel/destroy spider debugging. */
interface SpiderSession : AutoCloseable {
    fun invoke(invocation: SpiderInvocation?): CompletableFuture<SpiderTrace>
    fun cancel()
    @Throws(Exception::class)
    fun reload()
    override fun close()
}
