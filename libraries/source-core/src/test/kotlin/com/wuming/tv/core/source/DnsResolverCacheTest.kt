package com.wuming.tv.core.source

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DnsResolverCacheTest {
    private val systemAddress = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)))
    private val dohAddress = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2)))

    @Test fun switchingResolverAndReturningToSystemInvalidatesAnswers() {
        var calls = 0
        val cache = DnsResolverCache({ calls++; systemAddress }, { fail("Unexpected fallback"); emptyList() })
        assertEquals(systemAddress, cache.lookup("channel.example"))
        cache.select { dohAddress }
        assertEquals(dohAddress, cache.lookup("channel.example"))
        cache.select(null)
        assertEquals(systemAddress, cache.lookup("channel.example"))
        assertEquals(2, calls)
    }

    @Test fun expiredAndEvictedAnswersAreResolvedAgain() {
        var time = 0L
        var calls = 0
        val cache = DnsResolverCache({ calls++; systemAddress }, { emptyList() }, { time }, 10, 1)
        cache.lookup("a.example")
        cache.lookup("a.example")
        assertEquals(1, calls)
        time = 10
        cache.lookup("a.example")
        assertEquals(2, calls)
        cache.lookup("b.example")
        cache.lookup("a.example")
        assertEquals(4, calls)
    }

    @Test fun failuresAndEmptyAnswersAreNotCached() {
        var calls = 0
        val cache = DnsResolverCache({ calls++; emptyList() }, { throw UnknownHostException("offline") })
        repeat(2) {
            val error = assertThrows(UnknownHostException::class.java) { cache.lookup("a.example") }
            assertEquals(1, error.suppressed.size)
        }
        assertEquals(2, calls)
    }

    @Test fun fallbackRemainsAvailableAndItsAnswerExpires() {
        var time = 0L
        var calls = 0
        val cache = DnsResolverCache({ throw UnknownHostException("offline") },
            { calls++; dohAddress }, { time }, 10)
        assertEquals(dohAddress, cache.lookup("a.example"))
        assertEquals(dohAddress, cache.lookup("a.example"))
        assertEquals(1, calls)
        time = 10
        cache.lookup("a.example")
        assertEquals(2, calls)
    }

    @Test fun callerCannotMutateCachedAnswers() {
        val cache = DnsResolverCache({ systemAddress }, { emptyList() })
        val answer = cache.lookup("a.example")
        if (answer is MutableList<InetAddress>) runCatching { answer.clear() }
        assertEquals(systemAddress, cache.lookup("a.example"))
    }

    @Test fun resolverSwitchDiscardsAnInflightOldAnswer() = race { it.select { dohAddress } }

    @Test fun clearDiscardsAnInflightAnswer() = race { it.clear() }

    private fun race(change: (DnsResolverCache) -> Unit) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val cache = DnsResolverCache({
            calls++
            if (calls == 1) {
                started.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                systemAddress
            } else dohAddress
        }, { emptyList() })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<List<InetAddress>> { cache.lookup("channel.example") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            change(cache)
            release.countDown()
            assertEquals(dohAddress, future.get(2, TimeUnit.SECONDS))
            assertEquals(dohAddress, cache.lookup("channel.example"))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
