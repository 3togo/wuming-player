package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedProviderNetworkPolicyTest {

    @Test
    fun `bypasses only the verified NewCz detail hosts`() {
        assertEquals(
            listOf("www.4kcz.com", "4kcz.com", "t02.cz01.org"),
            GuardedProviderNetworkPolicy.nonProxyHosts("com.github.catvod.spider.NewCz"),
        )
        assertEquals(
            listOf("www.libvio.io", "libvio.io", "www.libvio.pw", "libvio.pw"),
            GuardedProviderNetworkPolicy.nonProxyHosts("com.github.catvod.spider.Libvio"),
        )
        assertTrue(GuardedProviderNetworkPolicy.nonProxyHosts("com.github.catvod.spider.Jpys").isEmpty())
    }
}
