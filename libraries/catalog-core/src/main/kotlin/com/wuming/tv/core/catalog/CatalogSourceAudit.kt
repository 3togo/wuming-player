package com.wuming.tv.core.catalog

enum class CatalogAuditStage {
    SOURCES,
    SEARCH,
    DETAIL,
    PLAYBACK,
}

enum class CatalogAuditStatus {
    PASSED,
    FAILED,
    UNSUPPORTED,
    SKIPPED,
    CANCELLED,
}

data class CatalogAuditStep(
    val stage: CatalogAuditStage,
    val status: CatalogAuditStatus,
    val message: String,
    val elapsedMillis: Long,
)

data class CatalogAuditReport(
    val source: CatalogSource,
    val query: String,
    val steps: List<CatalogAuditStep>,
) {
    val status: CatalogAuditStatus
        get() = when {
            steps.any { it.status == CatalogAuditStatus.CANCELLED } -> CatalogAuditStatus.CANCELLED
            steps.any { it.status == CatalogAuditStatus.FAILED } -> CatalogAuditStatus.FAILED
            steps.any { it.status == CatalogAuditStatus.UNSUPPORTED } -> CatalogAuditStatus.UNSUPPORTED
            else -> CatalogAuditStatus.PASSED
        }
}

fun interface CatalogAuditCancellation {
    fun isCancelled(): Boolean
}

/**
 * Audits the common catalogue path without depending on a spider runtime or transport.
 * Provider payloads and identifiers are intentionally absent from the report.
 */
class CatalogSourceAuditor(
    private val nanoTime: () -> Long = System::nanoTime,
) {

    fun audit(
        gateway: CatalogGateway,
        source: CatalogSource,
        query: String,
        cancellation: CatalogAuditCancellation = CatalogAuditCancellation { false },
        onStep: (CatalogAuditStep) -> Unit = {},
    ): CatalogAuditReport {
        require(source.key.isNotBlank()) { "Source key is required" }
        val probe = query.trim()
        require(probe.isNotEmpty()) { "Audit query is required" }
        val steps = mutableListOf<CatalogAuditStep>()

        fun record(stage: CatalogAuditStage, status: CatalogAuditStatus, message: String, started: Long) {
            CatalogAuditStep(stage, status, message, elapsed(started)).also {
                steps += it
                onStep(it)
            }
        }

        fun skipFrom(index: Int, message: String) {
            CatalogAuditStage.entries.drop(index).forEach { stage ->
                record(stage, CatalogAuditStatus.SKIPPED, message, nanoTime())
            }
        }

        fun cancelled(stage: CatalogAuditStage): CatalogAuditReport? {
            if (!cancellation.isCancelled()) return null
            record(stage, CatalogAuditStatus.CANCELLED, "Audit cancelled.", nanoTime())
            skipFrom(stage.ordinal + 1, "Skipped after cancellation.")
            return CatalogAuditReport(source, probe, steps.toList())
        }

        cancelled(CatalogAuditStage.SOURCES)?.let { return it }
        var started = nanoTime()
        val available = try {
            gateway.sources()
        } catch (error: Exception) {
            record(CatalogAuditStage.SOURCES, CatalogAuditStatus.FAILED, failure(error), started)
            skipFrom(1, "Skipped because source discovery failed.")
            return CatalogAuditReport(source, probe, steps.toList())
        }
        if (available.none { it.key == source.key }) {
            record(CatalogAuditStage.SOURCES, CatalogAuditStatus.FAILED, "Source is no longer available.", started)
            skipFrom(1, "Skipped because the source is unavailable.")
            return CatalogAuditReport(source, probe, steps.toList())
        }
        record(CatalogAuditStage.SOURCES, CatalogAuditStatus.PASSED, "Source is available.", started)

        cancelled(CatalogAuditStage.SEARCH)?.let { return it }
        started = nanoTime()
        val items = try {
            gateway.search(source.key, probe)
        } catch (error: Exception) {
            record(CatalogAuditStage.SEARCH, CatalogAuditStatus.FAILED, failure(error), started)
            skipFrom(2, "Skipped because search failed.")
            return CatalogAuditReport(source, probe, steps.toList())
        }
        if (items.isEmpty()) {
            record(CatalogAuditStage.SEARCH, CatalogAuditStatus.FAILED, "Search returned no results.", started)
            skipFrom(2, "Skipped because search returned no item to inspect.")
            return CatalogAuditReport(source, probe, steps.toList())
        }
        record(CatalogAuditStage.SEARCH, CatalogAuditStatus.PASSED, "Search returned ${items.size} result(s).", started)

        cancelled(CatalogAuditStage.DETAIL)?.let { return it }
        started = nanoTime()
        val detail = try {
            gateway.detail(source.key, items.first().id)
        } catch (error: Exception) {
            record(CatalogAuditStage.DETAIL, CatalogAuditStatus.FAILED, failure(error), started)
            skipFrom(3, "Skipped because detail loading failed.")
            return CatalogAuditReport(source, probe, steps.toList())
        }
        val line = detail.lines.firstOrNull { it.episodes.isNotEmpty() }
        if (line == null) {
            record(CatalogAuditStage.DETAIL, CatalogAuditStatus.FAILED, "Detail returned no playable episodes.", started)
            skipFrom(3, "Skipped because detail contained no episode to resolve.")
            return CatalogAuditReport(source, probe, steps.toList())
        }
        record(
            CatalogAuditStage.DETAIL,
            CatalogAuditStatus.PASSED,
            "Detail returned ${detail.lines.size} line(s) and ${detail.lines.sumOf { it.episodes.size }} episode(s).",
            started,
        )

        cancelled(CatalogAuditStage.PLAYBACK)?.let { return it }
        started = nanoTime()
        val playback = try {
            gateway.play(source.key, line.flag, line.episodes.first().id)
        } catch (error: Exception) {
            record(CatalogAuditStage.PLAYBACK, CatalogAuditStatus.FAILED, failure(error), started)
            return CatalogAuditReport(source, probe, steps.toList())
        }
        when {
            !playback.supported -> record(
                CatalogAuditStage.PLAYBACK,
                CatalogAuditStatus.UNSUPPORTED,
                playback.message.ifBlank { "Playback requires a platform capability unavailable on desktop." },
                started,
            )
            !CatalogPlaybackPolicy.isHttpRelayCandidate(playback.url) -> record(
                CatalogAuditStage.PLAYBACK,
                CatalogAuditStatus.FAILED,
                "Playback returned an invalid desktop media URL.",
                started,
            )
            else -> record(
                CatalogAuditStage.PLAYBACK,
                CatalogAuditStatus.PASSED,
                "Playback returned a relayable desktop media URL.",
                started,
            )
        }
        return CatalogAuditReport(source, probe, steps.toList())
    }

    private fun elapsed(started: Long): Long = ((nanoTime() - started) / 1_000_000).coerceAtLeast(0)

    private fun failure(error: Exception): String =
        "Gateway request failed (${error.javaClass.simpleName.ifBlank { "unknown error" }})."
}
