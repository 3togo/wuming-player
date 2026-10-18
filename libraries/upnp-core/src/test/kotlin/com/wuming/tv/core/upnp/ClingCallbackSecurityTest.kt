package com.wuming.tv.core.upnp

import org.fourthline.cling.model.message.header.CallbackAddressPolicy
import org.fourthline.cling.model.message.header.CallbackHeader
import org.fourthline.cling.model.message.header.InvalidHeaderException
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class ClingCallbackSecurityTest {
    private val source = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))

    @Test fun callbackCanOnlyNotifyTheSubscribingAddress() {
        fun allows(url: String) = CallbackAddressPolicy.allows(listOf(URL(url)), source)
        assertTrue(allows("http://192.168.1.20:8989/events"))
        assertFalse(allows("http://192.168.1.21/events"))
        assertFalse(allows("http://127.0.0.1/events"))
        assertFalse(allows("http://localhost/events"))
        assertFalse(allows("http://user@192.168.1.20/events"))
        assertFalse(allows("http://192.168.1.20:0/events"))
        assertFalse(allows("http://192.168.1.20:65536/events"))
        assertFalse(allows("https://192.168.1.20/events"))
        assertFalse(CallbackAddressPolicy.allows(listOf(URL("http://192.168.1.20/events")), null))
    }

    @Test fun boundsUntrustedCallbackLists() {
        val url = "<http://192.168.1.20/events>"
        val header = CallbackHeader()
        header.setString(url.repeat(4))
        assertEquals(4, header.value.size)
        assertThrows(InvalidHeaderException::class.java) { header.setString(url.repeat(5)) }
        assertThrows(InvalidHeaderException::class.java) { header.setString("x".repeat(4097)) }
    }
}
