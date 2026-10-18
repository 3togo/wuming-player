package com.wuming.tv.desktop.cast

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.playback.renderer.RendererProfileStore
import com.wuming.tv.core.playback.renderer.*
import com.wuming.tv.core.upnp.toRendererIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.nio.ByteBuffer

/** Desktop configuration port for the same renderer evidence and policy used by Android. */
object DesktopRendererProfiles {
    val default: RendererProfiles by lazy { open(defaultPath()) }
    fun defaultPath(): Path {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".config")
        return base.resolve("tvbox-desktop/renderer-capabilities.json")
    }
    private fun identity(device: org.fourthline.cling.model.meta.RemoteDevice): RendererIdentity =
        device.toRendererIdentity()
    fun remember(device: org.fourthline.cling.model.meta.RemoteDevice) {
        try { default.identify(identity(device)) } catch (error: Exception) { report(error) }
    }
    fun inspect(device: org.fourthline.cling.model.meta.RemoteDevice, control: org.fourthline.cling.controlpoint.ControlPoint) {
        val actions = device.findService(org.fourthline.cling.model.types.UDAServiceType("AVTransport"))
            ?.actions?.map { it.name }?.toSet().orEmpty()
        val service = device.findService(org.fourthline.cling.model.types.UDAServiceType("ConnectionManager"))
        val action = service?.getAction("GetProtocolInfo")
        fun save(sink: String) {
            try { default.advertised(identity(device), RendererAdvertisements.parse(sink, actions), "desktop-development") }
            catch (error: Exception) { report(error) }
        }
        // Action descriptions are independent of the format query, which some TVs deny.
        try { default.advertisedActions(identity(device), actions, "desktop-development") }
        catch (error: Exception) { report(error) }
        if (action == null) return
        val invocation = org.fourthline.cling.model.action.ActionInvocation<org.fourthline.cling.model.meta.RemoteService>(action)
        control.execute(object : org.fourthline.cling.controlpoint.ActionCallback(invocation) {
            override fun success(value: org.fourthline.cling.model.action.ActionInvocation<*>) {
                save(value.getOutput("Sink")?.value?.toString().orEmpty())
            }
            override fun failure(value: org.fourthline.cling.model.action.ActionInvocation<*>, response: org.fourthline.cling.model.message.UpnpResponse?, message: String?) {
                java.util.logging.Logger.getLogger("RendererProfiles").warning(queryFailureMessage(device, response, value.failure?.errorCode ?: -1))
            }
        })
    }
    internal fun queryFailureMessage(device: org.fourthline.cling.model.meta.RemoteDevice,
        response: org.fourthline.cling.model.message.UpnpResponse?, upnpCode: Int): String {
        val details = device.details
        return if (RendererPairingHint.requiresQrPairing(response?.statusCode ?: -1, response?.statusMessage,
                details.modelDetails?.modelDescription, details.manufacturerDetails?.manufacturer,
                details.modelDetails?.modelName, device.identity.udn.identifierString))
            "GetProtocolInfo requires QR pairing with 無名 on the TV. Open Settings > DLNA pairing on the TV and pair this sending device using the QR page, then retry."
        else "GetProtocolInfo failed: HTTP=${response?.statusCode ?: -1} UPnP=$upnpCode; saved evidence retained"
    }
    fun observation(device: org.fourthline.cling.model.meta.RemoteDevice, url: String): RendererPlaybackObservation {
        val identity = identity(device)
        return RendererPlaybackObservation(RendererPlaybackObservation.format(url),
            DesktopRendererProfiles::class.java.`package`.implementationVersion ?: "desktop-development", { evidence ->
            try { default.record(identity, evidence) } catch (error: Exception) { report(error) }
        })
    }
    private fun report(error: Exception) {
        java.util.logging.Logger.getLogger("RendererProfiles").warning("Profile storage unavailable: " + error.javaClass.simpleName)
    }

    fun open(path: Path): RendererProfiles = RendererProfiles(object : RendererProfileStore {
        override fun read(): String? {
            if (!Files.exists(path)) return null
            require(Files.size(path) <= 2_000_000) { "Renderer config too large" }
            return Files.readString(path)
        }
        override fun write(json: String) {
            AtomicFilePublisher.publish(path, "renderer-", ownerOnly = true) { temporary ->
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                    val bytes = ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        }
    })
}
