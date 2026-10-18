package com.wuming.tv.desktop.library

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.library.ViewingRecordRepository
import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * JSON-file backed implementation of the core [ViewingRecordRepository] port. Mirrors the
 * atomic-write discipline of [DesktopLibraryFile] so a desktop viewing session feeds the
 * shared recommendation policy without an Android database.
 */
class DesktopViewingRecordStore(
    path: Path,
    private val gson: Gson = Gson(),
    private val maxRecords: Int = 2000,
) : ViewingRecordRepository<DesktopViewingRecord> {

    private val path = path.toAbsolutePath().normalize()
    private val logger = Logger.getLogger("tvbox.viewing")

    init {
        require(maxRecords > 0) { "maxRecords must be positive" }
    }

    private val state = read()

    @Synchronized
    override fun save(record: DesktopViewingRecord) {
        state.records.removeIf { it.id == record.id }
        state.records.add(0, record)
        while (state.records.size > maxRecords) state.records.removeAt(state.records.lastIndex)
        write()
    }

    @Synchronized
    fun records(): List<DesktopViewingRecord> = state.records.toList()

    @Synchronized
    fun clear() {
        state.records.clear()
        write()
    }

    @Synchronized
    fun delete(key: String) {
        state.records.removeIf { it.key == key }
        write()
    }

    private fun read(): MutableViewingState {
        if (!Files.isRegularFile(path)) return MutableViewingState()
        return runCatching {
            gson.fromJson(Files.readString(path), MutableViewingState::class.java)?.sanitized(maxRecords)
                ?: MutableViewingState()
        }.onFailure {
            logger.log(Level.WARNING, "Ignoring unreadable desktop viewing record path=$path", it)
        }.getOrDefault(MutableViewingState())
    }

    private fun write() {
        AtomicFilePublisher.publish(path, ".viewing-") { Files.writeString(it, gson.toJson(state)) }
    }

    private data class MutableViewingState(
        val records: MutableList<DesktopViewingRecord> = mutableListOf(),
    ) {
        fun sanitized(maxRecords: Int): MutableViewingState =
            MutableViewingState(records.orEmpty().asSequence()
                .filterNotNull()
                .filter { it.key.isNotBlank() }
                .map { record ->
                    record.copy(
                        id = record.id.ifBlank { UUID.randomUUID().toString() },
                        watchedDurationMillis = record.watchedDurationMillis.coerceAtLeast(0L),
                        lastWatchedAtMillis = record.lastWatchedAtMillis.coerceAtLeast(0L),
                    )
                }
                .distinctBy { it.id }
                .take(maxRecords)
                .toMutableList())
    }
}
