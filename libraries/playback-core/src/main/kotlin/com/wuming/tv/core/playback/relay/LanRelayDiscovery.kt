package com.wuming.tv.core.playback.relay

import com.google.gson.Gson
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

data class DiscoveredRelay(val address: String, val service: RelayAdvertisement)

/** Active LAN discovery: advertisements never carry a playlist or bearer token. */
object LanRelayDiscovery {
    fun find(clientId: String, timeoutMillis: Int = 900): List<DiscoveredRelay> {
        require(LanRelayProtocol.validId(clientId))
        val nonce = LanRelayProtocol.secret()
        val bytes = "${LanRelayProtocol.DISCOVER}|$nonce|$clientId".toByteArray()
        val found = LinkedHashMap<String, DiscoveredRelay>()
        DatagramSocket().use { socket ->
            socket.broadcast = true; socket.soTimeout = 150
            val destinations = mutableSetOf(InetAddress.getByName("255.255.255.255"))
            runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach(destinations::add) }
            destinations.forEach { address -> runCatching { socket.send(DatagramPacket(bytes, bytes.size, address, LanRelayProtocol.DISCOVERY_PORT)) } }
            val deadline = System.nanoTime() + timeoutMillis * 1000000L
            while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                try {
                    socket.receive(packet)
                    if (!LanRelayProtocol.local(packet.address)) continue
                    val advertisement = Gson().fromJson(String(packet.data, 0, packet.length), RelayAdvertisement::class.java)
                    if (advertisement.nonce != nonce || !LanRelayProtocol.validId(advertisement.id) ||
                        advertisement.port !in 1..65535 || advertisement.name.length > 80) continue
                    found[advertisement.id] = DiscoveredRelay("http://${packet.address.hostAddress}:${advertisement.port}", advertisement)
                } catch (_: java.net.SocketTimeoutException) { } catch (_: Exception) { }
            }
        }
        return found.values.toList()
    }
}
