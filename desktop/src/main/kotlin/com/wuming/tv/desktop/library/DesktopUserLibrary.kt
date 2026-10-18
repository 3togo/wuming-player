package com.wuming.tv.desktop.library

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.catalog.CatalogEpisode
import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogLine
import com.wuming.tv.core.library.ContentRanker
import com.wuming.tv.core.library.FavoriteRepository
import com.wuming.tv.core.library.HistoryRepository
import com.wuming.tv.core.library.HistoryRetentionPolicy
import com.wuming.tv.core.library.UserLibraryPolicy
import com.wuming.tv.core.library.VideoLibraryEntry
import com.wuming.tv.core.library.VideoLibraryItemAccess
import com.google.gson.Gson
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.logging.Level
import java.util.logging.Logger

class DesktopUserLibrary internal constructor(
    private val histories: HistoryRepository<VideoLibraryEntry>,
    private val favorites: FavoriteRepository<VideoLibraryEntry>,
    private val viewing: DesktopViewingRecordStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    val privacy = com.wuming.tv.core.library.ViewingPrivacyPolicy()

    fun deleteHistory(item: CatalogItem) {
        privacy.invalidateSessions()
        favorites.find(0, item.libraryKey())?.let {
            favorites.save(com.wuming.tv.core.library.ViewingPrivacyPolicy.forgetProgress(it))
        }
        histories.delete(0, item.libraryKey())
        viewing.delete(item.libraryKey())
    }

    fun clearHistory() {
        privacy.invalidateSessions()
        favorites.videoFavorites().forEach {
            favorites.save(com.wuming.tv.core.library.ViewingPrivacyPolicy.forgetProgress(it))
        }
        histories.deleteCatalog(0)
        viewing.clear()
    }

    private val policy = UserLibraryPolicy(VideoLibraryItemAccess)

    fun recent(availableSourceKeys: Set<String>): List<VideoLibraryEntry> = visible(
        histories.recent(0),
        availableSourceKeys,
    )

    fun favorites(availableSourceKeys: Set<String>): List<VideoLibraryEntry> = visible(
        favorites.videoFavorites(),
        availableSourceKeys,
    )

    fun find(item: CatalogItem): VideoLibraryEntry? = histories.findByKey(item.libraryKey())

    fun isFavorite(item: CatalogItem): Boolean = favorites.exists(item.libraryKey())

    fun recordPlayback(item: CatalogItem, line: CatalogLine, episode: CatalogEpisode, session: com.wuming.tv.core.library.ViewingPrivacyPolicy.Session = privacy.beginSession()): VideoLibraryEntry {
        val previous = histories.findByKey(item.libraryKey())
        val fresh = item.toLibraryEntry(line, episode, clock.millis())
        val entry = if (previous?.lineFlag == line.flag && previous.episodeId == episode.id) {
            fresh.copy(positionMillis = previous.positionMillis, durationMillis = previous.durationMillis)
        } else fresh
        if (!privacy.mayRecord(session)) return entry
        histories.save(entry)
        favorites.find(0, entry.key)?.let { favorites.save(entry) }
        return entry
    }

    fun savePlaybackPosition(item: CatalogItem, positionMillis: Long, durationMillis: Long, session: com.wuming.tv.core.library.ViewingPrivacyPolicy.Session = privacy.beginSession()) {
        if (!privacy.mayRecord(session)) return
        val existing = histories.findByKey(item.libraryKey()) ?: return
        val updated = existing.copy(positionMillis = positionMillis, durationMillis = durationMillis)
        histories.save(updated)
        favorites.find(0, existing.key)?.let { favorites.save(updated) }
    }

    fun playbackPosition(item: CatalogItem): Long = histories.findByKey(item.libraryKey())?.positionMillis ?: 0L

    fun playbackPosition(item: CatalogItem, line: CatalogLine, episode: CatalogEpisode): Long {
        val saved = histories.findByKey(item.libraryKey()) ?: return 0L
        return com.wuming.tv.core.library.EpisodeSelectionPolicy.resumePosition(saved, line.flag, episode.id)
    }

    /** Persists one finished playback session for the shared recommendation policy. */
    fun recordViewing(
        item: CatalogItem,
        detail: CatalogDetail?,
        watchedDurationMillis: Long,
        completed: Boolean,
        session: com.wuming.tv.core.library.ViewingPrivacyPolicy.Session = privacy.beginSession(),
    ) {
        if (!privacy.mayRecord(session)) return
        if (watchedDurationMillis <= 0L && !completed) return
        viewing.save(DesktopViewingRecord.fromPlayback(
            item,
            detail,
            watchedDurationMillis,
            clock.millis(),
            completed,
        ))
    }

    /** Ranks catalogue candidates by the learned desktop viewing preference (shared core policy). */
    fun recommendations(): DesktopRecommendations = DesktopRecommendations({ viewing.records() }, { clock.millis() })

    /** @return true when the item is now a favorite. */
    fun toggleFavorite(item: CatalogItem): Boolean {
        val key = item.libraryKey()
        if (favorites.exists(key)) {
            favorites.deleteVideo(0, key)
            return false
        }
        val saved = histories.findByKey(key)
        favorites.save(saved ?: item.toLibraryEntry(updatedAtMillis = clock.millis()))
        return true
    }

    private fun visible(
        items: List<VideoLibraryEntry>,
        availableSourceKeys: Set<String>,
    ): List<VideoLibraryEntry> {
        val rank = items.sortedByDescending(VideoLibraryEntry::updatedAtMillis)
            .mapIndexed { index, item -> item.key to items.size - index }
            .toMap()
        return policy.visibleAndRanked(items, availableSourceKeys, ContentRanker { rank[it] ?: -1 })
    }

    companion object {
        fun openDefault(): DesktopUserLibrary {
            val configRoot = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".config")
            return open(configRoot.resolve("tvbox-desktop/user-library.json"))
        }

        internal fun open(path: Path, clock: Clock = Clock.systemUTC()): DesktopUserLibrary {
            val libraryPath = path.toAbsolutePath().normalize()
            val file = DesktopLibraryFile(libraryPath)
            val viewing = DesktopViewingRecordStore(libraryPath.resolveSibling("viewing-records.json"))
            return DesktopUserLibrary(
                DesktopHistoryRepository(file),
                DesktopFavoriteRepository(file),
                viewing,
                clock,
            )
        }
    }
}

private fun CatalogItem.libraryKey(): String = sourceKey + "@@@" + id

private fun CatalogItem.toLibraryEntry(
    line: CatalogLine? = null,
    episode: CatalogEpisode? = null,
    updatedAtMillis: Long,
): VideoLibraryEntry = VideoLibraryEntry(
    sourceKey = sourceKey,
    itemId = id,
    name = name,
    picture = picture,
    remarks = remarks,
    year = year,
    lineFlag = line?.flag.orEmpty(),
    episodeId = episode?.id.orEmpty(),
    episodeName = episode?.name.orEmpty(),
    updatedAtMillis = updatedAtMillis,
)

internal class DesktopHistoryRepository(
    private val file: DesktopLibraryFile,
    private val retention: HistoryRetentionPolicy = HistoryRetentionPolicy(),
) : HistoryRepository<VideoLibraryEntry> {
    override fun recent(cutoffTimeMillis: Long): List<VideoLibraryEntry> = file.histories()
        .filter { it.updatedAtMillis >= cutoffTimeMillis }
        .sortedByDescending(VideoLibraryEntry::updatedAtMillis)

    override fun recent(catalogId: Int, cutoffTimeMillis: Long): List<VideoLibraryEntry> =
        recent(cutoffTimeMillis).filter { it.catalogId == catalogId }

    override fun findByKey(key: String): VideoLibraryEntry? = file.histories().firstOrNull { it.key == key }

    override fun findByName(name: String): List<VideoLibraryEntry> = file.histories().filter { it.name == name }

    override fun save(item: VideoLibraryEntry) {
        file.updateHistories { existing ->
            (listOf(item) + existing.filterNot { it.key == item.key })
                .sortedByDescending(VideoLibraryEntry::updatedAtMillis)
                .take(retention.defaultLimit)
        }
    }

    override fun delete(catalogId: Int, key: String) =
        file.updateHistories { it.filterNot { item -> item.catalogId == catalogId && item.key == key } }

    override fun deleteCatalog(catalogId: Int) =
        file.updateHistories { it.filterNot { item -> item.catalogId == catalogId } }
}

internal class DesktopFavoriteRepository(
    private val file: DesktopLibraryFile,
) : FavoriteRepository<VideoLibraryEntry> {
    override fun find(catalogId: Int, key: String): VideoLibraryEntry? =
        file.favorites().firstOrNull { it.catalogId == catalogId && it.key == key }

    override fun exists(key: String): Boolean = file.favorites().any { it.key == key }

    override fun videoFavorites(): List<VideoLibraryEntry> =
        file.favorites().sortedByDescending(VideoLibraryEntry::updatedAtMillis)

    override fun liveFavorites(): List<VideoLibraryEntry> = emptyList()

    override fun save(item: VideoLibraryEntry) =
        file.updateFavorites { listOf(item) + it.filterNot { saved -> saved.key == item.key } }

    override fun deleteVideo(catalogId: Int, key: String) =
        file.updateFavorites { it.filterNot { item -> item.catalogId == catalogId && item.key == key } }

    override fun deleteLive(key: String) = file.updateFavorites { it.filterNot { item -> item.key == key } }

    override fun deleteVideoCatalog(catalogId: Int) =
        file.updateFavorites { it.filterNot { item -> item.catalogId == catalogId } }

    override fun deleteAllVideos() = file.updateFavorites { emptyList() }
}

internal class DesktopLibraryFile(
    private val path: Path,
    private val gson: Gson = Gson(),
) {
    private val logger = Logger.getLogger("tvbox.library")
    private var state: DesktopLibraryState = read()

    @Synchronized
    fun histories(): List<VideoLibraryEntry> = state.histories.toList()

    @Synchronized
    fun favorites(): List<VideoLibraryEntry> = state.favorites.toList()

    @Synchronized
    fun updateHistories(update: (List<VideoLibraryEntry>) -> List<VideoLibraryEntry>) {
        state = state.copy(histories = update(state.histories))
        write()
    }

    @Synchronized
    fun updateFavorites(update: (List<VideoLibraryEntry>) -> List<VideoLibraryEntry>) {
        state = state.copy(favorites = update(state.favorites))
        write()
    }

    private fun read(): DesktopLibraryState {
        if (!Files.isRegularFile(path)) return DesktopLibraryState()
        return runCatching {
            gson.fromJson(Files.readString(path), DesktopLibraryState::class.java)
                ?.sanitized() ?: DesktopLibraryState()
        }.onFailure {
            logger.log(Level.WARNING, "Ignoring unreadable desktop user library path=$path", it)
        }.getOrDefault(DesktopLibraryState())
    }

    private fun write() {
        AtomicFilePublisher.publish(path, ".user-library-") { Files.writeString(it, gson.toJson(state)) }
    }
}

internal data class DesktopLibraryState(
    val histories: List<VideoLibraryEntry> = emptyList(),
    val favorites: List<VideoLibraryEntry> = emptyList(),
) {
    fun sanitized(): DesktopLibraryState = DesktopLibraryState(
        histories = histories.orEmpty().filterNotNull()
            .filter(VideoLibraryEntry::isValid).distinctBy(VideoLibraryEntry::key),
        favorites = favorites.orEmpty().filterNotNull()
            .filter(VideoLibraryEntry::isValid).distinctBy(VideoLibraryEntry::key),
    )
}

private fun VideoLibraryEntry.isValid(): Boolean = sourceKey.isNotBlank() && itemId.isNotBlank()
