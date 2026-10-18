package com.wuming.android.tv.cast

import android.util.AtomicFile
import com.wuming.android.tv.App
import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.server.CastMediaAccess
import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.playback.renderer.*
import com.wuming.tv.core.upnp.toRendererIdentity
import org.fourthline.cling.model.meta.RemoteDevice
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Phone port for the receiver's internal DLNA renderer. No TVBox probes or automatic test playback. */
object RendererCapabilityProfiles {
    private val devices = ConcurrentHashMap<String, RemoteDevice>()
    private val identities = ConcurrentHashMap<String, RendererIdentity>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "renderer-profiles").apply { isDaemon = true } })
    @Volatile private var registry: RendererProfiles? = null
    private fun submit(onError: () -> Unit = {}, task: () -> Unit) {
        try { worker.execute {
            try {
                // AtomicFile protects replacement, not concurrent read/modify/write across processes.
                // Always reload while holding a separate stable lock inode; locking the replaced JSON
                // file would let a second process lock its new inode and overwrite newer evidence.
                java.io.RandomAccessFile(File(App.get().filesDir, "renderer-capabilities.lock"), "rw").use { lockFile ->
                    lockFile.channel.lock().use {
                        registry = RendererProfiles(object : RendererProfileStore {
                            private val file = AtomicFile(File(App.get().filesDir, "renderer-capabilities.json"))
                            override fun read(): String? = try { file.openRead().bufferedReader().use { it.readText() } }
                                catch (error: java.io.FileNotFoundException) {
                                    if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw error
                                    null
                                }
                            override fun write(json: String) {
                                val output = file.startWrite()
                                try { output.write(json.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
                                catch (error: Exception) { file.failWrite(output); throw error }
                            }
                        })
                        task()
                    }
                }
            } catch (error: Exception) {
                AppLog.w("RendererProfiles", "Profile storage unavailable: " + error.javaClass.simpleName)
                onError()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            AppLog.w("RendererProfiles", "Evidence queue full; observation skipped")
            onError()
        }
    }
    @JvmStatic fun discovered(device: RemoteDevice) {
        val id = device.toRendererIdentity()
        devices[id.deviceKey] = device
        if (identities.put(id.deviceKey, id) != id) submit {
            registry!!.upgradeFilteredServices(id)
            registry!!.identify(id)
        }
    }
    private fun current(identity: RendererIdentity): RendererIdentity {
        val live = identities[identity.deviceKey] ?: return identity
        if (live.profileKey() == identity.profileKey()) return live
        // Only redirect a stale dialog from the known filtered identity, never another firmware/model.
        val prefix = "urn:schemas-upnp-org:service:"
        val repaired = identity.services == "${prefix}AVTransport:1" &&
            live.services.split(',').toSet() == setOf("${prefix}AVTransport:1", "${prefix}ConnectionManager:1", "${prefix}RenderingControl:1") &&
            live.copy(services = identity.services).profileKey() == identity.profileKey()
        return if (repaired) live else identity
    }
    fun identityFor(device: Device): RendererIdentity? = identities[RendererIdentity.key(device.getUuid())]
    @JvmStatic fun hlsUnsupported(device: Device): Boolean = device.isDLNA() && identityFor(device)?.let {
        registry?.state(it, RendererCapability.HLS) == CapabilityState.UNSUPPORTED
    } == true

    @JvmStatic fun session(device: Device, url: String): Session? {
        if (!device.isDLNA()) return null
        val identity = identityFor(device) ?: return null
        val path = try { URI(url).rawPath } catch (_: Exception) { "" }
        val target = CastMediaAccess.getRelayTarget(path) ?: url
        // Aggregation may serve remuxed MP4 or TS; it must never teach native HLS support.
        val format = if (path.endsWith("/aggregated.mp4") || path.endsWith("/aggregated.ts")) null
            else RendererPlaybackObservation.format(target)
        return Session(identity, format)
    }
    class Session internal constructor(identity: RendererIdentity, capability: RendererCapability?) {
        private val observation = RendererPlaybackObservation(capability, BuildConfig.VERSION_NAME, { evidence ->
            submit { registry!!.record(current(identity), evidence) }
        })
        fun position(position: Long, playing: Boolean) = observation.position(position, playing)
        fun seeking() = observation.seeking()
        fun failure(action: String, http: Int, upnp: Int) = observation.failure(action, http, upnp)
    }
    /** Explicit controlled test API; caller must use a fresh run ID for each independent test. */
    fun recordControlled(device: Device, capability: RendererCapability, result: EvidenceResult, runId: String) {
        require(result == EvidenceResult.CONTROLLED_SUCCESS || result == EvidenceResult.CONTROLLED_FAILURE)
        val identity = identityFor(device) ?: return
        submit { registry!!.record(current(identity), RendererEvidence(capability, result, runId,
            System.currentTimeMillis(), BuildConfig.VERSION_NAME.take(128), "user_controlled_test")) }
    }
    fun liveDevice(identity: RendererIdentity): Device? = devices[identity.deviceKey]?.let { Device.get(it) }
    fun confirmProbe(identity: RendererIdentity, sample: RendererProbeSamples.Sample, runId: String, callback: (Boolean) -> Unit) {
        submit({ App.post { callback(false) } }) {
            RendererProbeSamples.recordConfirmed(registry!!, current(identity), sample, BuildConfig.VERSION_NAME, runId)
            App.post { callback(true) }
        }
    }
    fun inconclusiveProbe(identity: RendererIdentity, sample: RendererProbeSamples.Sample, runId: String) {
        submit { registry!!.record(current(identity), RendererEvidence(sample.capabilities.first(), EvidenceResult.INCONCLUSIVE,
            runId, System.currentTimeMillis(), BuildConfig.VERSION_NAME.take(128), "synthetic_test_inconclusive")) }
    }
    fun remembered(callback: (List<RendererIdentity>?) -> Unit) {
        submit({ App.post { callback(null) } }) {
            val saved = registry!!.identities()
            App.post { callback(saved) }
        }
    }
    /** Query only the profile the user opened. Never send playback commands during discovery. */
    fun inspect(identity: RendererIdentity, callback: (String) -> Unit) {
        val device = devices[identity.deviceKey]
        val control = com.wuming.android.tv.dlna.DLNACastManager.get().controlPoint
        val service = device?.findService(org.fourthline.cling.model.types.UDAServiceType("ConnectionManager"))
        val action = service?.getAction("GetProtocolInfo")
        if (device == null || control == null) { callback("offline"); return }
        val actions = device.findService(org.fourthline.cling.model.types.UDAServiceType("AVTransport"))
            ?.actions?.map { it.name }?.toSet().orEmpty()
        fun saveActions(status: String) {
            submit({ App.post { callback("storage_error") } }) {
                registry!!.advertisedActions(current(identity), actions, BuildConfig.VERSION_NAME)
                App.post { callback(status) }
            }
        }
        if (action == null) {
            saveActions("no_protocol_info")
            return
        }
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        val invocation = org.fourthline.cling.model.action.ActionInvocation<org.fourthline.cling.model.meta.RemoteService>(action)
        val timeout = Runnable { if (finished.compareAndSet(false, true)) saveActions("timeout") }
        App.post(timeout, 10000)
        try {
            control.execute(object : org.fourthline.cling.controlpoint.ActionCallback(invocation) {
                override fun success(value: org.fourthline.cling.model.action.ActionInvocation<*>) {
                    if (!finished.compareAndSet(false, true)) return
                    App.removeCallbacks(timeout)
                    val sink = value.getOutput("Sink")?.value?.toString().orEmpty()
                    submit({ App.post { callback("storage_error") } }) {
                        registry!!.advertised(current(identity), RendererAdvertisements.parse(sink, actions), BuildConfig.VERSION_NAME)
                        App.post { callback(if (sink.isBlank()) "empty_protocol_info" else "queried") }
                    }
                }
                override fun failure(value: org.fourthline.cling.model.action.ActionInvocation<*>, response: org.fourthline.cling.model.message.UpnpResponse?, message: String?) {
                    if (!finished.compareAndSet(false, true)) return
                    App.removeCallbacks(timeout)
                    val http = response?.statusCode ?: -1
                    val upnp = value.failure?.errorCode ?: -1
                    AppLog.w("RendererProfiles", "GetProtocolInfo failed: HTTP=$http UPnP=$upnp")
                    saveActions(when {
                        RendererPairingHint.requiresQrPairing(http, response?.statusMessage,
                            device.details.modelDetails?.modelDescription, device.details.manufacturerDetails?.manufacturer,
                            device.details.modelDetails?.modelName, device.identity.udn.identifierString) -> "pairing_required"
                        http >= 400 -> "query_http_$http"
                        // jUPnP also synthesizes 501 for connection failures; that is not a TV response.
                        response != null && upnp > 0 -> "query_upnp_$upnp"
                        else -> "query_failed"
                    })
                }
            })
        } catch (error: Exception) {
            if (finished.compareAndSet(false, true)) {
                App.removeCallbacks(timeout)
                AppLog.w("RendererProfiles", "GetProtocolInfo failed: " + error.javaClass.simpleName)
                saveActions("query_failed")
            }
        }
    }
    fun export(identity: RendererIdentity, callback: (String?) -> Unit) {
        submit({ App.post { callback(null) } }) { val json = registry!!.export(current(identity)); App.post { callback(json) } }
    }
    fun reset(identity: RendererIdentity, callback: (Boolean) -> Unit) {
        submit({ App.post { callback(false) } }) {
            registry!!.reset(identity.deviceKey); App.post { callback(true) }
        }
    }
    fun setOverride(identity: RendererIdentity, capability: RendererCapability, state: CapabilityState?, callback: (Boolean) -> Unit) {
        submit({ App.post { callback(false) } }) {
            registry!!.override(current(identity), capability, state); App.post { callback(true) }
        }
    }
}
