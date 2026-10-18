package com.wuming.android.tv.utils

import android.util.TypedValue
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Class as Category
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.ui.activity.CloudStorageActivity
import com.wuming.android.tv.ui.adapter.MobileTypeAdapter
import com.wuming.tv.core.design.TextRole
import com.wuming.tv.core.design.TypographyPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypographyOwnershipInstrumentedTest {
    @Test fun rotationAndLayoutDensityDoNotOverridePlatformFontScaling() {
        ActivityScenario.launch(CloudStorageActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (fontScale in listOf(1f, 1.3f, 2f)) {
                    var portraitSize = 0f
                    for (orientation in listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)) {
                        val config = Configuration(activity.resources.configuration).also {
                            it.fontScale = fontScale
                            it.orientation = orientation
                        }
                        val context = activity.createConfigurationContext(config)
                        val metrics = DisplayMetrics().also { it.setTo(context.resources.displayMetrics) }
                        metrics.density = config.densityDpi / 160f
                        @Suppress("DEPRECATION")
                        metrics.scaledDensity = metrics.density * fontScale
                        val tablet = TypographyPolicy.tabletScale(config.smallestScreenWidthDp.toFloat(),
                            com.wuming.android.tv.setting.DisplaySettings.getUiTextScaleBaseline(),
                            com.wuming.android.tv.setting.DisplaySettings.getUiTextScaleCap())
                        val expected = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                            TypographyPolicy.logicalSize(TextRole.LABEL) * tablet, metrics)
                        // Model AutoSize's geometry-only density mutation without changing configuration.
                        val original = DisplayMetrics().also { it.setTo(context.resources.displayMetrics) }
                        try {
                            context.resources.displayMetrics.density *= 2f
                            @Suppress("DEPRECATION")
                            context.resources.displayMetrics.scaledDensity *= 2f
                            val actual = TextScale.pixels(context, TextRole.LABEL)
                            assertEquals(expected, actual, 0.01f)
                            if (orientation == Configuration.ORIENTATION_PORTRAIT) portraitSize = actual
                            else assertEquals(portraitSize, actual, 0.01f)
                        } finally {
                            context.resources.displayMetrics.setTo(original)
                        }
                    }
                }
            }
        }
    }

    @Test fun categoryRebindingRestoresSizeForShortAndLongLabels() {
        ActivityScenario.launch(CloudStorageActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = FrameLayout(activity)
                val adapter = MobileTypeAdapter(null)
                adapter.addAll(Result().also { result ->
                    result.setTypes(listOf("短", "这是很长的分类名称", "A long category name").mapIndexed { index, name ->
                        Category().also { it.setTypeId(index.toString()); it.setTypeName(name) }
                    })
                })
                val holder = adapter.createViewHolder(parent, 0)
                val expected = TextScale.pixels(parent, TextRole.LABEL)
                assertEquals(expected, holder.binding.text.textSize, 0.01f)
                for (position in 0 until adapter.itemCount) {
                    holder.binding.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, expected * 1.5f)
                    adapter.bindViewHolder(holder, position)
                    assertEquals(expected, holder.binding.text.textSize, 0.01f)
                }
            }
        }
    }

    @Test fun cachedTargetCannotConcealASetterAndOverlayVariantIsIdempotent() {
        ActivityScenario.launch(CloudStorageActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val text = TextView(activity)
                TextScale.setRole(text, TextRole.CAPTION)
                val baseline = text.textSize
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseline * 2f)
                TextScale.bind(text)
                assertEquals(baseline, text.textSize, 0.01f)
                repeat(3) { TextScale.setPlayerOverlayScale(text, 1.6f); TextScale.apply(text) }
                assertEquals(baseline * 1.6f, text.textSize, 0.01f)
            }
        }
    }

    @Test fun recycledChildrenGetTheirRoleBeforeDisplay() {
        ActivityScenario.launch(CloudStorageActivity::class.java).use { scenario ->
            lateinit var recycler: RecyclerView
            scenario.onActivity { activity ->
                recycler = RecyclerView(activity)
                recycler.layoutManager = LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
                recycler.adapter = object : RecyclerView.Adapter<Row>() {
                    override fun getItemCount() = 100
                    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Row = Row(
                        LayoutInflater.from(parent.context).inflate(R.layout.adapter_type, parent, false) as TextView
                    )
                    override fun onBindViewHolder(holder: Row, position: Int) {
                        holder.text.text = "分类 $position"
                        // Simulate a legacy setter after inflation; attachment must repair it.
                        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 100f)
                    }
                }
                TextScale.apply(recycler)
                (activity.window.decorView as ViewGroup).addView(recycler, ViewGroup.LayoutParams(600, 150))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            for (position in listOf(0, 70, 15, 95)) {
                scenario.onActivity { recycler.scrollToPosition(position) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    assertTrue("Scrolling must expose some children", recycler.childCount > 0)
                    for (index in 0 until recycler.childCount) {
                        val child = recycler.getChildAt(index) as TextView
                        assertEquals(TextScale.pixels(child, TextRole.LABEL), child.textSize, 0.01f)
                    }
                }
            }
            scenario.onActivity { (recycler.parent as ViewGroup).removeView(recycler) }
        }
    }

    private class Row(val text: TextView) : RecyclerView.ViewHolder(text)
}
