package com.wuming.tv.desktop.cast

import java.nio.file.Path
import java.util.*
import java.util.concurrent.*
import java.util.function.Consumer
import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.controlpoint.ActionCallback
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.action.ActionInvocation
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.model.message.header.UDADeviceTypeHeader
import org.fourthline.cling.model.meta.*
import org.fourthline.cling.model.types.*
import org.fourthline.cling.registry.*
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService
import org.fourthline.cling.support.contentdirectory.DIDLParser
import org.fourthline.cling.support.lastchange.LastChangeAwareServiceManager
import org.fourthline.cling.support.model.DIDLContent
import org.fourthline.cling.support.model.ProtocolInfo
import org.fourthline.cling.support.model.ProtocolInfos
import org.fourthline.cling.support.model.Res
import org.fourthline.cling.support.model.item.VideoItem
import org.fourthline.cling.support.renderingcontrol.lastchange.RenderingControlLastChangeParser

/** All user operations are called on the panel's serial worker, never on the Swing event thread.  */
class DesktopCastService(devicesChanged: Consumer<List<Target>>) : AutoCloseable {
    @JvmRecord
    data class Target(val id: String?, val name: String?, val transport: RemoteService?) {
        // Java record toString returned the name directly, including a caller-supplied null.
        @Suppress("UNCHECKED_CAST")
        private fun <T> javaValue(value: T?): T = value as T
        override fun toString(): String = javaValue(name)
    }

    private val devicesChanged: Consumer<List<Target>>
    private val targets: MutableMap<String, Target> = ConcurrentHashMap()
    private var upnp: UpnpServiceImpl? = null
    private var outgoingObservation: com.wuming.tv.core.playback.renderer.RendererPlaybackObservation? = null
    private var receiver: LocalDevice? = null
    private var transport: DesktopAvTransport? = null
    private var events: ScheduledExecutorService? = null
    private var active: Target? = null
    private var sharedFile: AutoCloseable? = null
    var compatibilityQualityLabel: String? = null
        private set
    private var closed = false

    init {
        this.devicesChanged = devicesChanged
    }

    private fun start() {
        check(!closed) { "Casting is closed" }
        if (upnp != null) return
        val started: UpnpServiceImpl = UpnpServiceImpl(DesktopUpnpConfiguration())
        try {
            check(started.getRouter().isEnabled()) { "No multicast-capable network is available" }
            started.getRegistry().addListener(object : DefaultRegistryListener() {
                override fun remoteDeviceAdded(registry: Registry?, device: RemoteDevice) {
                    found(device)
                }

                override fun remoteDeviceUpdated(registry: Registry?, device: RemoteDevice) {
                    found(device)
                }

                override fun remoteDeviceRemoved(registry: Registry?, device: RemoteDevice) {
                    targets.entries.removeIf { e ->
                        e.value.transport?.device?.root == device.root
                    }
                    publish()
                }
            })
            upnp = started
            receiver?.let { started.getRegistry().addDevice(it) }
        } catch (error: Exception) {
            if (upnp === started) upnp = null
            started.shutdown()
            throw IllegalStateException("Could not start LAN casting", error)
        }
    }

    private fun found(device: RemoteDevice) {
        val service: RemoteService? = device.findService(UDAServiceType("AVTransport"))
        if (service != null) {
            DesktopRendererProfiles.remember(device)
            val id: String = device.getIdentity().getUdn().toString()
            targets.put(id, Target(id, device.getDetails().getFriendlyName(), service))
            publish()
        }
    }

    private fun publish() {
        devicesChanged.accept(
            targets.values.stream().sorted(Comparator.comparing { it.name ?: "" }).toList()
        )
    }

    @Synchronized
    fun discover() {
        start()
        upnp?.getControlPoint()?.search(UDADeviceTypeHeader(UDADeviceType("MediaRenderer")), 3)
    }

    @SuppressWarnings("unchecked")
    @Synchronized
    @Throws(Exception::class)
    fun receive(mpv: Path?) {
        start()
        if (receiver != null) return
        val player: CastPlayer = MpvCastPlayer(mpv)
        try {
            transport = DesktopAvTransport(player)
            val rendering: DesktopRenderingControl = DesktopRenderingControl(player)
            val binder: AnnotationLocalServiceBinder = AnnotationLocalServiceBinder()
            val av: LocalService<DesktopAvTransport> = binder.read(
                DesktopAvTransport::class.java
            ) as LocalService<DesktopAvTransport>
            val current = transport ?: return
            val avManager: LastChangeAwareServiceManager<DesktopAvTransport> = object :
                LastChangeAwareServiceManager<DesktopAvTransport>(
                    av,
                    AVTransportLastChangeParser()
                ) {
                override fun createServiceInstance(): DesktopAvTransport = current
            }
            av.setManager(avManager)
            val rc: LocalService<DesktopRenderingControl> =
                binder.read(
                    DesktopRenderingControl::class.java
                ) as LocalService<DesktopRenderingControl>
            val rcManager: LastChangeAwareServiceManager<DesktopRenderingControl> = object :
                LastChangeAwareServiceManager<DesktopRenderingControl>(
                    rc,
                    RenderingControlLastChangeParser()
                ) {
                override fun createServiceInstance(): DesktopRenderingControl = rendering
            }
            rc.setManager(rcManager)
            val cm: LocalService<ConnectionManagerService> =
                binder.read(
                    ConnectionManagerService::class.java
                ) as LocalService<ConnectionManagerService>
            cm.setManager(object : DefaultServiceManager<ConnectionManagerService>(cm) {
                override fun createServiceInstance(): ConnectionManagerService =
                    ConnectionManagerService(
                        ProtocolInfos(),
                        ProtocolInfos(
                            com.wuming.tv.core.playback.renderer.RendererSinkProtocols.forFormats(setOf(
                                com.wuming.tv.core.playback.renderer.RendererCapability.MP4,
                                com.wuming.tv.core.playback.renderer.RendererCapability.HLS)) +
                                ",http-get:*:video/mpeg:*,http-get:*:video/x-matroska:*,http-get:*:video/mp2t:*"
                        )
                    )
            })
            val identity: String =
                System.getProperty("user.home") + "@" + java.net.InetAddress.getLocalHost()
                    .getHostName()
            receiver = LocalDevice(
                DeviceIdentity(
                    UDN(
                        UUID.nameUUIDFromBytes(
                            identity.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                        )
                    )
                ),
                UDADeviceType("MediaRenderer", 1),
                DeviceDetails(
                    "TVBox Desktop",
                    ManufacturerDetails("TVBox"),
                    ModelDetails("Desktop", "Linux video receiver", "1")
                ),
                arrayOf<LocalService<*>>(av, rc, cm)
            )
            upnp?.getRegistry()?.addDevice(receiver)
            events = Executors.newSingleThreadScheduledExecutor({ r ->
                val t: Thread = Thread(r, "tvbox-cast-events")
                t.setDaemon(true)
                t
            })
            events?.scheduleWithFixedDelay({
                try {
                    current.refresh()
                    rendering.refresh()
                    avManager.fireLastChange()
                    rcManager.fireLastChange()
                } catch (
                    ignored: Exception
                ) {
                    /* Remote subscribers may disconnect between publications. */
                }
            }, 0, 500, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            stopReceiving()
            player.close()
            throw error
        }
    }

    @Synchronized
    fun stopReceiving() {
        events?.shutdownNow()
        events = null
        val currentUpnp = upnp
        if (receiver != null && currentUpnp != null) currentUpnp.getRegistry().removeDevice(receiver)
        receiver = null
        val currentTransport = transport
        if (currentTransport != null) {
            currentTransport.disable()
            transport = null
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun send(target: Target?, location: String?) {
        start()
        require(!(target == null || !targets.containsKey(target.id))) {
            "Choose an available receiver"
        }
        // Stop the old session before revoking its local-file capability or replacing its URI.
        if (active != null) disconnect()
        var file: CastFileServer? = null
        var relay: CastHttpRelay? = null
        compatibilityQualityLabel = null
        val currentLocation = location ?: return
        val uri: String?
        if (currentLocation.trim { it <= ' ' }.matches(Regex("(?i)^https?://.*"))) {
            val progressive = DesktopBilibiliCastResolver.resolve(currentLocation.trim())
            val receiverHost = target.transport?.device?.identity?.descriptorURL?.host
            if (progressive != null && receiverHost != null) {
                relay = CastHttpRelay(progressive.url, com.wuming.tv.core.playback.BilibiliCastPlayback.headers,
                    receiverHost, com.wuming.tv.desktop.player.DesktopPlaybackHttpClient.client)
                uri = relay.url
                compatibilityQualityLabel = progressive.label
            } else uri = CastUris.requireHttp(currentLocation.trim { it <= ' ' })
        } else {
            val path: Path =
                if (currentLocation.startsWith("file:")) {
                    Path.of(java.net.URI.create(currentLocation))
                } else {
                    Path.of(
                        currentLocation
                    )
                }
            val transportDevice = target.transport?.device
            val host = transportDevice?.identity?.descriptorURL?.host
            file = if (host != null) CastFileServer(path, host) else null
            uri = file?.url()
        }
        val currentUpnp = upnp
        try {
            // Even a timed-out URI request might have reached the renderer. Retain its identity
            // and capability so Disconnect can stop it before releasing the selected file.
            active = target
            val targetTransport = target.transport
            val currentUri = uri ?: ""
            if (targetTransport != null && currentUpnp != null) {
                DesktopRendererProfiles.inspect(targetTransport.device, currentUpnp.controlPoint)
                outgoingObservation = DesktopRendererProfiles.observation(targetTransport.device, currentUri)
            }
            sharedFile = relay ?: file
            val content: DIDLContent = DIDLContent()
            val resource: Res = Res(ProtocolInfo(if (relay != null) "http-get:*:video/mp4:*" else "http-get:*:video/*:*"), null, currentUri)
            content.addItem(VideoItem("0", "-1", "TVBox video", "", resource))
            val metadata: String? = DIDLParser().generate(content)
            invoke(
                target,
                "SetAVTransportURI",
                java.util.Map.of("CurrentURI", uri, "CurrentURIMetaData", metadata)
            )
            invoke(target, "Play", java.util.Map.of("Speed", "1"))
        } catch (error: Exception) {
            if (active == null && file != null) file.close()
            if (active == null) relay?.close()
            throw error
        }
    }

    @Synchronized
    @Throws(Exception::class)
    fun control(action: String?) {
        require(java.util.Set.of("Play", "Pause", "Stop").contains(action)) {
            "Unsupported control"
        }
        val currentActive = active
        checkNotNull(currentActive) { "No outgoing cast is active" }
        invoke(
            currentActive,
            action,
            if (action.equals("Play")) java.util.Map.of("Speed", "1") else java.util.Map.of()
        )
    }

    @Synchronized
    @Throws(Exception::class)
    fun disconnect() {
        try {
            val currentActive = active
            if (currentActive != null) invoke(currentActive, "Stop", java.util.Map.of())
        } finally {
            // Disconnect must revoke file access even if the receiver is no longer reachable.
            active = null
            compatibilityQualityLabel = null
            val currentSharedFile = sharedFile
            if (currentSharedFile != null) {
                currentSharedFile.close()
                sharedFile = null
            }
        }
    }

    @Throws(Exception::class)
    private fun invoke(target: Target, name: String?, arguments: Map<String, String>) {
        val action = target.transport?.getAction(name)
        checkNotNull(action) { "Receiver does not support " + name }
        val invocation: ActionInvocation<RemoteService> = ActionInvocation<RemoteService>(action)
        invocation.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        arguments.forEach(invocation::setInput)
        val observation = outgoingObservation
        val result: CompletableFuture<Void?> = CompletableFuture()
        val currentUpnp = upnp
        val request: Future<*> =
            (currentUpnp?.controlPoint ?: throw IllegalStateException("UPnP not started")).execute(object : ActionCallback(invocation) {
                override fun success(value: ActionInvocation<*>?) {
                    result.complete(null)
                }

                override fun failure(
                    value: ActionInvocation<*>?,
                    response: UpnpResponse?,
                    message: String?
                ) {
                    observation?.failure(name ?: "unknown", response?.statusCode ?: -1, value?.failure?.errorCode ?: -1)
                    java.util.logging.Logger.getLogger("DLNACast").warning(
                        "action=$name http=${response?.statusCode ?: -1} upnp=${value?.failure?.errorCode ?: -1}" +
                            " responsePresent=${response != null} causes=" +
                            com.wuming.tv.core.upnp.UpnpFailureDiagnostic.types(value?.failure),
                    )
                    result.completeExceptionally(IllegalStateException("Receiver rejected " + name))
                }
            })
        try {
            result.get(10, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            request.cancel(true)
            observation?.failure(name ?: "unknown", -1, -1)
            throw IllegalStateException("Receiver did not answer " + name)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            disconnect()
        } catch (
            ignored: Exception
        ) {
            /* Still revoke local resources if a receiver went offline. */
        }
        sharedFile?.close()
        sharedFile = null
        active = null
        stopReceiving()
        upnp?.shutdown()
        upnp = null
        targets.clear()
    }
}
