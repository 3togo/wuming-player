package com.wuming.tv.desktop.cast

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.meta.*
import org.fourthline.cling.model.types.*
import java.net.URI
import java.net.http.*
import java.util.UUID

class UpnpTransportIntegrationTest {
    @Test fun desktopServerServesDescriptorAndDispatchesSoap() {
        // Skip in CI/headless environments where network interfaces may be limited
        assumeTrue("Skip UPnP integration test in headless environment", !java.awt.GraphicsEnvironment.isHeadless())
        val config = DesktopUpnpConfiguration()
        val upnp = UpnpServiceImpl(config)
        try {
            assertTrue("A LAN interface is required for this integration test", upnp.router.isEnabled)
            val player = object : CastPlayer {
                var state = "STOPPED"
                override fun load(uri: String) {}
                override fun play() { state = "PLAYING" }
                override fun pause() { state = "PAUSED_PLAYBACK" }
                override fun stop() { state = "STOPPED" }
                override fun seek(seconds: Double) {}
                override fun volume(volume: Int) {}
                override fun mute(mute: Boolean) {}
                override fun snapshot() = CastPlayer.Snapshot(state, 0.0, 10.0, 50, false)
                override fun close() {}
            }
            val transport = DesktopAvTransport(player)
            @Suppress("UNCHECKED_CAST")
            val service = AnnotationLocalServiceBinder().read(DesktopAvTransport::class.java) as LocalService<DesktopAvTransport>
            service.manager = object : DefaultServiceManager<DesktopAvTransport>(service) {
                override fun createServiceInstance() = transport
            }
            val device = LocalDevice(DeviceIdentity(UDN(UUID.randomUUID())), UDADeviceType("MediaRenderer"),
                DeviceDetails("TVBox protocol test"), arrayOf(service))
            upnp.registry.addDevice(device)
            val address = upnp.router.getActiveStreamServers(null).first { it.address.address.size == 4 }
            val base = "http://${address.address.hostAddress}:${address.port}"
            val client = HttpClient.newBuilder().proxy(object : java.net.ProxySelector() {
                override fun select(uri: URI) = listOf(java.net.Proxy.NO_PROXY)
                override fun connectFailed(uri: URI, address: java.net.SocketAddress, error: java.io.IOException) {}
            }).build()
            fun get(path: String) = client.send(HttpRequest.newBuilder(URI(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString())
            val descriptor = get(config.namespace.getDescriptorPath(device).toString())
            assertEquals(200, descriptor.statusCode())
            assertTrue(descriptor.body().contains("MediaRenderer"))
            fun action(name: String, values: String): HttpResponse<String> {
                val body = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$name xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>$values</u:$name></s:Body></s:Envelope>"""
                val request = HttpRequest.newBuilder(URI(base + config.namespace.getControlPath(service)))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$name\"")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                return client.send(request, HttpResponse.BodyHandlers.ofString())
            }
            assertEquals(200, action("SetAVTransportURI", "<CurrentURI>http://127.0.0.1/video.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>").statusCode())
            assertEquals(200, action("Play", "<Speed>1</Speed>").statusCode())
            assertTrue(action("GetTransportInfo", "").body().contains("PLAYING"))
            assertEquals(200, action("Stop", "").statusCode())
            val invalid = action("SetAVTransportURI", "<CurrentURI>file:///etc/passwd</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
            assertEquals(500, invalid.statusCode())
            assertTrue(invalid.body().contains("716"))
            val discovered = java.util.concurrent.CopyOnWriteArrayList<DesktopCastService.Target>()
            DesktopCastService { targets -> discovered.clear(); discovered.addAll(targets) }.use { sender ->
                sender.discover()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8)
                while (discovered.none { it.id == device.identity.udn.toString() } && System.nanoTime() < deadline) Thread.sleep(50)
                val target = discovered.firstOrNull { it.id == device.identity.udn.toString() }
                assertNotNull("Sender must discover the test renderer via SSDP", target)
                sender.send(target, "http://127.0.0.1/video.mp4")
                assertEquals("PLAYING", player.state)
                sender.control("Pause")
                assertEquals("PAUSED_PLAYBACK", player.state)
                sender.disconnect()
                assertEquals("STOPPED", player.state)
            }
        } finally { upnp.shutdown() }
    }
}
