package com.wuming.tv.core.playback.renderer

import java.util.UUID

/** Discovery hints for instructions only. Never use these self-reported fields to authorize control. */
object RendererPairingHint {
    const val MODEL_DESCRIPTION = "Wuming TV leanback renderer; qr-pairing-v1"

    @JvmStatic
    fun requiresQrPairing(httpStatus: Int, responseReason: String?, modelDescription: String?,
        manufacturer: String?, modelName: String?, udn: String?): Boolean {
        if (httpStatus != 403) return false
        if (modelDescription == MODEL_DESCRIPTION) return true
        // Compatibility with releases before the explicit description marker. A friendly name,
        // Skyworth model, UUID, or a generic 403 alone is not enough to identify this receiver.
        if (responseReason != "QR pairing required" || modelDescription != "DLNA Renderer" ||
            manufacturer.isNullOrBlank() || modelName.isNullOrBlank() || udn.isNullOrBlank()) return false
        val legacy = UUID.nameUUIDFromBytes((manufacturer + modelName + "-MediaRenderer").toByteArray(Charsets.UTF_8))
        return udn.removePrefix("uuid:").equals(legacy.toString(), ignoreCase = true)
    }
}
