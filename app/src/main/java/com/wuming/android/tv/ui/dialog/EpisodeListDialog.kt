package com.wuming.android.tv.ui.dialog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.databinding.DialogEpisodeListBinding
import com.wuming.android.tv.ui.adapter.EpisodeAdapter
import com.wuming.android.tv.ui.base.ViewType
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

open class EpisodeListDialog :
    BaseDialog(),
    EpisodeAdapter.OnClickListener {
    private var listener: EpisodeAdapter.OnClickListener? = null
    private var binding: DialogEpisodeListBinding? = null
    private lateinit var adapter: EpisodeAdapter
    private lateinit var episodes: List<Episode>

    companion object {
        @JvmStatic fun create(): EpisodeListDialog = EpisodeListDialog()

        @JvmStatic fun create(activity: FragmentActivity): EpisodeListDialog =
            EpisodeListDialog().also {
                it.listener = activity as EpisodeAdapter.OnClickListener
            }
    }

    @JvmSuppressWildcards
    open fun episodes(episodes: List<Episode>): EpisodeListDialog {
        this.episodes = episodes
        return this
    }

    open fun show() {
        val activity = activity ?: return
        if (isDialogShowing(activity)) return
        show(activity.supportFragmentManager, null)
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
        DialogEpisodeListBinding.inflate(inflater, container, false).also {
            binding = it
        }

    override fun releaseBinding() {
        binding = null
    }

    override fun initView() {
        var total = 0
        for (item in episodes) total += item.getDisplayName().length
        val offset = Math.ceil(total.toDouble() / episodes.size).toInt()
        val spanCount =
            when {
                offset >= 12 -> 1
                offset >= 8 -> 2
                offset >= 4 -> 3
                offset >= 2 -> 4
                else -> 5
            }
        val binding = binding!!
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(context, spanCount)
        adapter = EpisodeAdapter(this, ViewType.GRID)
        binding.recycler.adapter = adapter
        adapter.addAll(episodes)
        binding.recycler.scrollToPosition(adapter.getPosition())
    }

    override fun onItemClick(item: Episode) {
        listener?.onItemClick(item)
        dismiss()
    }
}
