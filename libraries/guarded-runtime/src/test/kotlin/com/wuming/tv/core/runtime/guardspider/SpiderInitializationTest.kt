package com.wuming.tv.core.runtime.guardspider

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class SpiderInitializationTest {
    class Extended {
        var calls = 0
        var context: Any? = null
        var extension: String? = null
        fun init(context: Any?, extension: String) {
            calls++
            this.context = context
            this.extension = extension
        }
        fun init(context: Any?) { error("Two-argument initialization must take precedence") }
    }

    class Legacy {
        var calls = 0
        var context: Any? = null
        fun init(context: Any?) { calls++; this.context = context }
    }

    class Failing {
        fun init(context: Any?, extension: String) { error("initialization failed") }
    }

    @Test fun emptyExtensionStillInitializesProviderExactlyOnce() {
        val provider = Extended()
        val context = Any()
        SpiderInvocationSupport.initialize(provider, Any::class.java, context, "")
        assertEquals(1, provider.calls)
        assertSame(context, provider.context)
        assertEquals("", provider.extension)
    }

    @Test fun preservesExtensionAndSupportsTheLegacyContextOnlySignature() {
        val provider = Extended()
        SpiderInvocationSupport.initialize(provider, Any::class.java, null, "configuration")
        assertEquals("configuration", provider.extension)
        assertNull(provider.context)
        val legacy = Legacy()
        val context = Any()
        SpiderInvocationSupport.initialize(legacy, Any::class.java, context, "configuration")
        assertEquals(1, legacy.calls)
        assertSame(context, legacy.context)
    }

    @Test fun initializationFailureReachesTheCallerAndMissingAbiIsRejected() {
        val failure = assertThrows(InvocationTargetException::class.java) {
            SpiderInvocationSupport.initialize(Failing(), Any::class.java, null, "")
        }
        assertEquals("initialization failed", failure.cause?.message)
        assertThrows(IllegalArgumentException::class.java) {
            SpiderInvocationSupport.initialize(Any(), Any::class.java, null, "")
        }
    }
}
