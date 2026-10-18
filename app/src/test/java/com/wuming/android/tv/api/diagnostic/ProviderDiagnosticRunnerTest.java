package com.wuming.android.tv.api.diagnostic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.tv.core.source.diagnostic.ProbeFailureCode;
import com.wuming.tv.core.source.diagnostic.ProbeObservation;
import com.wuming.tv.core.source.diagnostic.ProbePlan;
import com.wuming.tv.core.source.diagnostic.ProbeResult;
import com.wuming.tv.core.source.diagnostic.ProbeStage;
import com.wuming.tv.core.source.diagnostic.ProbeState;
import com.wuming.tv.core.source.diagnostic.ProbeStatus;
import com.wuming.tv.core.source.diagnostic.ProviderDiagnosticRun;
import com.wuming.tv.core.source.diagnostic.ProviderDiagnosticRunner;
import com.wuming.tv.core.source.diagnostic.ProviderDiagnosticTarget;

import org.junit.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ProviderDiagnosticRunnerTest {

    @Test
    public void runsSelectedStagesAndRedactsEvidence() throws Exception {
        ProviderDiagnosticTarget target = new ProviderDiagnosticTarget() {
            @Override public String sourceKey() { return "source"; }
            @Override public ProbeObservation probe(ProbeStage stage, ProbePlan plan, ProbeState state) {
                state.put(stage.name(), true);
                return ProbeObservation.pass("ok", Map.of(
                        "url", "https://media.example/file.m3u8?token=secret",
                        "authorization", "Bearer secret"));
            }
        };
        ProbePlan plan = new ProbePlan(EnumSet.of(ProbeStage.HOME, ProbeStage.SEARCH),
                "query", 1, Duration.ofSeconds(1), 0);

        try (ProviderDiagnosticRunner runner = new ProviderDiagnosticRunner()) {
            List<ProbeResult> results = runner.start(target, plan).future().get(2, TimeUnit.SECONDS);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(result -> result.getStatus() == ProbeStatus.PASSED));
            assertEquals("<redacted>", results.get(0).getEvidence().get("authorization"));
            assertFalse(results.get(0).getEvidence().get("url").contains("secret"));
        }
    }

    @Test
    public void retriesThenReportsTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ProviderDiagnosticTarget target = new ProviderDiagnosticTarget() {
            @Override public String sourceKey() { return "slow"; }
            @Override public ProbeObservation probe(ProbeStage stage, ProbePlan plan, ProbeState state)
                    throws Exception {
                calls.incrementAndGet();
                Thread.sleep(1_000L);
                return ProbeObservation.pass("late", Map.of());
            }
        };
        ProbePlan plan = new ProbePlan(EnumSet.of(ProbeStage.HOME), "", 1,
                Duration.ofMillis(20), 1);

        try (ProviderDiagnosticRunner runner = new ProviderDiagnosticRunner()) {
            ProbeResult result = runner.start(target, plan).future().get(2, TimeUnit.SECONDS).get(0);
            assertEquals(ProbeStatus.TIMED_OUT, result.getStatus());
            assertEquals(ProbeFailureCode.TIMEOUT, result.getFailureCode());
            assertEquals(2, result.getAttempts());
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void cancellationIsScopedToRun() throws Exception {
        ProviderDiagnosticTarget target = new ProviderDiagnosticTarget() {
            @Override public String sourceKey() { return "cancel"; }
            @Override public ProbeObservation probe(ProbeStage stage, ProbePlan plan, ProbeState state)
                    throws Exception {
                Thread.sleep(5_000L);
                return ProbeObservation.pass("late", Map.of());
            }
        };
        ProbePlan plan = new ProbePlan(EnumSet.of(ProbeStage.HOME), "", 1,
                Duration.ofSeconds(10), 0);

        try (ProviderDiagnosticRunner runner = new ProviderDiagnosticRunner()) {
            ProviderDiagnosticRun run = runner.start(target, plan);
            run.cancel();
            assertTrue(run.future().isCancelled() || run.isDone());
        }
    }
}
