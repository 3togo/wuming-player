package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCredentialPoliciesTest {

    @Test
    fun loginProfileAcceptsUnreservedCredentials() {
        assertTrue(CredentialShapePolicy.isLoginValue("abcdefgh"))
        assertTrue(CredentialShapePolicy.isLoginValue("A1_b-c.d~e"))
        assertFalse(CredentialShapePolicy.isLoginValue("short"))
        assertFalse(CredentialShapePolicy.isLoginValue(null))
    }

    @Test
    fun loginProfileRejectsSeparatorsAndOversizedValues() {
        assertFalse(CredentialShapePolicy.isLoginValue("bad value"))
        assertFalse(CredentialShapePolicy.isLoginValue("bad&value"))
        assertFalse(CredentialShapePolicy.isLoginValue("bad\nvalue"))
        assertFalse(CredentialShapePolicy.isLoginValue("x".repeat(2049)))
    }

    @Test
    fun tokenProfileNeedsACompleteCapture() {
        assertTrue(CredentialShapePolicy.isOpaqueToken("t".repeat(40)))
        assertTrue(CredentialShapePolicy.isOpaqueToken("t".repeat(8192)))
        assertFalse(CredentialShapePolicy.isOpaqueToken("t".repeat(39)))
        assertFalse(CredentialShapePolicy.isOpaqueToken("t".repeat(8193)))
        assertFalse(CredentialShapePolicy.isOpaqueToken("t".repeat(41).substring(0, 40) + "!"))
    }

    @Test
    fun passcodeProfileStaysAlphanumericAndShort() {
        assertTrue(CredentialShapePolicy.isSharePasscode("A1b2"))
        assertTrue(CredentialShapePolicy.isSharePasscode("a".repeat(16)))
        assertFalse(CredentialShapePolicy.isSharePasscode(""))
        assertFalse(CredentialShapePolicy.isSharePasscode("a".repeat(17)))
        assertFalse(CredentialShapePolicy.isSharePasscode("bad-value"))
    }

    @Test
    fun bareHttpsOriginRejectsLookAlikeAuthorities() {
        assertEquals("pan.quark.cn", FirstPartyOriginPolicy.httpsHost("https://pan.quark.cn/s/abcd1234"))
        assertEquals("pan.quark.cn", FirstPartyOriginPolicy.httpsHost("HTTPS://PAN.QUARK.CN/s/x"))
        assertNull(FirstPartyOriginPolicy.httpsHost("http://pan.quark.cn/s/x"))
        assertNull(FirstPartyOriginPolicy.httpsHost("https://pan.quark.cn:8443/s/x"))
        assertNull(FirstPartyOriginPolicy.httpsHost("https://user@pan.quark.cn/s/x"))
        assertNull(FirstPartyOriginPolicy.httpsHost("pan.quark.cn/s/x"))
        assertNull(FirstPartyOriginPolicy.httpsHost("not a url"))
        assertNull(FirstPartyOriginPolicy.httpsHost(null))
    }

    @Test
    fun lookAlikeHostIsReturnedForTheCallerToJudge() {
        val attacker = "https://attacker.example/?next=https://pan.quark.cn/s/x"
        assertEquals("attacker.example", FirstPartyOriginPolicy.httpsHost(attacker))
        assertFalse(FirstPartyOriginPolicy.isWithinDomain(FirstPartyOriginPolicy.httpsHost(attacker), "pan.quark.cn", true))
    }

    @Test
    fun lengthCapAppliesToTheRawValueBeforeTrimming() {
        assertNull(FirstPartyOriginPolicy.httpsUri("https://a.example/" + "x".repeat(4096), 2048, true))
        assertEquals("a.example", FirstPartyOriginPolicy.httpsUri("  https://a.example/s/x  ", 2048, true)!!.host)
        assertNull(FirstPartyOriginPolicy.httpsUri(" https://a.example/s/x", 2048, false))
    }

    @Test
    fun malformedLinksYieldNullInsteadOfThrowing() {
        assertNull(FirstPartyOriginPolicy.httpsUri("https://ex ample/s/x", 2048, true))
        assertNull(FirstPartyOriginPolicy.httpsUri("https://]", 2048, true))
    }

    @Test
    fun domainMatchNeedsSubdomainOptIn() {
        assertTrue(FirstPartyOriginPolicy.isWithinDomain("alipan.com", "alipan.com", false))
        assertTrue(FirstPartyOriginPolicy.isWithinDomain("www.alipan.com", "alipan.com", true))
        assertFalse(FirstPartyOriginPolicy.isWithinDomain("www.alipan.com", "alipan.com", false))
        assertFalse(FirstPartyOriginPolicy.isWithinDomain("alipan.com.attacker.example", "alipan.com", true))
        assertFalse(FirstPartyOriginPolicy.isWithinDomain("nullalipan.com", "alipan.com", true))
        assertFalse(FirstPartyOriginPolicy.isWithinDomain(null, "alipan.com", true))
    }
}
