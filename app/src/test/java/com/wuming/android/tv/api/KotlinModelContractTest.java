package com.wuming.android.tv.api;

import static org.junit.Assert.*;

import com.wuming.android.tv.api.debug.SpiderInvocation;
import com.wuming.android.tv.api.debug.SpiderOperation;
import com.wuming.android.tv.api.debug.SpiderTrace;
import com.wuming.android.tv.api.debug.SpiderTraceStatus;
import com.wuming.tv.core.source.diagnostic.ProbePlan;
import com.wuming.tv.core.source.diagnostic.ProbeStage;
import com.wuming.android.tv.media.epg.ProgramGuideLayout;
import com.wuming.tv.core.playback.hls.HlsTransformContext;
import com.wuming.tv.core.media.subtitle.SubtitleQuery;
import com.wuming.tv.core.media.subtitle.SubtitlePage;
import com.wuming.tv.core.media.subtitle.SubtitleCandidate;

import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Java callers must retain the pre-migration record constructor/accessor contracts. */
public class KotlinModelContractTest {
    @Test
    public void traceAlwaysNormalizesAndSanitizes() {
        SpiderTrace trace = new SpiderTrace(SpiderOperation.HOME, SpiderTraceStatus.SUCCESS,
                1, -2, Map.of("authorization", "Bearer secret"), null, null, null);
        assertEquals(0, trace.elapsedMs());
        assertEquals(trace.elapsedMs(), trace.getElapsedMs());
        assertEquals("<redacted>", trace.request().get("authorization"));
        assertEquals("", trace.response());
        assertEquals("", trace.errorCode());
        assertEquals("", trace.errorMessage());
        assertEquals(trace, new SpiderTrace(SpiderOperation.HOME, SpiderTraceStatus.SUCCESS,
                1, 0, trace.request(), "", "", ""));
    }

    @Test
    public void invocationAndPlanSnapshotMutableInputs() {
        ArrayList<String> arguments = new ArrayList<>(List.of("one"));
        SpiderInvocation invocation = new SpiderInvocation(SpiderOperation.HOME,
                arguments, null, null, true, false);
        arguments.clear();
        assertEquals(List.of("one"), invocation.arguments());
        assertEquals(invocation.arguments(), invocation.getArguments());
        assertThrows(UnsupportedOperationException.class, () -> invocation.arguments().clear());

        EnumSet<ProbeStage> stages = EnumSet.of(ProbeStage.HOME);
        ProbePlan plan = new ProbePlan(stages, " query ", 20, null, -1);
        stages.clear();
        assertEquals(EnumSet.of(ProbeStage.HOME), plan.stages());
        assertEquals("query", plan.searchKeyword());
        assertEquals(5, plan.sampleCount());
        assertEquals(Duration.ofSeconds(15), plan.stageTimeout());
        assertEquals(0, plan.retries());
        assertThrows(IllegalArgumentException.class,
                () -> new ProbePlan(null, null, 1, Duration.ZERO, 0));
    }

    @Test
    public void hlsAndGuideSnapshotCollectionsAndClampOffsets() {
        HashMap<String, String> attributes = new HashMap<>(Map.of("key", "value"));
        HlsTransformContext context = new HlsTransformContext(null, null, attributes);
        attributes.clear();
        assertEquals(Map.of("key", "value"), context.attributes());
        assertEquals("uri", context.uriMapper().apply("uri"));
        assertThrows(UnsupportedOperationException.class, () -> context.attributes().clear());

        ArrayList<ProgramGuideLayout.Row> rows = new ArrayList<>(List.of(
                new ProgramGuideLayout.Row(null, null, null)));
        ProgramGuideLayout guide = new ProgramGuideLayout(10, 20, 30, 2, rows);
        rows.clear();
        assertEquals(1, guide.rows().size());
        assertEquals(1, guide.nowOffset(), 0);
        assertFalse(guide.isNowVisible());
        assertEquals(new ProgramGuideLayout.Row("", "", List.of()), guide.rows().get(0));
    }

    @Test
    public void subtitleInputsNormalizeBeforeValidation() {
        SubtitleQuery query = new SubtitleQuery(" title ", null, null, null, -1, 100);
        assertEquals("title", query.title());
        assertEquals(1, query.page());
        assertEquals(50, query.pageSize());
        assertEquals(new SubtitleQuery("title", null, null, List.of(), 1, 50), query);
        assertThrows(IllegalArgumentException.class,
                () -> new SubtitleQuery("  ", null, null, null, 1, 1));
        SubtitlePage page = new SubtitlePage(null, -5, -10);
        assertEquals(1, page.page());
        assertEquals(1, page.totalPages());
        SubtitleCandidate candidate = new SubtitleCandidate(" provider ", " id ", null,
                null, " SRT ", null, false, 200);
        assertEquals("provider", candidate.providerId());
        assertEquals("srt", candidate.format());
        assertEquals(100, candidate.matchScore());
        assertNull(candidate.downloadUri());
    }
}
