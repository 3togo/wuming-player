package com.wuming.tv.core.source.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.EnumSet

class ProbePlanTest {

    @Test
    fun `clamps sample count and retries`() {
        val plan = ProbePlan(EnumSet.of(ProbeStage.HOME), "query", 20, null, -1)
        assertEquals(5, plan.sampleCount)
        assertEquals(0, plan.retries)
    }

    @Test
    fun `defaults timeout to 15 seconds`() {
        val plan = ProbePlan(EnumSet.of(ProbeStage.HOME), "query", 1, null, 0)
        assertEquals(Duration.ofSeconds(15), plan.stageTimeout)
    }

    @Test
    fun `trims search keyword`() {
        val plan = ProbePlan(EnumSet.of(ProbeStage.HOME), " query ", 1, null, 0)
        assertEquals("query", plan.searchKeyword)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects zero timeout`() {
        ProbePlan(null, null, 1, Duration.ZERO, 0)
    }
}

class DiagnosticSanitizerTest {

    @Test
    fun `redacts authorization header`() {
        val sanitized = DiagnosticSanitizer.sanitize(mapOf("authorization" to "Bearer secret"))
        assertEquals("<redacted>", sanitized["authorization"])
    }

    @Test
    fun `redacts url query parameters`() {
        val sanitized = DiagnosticSanitizer.sanitizeText("https://media.example/file.m3u8?token=secret")
        assertFalse(sanitized.contains("secret"))
    }

    @Test
    fun `returns empty for null input`() {
        assertEquals("", DiagnosticSanitizer.sanitizeText(null))
    }
}

class ProbeObservationTest {

    @Test
    fun `pass factory creates successful observation`() {
        val obs = ProbeObservation.pass("ok", emptyMap())
        assertTrue(obs.successful)
        assertEquals(ProbeFailureCode.NONE, obs.failureCode)
    }

    @Test
    fun `fail factory creates failed observation`() {
        val obs = ProbeObservation.fail(ProbeFailureCode.TIMEOUT, "timed out")
        assertFalse(obs.successful)
        assertEquals(ProbeFailureCode.TIMEOUT, obs.failureCode)
    }
}
