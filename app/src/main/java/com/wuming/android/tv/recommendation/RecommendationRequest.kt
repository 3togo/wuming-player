package com.wuming.android.tv.recommendation

import androidx.lifecycle.LifecycleOwner
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.utils.LifecycleCleanup
import java.util.function.Consumer
import java.util.function.Supplier

/** Android UI delivery only. Start, invalidate and receive callbacks on main. */
@JvmSuppressWildcards
class RecommendationRequest
    @JvmOverloads
    constructor(
        private val ranker: Ranker = Ranker(RecommendationEngine::rankAsync),
    ) {
        fun interface Ranker {
            fun rank(
                candidates: List<Vod>,
                siteKey: String,
                callback: Consumer<List<Vod>>,
            )
        }

        private var pending: Delivery? = null

        fun rank(
            owner: LifecycleOwner,
            candidates: List<Vod>,
            siteKey: String,
            currentSite: Supplier<String>,
            callback: Consumer<List<Vod>>,
        ) {
            invalidate()
            val delivery = Delivery(owner, siteKey, currentSite, callback)
            pending = delivery
            if (!delivery.active) return
            try {
                ranker.rank(candidates, siteKey, delivery)
            } catch (error: RuntimeException) {
                delivery.close()
                throw error
            }
        }

        /** Drop only this screen's delivery; never cancel the shared profile load. */
        fun invalidate() {
            pending?.close()
            pending = null
        }

        private class Delivery(
            owner: LifecycleOwner,
            private val siteKey: String,
            private var currentSite: Supplier<String>?,
            private var callback: Consumer<List<Vod>>?,
        ) : Consumer<List<Vod>>,
            AutoCloseable {
            private var lifetime: LifecycleCleanup? = null
            val active: Boolean get() = callback != null

            init {
                lifetime = LifecycleCleanup(owner) { close() }
            }

            override fun accept(value: List<Vod>) {
                val target = callback ?: return
                val source = currentSite
                close()
                if (source?.get() == siteKey) target.accept(value)
            }

            override fun close() {
                callback = null
                currentSite = null
                lifetime?.close()
                lifetime = null
            }
        }
    }
