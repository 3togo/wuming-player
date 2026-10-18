package com.wuming.android.tv.repository

import com.wuming.android.tv.bean.Keep
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.DataCache
import com.wuming.tv.core.library.FavoriteRepository

/** Android cache/Room adapter for video and live favorites. */
object AndroidFavoriteRepository : FavoriteRepository<Keep> {
    @JvmStatic
    fun getInstance(): AndroidFavoriteRepository = this

    override fun find(
        catalogId: Int,
        key: String,
    ): Keep? =
        if (DataCache.isReady()) {
            DataCache.get().findKeep(catalogId, key)
        } else {
            AppDatabase.get().getKeepDao().find(catalogId, key)
        }

    override fun exists(key: String): Boolean =
        if (DataCache.isReady()) {
            DataCache.get().keepExists(key)
        } else {
            AppDatabase.get().getKeepDao().find(key) != null
        }

    override fun videoFavorites(): List<Keep> =
        if (DataCache.isReady()) {
            DataCache.get().getVodKeeps()
        } else {
            AppDatabase.get().getKeepDao().getVod()
        }

    override fun liveFavorites(): List<Keep> =
        if (DataCache.isReady()) {
            DataCache.get().getLiveKeeps()
        } else {
            AppDatabase.get().getKeepDao().getLive()
        }

    override fun save(item: Keep) {
        if (DataCache.isReady()) {
            DataCache.get().putKeep(item)
        } else {
            AppDatabase.get().getKeepDao().insertOrUpdate(item)
        }
    }

    override fun deleteVideo(
        catalogId: Int,
        key: String,
    ) {
        if (DataCache.isReady()) {
            DataCache.get().removeVideoKeep(catalogId, key)
        } else {
            AppDatabase.get().getKeepDao().delete(catalogId, key)
        }
    }

    override fun deleteLive(key: String) {
        if (DataCache.isReady()) {
            DataCache.get().removeLiveKeep(key)
        } else {
            AppDatabase.get().getKeepDao().delete(key)
        }
    }

    override fun deleteVideoCatalog(catalogId: Int) {
        if (DataCache.isReady()) {
            DataCache.get().removeVideoKeepsByCid(catalogId)
        } else {
            AppDatabase.get().getKeepDao().delete(catalogId)
        }
    }

    override fun deleteAllVideos() {
        if (DataCache.isReady()) {
            DataCache.get().clearVideoKeeps()
        } else {
            AppDatabase.get().getKeepDao().delete()
        }
    }
}
