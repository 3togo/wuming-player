package com.wuming.tv.core.epg

data class GuideChannel(
    val id: String,
    val displayName: String,
    val aliases: Set<String> = emptySet(),
)

data class ProgrammeGuideEntry(
    val channelId: String,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val description: String = "",
    val category: String = "",
) {
    init {
        require(endTimeMs >= startTimeMs) { "Programme end must not precede start" }
    }

    fun contains(timeMs: Long): Boolean = timeMs >= startTimeMs && timeMs < endTimeMs
}

data class NowNext(
    val now: ProgrammeGuideEntry?,
    val next: ProgrammeGuideEntry?,
)

object ProgrammeGuidePolicy {

    @JvmStatic
    fun nowNext(entries: Collection<ProgrammeGuideEntry>, timeMs: Long): NowNext {
        val ordered = entries.sortedBy(ProgrammeGuideEntry::startTimeMs)
        val current = ordered.firstOrNull { it.contains(timeMs) }
        val next = ordered.firstOrNull { it.startTimeMs > timeMs && it !== current }
        return NowNext(current, next)
    }

    @JvmStatic
    fun within(
        entries: Collection<ProgrammeGuideEntry>,
        rangeStartMs: Long,
        rangeEndMs: Long,
    ): List<ProgrammeGuideEntry> {
        require(rangeEndMs >= rangeStartMs) { "Range end must not precede start" }
        return entries.filter { it.endTimeMs > rangeStartMs && it.startTimeMs < rangeEndMs }
            .sortedBy(ProgrammeGuideEntry::startTimeMs)
    }
}
