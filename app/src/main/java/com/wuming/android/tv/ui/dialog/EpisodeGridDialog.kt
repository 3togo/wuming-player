package com.wuming.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.databinding.DialogEpisodeGridBinding
import com.wuming.android.tv.ui.adapter.EpisodeAdapter
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.utils.ResUtil
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.tabs.TabLayout
import java.util.ArrayList

open class EpisodeGridDialog :
    BaseDialog(),
    EpisodeAdapter.OnClickListener {
    private val titles = ArrayList<String>()
    private var listener: EpisodeAdapter.OnClickListener? = null
    private var binding: DialogEpisodeGridBinding? = null
    private var adapter: EpisodeAdapter? = null
    private var episodes: List<Episode>? = null
    private var reverse = false
    private var spanCount = 5
    private var itemCount = 0
    private val tabListener: TabLayout.OnTabSelectedListener = TabSelectedListener(this)

    companion object {
        @JvmStatic fun create(): EpisodeGridDialog = EpisodeGridDialog()

        @JvmStatic fun create(activity: FragmentActivity): EpisodeGridDialog =
            EpisodeGridDialog().also {
                it.listener = activity as EpisodeAdapter.OnClickListener
            }
    }

    open fun reverse(reverse: Boolean): EpisodeGridDialog {
        this.reverse = reverse
        return this
    }

    @JvmSuppressWildcards
    open fun episodes(episodes: List<Episode>?): EpisodeGridDialog {
        this.episodes = episodes
        return this
    }

    open fun show(activity: FragmentActivity) {
        if (isDialogShowing(activity)) return
        if (listener == null) listener = activity as EpisodeAdapter.OnClickListener
        show(activity.supportFragmentManager, null)
    }

    override fun getBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): ViewBinding =
        DialogEpisodeGridBinding.inflate(inflater, container, false).also {
            binding = it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        if (episodes.isNullOrEmpty()) {
            dismissAllowingStateLoss()
            return
        }
        setSpanCount()
        setTitles()
        setRecyclerView()
        setTabLayout()
    }

    private fun setSpanCount() {
        var total = 0
        val row = if (ResUtil.isLand(requireActivity())) 5 else 10
        val episodes = episodes!!
        for (item in episodes) total += item.getDisplayName().length
        val offset = Math.ceil(total.toDouble() / episodes.size).toInt()
        if (offset >= 12) {
            spanCount = 1
        } else if (offset >= 8) {
            spanCount = 2
        } else if (offset >= 4) {
            spanCount = 3
        } else if (offset >= 2) {
            spanCount = 4
        }
        itemCount = spanCount * row
    }

    private fun setTitles() {
        val episodes = episodes!!
        if (reverse) {
            var i = episodes.size
            while (i > 0) {
                titles.add(i.toString() + " - " + maxOf(i - itemCount - 1, 1))
                i -= itemCount
            }
        } else {
            var i = 0
            while (i < episodes.size) {
                titles.add((i + 1).toString() + " - " + minOf(i + itemCount, episodes.size))
                i += itemCount
            }
        }
    }

    private fun setRecyclerView() {
        val binding = binding!!
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(context, spanCount)
        adapter = EpisodeAdapter(this, ViewType.GRID)
        binding.recycler.adapter = adapter
    }

    private fun setTabLayout() {
        val binding = binding!!
        for (title in titles) binding.tabs.addTab(binding.tabs.newTab().setText(title))
        binding.tabs.addOnTabSelectedListener(tabListener)
        val position = getCurrentTabPosition()
        val tab = binding.tabs.getTabAt(position)
        if (tab != null && !tab.isSelected) tab.select() else showPage(position)
    }

    private fun getCurrentTabPosition(): Int {
        val episodes = episodes!!
        for (i in episodes.indices) if (episodes[i].isActivated()) return i / itemCount
        return 0
    }

    private fun showPage(position: Int) {
        val binding = binding ?: return
        val adapter = adapter ?: return
        val episodes = episodes ?: return
        if (itemCount <= 0) return
        val start = position * itemCount
        if (start < 0 || start >= episodes.size) return
        val end = minOf(start + itemCount, episodes.size)
        adapter.addAll(episodes.subList(start, end))
        binding.recycler.scrollToPosition(maxOf(adapter.getPosition(), 0))
    }

    override fun onItemClick(item: Episode) {
        listener?.onItemClick(item)
        dismiss()
    }

    override fun onDestroyView() {
        binding?.tabs?.removeOnTabSelectedListener(tabListener)
        listener = null
        adapter = null
        episodes = null
        titles.clear()
        super.onDestroyView()
    }

    private class TabSelectedListener(
        private val dialog: EpisodeGridDialog,
    ) : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab) {
            dialog.showPage(tab.position)
        }

        override fun onTabUnselected(tab: TabLayout.Tab) {}

        override fun onTabReselected(tab: TabLayout.Tab) {
            dialog.showPage(tab.position)
        }
    }
}
