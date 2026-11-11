package com.wuming.android.tv.ui

import android.view.KeyEvent
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.search.FastSearchCoordinator
import com.wuming.android.tv.ui.activity.LeanbackFastSearchActivity
import com.wuming.android.tv.ui.adapter.LeanbackFastSearchAdapter
import com.wuming.android.tv.ui.adapter.LeanbackFastListAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeanbackSearchOrderInstrumentedTest {
    @Test fun remoteInputAppendsUntilIdleThenSortsWithoutChangingFocusedVideo() = verifyFocus(false)
    @Test fun idleSortKeepsFocusedSiteAndItsFilter() = verifyFocus(true)

    private fun verifyFocus(siteFocus: Boolean) {
        val slow = Site.get("idle-slow", "Slow source").apply { setSearchable(1) }
        val popular = Site.get("idle-popular", "Popular source").apply { setSearchable(1) }
        fun vod(site: Site, id: String, title: String) = Vod().apply {
            setSite(site); setId(id); setName(title)
        }
        val first = vod(slow, "first", "Astronomy")
        val later = listOf("Cooking", "Biology", "History", "Music", "Ocean", "Forest", "Travel",
            "Football", "Painting", "Physics", "Drama", "Comedy", "Robotics", "Chess", "Gardens",
            "Mountains", "Volcanoes", "Deserts", "Rivers", "Architecture")
            .mapIndexed { index, title -> vod(popular, "later-$index", title) }
        val activityClass = LeanbackFastSearchActivity::class.java
        fun field(activity: LeanbackFastSearchActivity, name: String): Any =
            activityClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)!!
        fun add(activity: LeanbackFastSearchActivity, value: Vod) {
            activityClass.getDeclaredMethod("searchData", List::class.java).apply { isAccessible = true }
                .invoke(activity, listOf(value))
        }
        // No activity result is needed. Keep this test in its own task and avoid the
        // separate test-APK BootstrapActivity, whose UID this firmware kills when hidden.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        ActivityScenario.launch<LeanbackFastSearchActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                (field(activity, "searchCoordinator") as FastSearchCoordinator).begin(listOf(popular, slow), null)
                @Suppress("UNCHECKED_CAST")
                (field(activity, "sourceOrder") as MutableMap<String, Int>).putAll(mapOf(popular.name to 0, slow.name to 1))
                (field(activity, "spListAdapter") as LeanbackFastListAdapter).addData(activity.getString(R.string.fs_show_all))
                add(activity, first)
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var laidOut = false
            while (!laidOut && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    laidOut = activity.findViewById<RecyclerView>(R.id.mGridView)
                        .findViewHolderForAdapterPosition(0) != null
                }
                if (!laidOut) Thread.sleep(50)
            }
            assertTrue("First result must be laid out before remote input", laidOut)
            scenario.onActivity { activity ->
                val grid = activity.findViewById<RecyclerView>(R.id.mGridView)
                val sites = activity.findViewById<RecyclerView>(R.id.mGridViewWord)
                val target = if (siteFocus) sites.findViewHolderForAdapterPosition(1) else grid.findViewHolderForAdapterPosition(0)
                assertNotNull("Focused item must be attached", target)
                assertTrue(target!!.itemView.requestFocus())
                // A boundary Left press keeps the focused first card while exercising real remote dispatch.
                val key = if (siteFocus) KeyEvent.KEYCODE_DPAD_CENTER else KeyEvent.KEYCODE_DPAD_LEFT
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                later.forEach { add(activity, it) }
                assertEquals(listOf(first) + later, (field(activity, "searchAdapter") as LeanbackFastSearchAdapter).getData())
                assertEquals(listOf(activity.getString(R.string.fs_show_all), slow.name, popular.name),
                    (field(activity, "spListAdapter") as LeanbackFastListAdapter).getData())
            }
            Thread.sleep(2300)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val adapter = field(activity, "searchAdapter") as LeanbackFastSearchAdapter
                assertEquals(later + first, adapter.getData())
                assertEquals(listOf(activity.getString(R.string.fs_show_all), popular.name, slow.name),
                    (field(activity, "spListAdapter") as LeanbackFastListAdapter).getData())
                val grid = activity.findViewById<RecyclerView>(if (siteFocus) R.id.mGridViewWord else R.id.mGridView)
                val focused = grid.findContainingViewHolder(activity.currentFocus!!)
                assertNotNull(focused)
                val expectedPosition = if (siteFocus) 2 else later.size
                assertEquals(expectedPosition, focused!!.bindingAdapterPosition)
                assertEquals(grid.adapter!!.getItemId(expectedPosition), focused.itemId)
                if (siteFocus) {
                    assertEquals(listOf(first), (field(activity, "searchAdapterFilter") as LeanbackFastSearchAdapter).getData())
                }
            }
        }
    }
}
