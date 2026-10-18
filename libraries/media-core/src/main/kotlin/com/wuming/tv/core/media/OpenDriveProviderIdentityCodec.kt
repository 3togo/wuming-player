package com.wuming.tv.core.media

import com.wuming.tv.core.media.catalog.MediaCatalogStore

/** Stores a durable provider handle as a universal catalog resource. */
class OpenDriveProviderIdentityCodec(private val catalog: MediaCatalogStore) {
    fun encode(providerId: String?, itemHandle: String?, title: String?): String {
        if (providerId == null || !providerId.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))
            || itemHandle == null
            || !itemHandle.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) return ""
        val locator = mapOf("provider" to providerId, "handle" to itemHandle)
        val source = MediaSourceDescriptor(
            ADAPTER_ID, 1, locator,
            MediaFingerprint.sha256("$ADAPTER_ID\n$providerId\n$itemHandle"),
            100, true, "vault:opendrive/$providerId/$itemHandle")
        val media = DecodedMedia(OpenDriveUri.Kind.ASSET, title, listOf(source))
        return catalog.save(media).uri.toString()
    }

    companion object {
        @JvmField
        val ADAPTER_ID = "org.opendrive.provider"
    }
}
