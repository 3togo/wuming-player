package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GitHubProxyProbeTest {

    /** A scripted transport that records probe calls and returns queued outcomes. */
    private class ScriptedTransport(private val script: List<GitHubProxyProbe.Outcome>) :
        GitHubProxyProbe.Transport {
        private val calls = mutableListOf<Pair<String, Boolean>>()
        private var index = 0
        override fun probe(url: String, withRange: Boolean): GitHubProxyProbe.Outcome {
            calls.add(url to withRange)
            return script[index++]
        }

        fun calls() = calls
    }

    @Test
    fun successWhenTwoHundredAndEnoughBytes() {
        val transport = ScriptedTransport(listOf(GitHubProxyProbe.Outcome(200, 4_096)))
        val result = GitHubProxyProbe.probeOne(transport, "https://mirror.example/x")
        assertTrue(result.success)
        assertEquals(200, result.status)
        assertEquals(4_096, result.contentBytes)
        assertEquals("https://mirror.example/x", transport.calls().single().first)
        assertTrue(transport.calls().single().second) // first probe uses Range
    }

    @Test
    fun retriesWithoutRangeOnFourHundred() {
        val transport = ScriptedTransport(
            listOf(
                GitHubProxyProbe.Outcome(400, 0),
                GitHubProxyProbe.Outcome(200, 2_000),
            ),
        )
        val result = GitHubProxyProbe.probeOne(transport, "https://mirror.example/x")
        assertTrue(result.success)
        assertEquals(2, transport.calls().size)
        assertEquals("https://mirror.example/x", transport.calls()[1].first)
        assertEquals(false, transport.calls()[1].second)
    }

    @Test
    fun truncatedWhenBodyTooSmall() {
        val transport = ScriptedTransport(listOf(GitHubProxyProbe.Outcome(200, 500)))
        val result = GitHubProxyProbe.probeOne(transport, "https://mirror.example/x")
        assertFalse(result.success)
        assertEquals("truncated", result.error)
    }

    @Test
    fun nonSuccessStatusReportedWithoutDownload() {
        val transport = ScriptedTransport(listOf(GitHubProxyProbe.Outcome(403, 0)))
        val result = GitHubProxyProbe.probeOne(transport, "https://mirror.example/x")
        assertFalse(result.success)
        assertEquals(403, result.status)
        assertNull(result.error)
    }

    @Test
    fun timeoutAndDnsClassified() {
        val timeout = object : GitHubProxyProbe.Transport {
            override fun probe(url: String, withRange: Boolean): GitHubProxyProbe.Outcome =
                throw SocketTimeoutException("read")
        }
        val dns = object : GitHubProxyProbe.Transport {
            override fun probe(url: String, withRange: Boolean): GitHubProxyProbe.Outcome =
                throw UnknownHostException("nope")
        }
        val other = object : GitHubProxyProbe.Transport {
            override fun probe(url: String, withRange: Boolean): GitHubProxyProbe.Outcome =
                throw IOException("boom")
        }
        assertEquals("timeout", GitHubProxyProbe.probeOne(timeout, "u").error)
        assertEquals("DNS fail", GitHubProxyProbe.probeOne(dns, "u").error)
        assertEquals("boom", GitHubProxyProbe.probeOne(other, "u").error)
    }

    @Test
    fun bestPicksFastestSuccessful() {
        val results = listOf(
            GitHubProxyProbe.Result(900, 200, 4_000, null),
            GitHubProxyProbe.Result(120, 200, 4_000, null),
            GitHubProxyProbe.Result(-1, 403, 0, null),
        )
        val best = GitHubProxyProbe.best(results)
        assertEquals(120, best?.elapsedMs)
    }

    @Test
    fun bestIsNullWhenAllFail() {
        val results = listOf(
            GitHubProxyProbe.Result(-1, 403, 0, null),
            GitHubProxyProbe.Result(50, 200, 10, "truncated"),
        )
        assertNull(GitHubProxyProbe.best(results))
    }
}
