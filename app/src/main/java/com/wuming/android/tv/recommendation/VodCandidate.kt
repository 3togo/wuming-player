package com.wuming.android.tv.recommendation

import com.wuming.android.tv.bean.Vod
import com.wuming.tv.core.catalog.RecommendationCandidate

/** Adapts the Android [Vod] bean to the platform-neutral recommendation contract. */
internal class VodCandidate(val source: Vod) : RecommendationCandidate {
    override val itemId: String get() = source.id
    override val typeName: String get() = source.typeName
    override val year: String get() = source.year
    override val area: String get() = source.area
    override val director: String get() = source.director
    override val actor: String get() = source.actor
    override val content: String get() = source.content
}
