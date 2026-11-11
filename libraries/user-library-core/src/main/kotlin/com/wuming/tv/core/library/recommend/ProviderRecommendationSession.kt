package com.wuming.tv.core.library.recommend

/** Bounded recommendation seeds. Later pages retain order but cannot resurrect deleted history. */
class ProviderRecommendationSession {
    private val snapshots = LinkedHashMap<String, List<String>>()

    @Synchronized
    fun seeds(scope: String, page: Int, visibleNames: List<String>, privateViewing: Boolean): List<String> {
        if (privateViewing) { snapshots.remove(scope); return emptyList() }
        val visible = visibleNames.asSequence().map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 512 && it.none(Character::isISOControl) }.distinct().toList()
        val previous = snapshots[scope]
        val result = if (page <= 1 || previous == null) visible.take(10) else previous.filter { it in visible }
        snapshots.remove(scope)
        snapshots[scope] = result
        while (snapshots.size > 32) snapshots.remove(snapshots.keys.first())
        return result.toList()
    }

    @Synchronized fun clear() = snapshots.clear()

    companion object {
        @JvmStatic
        fun applies(api: String, name: String, category: String): Boolean = category == "guess_you_like" &&
            (api.contains("douban", true) || name.contains("douban", true) || name.contains("豆瓣"))
    }
}
