package com.wuming.android.tv.media.epg

class ProgramGuideLayout(
    windowStartMs: Long,
    windowEndMs: Long,
    nowMs: Long,
    nowOffset: Double,
    rows: List<Row>?
) {
    val windowStartMs: Long = windowStartMs
    val windowEndMs: Long = windowEndMs
    val nowMs: Long = nowMs
    val nowOffset: Double = nowOffset.coerceIn(0.0, 1.0)
    val rows: List<Row> = java.util.List.copyOf(rows ?: emptyList())

    init {
        require(windowEndMs > windowStartMs) { "Guide window must have positive duration" }
    }

    fun windowStartMs(): Long = windowStartMs
    fun windowEndMs(): Long = windowEndMs
    fun nowMs(): Long = nowMs
    fun nowOffset(): Double = nowOffset
    fun rows(): List<Row> = rows

    override fun equals(other: Any?): Boolean =
        other is ProgramGuideLayout && windowStartMs == other.windowStartMs &&
        windowEndMs == other.windowEndMs &&
        nowMs == other.nowMs &&
        java.lang.Double.compare(nowOffset, other.nowOffset) == 0 &&
        rows == other.rows

    override fun hashCode(): Int = listOf(windowStartMs, windowEndMs, nowMs, nowOffset, rows).hashCode()

    override fun toString(): String = "ProgramGuideLayout(windowStartMs=$windowStartMs, windowEndMs=$windowEndMs, nowMs=$nowMs, nowOffset=$nowOffset, rows=$rows)"


    val isNowVisible: Boolean
        get() = nowMs >= windowStartMs && nowMs <= windowEndMs

    class Channel(
        id: String?,
        name: String?,
        programmes: List<Programme>?
    ) {
        val id: String = id ?: ""
        val name: String = name ?: ""
        val programmes: List<Programme> = java.util.List.copyOf(programmes ?: emptyList())


        fun id(): String = id
        fun name(): String = name
        fun programmes(): List<Programme> = programmes

        override fun equals(other: Any?): Boolean =
            other is Channel && id == other.id &&
            name == other.name &&
            programmes == other.programmes

        override fun hashCode(): Int = listOf(id, name, programmes).hashCode()

        override fun toString(): String = "Channel(id=$id, name=$name, programmes=$programmes)"

    }

    class Programme(
        id: String?,
        title: String?,
        startMs: Long,
        endMs: Long
    ) {
        val id: String = id ?: ""
        val title: String = title ?: ""
        val startMs: Long = startMs
        val endMs: Long = endMs


        fun id(): String = id
        fun title(): String = title
        fun startMs(): Long = startMs
        fun endMs(): Long = endMs

        override fun equals(other: Any?): Boolean =
            other is Programme && id == other.id &&
            title == other.title &&
            startMs == other.startMs &&
            endMs == other.endMs

        override fun hashCode(): Int = listOf(id, title, startMs, endMs).hashCode()

        override fun toString(): String = "Programme(id=$id, title=$title, startMs=$startMs, endMs=$endMs)"

    }

    class Row(
        channelId: String?,
        channelName: String?,
        cells: List<Cell>?
    ) {
        val channelId: String = channelId ?: ""
        val channelName: String = channelName ?: ""
        val cells: List<Cell> = java.util.List.copyOf(cells ?: emptyList())


        fun channelId(): String = channelId
        fun channelName(): String = channelName
        fun cells(): List<Cell> = cells

        override fun equals(other: Any?): Boolean =
            other is Row && channelId == other.channelId &&
            channelName == other.channelName &&
            cells == other.cells

        override fun hashCode(): Int = listOf(channelId, channelName, cells).hashCode()

        override fun toString(): String = "Row(channelId=$channelId, channelName=$channelName, cells=$cells)"

    }

    class Cell(
        programmeId: String?,
        title: String?,
        originalStartMs: Long,
        originalEndMs: Long,
        clippedStartMs: Long,
        clippedEndMs: Long,
        offset: Double,
        width: Double,
        current: Boolean
    ) {
        val programmeId: String = programmeId ?: ""
        val title: String = title ?: ""
        val originalStartMs: Long = originalStartMs
        val originalEndMs: Long = originalEndMs
        val clippedStartMs: Long = clippedStartMs
        val clippedEndMs: Long = clippedEndMs
        val offset: Double = offset.coerceIn(0.0, 1.0)
        val width: Double = width.coerceIn(0.0, 1.0)
        val current: Boolean = current


        fun programmeId(): String = programmeId
        fun title(): String = title
        fun originalStartMs(): Long = originalStartMs
        fun originalEndMs(): Long = originalEndMs
        fun clippedStartMs(): Long = clippedStartMs
        fun clippedEndMs(): Long = clippedEndMs
        fun offset(): Double = offset
        fun width(): Double = width
        fun current(): Boolean = current

        override fun equals(other: Any?): Boolean =
            other is Cell && programmeId == other.programmeId &&
            title == other.title &&
            originalStartMs == other.originalStartMs &&
            originalEndMs == other.originalEndMs &&
            clippedStartMs == other.clippedStartMs &&
            clippedEndMs == other.clippedEndMs &&
            java.lang.Double.compare(offset, other.offset) == 0 &&
            java.lang.Double.compare(width, other.width) == 0 &&
            current == other.current

        override fun hashCode(): Int = listOf(programmeId, title, originalStartMs, originalEndMs, clippedStartMs, clippedEndMs, offset, width, current).hashCode()

        override fun toString(): String = "Cell(programmeId=$programmeId, title=$title, originalStartMs=$originalStartMs, originalEndMs=$originalEndMs, clippedStartMs=$clippedStartMs, clippedEndMs=$clippedEndMs, offset=$offset, width=$width, current=$current)"

    }

    companion object {
        @JvmStatic
        fun create(
            windowStartMs: Long,
            windowEndMs: Long,
            nowMs: Long,
            channels: List<Channel>?
        ): ProgramGuideLayout {
            require(windowEndMs > windowStartMs) { "Guide window must have positive duration" }
            val duration = windowEndMs - windowStartMs
            val rows = mutableListOf<Row>()
            channels?.forEach { channel ->
                val cells = mutableListOf<Cell>()
                val programmes = channel.programmes
                programmes
                    .filter { it.endMs > it.startMs && it.endMs > windowStartMs && it.startMs < windowEndMs }
                    .sortedWith(compareBy({ it.startMs }, { it.endMs }))
                    .forEach { programme ->
                        val clippedStart = maxOf(windowStartMs, programme.startMs)
                        val clippedEnd = minOf(windowEndMs, programme.endMs)
                        cells.add(Cell(
                            programme.id, programme.title, programme.startMs, programme.endMs,
                            clippedStart, clippedEnd,
                            (clippedStart - windowStartMs).toDouble() / duration,
                            (clippedEnd - clippedStart).toDouble() / duration,
                            nowMs >= programme.startMs && nowMs < programme.endMs
                        ))
                    }
                rows.add(Row(channel.id, channel.name, cells))
            }
            return ProgramGuideLayout(
                windowStartMs, windowEndMs, nowMs,
                (nowMs - windowStartMs).toDouble() / duration,
                rows
            )
        }
    }
}
