package com.wuming.android.tv.player.offline

internal object AuthHeaderRegistry {
    @JvmStatic fun register(uri: String, headers: Map<String, String>) = com.wuming.tv.core.playback.AuthHeaderRegistry.register(uri, headers)
    @JvmStatic fun lookup(uri: String) = com.wuming.tv.core.playback.AuthHeaderRegistry.lookup(uri)
    @JvmStatic fun unregister(uri: String) = com.wuming.tv.core.playback.AuthHeaderRegistry.unregister(uri)
    @JvmStatic fun serialize(headers: Map<String, String>) = com.wuming.tv.core.playback.AuthHeaderRegistry.serialize(headers)
    @JvmStatic fun deserialize(data: ByteArray?) = com.wuming.tv.core.playback.AuthHeaderRegistry.deserialize(data)
}
