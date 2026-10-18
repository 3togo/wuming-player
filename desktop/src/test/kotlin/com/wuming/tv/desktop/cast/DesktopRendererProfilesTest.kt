package com.wuming.tv.desktop.cast

import com.wuming.tv.core.playback.renderer.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DesktopRendererProfilesTest {
    @Test fun pairingGuidanceUsesSharedReceiverIdentification() {
        fun receiver(description: String) = org.fourthline.cling.model.meta.RemoteDevice(
            org.fourthline.cling.model.meta.RemoteDeviceIdentity(org.fourthline.cling.model.types.UDN("3f35ac2d-cbb2-3900-a78b-2380956508fa"),
                1800, java.net.URI("http://127.0.0.1:9/description").toURL(), null, java.net.InetAddress.getLoopbackAddress()),
            org.fourthline.cling.model.types.UDADeviceType("MediaRenderer", 1),
            org.fourthline.cling.model.meta.DeviceDetails("Any renamed receiver", org.fourthline.cling.model.meta.ManufacturerDetails("Skyworth"),
                org.fourthline.cling.model.meta.ModelDetails("7AA49_P3D", description, "1.0")),
            emptyArray<org.fourthline.cling.model.meta.RemoteService>())
        val denied = org.fourthline.cling.model.message.UpnpResponse(403, "Forbidden")
        assertTrue(DesktopRendererProfiles.queryFailureMessage(receiver(RendererPairingHint.MODEL_DESCRIPTION), denied, 501)
            .contains("Settings > DLNA pairing"))
        assertFalse(DesktopRendererProfiles.queryFailureMessage(receiver("DLNA Renderer"), denied, 501).contains("QR pairing"))
        assertTrue(DesktopRendererProfiles.queryFailureMessage(receiver("DLNA Renderer"),
            org.fourthline.cling.model.message.UpnpResponse(403, "QR pairing required"), 501).contains("Settings > DLNA pairing"))
        assertFalse(DesktopRendererProfiles.queryFailureMessage(receiver(RendererPairingHint.MODEL_DESCRIPTION), null, 501).contains("QR pairing"))
    }
    @Test fun configRoundTripsAndResetIsDurable() {
        val directory = Files.createTempDirectory("renderer-test")
        val file = directory.resolve("renderer-capabilities.json")
        try {
            val tv = RendererIdentity(RendererIdentity.key("id"), "TV", "vendor", "model", "unknown", "AVTransport")
            DesktopRendererProfiles.open(file).override(tv, RendererCapability.MP4, CapabilityState.SUPPORTED)
            val reopened = DesktopRendererProfiles.open(file)
            assertEquals(CapabilityState.SUPPORTED, reopened.state(tv, RendererCapability.MP4))
            reopened.advertised(tv, RendererAdvertisements.parse("http-get:*:video/mp4:*", setOf("Seek")), "desktop-test")
            assertTrue(DesktopRendererProfiles.open(file).export(tv).contains("\"advertised\": true"))
            assertEquals(CapabilityState.UNKNOWN, reopened.state(tv, RendererCapability.SEEK))
            reopened.advertisedActions(tv, setOf("Stop"), "desktop-test")
            val afterDeniedQuery = com.google.gson.JsonParser.parseString(DesktopRendererProfiles.open(file).export(tv))
                .asJsonObject.getAsJsonObject("assessments")
            assertTrue(afterDeniedQuery.getAsJsonObject("MP4").get("advertised").asBoolean)
            assertTrue(afterDeniedQuery.getAsJsonObject("STOP").get("advertised").asBoolean)
            assertFalse(afterDeniedQuery.getAsJsonObject("SEEK").get("advertised").asBoolean)
            reopened.reset(tv.deviceKey)
            assertEquals(CapabilityState.UNKNOWN, DesktopRendererProfiles.open(file).state(tv, RendererCapability.MP4))
            Files.writeString(file, "{\"schemaVersion\":2,\"profiles\":[]}")
            try { DesktopRendererProfiles.open(file); fail() } catch (_: Exception) {}
            assertTrue(Files.readString(file).contains("\"schemaVersion\":2"))
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }
}
