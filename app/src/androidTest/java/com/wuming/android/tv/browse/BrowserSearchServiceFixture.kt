package com.wuming.android.tv.browse

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.wuming.android.tv.event.ConfigEvent
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.util.concurrent.CopyOnWriteArrayList
import androidx.media3.common.MediaItem
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.Vod
import com.wuming.tv.core.cache.ExpiringLruCache
import com.wuming.tv.core.search.MediaBrowserSearch
import com.wuming.tv.core.search.SearchQueryPlan
import com.wuming.tv.core.search.SearchQueryPlanner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Deterministic provider/metadata ports; service, Binder, browse adapter and core remain real. */
internal class BrowserSearchServiceFixture : AutoCloseable {
    private val original = VodBrowse.browserSearch
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val invalidations = CopyOnWriteArrayList<String>()
    private val observer = BrowserInvalidationObserver(invalidations)
    val completed = LinkedBlockingQueue<Unit>()
    private val workers = object : ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) {
        override fun afterExecute(task: Runnable, failure: Throwable?) {
            super.afterExecute(task, failure)
            completed.add(Unit)
        }
    }
    val timers = Executors.newSingleThreadScheduledExecutor()
    val calls = AtomicInteger()
    val clock = AtomicLong()
    @Volatile var strict = false
    @Volatile var sources = listOf("one")
    @Volatile var provider: (String, String) -> List<String> = { source, query -> listOf("$source-$query") }
    private val gates = mutableListOf<CountDownLatch>()

    init {
        EventBus.getDefault().register(observer)
        context.registerComponentCallbacks(observer)
        VodBrowse.browserSearch = MediaBrowserSearch(workers, timers,
            object : MediaBrowserSearch.Host<Site, Vod, MediaItem> {
                override fun plan(query: String) = SearchQueryPlanner.localOnly().plan(query)
                override fun strict() = strict
                override fun sources() = sources.map { Site.get(it, it) }
                override fun key(source: Site) = source.key
                override fun search(source: Site, plan: SearchQueryPlan, variant: SearchQueryPlan.Variant, strict: Boolean): List<Vod> {
                    calls.incrementAndGet()
                    return provider(source.key, variant.text()).map { id -> Vod().also { it.setId(id) } }
                }
                override fun merge(items: List<Vod>) = items
                override fun items(results: List<Vod>, query: String) = results.map {
                    BrowseTree.playable(it.id, it.id, null, null)
                }
            }, ExpiringLruCache(4, TimeUnit.MINUTES.toMillis(5), clock::get), 5_000, 50)
    }

    fun gate() = CountDownLatch(1).also { gates.add(it) }

    override fun close() {
        EventBus.getDefault().unregister(observer)
        context.unregisterComponentCallbacks(observer)
        gates.forEach { it.countDown() }
        VodBrowse.browserSearch.clear()
        workers.shutdownNow()
        timers.shutdownNow()
        try {
            check(workers.awaitTermination(10, TimeUnit.SECONDS)) { "Provider fixture did not terminate" }
            check(timers.awaitTermination(10, TimeUnit.SECONDS)) { "Timer fixture did not terminate" }
        } finally {
            VodBrowse.browserSearch = original
        }
    }
}


/** Failure diagnostics distinguish framework/config invalidation from a lost service callback. */
@Suppress("deprecation")
class BrowserInvalidationObserver(private val events: MutableList<String>) : ComponentCallbacks2 {
    @Subscribe fun config(event: ConfigEvent) { events.add("config:vod=${event.isVod}") }
    override fun onTrimMemory(level: Int) { events.add("trim:$level") }
    override fun onLowMemory() { events.add("low-memory") }
    override fun onConfigurationChanged(configuration: Configuration) = Unit
}
