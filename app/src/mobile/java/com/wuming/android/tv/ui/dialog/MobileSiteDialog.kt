package com.wuming.android.tv.ui.dialog

import com.wuming.android.tv.utils.ControlSpacing
import android.app.Activity
import android.content.ComponentCallbacks
import android.content.res.Configuration
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.databinding.DialogSiteBinding
import com.wuming.android.tv.impl.SiteCallback
import com.wuming.android.tv.ui.adapter.MobileSiteAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.UiSafe
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class MobileSiteDialog private constructor(
    private val activity: Activity,
    private var callback: SiteCallback?,
) : MobileSiteAdapter.OnClickListener {
    private val binding =
        DialogSiteBinding.inflate(
            com.wuming.android.tv.utils.DialogUtil
                .inflater(activity),
        )
    private val dialog = TvAlertDialogBuilder(activity, R.style.SiteDialogStyle).setTitle(R.string.popup_sites).setView(binding.root).create()
    private val adapter = MobileSiteAdapter(this)
    private var spanCount = computeSpanCount(activity, adapter.itemCount)
    private val configCallbacks: ComponentCallbacks
    constructor(activity: Activity) : this(activity, activity as? SiteCallback)
    constructor(fragment: Fragment) : this(fragment.requireActivity(), fragment as? SiteCallback)

    companion object {
        private const val GRID_THRESHOLD = 5
        private const val MAX_COLUMNS_LANDSCAPE = 3

        @JvmStatic fun create(activity: Activity): MobileSiteDialog = MobileSiteDialog(activity)

        @JvmStatic fun create(fragment: Fragment): MobileSiteDialog = MobileSiteDialog(fragment)

        private fun isLandscape(activity: Activity): Boolean =
            activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        private fun computeSpanCount(
            activity: Activity,
            itemCount: Int,
        ): Int {
            if (!isLandscape(activity) || itemCount <= GRID_THRESHOLD) return 1
            return minOf(MAX_COLUMNS_LANDSCAPE, maxOf(2, Math.ceil(Math.sqrt(itemCount.toDouble())).toInt()))
        }
    }

    init {
        adapter.setGridMode(spanCount > 1)
        adapter.setSpanCount(spanCount)
        configCallbacks =
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    reconfigureForOrientation()
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onLowMemory() {}
            }
        activity.registerComponentCallbacks(configCallbacks)
        dialog.setOnDismissListener { cleanup() }
        dialog.setOnCancelListener { cleanup() }
    }

    open fun search(): MobileSiteDialog {
        adapter.search(true)
        return this
    }

    open fun change(): MobileSiteDialog {
        adapter.change(true)
        return this
    }

    open fun all(): MobileSiteDialog {
        adapter.search(true)
        adapter.change(true)
        return this
    }

    open fun show() {
        setRecyclerView()
        setDialog()
    }

    private fun reconfigureForOrientation() {
        val newSpanCount = computeSpanCount(activity, adapter.itemCount)
        if (newSpanCount == spanCount) return
        spanCount = newSpanCount
        adapter.setGridMode(spanCount > 1)
        adapter.setSpanCount(spanCount)
        binding.recycler.layoutManager = GridLayoutManager(dialog.context, spanCount)
        while (binding.recycler.itemDecorationCount > 0) binding.recycler.removeItemDecorationAt(0)
        binding.recycler.addItemDecoration(SpaceItemDecoration(spanCount, R.dimen.ds_control_gap, dialog.context))
        binding.recycler.setMaxHeight(LayoutConfig.getSiteDialogMaxHeightPixels())
        dialog.window?.let { window ->
            val params = window.attributes
            params.width =
                (ResUtil.getScreenWidth() * LayoutConfig.getMobileSiteDialogWidthRatio(spanCount)).toInt()
            window.attributes = params
        }
        UiSafe.notifyItemRangeChanged(binding.recycler, adapter)
    }

    private fun setRecyclerView() {
        val padding = com.wuming.android.tv.utils.ControlSpacing.dimensionPixels(dialog.context, R.dimen.ds_space_large)
        binding.root.setPadding(padding, padding, padding, padding)
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(dialog.context, spanCount)
        binding.recycler.addItemDecoration(SpaceItemDecoration(spanCount, R.dimen.ds_control_gap, dialog.context))
        binding.recycler.setMaxHeight(LayoutConfig.getSiteDialogMaxHeightPixels())
        // Use the displayed order: hidden sites and performance ranking change indices.
        binding.recycler.post {
            if (dialog.isShowing && adapter.itemCount > 0) binding.recycler.scrollToPosition(adapter.getPosition())
        }
    }

    private fun setDialog() {
        if (adapter.itemCount == 0) return
        val window = dialog.window!!
        val params = window.attributes
        params.width = (ResUtil.getScreenWidth() * LayoutConfig.getMobileSiteDialogWidthRatio(spanCount)).toInt()
        window.attributes = params
        window.setDimAmount(0.35f)
        dialog.show()
    }

    private fun cleanup() {
        activity.unregisterComponentCallbacks(configCallbacks)
        binding.recycler.adapter = null
        adapter.clearListener()
        callback =
            null
    }

    override fun onTextClick(item: Site) {
        val callback = callback ?: return
        callback.setSite(item)
        dialog.dismiss()
    }

    override fun onSearchClick(
        position: Int,
        item: Site,
    ) {
        item.setSearchable(!item.isSearchable()).saveAsync()
        UiSafe.notifyItemChanged(binding.recycler, adapter, position)
    }

    override fun onChangeClick(
        position: Int,
        item: Site,
    ) {
        item.setChangeable(!item.isChangeable()).saveAsync()
        UiSafe.notifyItemChanged(binding.recycler, adapter, position)
    }

    override fun onSearchLongClick(item: Site): Boolean {
        val result = !item.isSearchable()
        for (site in adapter.getItems()) site.setSearchable(result).saveAsync()
        UiSafe.notifyItemRangeChanged(binding.recycler, adapter)
        return true
    }

    override fun onChangeLongClick(item: Site): Boolean {
        val result = !item.isChangeable()
        for (site in adapter.getItems()) site.setChangeable(result).saveAsync()
        UiSafe.notifyItemRangeChanged(binding.recycler, adapter)
        return true
    }
}
