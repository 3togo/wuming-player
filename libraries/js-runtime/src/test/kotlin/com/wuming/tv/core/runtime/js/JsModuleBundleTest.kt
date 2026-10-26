package com.wuming.tv.core.runtime.js

import org.junit.Assert.*
import org.junit.Test

class JsModuleBundleTest {
    @Test fun cancellationStopsBeforeFetchingTheNextImport() {
        val requested = mutableListOf<String>()
        try {
            assertThrows(InterruptedException::class.java) {
                JsModuleBundle.fetch("https://fixture.example/entry.js") { url ->
                    requested += url
                    Thread.currentThread().interrupt()
                    "import './helper.js';".toByteArray()
                }
            }
            assertEquals(listOf("https://fixture.example/entry.js"), requested)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
