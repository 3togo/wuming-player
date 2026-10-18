package com.wuming.android.tv.ui.controller

import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import com.wuming.android.tv.api.config.LiveConfig
import com.wuming.android.tv.bean.Epg
import com.wuming.android.tv.bean.EpgData
import com.wuming.android.tv.bean.Group
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.utils.LayoutConfig
import java.util.ArrayList

open class LiveController(
    activity: Any?,
) {
    open fun getHomeButtonText(): String = LiveConfig.get().getHome().getName()

    open class GroupFilterResult(
        @JvmField val visibleGroups: List<Group>,
        @JvmField val hiddenGroups: List<Group>,
    )

    open fun filterGroups(live: Live): GroupFilterResult {
        val visible = ArrayList<Group>()
        val hidden = ArrayList<Group>()
        for (group in live.getGroups()) if (group.isHidden()) hidden.add(group) else visible.add(group)
        return GroupFilterResult(visible, hidden)
    }

    open fun calculateGroupWidth(live: Live): Int {
        var maxNameLength = 0
        for (group in live.getGroups()) {
            if (group.isHidden()) continue
            val len = group.getName().length
            if (len > maxNameLength) maxNameLength = len
        }
        return LayoutConfig.getLiveGroupWidthDp(maxNameLength)
    }

    open fun calculateChannelWidth(group: Group): Int {
        var maxNameLength = 0
        for (channel in group.getChannel()) {
            val len = channel.getName().length
            if (len > maxNameLength) maxNameLength = len
        }
        return LayoutConfig.getLiveChannelWidthDp(maxNameLength)
    }

    open fun calculateEpgWidth(epg: Epg): Int {
        var maxTitleLength = 0
        for (data in epg.getList()) {
            val len = data.getTitle().length
            if (len > maxTitleLength) maxTitleLength = len
        }
        if (maxTitleLength == 0) return 0
        return LayoutConfig.getLiveEpgWidthDp(maxTitleLength)
    }

    open fun applyWidth(
        view: View?,
        width: Int,
    ) {
        if (view == null) return
        val params = view.layoutParams ?: return
        val metrics = view.context.resources.displayMetrics
        params.width = (width * metrics.density).toInt()
        view.layoutParams = params
    }
}
