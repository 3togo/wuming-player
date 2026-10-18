package com.wuming.android.tv.ui.dialog

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.DialogSiteRankingBinding
import com.wuming.android.tv.ui.adapter.SiteRankingAdapter
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.SitePerformanceTracker
import com.wuming.android.tv.utils.TvAlertDialogBuilder

open class SiteRankingDialog {
    private var binding: DialogSiteRankingBinding? = null
    private val adapter = SiteRankingAdapter()
    private var dialog: AlertDialog? = null
    private var currentSort = SiteRankingAdapter.SORT_PERFORMANCE

    companion object {
        @JvmStatic fun create(): SiteRankingDialog = SiteRankingDialog()

        private fun isClickConsumed(v: View): Boolean = v.getTag(R.id.tag_click_consumed) == true
    }

    open fun show(activity: Activity) {
        binding =
            DialogSiteRankingBinding.inflate(
                com.wuming.android.tv.utils.DialogUtil
                    .inflater(activity),
            )
        dialog = TvAlertDialogBuilder(activity).setTitle(R.string.site_ranking).setView(binding!!.root).create()
        dialog!!.setOnDismissListener { clear() }
        initView()
        initEvent()
        setDialog()
    }

    private fun initView() {
        val binding = binding!!
        binding.recycler.adapter = adapter
        binding.recycler.itemAnimator = null
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = LinearLayoutManager(dialog!!.context)
        adapter.setOnSelectionChanged { if (adapter.isSelectMode()) updateClearButtonText() }
        updateSortHighlight()
        updateSelectUI()
        adapter.refresh()
        binding.recycler.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) {
                    child.isFocusable = true
                    child.isFocusableInTouchMode = true
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
            },
        )
    }

    private fun initEvent() {
        val binding = binding!!
        binding.sortPerformance.setOnClickListener { setSort(SiteRankingAdapter.SORT_PERFORMANCE) }
        binding.sortReliability.setOnClickListener { setSort(SiteRankingAdapter.SORT_RELIABILITY) }
        binding.sortSpeed.setOnClickListener { setSort(SiteRankingAdapter.SORT_SPEED) }
        binding.selectToggle.setOnClickListener { toggleSelectMode() }
        binding.clearAll.setOnClickListener { clearSelected() }
        val keyListener =
            View.OnKeyListener { v, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_UP) {
                    if (!isClickConsumed(v)) v.performClick()
                    true
                } else {
                    false
                }
            }
        binding.sortPerformance.setOnKeyListener(keyListener)
        binding.sortReliability.setOnKeyListener(keyListener)
        binding.sortSpeed.setOnKeyListener(keyListener)
        binding.selectToggle.setOnKeyListener(keyListener)
        binding.clearAll.setOnKeyListener(keyListener)
    }

    private fun setSort(mode: Int) {
        currentSort = mode
        updateSortHighlight()
        adapter.setSortMode(mode)
    }

    private fun updateSortHighlight() {
        binding!!.sortPerformance.alpha = if (currentSort == SiteRankingAdapter.SORT_PERFORMANCE) 1f else 0.5f
        binding!!.sortReliability.alpha = if (currentSort == SiteRankingAdapter.SORT_RELIABILITY) 1f else 0.5f
        binding!!.sortSpeed.alpha = if (currentSort == SiteRankingAdapter.SORT_SPEED) 1f else 0.5f
    }

    private fun toggleSelectMode() {
        adapter.setSelectMode(!adapter.isSelectMode())
        updateSelectUI()
    }

    private fun updateSelectUI() {
        val binding = binding!!
        if (adapter.isSelectMode()) {
            binding.selectToggle.setText(R.string.select_done)
            binding.selectToggle.setTextColor(ResUtil.getColor(R.color.ranking_blue))
            updateClearButtonText()
        } else {
            binding.selectToggle.setText(R.string.select_sites)
            binding.selectToggle.setTextColor(ResUtil.getColor(R.color.white))
            binding.clearAll.setText(R.string.clear_ranking)
            binding.clearAll.setTextColor(ResUtil.getColor(R.color.ranking_red))
        }
    }

    private fun updateClearButtonText() {
        val count = adapter.getSelectedCount()
        if (count >
            0
        ) {
            binding!!.clearAll.text = dialog!!.context.getString(R.string.clear_selected, count)
        } else {
            binding!!.clearAll.setText(R.string.clear_ranking)
        }
        binding!!.clearAll.setTextColor(ResUtil.getColor(R.color.ranking_red))
    }

    private fun clearSelected() {
        if (adapter.isSelectMode()) {
            val selected = adapter.getSelectedKeys()
            if (selected.isEmpty()) {
                SitePerformanceTracker.get().clearAll()
            } else {
                for (siteKey in selected) {
                    SitePerformanceTracker
                        .get()
                        .clear(
                            siteKey,
                        )
                }
            }
        } else {
            SitePerformanceTracker.get().clearAll()
        }
        adapter.setSelectMode(false)
        updateSelectUI()
        adapter.refresh()
    }

    private fun setDialog() {
        val window = dialog!!.window!!
        val params = window.attributes
        params.width = LayoutConfig.getScreenWidthPixels(LayoutConfig.SITE_RANKING_DIALOG_WIDTH_RATIO)
        window.attributes = params
        dialog!!.show()
        binding!!.sortPerformance.requestFocus()
        // Skip clicks already handled by a focused view's own key listener.
        dialog!!.setOnKeyListener { _, keyCode, event ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.action == KeyEvent.ACTION_UP) {
                val focused = dialog!!.currentFocus
                if (focused != null && !isClickConsumed(focused)) focused.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun clear() {
        adapter.setOnSelectionChanged(null)
        binding?.recycler?.adapter = null
        binding = null
        dialog = null
    }
}
