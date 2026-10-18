package com.wuming.tv.core.upnp

import java.net.ConnectException
import org.junit.Assert.*
import org.junit.Test

class UpnpFailureDiagnosticTest {
    @Test fun reportsCauseTypesWithoutMessagesOrCredentials() {
        val failure = IllegalStateException("http://private/?token=secret", ConnectException("credential"))
        assertEquals("IllegalStateException>ConnectException", UpnpFailureDiagnostic.types(failure))
        assertEquals("unavailable", UpnpFailureDiagnostic.types(null))
    }

    @Test fun boundsCyclicAndDeepCauseChains() {
        val first = Exception()
        val second = Exception(first)
        first.initCause(second)
        assertEquals("Exception>Exception", UpnpFailureDiagnostic.types(first))
        var deep: Throwable = Exception()
        repeat(20) { deep = Exception(deep) }
        assertEquals(8, UpnpFailureDiagnostic.types(deep).split('>').size)
    }
}
