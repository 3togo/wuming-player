package com.wuming.tv.core.upnp

import org.junit.Assert.*
import org.junit.Test

class RemoteAccessWorkflowTest {
    private class Memory : RemoteAccessPolicy.Store {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String) { values[key] = value }
    }
    @Test fun pairingAuthorizesProtectedRoutesAndRotationRevokesOldToken() {
        val memory = Memory()
        val policy = RemoteAccessPolicy(memory) { true }
        policy.openPairingWindow()
        val code = policy.secureWebAddress("http://fixture", 0).substringAfter("#pair=")
        val token = policy.claimPairing(code, "peer")
        assertEquals(64, token.length)
        assertTrue(policy.allow("192.0.2.1", "/desktop/v1/search", false, "Bearer $token"))
        assertFalse(policy.allow("192.0.2.1", "/desktop/v1/search", false, null))
        policy.rotateServerToken()
        assertFalse(policy.allow("192.0.2.1", "/desktop/v1/search", false, "Bearer $token"))
    }
    @Test fun rememberedCredentialsAreBoundToOriginAndRoleGatesAdministration() {
        val policy = RemoteAccessPolicy(Memory()) { false }
        val token = "a".repeat(64)
        policy.rememberPairing("http://HOST:9978/desktop", token)
        assertEquals("Bearer $token", policy.authorizationFor("http://host:9978/action"))
        assertNull(policy.authorizationFor("http://host:9979/action"))
        assertNull(policy.authorizationFor("https://host:9978/action"))
        assertTrue(policy.allow("::1", "/file", false, null))
        assertTrue(policy.allow("192.0.2.1", "/m3u8", false, null))
        assertTrue(policy.allow("192.0.2.1", "/media", true, null))
        assertFalse(policy.allow("192.0.2.1", "/file", false, "Bearer $token"))
    }
}
