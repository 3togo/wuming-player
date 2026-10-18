package com.wuming.tv.core.upnp

import com.wuming.tv.core.playback.renderer.RendererIdentity
import org.fourthline.cling.model.meta.RemoteDevice

/**
 * Builds the shared [RendererIdentity] from a discovered UPnP [RemoteDevice]. The construction was
 * duplicated verbatim in the Android and desktop renderer-profile stores; centralising it keeps the
 * truncation and service-list ordering identical across both ports.
 */
fun RemoteDevice.toRendererIdentity(): RendererIdentity {
    val details = details
    return RendererIdentity(
        RendererIdentity.key(identity.udn.identifierString),
        (details.friendlyName ?: "").take(512),
        (details.manufacturerDetails?.manufacturer ?: "unknown").take(512),
        listOfNotNull(details.modelDetails?.modelName, details.modelDetails?.modelNumber)
            .joinToString(" ").take(512),
        "unknown",
        services.map { it.serviceType.toString() }.sorted().joinToString(",").take(512),
    )
}
