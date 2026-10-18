package com.wuming.tv.core.library

/** Storage ports implemented by Room on Android and by a desktop persistence adapter later. */
interface HistoryRepository<T> {
    fun recent(cutoffTimeMillis: Long): List<T>
    fun recent(catalogId: Int, cutoffTimeMillis: Long): List<T>
    fun findByKey(key: String): T?
    fun findByName(name: String): List<T>
    fun save(item: T)
    fun delete(catalogId: Int, key: String)
    fun deleteCatalog(catalogId: Int)
}

interface FavoriteRepository<T> {
    fun find(catalogId: Int, key: String): T?
    fun exists(key: String): Boolean
    fun videoFavorites(): List<T>
    fun liveFavorites(): List<T>
    fun save(item: T)
    fun deleteVideo(catalogId: Int, key: String)
    fun deleteLive(key: String)
    fun deleteVideoCatalog(catalogId: Int)
    fun deleteAllVideos()
}

interface ViewingRecordRepository<T> {
    fun save(record: T)
}
