package com.wuming.android.tv.ui

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Collect
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.model.SiteViewModel
import com.wuming.android.tv.search.CollectionWorkflow
import com.wuming.android.tv.setting.AppSettings
import com.wuming.android.tv.ui.activity.CollectActivity
import com.wuming.android.tv.ui.adapter.LeanbackCollectAdapter
import com.wuming.android.tv.ui.custom.GridFocusRestorer
import com.wuming.android.tv.ui.fragment.LeanbackCollectFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanbackCollectSearchFocusInstrumentedTest {
    @Test fun actualCollectionRouteKeepsDpadFocusedCardAfterIdleRanking() {
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)!!
        fun fragment(activity: CollectActivity) = CollectActivity::class.java.getDeclaredMethod("getFragment")
            .apply { isAccessible = true }.invoke(activity) as LeanbackCollectFragment
        fun grid(activity: CollectActivity) = fragment(activity).requireView().findViewById<BaseGridView>(R.id.recycler)
        fun card(view: View, key: String): View? {
            if (view.getTag(R.id.tag_vod_focus_key) == key) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) card(view.getChildAt(i), key)?.let { return it }
            return null
        }
        val history = AppSettings.getKeyword()
        val config = VodConfig.get()
        val originalSites = config.sites
        val setSites = VodConfig::class.java.getDeclaredMethod("setSites", List::class.java).apply { isAccessible = true }
        val sites = listOf(Site.get("collect-focus-popular", "Popular fixture"), Site.get("collect-focus-slow", "Slow fixture"))
        // Disable automatic provider requests during launch; this is an in-memory
        // catalogue fixture, restored below without writing the user's configuration.
        sites.forEach { it.setSearchable(0) }
        setSites.invoke(config, sites)
        fun item(siteIndex: Int, index: Int) = Vod().apply {
            setSite(sites[siteIndex]); setId("focus-fixture-$siteIndex-$index"); setName("Focus fixture $siteIndex $index")
        }
        val initial = (0 until 20).map { item(sites.lastIndex, it) }
        val popular = (0 until 20).map { item(0, it) }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, CollectActivity::class.java).putExtra("keyword", "focus fixture")
            .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            ActivityScenario.launch<CollectActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    (field(activity, "mViewModel") as SiteViewModel).stopSearch()
                    val tabs = field(activity, "mAdapter") as LeanbackCollectAdapter
                    tabs.clear(); tabs.add(Collect.all())
                    CollectActivity::class.java.getDeclaredMethod("setPager").apply { isAccessible = true }.invoke(activity)
                }
                var ready = false
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!ready && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity -> ready = fragment(activity).view != null }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Collection fragment must be visible", ready)
                scenario.onActivity { activity ->
                    val page = fragment(activity)
                    sites.forEach { it.setSearchable(1) }
                    @Suppress("UNCHECKED_CAST")
                    (field(page, "allVods") as MutableList<Vod>).clear()
                    (field(page, "mAdapter") as ArrayObjectAdapter).clear()
                    page.addVideo(initial)
                }
                val target = GridFocusRestorer.vodKey(initial[7])
                ready = false
                val cardDeadline = SystemClock.uptimeMillis() + 5000
                while (!ready && SystemClock.uptimeMillis() < cardDeadline) {
                    scenario.onActivity { activity -> ready = card(grid(activity), target) != null }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("A non-first card must be laid out", ready)
                scenario.onActivity { activity -> assertTrue(card(grid(activity), target)!!.requestFocus()) }
                Thread.sleep(350)
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                var focusedKey = ""
                scenario.onActivity { activity ->
                    focusedKey = GridFocusRestorer.captureVodKey(grid(activity))!!
                    assertNotEquals("Test must use a non-first D-pad-focused card", GridFocusRestorer.vodKey(initial[0]), focusedKey)
                    fragment(activity).addVideo(popular)
                    @Suppress("UNCHECKED_CAST")
                    val frozen = field(fragment(activity), "allVods") as List<Vod>
                    assertEquals("Remote activity must defer reordering", initial + popular, frozen)
                }
                Thread.sleep(2500)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val visibleGrid = grid(activity)
                    assertNotNull("Grid must have actual D-pad focus", visibleGrid.findFocus())
                    assertEquals("Idle sorting focused another video", focusedKey, GridFocusRestorer.captureVodKey(visibleGrid))
                    @Suppress("UNCHECKED_CAST")
                    val values = field(fragment(activity), "allVods") as List<Vod>
                    assertEquals("Popular source must actually move ahead", sites.first().key, values.first().siteKey)
                    assertTrue(values.indexOfFirst { GridFocusRestorer.vodKey(it) == focusedKey } >= popular.size)
                }
                scenario.onActivity { activity ->
                    val source = Collect.create(initial)
                    (field(activity, "mAdapter") as LeanbackCollectAdapter).add(source)
                    val pager = activity.findViewById<androidx.viewpager.widget.ViewPager>(R.id.pager)
                    pager.adapter!!.notifyDataSetChanged()
                    activity.onItemClick(1, source)
                }
                var sourceVisible = false
                val sourceDeadline = SystemClock.uptimeMillis() + 5000
                while (!sourceVisible && SystemClock.uptimeMillis() < sourceDeadline) {
                    scenario.onActivity { activity ->
                        sourceVisible = activity.supportFragmentManager.fragments.filterIsInstance<LeanbackCollectFragment>()
                            .any { it.getSourceKey() == sites.last().key && it.view?.isShown == true && card(it.requireView(), target) != null }
                    }
                    if (!sourceVisible) Thread.sleep(50)
                }
                assertTrue("Selecting a source must still render its initial results", sourceVisible)
            }
        } finally {
            setSites.invoke(config, originalSites)
            AppSettings.putKeyword(history)
        }
    }
}
