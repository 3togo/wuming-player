package com.wuming.tv.core.source.diagnostic

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.ArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ProviderDiagnosticRunner private constructor(
    private val coordinator: ExecutorService,
    private val stages: ExecutorService,
    private val ownsExecutors: Boolean,
) : AutoCloseable {
    constructor() : this(Executors.newFixedThreadPool(2), Executors.newFixedThreadPool(4), true)
    constructor(coordinator: ExecutorService, stages: ExecutorService) : this(coordinator, stages, false)

    fun start(
        target: ProviderDiagnosticTarget?,
        plan: ProbePlan?,
    ): ProviderDiagnosticRun {
        require(target != null && plan != null) { "Target and plan are required" }
        val cancelled = AtomicBoolean()
        val active = AtomicReference<Future<*>?>()
        val future = coordinator.submit(java.util.concurrent.Callable { execute(target, plan, cancelled, active) })
        return ProviderDiagnosticRun(
            future,
            Runnable {
                cancelled.set(true)
                active.getAndSet(null)?.cancel(true)
                future.cancel(true)
            },
        )
    }

    private fun execute(
        target: ProviderDiagnosticTarget,
        plan: ProbePlan,
        cancelled: AtomicBoolean,
        active: AtomicReference<Future<*>?>,
    ): List<ProbeResult> {
        val results = ArrayList<ProbeResult>()
        val state = ProbeState()
        for (stage in ProbeStage.values()) {
            if (!plan.stages.contains(stage)) continue
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                results.add(cancelled(target.sourceKey(), stage))
                break
            }
            results.add(executeStage(target, plan, state, stage, cancelled, active))
            if (results.last().status == ProbeStatus.CANCELLED) break
        }
        return java.util.List.copyOf(results)
    }

    private fun executeStage(
        target: ProviderDiagnosticTarget,
        plan: ProbePlan,
        state: ProbeState,
        stage: ProbeStage,
        cancelled: AtomicBoolean,
        active: AtomicReference<Future<*>?>,
    ): ProbeResult {
        val started = System.currentTimeMillis()
        val maxAttempts = plan.retries + 1
        for (attempt in 1..maxAttempts) {
            if (cancelled.get()) return cancelled(target.sourceKey(), stage)
            val future = stages.submit(java.util.concurrent.Callable { target.probe(stage, plan, state) })
            active.set(future)
            try {
                val observation = future.get(plan.stageTimeout.toMillis(), TimeUnit.MILLISECONDS)
                active.compareAndSet(future, null)
                val elapsed = System.currentTimeMillis() - started
                if (observation ==
                    null
                ) {
                    return result(
                        target,
                        stage,
                        ProbeStatus.FAILED,
                        ProbeFailureCode.INVALID_RESPONSE,
                        started,
                        elapsed,
                        attempt,
                        "Diagnostic target returned no result",
                        emptyMap(),
                    )
                }
                return result(
                    target,
                    stage,
                    if (observation.successful) ProbeStatus.PASSED else ProbeStatus.FAILED,
                    observation.failureCode,
                    started,
                    elapsed,
                    attempt,
                    observation.summary,
                    observation.evidence,
                )
            } catch (error: TimeoutException) {
                future.cancel(true)
                active.compareAndSet(future, null)
                if (attempt ==
                    maxAttempts
                ) {
                    return result(
                        target,
                        stage,
                        ProbeStatus.TIMED_OUT,
                        ProbeFailureCode.TIMEOUT,
                        started,
                        System.currentTimeMillis() - started,
                        attempt,
                        "Stage exceeded " + plan.stageTimeout.toMillis() + " ms",
                        emptyMap(),
                    )
                }
            } catch (error: CancellationException) {
                future.cancel(true)
                active.compareAndSet(future, null)
                Thread.currentThread().interrupt()
                return cancelled(target.sourceKey(), stage)
            } catch (error: InterruptedException) {
                future.cancel(true)
                active.compareAndSet(future, null)
                Thread.currentThread().interrupt()
                return cancelled(target.sourceKey(), stage)
            } catch (error: Exception) {
                active.compareAndSet(future, null)
                val cause = error.cause ?: error
                if (attempt == maxAttempts) {
                    val code =
                        when (cause) {
                            is SocketTimeoutException -> ProbeFailureCode.TIMEOUT
                            is IOException -> ProbeFailureCode.NETWORK
                            else -> ProbeFailureCode.PROVIDER_ERROR
                        }
                    return result(
                        target,
                        stage,
                        ProbeStatus.FAILED,
                        code,
                        started,
                        System.currentTimeMillis() - started,
                        attempt,
                        cause.javaClass.simpleName + ": " + cause.message,
                        emptyMap(),
                    )
                }
            }
        }
        throw IllegalStateException("Unreachable diagnostic attempt state")
    }

    companion object {
        private fun cancelled(
            key: String,
            stage: ProbeStage,
        ): ProbeResult =
            ProbeResult(
                key,
                stage,
                ProbeStatus.CANCELLED,
                ProbeFailureCode.CANCELLED,
                System.currentTimeMillis(),
                0,
                0,
                "Diagnostic cancelled",
                emptyMap(),
            )

        private fun result(
            target: ProviderDiagnosticTarget,
            stage: ProbeStage,
            status: ProbeStatus,
            code: ProbeFailureCode,
            started: Long,
            elapsed: Long,
            attempts: Int,
            summary: String,
            evidence: Map<String, String>,
        ): ProbeResult = ProbeResult(target.sourceKey(), stage, status, code, started, elapsed, attempts, summary, evidence)
    }

    override fun close() {
        if (!ownsExecutors) return
        coordinator.shutdownNow()
        stages.shutdownNow()
    }
}
