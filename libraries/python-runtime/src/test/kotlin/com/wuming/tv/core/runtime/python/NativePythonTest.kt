package com.wuming.tv.core.runtime.python

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors

class NativePythonTest {
    @get:Rule val temporary = TemporaryFolder()
    private val runtime = File(System.getProperty("wuming.python.runtime"))
    private val version = com.google.gson.JsonParser.parseString(File(runtime, "runtime.json").readText()).asJsonObject.get("python").asString
    private val home get() = File(runtime, "home")
    private val bridge get() = File(home, "bridge")
    private val packages get() = File(home, "lib/python$version/site-packages")
    private val library get() = File(runtime, "lib/libwuming_python.so")
    private fun start() = NativePythonBridge.start(home, listOf(bridge, packages), library)
    private fun provider(key: String, code: String) = PythonProvider(PythonSourceSpec(key, key, "https://example.invalid/spider.py", providerKey = "raw", storageIdentity = key), temporary.root, ::start, { code.toByteArray() })
    private val code = """
        from base.spider import Spider as Base
        class Spider(Base):
            def init(self, extend=''):
                self.count = 0
            def homeContent(self, filter):
                self.count += 1
                return {'list':[{'vod_id':'一', 'vod_name':self.siteKey + '😀' + str(self.count)}]}
            def localProxy(self, params):
                return [206, 'application/octet-stream', bytes([0, 128, 255]), {'X-Test':'yes'}]
            def action(self, value):
                if value == 'fail': raise ValueError('expected failure')
                if value == 'exit': raise SystemExit(7)
                if value == 'packages':
                    import requests, cachetools, bs4, pyquery
                    from lxml import etree
                    from Crypto.Cipher import AES
                    return {'xml':etree.fromstring(b'<x>ok</x>').text, 'aes':len(AES.new(b'0'*16,AES.MODE_ECB).encrypt(b'1'*16))}
                return {'value':value}
    """.trimIndent()
    @Test fun realJniPreservesIdentityUnicodeIsolationAndBinaryProxy() {
        provider("package-a", code).use { a -> provider("package-b", code).use { b ->
            assertTrue(a.call("homeContent", true).toString().contains("raw😀1"))
            assertTrue(b.call("homeContent", true).toString().contains("raw😀1"))
            assertTrue(a.call("homeContent", true).toString().contains("raw😀2"))
            val response = a.call("localProxy", emptyMap<String,String>()).asJsonObject
            assertArrayEquals(byteArrayOf(0, -128, -1), Base64.getDecoder().decode(response.get("body").asString))
            assertEquals(16, a.call("action", "packages").asJsonObject.get("aes").asInt)
            assertThrows(IllegalStateException::class.java) { a.call("action", "fail") }
            assertThrows(IllegalStateException::class.java) { a.call("action", "exit") }
            assertEquals("still alive", a.call("action", "still alive").asJsonObject.get("value").asString)
        } }
    }
    @Test fun differentProvidersCanOverlapBlockingPythonCalls() {
        val code = """
            from base.spider import Spider as Base
            import wuming_bridge, threading
            class Spider(Base):
                def action(self, value):
                    if value == 'prepare':
                        wuming_bridge._test_barrier = threading.Barrier(2)
                        return True
                    wuming_bridge._test_barrier.wait(timeout=5)
                    return 'ready'
        """.trimIndent()
        provider("parallel-a", code).use { a -> provider("parallel-b", code).use { b ->
            a.call("action", "prepare")
            val workers = Executors.newFixedThreadPool(2)
            try {
                val first = workers.submit<String> { a.call("action", "wait").asString }
                val second = workers.submit<String> { b.call("action", "wait").asString }
                assertEquals("ready", first.get())
                assertEquals("ready", second.get())
            } finally { workers.shutdownNow() }
        } }
    }

    @Test fun legacyProvidersWithoutDestroyAndWithoutSearchPageStillWork() {
        val legacy = """
            class Spider:
                def init(self, ext=''): pass
                def searchContent(self, key, quick): return {'list': [key + str(quick)]}
                def getName(self): return 'legacy'
        """.trimIndent()
        val provider = provider("legacy", legacy)
        assertEquals("legacy", provider.call("getName").asString)
        assertEquals("queryFalse", provider.call("searchContent", "query", false, "2").asJsonObject.getAsJsonArray("list").get(0).asString)
        // Teardown must tolerate providers that predated destroy().
        provider.close()
        assertThrows(IllegalStateException::class.java) { provider.call("getName") }
    }

    /** Only meaningful before another test starts the shared process interpreter. */
    @Test fun dispatcherImportFailureIsRetryable() {
        assumeTrue(runCatching { NativePythonBridge.request(mapOf("op" to "close", "handle" to "absent")) }.isFailure)
        // Starting without the bridge directory leaves CPython initialized but unusable.
        assertThrows(IllegalStateException::class.java) { NativePythonBridge.start(home, listOf(packages), library) }
        NativePythonBridge.start(home, listOf(bridge, packages), library)
        val handle = NativePythonBridge.request(mapOf("op" to "open", "api" to "https://example.invalid/a.py",
            "source" to "class Spider:\n def init(self,ext=''): pass\n def action(self,v): return {'ok': v}\n",
            "directory" to File(temporary.root, "retry").absolutePath, "providerKey" to "raw")).asString
        val value = NativePythonBridge.request(mapOf("op" to "call", "handle" to handle, "method" to "action",
            "args" to listOf("yes"))).asJsonObject
        assertEquals("yes", value.get("ok").asString)
    }

    @Test fun jniCallsCanMoveBetweenJvmThreadsAndClosedProviderCannotRestart() {
        val provider = provider("threaded", code)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = (1..6).map { pool.submit<String> { provider.call("homeContent", true).toString() } }.map { it.get() }
            assertEquals(6, results.toSet().size)
        } finally { pool.shutdownNow(); provider.close() }
        assertThrows(IllegalStateException::class.java) { provider.call("homeContent", true) }
        provider.close()
    }
}
