package com.wuming.android.tv.ui.base

import com.wuming.android.tv.bean.Group

/** Channel/group navigation shared by touch and remote-controlled live screens. */
object LiveSessionNavigator {
    @JvmRecord data class Selection(
        val groupIndex: Int,
        val channelIndex: Int,
    )

    @JvmStatic fun move(
        groups: List<Group>?,
        groupIndex: Int,
        channelIndex: Int,
        direction: Int,
        acrossGroups: Boolean,
    ): Selection {
        if (groups.isNullOrEmpty()) return Selection(-1, -1)
        val safeGroup = maxOf(0, minOf(groupIndex, groups.size - 1))
        val group = groups[safeGroup]
        if (group.isEmpty()) return Selection(safeGroup, -1)
        val target = channelIndex + Integer.signum(direction)
        if (target >= 0 && target < group.getChannel().size) return Selection(safeGroup, target)
        if (!acrossGroups) return Selection(safeGroup, if (target < 0) group.getChannel().size - 1 else 0)
        var candidate = safeGroup
        repeat(groups.size) {
            candidate = Math.floorMod(candidate + Integer.signum(direction), groups.size)
            val next = groups[candidate]
            if (!next.skip() && !next.isEmpty()) return Selection(candidate, if (direction < 0) next.getChannel().size - 1 else 0)
        }
        return Selection(safeGroup, maxOf(0, minOf(channelIndex, group.getChannel().size - 1)))
    }
}
