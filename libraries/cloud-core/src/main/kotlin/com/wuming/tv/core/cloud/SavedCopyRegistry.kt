package com.wuming.tv.core.cloud

import java.io.IOException

/** One provider per store. No credentials, share tokens or media URLs are persisted. */
data class SavedCopyRecord(
    val account: String,
    val source: String,
    val folder: String,
    val file: String,
    val updated: Long,
    val owners: List<Long>,
    /** Empty file + null task is an uncertain submission; never automatically resubmit it. */
    val task: String? = null,
)

interface SavedCopyStore {
    fun <T> locked(action: () -> T): T
    fun read(): List<SavedCopyRecord>
    fun write(records: List<SavedCopyRecord>)
}

/** Only an authoritative terminal provider failure permits removing a pending intent. */
class SavedCopyTaskRejected(message: String) : IOException(message)

data class SavedCopyReview(val record: SavedCopyRecord, val state: State) {
    enum class State { ELIGIBLE, PROTECTED, PENDING, UNCERTAIN, OTHER_SESSION }
}

data class SavedCopyMaintenance(val changed: Int, val retained: Int)

class SavedCopyRegistry(
    private val store: SavedCopyStore,
    private val owner: Long,
    private val ownerAlive: (Long) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val holdMillis: Long = 24 * 60 * 60 * 1000L,
    private val limit: Int = 128,
) {
    fun obtain(account: String, source: String, folder: String,
               exists: (SavedCopyRecord) -> Boolean, delete: (SavedCopyRecord) -> Boolean,
               save: () -> String): String = obtainInternal(account, source, folder, exists, delete) { pending, _ ->
        if (pending != null) throw IOException("A previous save needs reconciliation under Accounts → Saved copies.")
        save()
    }

    fun obtainTask(account: String, source: String, folder: String,
                   exists: (SavedCopyRecord) -> Boolean, delete: (SavedCopyRecord) -> Boolean,
                   start: () -> String, await: (String) -> String): String =
        obtainInternal(account, source, folder, exists, delete) { pending, recordTask ->
            val task = if (pending != null) pending.task?.takeIf(String::isNotBlank)
                ?: throw IOException("A previous save has an unknown outcome. Review it under Accounts → Saved copies before retrying.")
            else start().also { require(it.isNotBlank()); recordTask(it) }
            await(task)
        }

    private fun obtainInternal(account: String, source: String, folder: String,
                               exists: (SavedCopyRecord) -> Boolean, delete: (SavedCopyRecord) -> Boolean,
                               save: (SavedCopyRecord?, (String) -> Unit) -> String): String = store.locked {
        require(account.isNotBlank() && source.isNotBlank() && folder.isNotBlank())
        val records = store.read().toMutableList()
        val cached = records.firstOrNull { it.account == account && it.source == source && it.folder == folder }
        if (cached != null && cached.file.isNotBlank() && exists(cached)) {
            records[records.indexOf(cached)] = cached.copy(updated = clock(), owners = (cached.owners.filter(ownerAlive) + owner).distinct())
            store.write(records)
            return@locked cached.file
        }
        if (cached != null && cached.file.isNotBlank()) records.remove(cached)
        val pending = cached?.takeIf { it.file.isBlank() }
        if (pending != null && pending.owners.any { it != owner && ownerAlive(it) }) {
            throw IOException("Another desktop instance is still resolving this save.")
        }
        store.write(records)
        if (pending == null && records.count { it.account == account } >= 10) {
            records.filter { eligible(it, account, records) && it.folder == folder }.sortedBy { it.updated }.take(8)
                .forEach { candidate ->
                    if (!exists(candidate) || delete(candidate)) { records.remove(candidate); store.write(records) }
                }
        }
        if (pending == null && records.size >= limit) throw IOException("Saved-copy limit reached. Review retained copies under Accounts → Saved copies.")
        var intent = pending ?: SavedCopyRecord(account, source, folder, "", clock(), listOf(owner))
        if (pending == null) { records += intent; store.write(records) }
        try {
            val file = save(pending) { task ->
                val next = intent.copy(task = task)
                records[records.indexOf(intent)] = next
                store.write(records)
                intent = next
            }
            require(file.isNotBlank())
            records[records.indexOf(intent)] = intent.copy(file = file, task = null, updated = clock(), owners = listOf(owner))
            store.write(records)
            file
        } catch (rejected: SavedCopyTaskRejected) {
            records.remove(intent)
            store.write(records)
            throw rejected
        }
    }

    fun review(account: String): List<SavedCopyReview> = store.locked {
        val records = store.read()
        records.map { record -> SavedCopyReview(record, when {
            record.account != account -> SavedCopyReview.State.OTHER_SESSION
            record.file.isBlank() && record.task.isNullOrBlank() -> SavedCopyReview.State.UNCERTAIN
            record.file.isBlank() -> SavedCopyReview.State.PENDING
            eligible(record, account, records) -> SavedCopyReview.State.ELIGIBLE
            else -> SavedCopyReview.State.PROTECTED
        }) }
    }

    /** Revalidate the exact reviewed records under the lock; stale approvals cannot delete new work. */
    fun clean(account: String, reviewed: List<SavedCopyRecord>, exists: (SavedCopyRecord) -> Boolean,
              delete: (SavedCopyRecord) -> Boolean): SavedCopyMaintenance = store.locked {
        val records = store.read().toMutableList()
        var changed = 0
        reviewed.distinct().forEach { record ->
            if (record in records && eligible(record, account, records) && (!exists(record) || delete(record))) {
                records.remove(record); store.write(records); changed++
            }
        }
        SavedCopyMaintenance(changed, records.size)
    }

    fun reconcile(account: String, await: (SavedCopyRecord) -> String): SavedCopyMaintenance = store.locked {
        val records = store.read().toMutableList()
        var changed = 0
        records.toList().filter { it.account == account && it.file.isBlank() && !it.task.isNullOrBlank() &&
            it.owners.none { pid -> pid != owner && ownerAlive(pid) } }.forEach { record ->
            try {
                val file = await(record)
                require(file.isNotBlank())
                records[records.indexOf(record)] = record.copy(file = file, task = null, updated = clock(), owners = listOf(owner))
                store.write(records); changed++
            } catch (_: SavedCopyTaskRejected) {
                records.remove(record); store.write(records); changed++
            }
        }
        SavedCopyMaintenance(changed, records.size)
    }

    /** Local-only, explicitly reviewed abandonment. Never authorizes a remote delete or account reassignment. */
    fun forget(account: String, reviewed: List<SavedCopyRecord>): SavedCopyMaintenance = store.locked {
        val records = store.read().toMutableList()
        var changed = 0
        reviewed.distinct().forEach { record ->
            val oldSession = record.account != account && record.owners.none(ownerAlive)
            val uncertain = record.account == account && record.file.isBlank() && record.task.isNullOrBlank() &&
                record.owners.none { it != owner && ownerAlive(it) }
            if (record in records && (oldSession || uncertain)) { records.remove(record); changed++ }
        }
        store.write(records)
        SavedCopyMaintenance(changed, records.size)
    }

    private fun eligible(record: SavedCopyRecord, account: String, records: List<SavedCopyRecord>): Boolean =
        record.account == account && record.file.isNotBlank() &&
            records.none { other -> other.file == record.file && other.folder == record.folder &&
                (other.owners.any(ownerAlive) || !SavedCopyPolicy.expired(other.updated, clock(), holdMillis)) }
}
