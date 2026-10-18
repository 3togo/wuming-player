package com.wuming.tv.core.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderJarRecoveryPolicyTest {
    private val md5 = "a".repeat(32)
    private val sha256 = "b".repeat(64)

    @Test
    fun candidateMustMatchItsConfiguredDigest() {
        assertTrue(ProviderJarRecoveryPolicy.matchesExpected(null, md5, sha256))
        assertTrue(ProviderJarRecoveryPolicy.matchesExpected(SpiderDigest(SpiderDigestKind.SHA256, sha256.uppercase()), md5, sha256))
        assertFalse(ProviderJarRecoveryPolicy.matchesExpected(SpiderDigest(SpiderDigestKind.MD5, "c".repeat(32)), md5, sha256))
        assertFalse(ProviderJarRecoveryPolicy.matchesExpected(SpiderDigest(SpiderDigestKind.SHA256, "short"), md5, sha256))
    }

    @Test
    fun recoveryNeedsRecordedHashAndCurrentPin() {
        assertTrue(ProviderJarRecoveryPolicy.mayRecover(null, md5, sha256, sha256.uppercase()))
        assertFalse(ProviderJarRecoveryPolicy.mayRecover(null, md5, sha256, null))
        assertFalse(ProviderJarRecoveryPolicy.mayRecover(null, md5, sha256, "c".repeat(64)))
        assertFalse(ProviderJarRecoveryPolicy.mayRecover(SpiderDigest(SpiderDigestKind.MD5, "c".repeat(32)), md5, sha256, sha256))
    }
}
