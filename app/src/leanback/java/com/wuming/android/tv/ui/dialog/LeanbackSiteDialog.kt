package com.wuming.android.tv.ui.dialog

import com.wuming.android.tv.utils.ControlSpacing
import android.app.Activity
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.databinding.DialogSiteBinding
import com.wuming.android.tv.impl.SiteCallback
import com.wuming.android.tv.ui.adapter.LeanbackSiteAdapter
import com.wuming.android.tv.ui.custom.SpaceItemDecoration
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class LeanbackSiteDialog(
    activity: Activity,
) : LeanbackSiteAdapter.OnClickListener {
    companion object {
        private const val GRID_THRESHOLD = 5
        private const val MAX_COLUMNS = 3

        @JvmStatic fun create(activity: Activity): LeanbackSiteDialog = LeanbackSiteDialog(activity)

        private fun computeSpanCount(itemCount: Int): Int =
            if (itemCount <= GRID_THRESHOLD) {
                1
            } else {
                minOf(MAX_COLUMNS, maxOf(2, Math.ceil(Math.sqrt(itemCount.toDouble())).toInt()))
            }
    }

    private val adapter = LeanbackSiteAdapter(this).setDialog(true)
    private val binding =
        DialogSiteBinding.inflate(
            com.wuming.android.tv.utils.DialogUtil
                .inflater(activity),
        )
    private val dialog = TvAlertDialogBuilder(activity, R.style.SiteDialogStyle).setTitle(R.string.popup_sites).setView(binding.root).create()
    private var decoration: RecyclerView.ItemDecoration? = null
    private var callback: SiteCallback? = activity as? SiteCallback
    private var spanCount = computeSpanCount(adapter.itemCount)
    private var type = 0

    init {
        adapter.setGridMode(spanCount > 1)
        adapter.setSpanCount(spanCount)
        dialog.setOnDismissListener { cleanup() }
        dialog.setOnCancelListener { cleanup() }
    }

    open fun search(): LeanbackSiteDialog {
        type = LeanbackSiteAdapter.TYPE_SEARCH
        return this
    }

    open fun action(): LeanbackSiteDialog {
        binding.action.visibility = View.VISIBLE
        return this
    }

    open fun show() {
        setType(type)
        initView()
        initEvent()
    }

    private fun getWidth(): Float = LayoutConfig.getTvSiteDialogWidthRatio(spanCount)

    private fun initView() {
        setRecyclerView()
        setDialog()
        setMode()
    }

    private fun initEvent() {
        binding.select.setOnClickListener { adapter.selectAll() }
        binding.cancel.setOnClickListener { adapter.cancelAll() }
        binding.search.setOnClickListener {
            setType(
                if (it.isActivated) LeanbackSiteAdapter.TYPE_SELECT else LeanbackSiteAdapter.TYPE_SEARCH,
            )
        }
        binding.change.setOnClickListener {
            setType(
                if (it.isActivated) LeanbackSiteAdapter.TYPE_SELECT else LeanbackSiteAdapter.TYPE_CHANGE,
            )
        }
    }

    private fun setRecyclerView() {
        val padding = com.wuming.android.tv.utils.ControlSpacing.dimensionPixels(dialog.context, R.dimen.ds_space_large)
        binding.root.setPadding(padding, padding, padding, padding)
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.itemAnimator = null
        binding.recycler.setMaxHeight(LayoutConfig.getSiteDialogMaxHeightPixels())
        decoration?.let { binding.recycler.removeItemDecoration(it) }
        decoration = SpaceItemDecoration(spanCount, R.dimen.ds_control_gap, dialog.context)
        binding.recycler.addItemDecoration(decoration!!)
        binding.recycler.layoutManager = GridLayoutManager(dialog.context, spanCount)
        // Use the displayed order: hidden sites and performance ranking change indices.
        if (!binding.mode.hasFocus()) binding.recycler.post {
            if (dialog.isShowing && adapter.itemCount > 0) binding.recycler.scrollToPosition(adapter.getPosition())
        }
    }

    private fun setDialog() {
        if (adapter.itemCount == 0) return
        val window = dialog.window!!
        val params = window.attributes
        params.width = (ResUtil.getScreenWidth() * getWidth()).toInt()
        window.attributes = params
        window.setDimAmount(0.35f)
        dialog.show()
    }

    private fun setMode() {
        binding.mode.isEnabled = false
        binding.mode.setImageResource(
            if (spanCount == 1) {
                R.drawable.ic_site_grid
            } else {
                R.drawable.ic_site_list
            },
        )
    }

    private fun setType(type: Int) {
        binding.search.isActivated = type == LeanbackSiteAdapter.TYPE_SEARCH
        binding.change.isActivated = type == LeanbackSiteAdapter.TYPE_CHANGE
        binding.select.isClickable = type != LeanbackSiteAdapter.TYPE_SELECT
        binding.cancel.isClickable = type != LeanbackSiteAdapter.TYPE_SELECT
        this.type = type
        adapter.setType(type)
    }

    private fun cleanup() {
        binding.recycler.adapter = null
        binding.select.setOnClickListener(null)
        binding.cancel.setOnClickListener(null)
        binding.search.setOnClickListener(null)
        binding.change.setOnClickListener(null)
        adapter.clearListener()
        decoration = null
        callback = null
    }

    override fun onItemClick(item: Site) {
        val callback = callback ?: return
        callback.setSite(item)
        dialog.dismiss()
    }
}
