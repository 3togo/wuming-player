package com.wuming.tv.core.media.catalog

import com.wuming.tv.core.media.DecodedMedia
import com.wuming.tv.core.media.OpenDriveUri
import java.util.Optional

/** Storage boundary used by the decode/resolve pipeline. */
interface MediaCatalogStore {
    fun save(media: DecodedMedia): SaveResult
    fun find(uri: OpenDriveUri): Optional<MediaManifest>

    /** Marks an explicitly supplied canonical URI as externally retained. */
    fun pin(uri: OpenDriveUri) {}

    /** Runs bounded, reference-aware maintenance and returns the deleted resource count. */
    fun prune(): Int = 0

    class SaveResult(val uri: OpenDriveUri, val created: Boolean) {
        @JvmName("uri")
        fun uriAccessor(): OpenDriveUri = uri
        @JvmName("created")
        fun createdAccessor(): Boolean = created
    }
}
