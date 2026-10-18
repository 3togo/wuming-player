package com.wuming.tv.core.playback.relay

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class LanRelayProtocolTest {
    @Test fun pairingIsOneUseAndExpires() {
        var now = 1000L
        val gate = RelayPairingGate(clock = { now })
        val code = gate.open()
        val token = gate.claim("client-123", code)!!
        assertTrue(gate.authorized(token))
        assertNull(gate.claim("client-456", code))
        val expired = gate.open(); now += 120000
        assertNull(gate.claim("client-456", expired))
        gate.clear(); assertFalse(gate.authorized(token))
    }
    @Test fun fiveBadAttemptsClosePairing() {
        val gate = RelayPairingGate(); val code = gate.open()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(5) { assertNull(gate.claim("client-123", wrong)) }
        assertNull(gate.claim("client-123", code))
    }
    @Test fun discoveryProofBindsIdentityNonceAndPort() {
        val token = LanRelayProtocol.secret(); val nonce = LanRelayProtocol.secret()
        val proof = LanRelayProtocol.proof(token, nonce, "desktop-123", 8000)
        assertTrue(LanRelayProtocol.equal(proof, LanRelayProtocol.proof(token, nonce, "desktop-123", 8000)))
        assertFalse(LanRelayProtocol.equal(proof, LanRelayProtocol.proof(token, nonce, "desktop-123", 8001)))
        assertFalse(LanRelayProtocol.equal(proof, LanRelayProtocol.proof(token, LanRelayProtocol.secret(), "desktop-123", 8000)))
        assertFalse(LanRelayProtocol.equal(proof, LanRelayProtocol.proof(token, nonce, "desktop-456", 8000)))
    }
    @Test fun credentialsStayAtOrigin() {
        val origin = URI("https://a.example/master.m3u8")
        val headers = mapOf("Cookie" to "private", "Authorization" to "secret", "User-Agent" to "tv", "Host" to "evil", "Referer" to "https://a.example/")
        assertEquals(4, LanRelayProtocol.scopedHeaders(headers, origin, origin.resolve("part.ts")).size)
        assertEquals(4, LanRelayProtocol.scopedHeaders(headers, origin, URI("https://a.example:443/part.ts")).size)
        assertEquals(setOf("User-Agent", "Referer"), LanRelayProtocol.scopedHeaders(headers, origin, URI("https://a.example:8443/part.ts")).keys)
        assertEquals(setOf("User-Agent", "Referer"), LanRelayProtocol.scopedHeaders(headers, origin, URI("https://b.example/part.ts")).keys)
    }
    @Test fun fallbackPreservesHeadersAndConsumesOnce() {
        val memory = RelayRouteMemory(); val headers = mutableMapOf("Cookie" to "original")
        memory.remember("relay", RelayOriginal("direct", headers, "hls")); headers["Cookie"] = "changed"
        assertEquals("original", memory.fallback("relay")!!.headers["Cookie"])
        assertNull(memory.fallback("relay"))
    }
    @Test fun rejectsRelativeAndCredentialUrls() {
        for (url in listOf("relative.m3u8", "file:///tmp/video", "https://user:pass@example.org/", "http://example.org/\n")) {
            try { LanRelayProtocol.upstream(url); fail("Accepted invalid URL") } catch (_: IllegalArgumentException) { }
        }
    }
}
