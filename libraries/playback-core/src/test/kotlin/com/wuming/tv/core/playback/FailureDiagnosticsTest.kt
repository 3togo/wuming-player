package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class FailureDiagnosticsTest {
    @Test fun reportsNestedTypesWithoutPrivateMessages() {
        val error = RuntimeException("https://private.example/?token=secret",
            java.io.IOException("Cookie: private"))
        assertEquals(listOf("java.lang.RuntimeException", "java.io.IOException"), FailureDiagnostics.causeTypes(error))
        assertTrue(FailureDiagnostics.causeTypes(null).isEmpty())
    }
    @Test fun cyclesAndDeepChainsAreBounded() {
        val first = RuntimeException("first")
        val second = RuntimeException("second", first)
        first.initCause(second)
        assertEquals(2, FailureDiagnostics.causeTypes(first).size)
        var deep = RuntimeException()
        repeat(50) { deep = RuntimeException(deep) }
        assertEquals(16, FailureDiagnostics.causeTypes(deep).size)
    }
}
