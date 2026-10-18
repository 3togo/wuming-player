package com.wuming.tv.core.media

import com.wuming.tv.core.cloud.OpenDrivePlayback

/** Host-neutral locator validation and conversion into ephemeral player targets. */
class OpenDriveProviderSourceResolver(private val port: PlaybackPort) : SourceResolver {
    fun interface PlaybackPort {
        @Throws(Exception::class)
        fun resolve(provider: String, handle: String): OpenDrivePlayback
    }
    override fun adapterId(): String = OpenDriveProviderIdentityCodec.ADAPTER_ID
    override fun resolve(source: MediaSourceDescriptor): PlaybackTarget {
        require(source.locatorVersion == 1 && adapterId() == source.adapterId) { "Unsupported OpenDrive provider locator" }
        val provider = source.locator["provider"]
        val handle = source.locator["handle"]
        require(provider != null && handle != null) { "OpenDrive provider locator is invalid" }
        try {
            val playback = port.resolve(provider, handle)
            return PlaybackTarget(playback.url, playback.headers, playback.expiresAtEpochMillis)
        } catch (error: RuntimeException) { throw error }
        catch (error: Exception) {
            throw IllegalStateException(error.message ?: "OpenDrive provider could not resolve playback", error)
        }
    }
}
