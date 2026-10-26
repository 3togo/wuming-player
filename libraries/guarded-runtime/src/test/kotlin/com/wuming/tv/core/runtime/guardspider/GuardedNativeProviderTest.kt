package com.wuming.tv.core.runtime.guardspider

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test

class GuardedNativeProviderTest {
    @Test fun missingProviderKeepsPublicNativeExecutionDisabled() = withProvider(null) {
        assertNull(GuardedNativeProviders.load())
        val error = runCatching {
            GuardedNativeCryptoRuntime(GuardedSpiderAbi.ARMEABI_V7A, Path.of("library"), Path.of("guard"), Path.of("data"))
        }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        assertEquals(GuardedNativeCryptoRuntime.REASON, error?.message)
    }

    @Test fun editionProviderOwnsHostAndCryptoExecution() = withProvider(FixtureNativeProvider::class.java.name) {
        FixtureNativeProvider.calls.clear()
        GuardedSpiderNativeHostMain.main(arrayOf("abi", "library", "guard", "data", "decrypt"))
        GuardedNativeCryptoRuntime(GuardedSpiderAbi.ARMEABI_V7A, Path.of("library"), Path.of("guard"), Path.of("data")).use {
            assertEquals("decoded", it.callString("decrypt", "ciphertext"))
            assertArrayEquals(intArrayOf(3, 2, 1), it.callIntArray("calculate", intArrayOf(1, 2, 3)))
        }
        assertEquals(listOf("host:decrypt", "open:armeabi-v7a", "string:decrypt:ciphertext", "array:calculate", "close"),
            FixtureNativeProvider.calls)
    }

    private fun withProvider(provider: String?, action: () -> Unit) {
        val directory = Files.createTempDirectory("native-provider-test-")
        val service = "META-INF/services/${GuardedNativeProvider::class.java.name}"
        val path = directory.resolve(service)
        if (provider != null) {
            Files.createDirectories(path.parent)
            Files.writeString(path, "$provider\n")
        }
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = object : ClassLoader(previous) {
            override fun getResources(name: String): java.util.Enumeration<java.net.URL> =
                if (name == service) Collections.enumeration(if (provider == null) emptyList() else listOf(path.toUri().toURL()))
                else super.getResources(name)
        }
        try { action() } finally {
            thread.contextClassLoader = previous
            directory.toFile().deleteRecursively()
        }
    }
}

class FixtureNativeProvider : GuardedNativeProvider {
    override fun runHost(arguments: Array<String?>) { calls += "host:${arguments.last()}" }
    override fun open(abi: GuardedSpiderAbi, library: Path, guard: Path, dataDirectory: Path): GuardedNativeSession {
        calls += "open:${abi.configName}"
        return object : GuardedNativeSession {
            override fun callString(signature: String, vararg arguments: Any?): String {
                calls += "string:$signature:${arguments.single()}"
                return "decoded"
            }
            override fun callIntArray(signature: String, value: IntArray): IntArray {
                calls += "array:$signature"
                return value.reversedArray()
            }
            override fun close() { calls += "close" }
        }
    }
    companion object { val calls = mutableListOf<String>() }
}
