package com.wuming.android.tv.dlna

import com.wuming.tv.core.playback.renderer.RendererCapability
import com.wuming.tv.core.playback.renderer.RendererSinkProtocols
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.model.DefaultServiceManager
import org.fourthline.cling.model.meta.LocalService
import org.fourthline.cling.support.connectionmanager.ConnectionManagerService
import org.fourthline.cling.support.model.ProtocolInfos

/** Matches the progressive MP4, HLS and DASH paths used by CastActivity's MediaSourceFactory. */
object RendererConnectionManager {
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun create(): LocalService<ConnectionManagerService> {
        val service = AnnotationLocalServiceBinder().read(ConnectionManagerService::class.java) as LocalService<ConnectionManagerService>
        service.manager = object : DefaultServiceManager<ConnectionManagerService>(service) {
            override fun createServiceInstance() = ConnectionManagerService(ProtocolInfos(), ProtocolInfos(
                RendererSinkProtocols.forFormats(setOf(RendererCapability.MP4, RendererCapability.HLS, RendererCapability.DASH))))
        }
        return service
    }
}
