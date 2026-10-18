package com.wuming.tv.core.source

import org.junit.Assert.*
import org.junit.Test

class HttpConnectionPolicyTest {
    @Test fun onlyTheMeasuredCleartextResolverRetiresConnections() {
        assertTrue(HttpConnectionPolicy.shouldClose("http", "gslbmgsplive.miguvideo.com"))
        assertTrue(HttpConnectionPolicy.shouldClose("HTTP", "GSLBMGSPLIVE.MIGUVIDEO.COM"))
        assertFalse(HttpConnectionPolicy.shouldClose("https", "gslbmgsplive.miguvideo.com"))
        assertFalse(HttpConnectionPolicy.shouldClose("http", "hlsztemgsplive.miguvideo.com"))
        assertFalse(HttpConnectionPolicy.shouldClose("http", "other.example"))
        assertFalse(HttpConnectionPolicy.shouldClose("http", "gslbmgsplive.miguvideo.com.other.example"))
    }
}
