package com.wuming.android.tv.media.catalog

import com.wuming.tv.core.media.DecodedMedia
import com.wuming.tv.core.media.OpenDriveUri
import com.wuming.tv.core.media.catalog.MediaCatalogStore
import com.wuming.tv.core.media.catalog.MediaManifest
import java.util.Optional

class LazyRoomMediaCatalog : MediaCatalogStore {

    @Volatile
    private var delegate: MediaCatalogStore? = null

    override fun save(media: DecodedMedia): MediaCatalogStore.SaveResult = delegate().save(media)

    override fun find(uri: OpenDriveUri): Optional<MediaManifest> = delegate().find(uri)

    override fun pin(uri: OpenDriveUri) = delegate().pin(uri)

    override fun prune(): Int = delegate().prune()

    private fun delegate(): MediaCatalogStore {
        delegate?.let { return it }
        synchronized(this) {
            return delegate ?: RoomMediaCatalog().also { delegate = it }
        }
    }
}
