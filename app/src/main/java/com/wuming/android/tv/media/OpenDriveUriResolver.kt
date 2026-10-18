package com.wuming.android.tv.media

import com.wuming.android.tv.cloud.opendrive.OpenDriveEngine
import com.wuming.android.tv.cloud.opendrive.OpenDriveLog
import com.wuming.android.tv.cloud.opendrive.OpenDriveRuntime
import com.wuming.tv.core.media.PlaybackTarget

/** Android callable API; session selection and lifecycle live in media-core. */
fun interface OpenDriveUriResolver : com.wuming.tv.core.media.OpenDriveUriResolver {
    override fun resolve(uri: String?): PlaybackTarget?
    override fun refresh(uri: String?): PlaybackTarget? = super<com.wuming.tv.core.media.OpenDriveUriResolver>.refresh(uri)
    override fun failover(uri: String?): PlaybackTarget? = super<com.wuming.tv.core.media.OpenDriveUriResolver>.failover(uri)

    class LazyDefaultResolver @JvmOverloads constructor(engine: OpenDriveEngine? = OpenDriveRuntime.engine()) : OpenDriveUriResolver {
        private val shared = com.wuming.tv.core.media.LazyOpenDriveUriResolver(
            { if (engine == null) AndroidMediaPipelineFactory.pipeline() else AndroidMediaPipelineFactory.pipeline(engine) },
            { uri, status, message -> OpenDriveLog.resolveInvalid("opendrive", uri ?: "", status, message) })
        override fun resolve(uri: String?): PlaybackTarget? = shared.resolve(uri)
        override fun refresh(uri: String?): PlaybackTarget = shared.refresh(uri)
        override fun failover(uri: String?): PlaybackTarget = shared.failover(uri)
    }
    companion object {
        @JvmStatic @JvmOverloads fun createDefault(engine: OpenDriveEngine? = OpenDriveRuntime.engine()): OpenDriveUriResolver = LazyDefaultResolver(engine)
    }
}
