package com.wuming.android.tv.media

import com.wuming.android.tv.cloud.opendrive.OpenDriveEngine
import com.wuming.android.tv.cloud.opendrive.OpenDriveRuntime
import com.wuming.tv.core.cloud.OpenDrivePlayback
import com.wuming.tv.core.media.MediaSourceDescriptor
import com.wuming.tv.core.media.OpenDriveProviderIdentityCodec
import com.wuming.tv.core.media.PlaybackTarget
import com.wuming.tv.core.media.SourceResolver
import kotlin.concurrent.Volatile

/** Resolves restart-safe provider handles through the reviewed built-in OpenDrive engine.  */
class OpenDriveProviderSourceResolver @JvmOverloads constructor(
    @field:Volatile private var engine: OpenDriveEngine? = null
) : SourceResolver {
    private val shared = com.wuming.tv.core.media.OpenDriveProviderSourceResolver { provider, handle ->
        engine()!!.resolve(provider, handle)
    }
    override fun adapterId(): String = shared.adapterId()
    override fun resolve(source: MediaSourceDescriptor): PlaybackTarget = shared.resolve(source)

    private fun engine(): OpenDriveEngine? {
        val value: OpenDriveEngine? = engine
        if (value != null) return value
        synchronized(this) {
            if (engine == null) engine = OpenDriveRuntime.engine()
            return engine
        }
    }
}
