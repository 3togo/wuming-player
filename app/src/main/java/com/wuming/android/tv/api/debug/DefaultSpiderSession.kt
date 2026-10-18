package com.wuming.android.tv.api.debug

import com.wuming.tv.core.source.diagnostic.DiagnosticSanitizer
import com.github.catvod.crawler.Spider
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single-invocation debug session with explicit cancellation and hot reload. */
class DefaultSpiderSession private constructor(
    factory: SpiderFactory?,
    executor: ExecutorService?,
    private val ownsExecutor: Boolean,
) : SpiderSession {
    private val factory: SpiderFactory
    private val executor: ExecutorService
    private val active = AtomicReference<ActiveInvocation?>()
    private val closed = AtomicBoolean()

    @Volatile private var spider: Spider?

    init {
        require(factory != null && executor != null) { "Factory and executor are required" }
        this.factory = factory
        this.executor = executor
        spider = requireSpider(factory.create())
    }

    @Throws(Exception::class)
    constructor(factory: SpiderFactory?) : this(factory, Executors.newSingleThreadExecutor(), true)

    @Throws(Exception::class)
    constructor(factory: SpiderFactory?, executor: ExecutorService?) : this(factory, executor, false)

    override fun invoke(invocation: SpiderInvocation?): CompletableFuture<SpiderTrace> {
        require(invocation != null) { "Invocation is required" }
        check(!closed.get()) { "Spider session is closed" }
        cancel()
        val completion = CompletableFuture<SpiderTrace>()
        val started = System.currentTimeMillis()
        val task =
            executor.submit {
                try {
                    val response = dispatch(spider!!, invocation)
                    completion.complete(
                        SpiderTrace(
                            invocation.operation,
                            SpiderTraceStatus.SUCCESS,
                            started,
                            System.currentTimeMillis() - started,
                            request(invocation),
                            response,
                            "",
                            "",
                        ),
                    )
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    completion.complete(cancelled(invocation, started))
                } catch (error: Exception) {
                    completion.complete(
                        SpiderTrace(
                            invocation.operation,
                            SpiderTraceStatus.FAILURE,
                            started,
                            System.currentTimeMillis() - started,
                            request(invocation),
                            "",
                            error.javaClass.simpleName,
                            error.message,
                        ),
                    )
                } finally {
                    val current = active.get()
                    if (current != null && current.completion === completion) active.compareAndSet(current, null)
                }
            }
        val next = ActiveInvocation(task, completion, invocation, started)
        active.set(next)
        if (task.isDone) active.compareAndSet(next, null)
        return completion
    }

    override fun cancel() {
        val invocation = active.getAndSet(null) ?: return
        invocation.task.cancel(true)
        invocation.completion.complete(cancelled(invocation.invocation, invocation.startedAtMs))
    }

    @Synchronized
    @Throws(Exception::class)
    override fun reload() {
        check(!closed.get()) { "Spider session is closed" }
        cancel()
        val replacement = requireSpider(factory.create())
        val previous = spider
        spider = replacement
        if (previous != null && previous !== replacement) previous.destroy()
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        val previous = spider
        spider = null
        previous?.destroy()
        if (ownsExecutor) executor.shutdownNow()
    }

    companion object {
        private fun dispatch(
            spider: Spider,
            invocation: SpiderInvocation,
        ): String? {
            val args = invocation.arguments
            return when (invocation.operation) {
                SpiderOperation.HOME -> {
                    spider.homeContent(invocation.filter)
                }

                SpiderOperation.HOME_VIDEO -> {
                    spider.homeVideoContent()
                }

                SpiderOperation.CATEGORY -> {
                    spider.categoryContent(
                        arg(args, 0),
                        defaultArg(args, 1, "1"),
                        invocation.filter,
                        HashMap(invocation.parameters),
                    )
                }

                SpiderOperation.DETAIL -> {
                    spider.detailContent(args)
                }

                SpiderOperation.SEARCH -> {
                    spider.searchContent(arg(args, 0), invocation.quick, defaultArg(args, 1, "1"))
                }

                SpiderOperation.PLAYER -> {
                    spider.playerContent(arg(args, 0), arg(args, 1), invocation.flags)
                }

                SpiderOperation.LIVE -> {
                    spider.liveContent(arg(args, 0))
                }

                SpiderOperation.ACTION -> {
                    spider.action(arg(args, 0))
                }

                SpiderOperation.MANUAL_VIDEO_CHECK -> {
                    spider.manualVideoCheck().toString()
                }

                SpiderOperation.VIDEO_FORMAT_CHECK -> {
                    spider.isVideoFormat(arg(args, 0)).toString()
                }
            }
        }

        private fun request(invocation: SpiderInvocation): Map<String, String> {
            val request = LinkedHashMap<String, String>()
            request["operation"] = invocation.operation.name
            request["arguments"] = DiagnosticSanitizer.sanitizeText(invocation.arguments.toString())
            invocation.parameters.forEach { (key, value) -> request["parameter." + key] = value }
            if (invocation.flags.isNotEmpty()) request["flags"] = invocation.flags.toString()
            request["filter"] = invocation.filter.toString()
            request["quick"] = invocation.quick.toString()
            return request
        }

        private fun cancelled(
            invocation: SpiderInvocation,
            started: Long,
        ): SpiderTrace =
            SpiderTrace(
                invocation.operation,
                SpiderTraceStatus.CANCELLED,
                started,
                System.currentTimeMillis() - started,
                request(invocation),
                "",
                "CANCELLED",
                "Spider invocation cancelled",
            )

        private fun requireSpider(spider: Spider?): Spider = checkNotNull(spider) { "Spider factory returned null" }

        private fun arg(
            values: List<String?>,
            index: Int,
        ): String {
            require(index < values.size && values[index] != null) { "Missing spider argument " + index }
            return values[index]!!
        }

        private fun defaultArg(
            values: List<String?>,
            index: Int,
            fallback: String,
        ): String =
            if (index < values.size &&
                values[index] != null
            ) {
                values[index]!!
            } else {
                fallback
            }
    }

    private data class ActiveInvocation(
        val task: Future<*>,
        val completion: CompletableFuture<SpiderTrace>,
        val invocation: SpiderInvocation,
        val startedAtMs: Long,
    )
}
