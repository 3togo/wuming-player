package com.wuming.android.tv.media

import com.wuming.android.tv.cloud.opendrive.OpenDriveEngine
import com.wuming.android.tv.media.catalog.LazyRoomMediaCatalog
import com.wuming.tv.core.media.InputDecoderRegistry
import com.wuming.tv.core.media.MediaResolverRegistry
import com.wuming.tv.core.media.OpenDrivePipeline
import com.wuming.tv.core.media.OpenDriveIdentityCodec
import com.wuming.tv.core.media.OpenDriveProviderIdentityCodec
import com.wuming.tv.core.media.OpenDriveShadowProbe

/** Android composition root for the platform-neutral media-core pipeline. */
object AndroidMediaPipelineFactory {
    @JvmStatic
    fun decoders(): InputDecoderRegistry = InputDecoderRegistry(listOf(DirectHttpInputDecoder()))

    @JvmStatic
    fun resolvers(): MediaResolverRegistry = resolvers(null)

    @JvmStatic
    fun resolvers(engine: OpenDriveEngine?): MediaResolverRegistry =
        MediaResolverRegistry(listOf(OpenDriveProviderSourceResolver(engine), DirectHttpSourceResolver()))

    /** Creates the Room-backed Android pipeline. Call its operations on a worker thread. */
    @JvmStatic
    fun pipeline(): OpenDrivePipeline =
        OpenDrivePipeline(decoders(), LazyRoomMediaCatalog(), resolvers())

    @JvmStatic
    fun pipeline(engine: OpenDriveEngine): OpenDrivePipeline =
        OpenDrivePipeline(decoders(), LazyRoomMediaCatalog(), resolvers(engine))

    @JvmStatic
    fun shadowProbe(): OpenDriveShadowProbe =
        OpenDriveShadowProbe(decoders(), resolvers())

    @JvmStatic
    fun identityCodec(): OpenDriveIdentityCodec {
        val decoders = decoders()
        val resolvers = resolvers()
        val pipeline = OpenDrivePipeline(decoders, LazyRoomMediaCatalog(), resolvers)
        return OpenDriveIdentityCodec(pipeline, OpenDriveShadowProbe(decoders, resolvers))
    }

    @JvmStatic
    fun providerIdentityCodec(): OpenDriveProviderIdentityCodec =
        OpenDriveProviderIdentityCodec(LazyRoomMediaCatalog())
}
