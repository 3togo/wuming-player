package com.github.catvod.net

import com.github.catvod.bean.Doh
import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test

class OkDnsTest {
    private val systemAnswer = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)))
    private val dohAnswer = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 2)))
    private fun resolver(factory: (Doh) -> Dns = { Dns { dohAnswer } }) =
        OkDns(Dns { systemAnswer }, { Dns { throw AssertionError("Unexpected fallback") } }, factory)

    @Test fun systemSelectionActuallyRemovesDohWithoutRestart() {
        val dns = resolver()
        assertEquals(systemAnswer, dns.lookup("channel.example"))
        dns.setDoh(Doh().url("https://resolver.example/dns-query"))
        assertEquals(dohAnswer, dns.lookup("channel.example"))
        dns.setDoh(Doh())
        assertEquals(systemAnswer, dns.lookup("channel.example"))
    }

    @Test fun invalidResolverDoesNotReplaceWorkingSelection() {
        val dns = resolver { item ->
            require(item.getUrl().startsWith("https://"))
            Dns { dohAnswer }
        }
        dns.setDoh(Doh().url("https://resolver.example/dns-query"))
        assertThrows(IllegalArgumentException::class.java) { dns.setDoh(Doh().url("invalid")) }
        assertEquals(dohAnswer, dns.lookup("channel.example"))
    }

    @Test fun literalOverridesAndIpv6SurviveResolverChanges() {
        val dns = resolver()
        dns.addAll(mutableListOf("channel.example=192.0.2.3", null, "invalid", "=empty"))
        dns.setDoh(Doh().url("https://resolver.example/dns-query"))
        assertEquals("192.0.2.3", dns.lookup("channel.example").single().hostAddress)
        assertEquals(16, dns.lookup("2001:db8::1").single().address.size)
        dns.clear()
        assertEquals(dohAnswer, dns.lookup("channel.example"))
    }

    @Test fun internationalHostnamesUseAsciiForResolution() {
        var requested = ""
        val dns = OkDns(Dns { requested = it; systemAnswer }, { Dns.SYSTEM }, { Dns.SYSTEM })
        dns.lookup("例子.example")
        assertEquals("xn--fsqu00a.example", requested)
    }
}
