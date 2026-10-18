package com.wuming.android.tv.ui.holder

import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.databinding.AdapterEpisodeHoriBinding
import com.wuming.android.tv.ui.adapter.EpisodeAdapter
import com.wuming.android.tv.ui.base.BaseEpisodeHolder
import com.wuming.android.tv.utils.ResUtil

open class EpisodeHoriHolder(
    private val binding: AdapterEpisodeHoriBinding,
    listener: EpisodeAdapter.OnClickListener,
) : BaseEpisodeHolder(binding.root) {
    private var item: Episode? = null
    private val maxWidth = ResUtil.getScreenWidth() - ResUtil.getDimensionPixelSize(R.dimen.vs_32)

    init {
        binding.text.setOnClickListener { item?.let(listener::onItemClick) }
        binding.text.setOnLongClickListener { item?.let(listener::onLongClick) ?: false }
    }

    override fun initView(item: Episode) {
        this.item = item
        binding.text.maxWidth = maxWidth
        binding.text.isSelected = item.isSelected()
        binding.text.isActivated = item.isActivated()
        binding.text.text = item.getDesc() + item.getDisplayName()
        binding.text.contentDescription = item.getDesc() + item.getDisplayName()
    }
}
