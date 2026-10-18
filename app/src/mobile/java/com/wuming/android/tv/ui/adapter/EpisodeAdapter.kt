package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.databinding.AdapterEpisodeGridBinding
import com.wuming.android.tv.databinding.AdapterEpisodeHoriBinding
import com.wuming.android.tv.ui.base.BaseEpisodeHolder
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.ui.holder.EpisodeGridHolder
import com.wuming.android.tv.ui.holder.EpisodeHoriHolder
import java.util.ArrayList

open class EpisodeAdapter
    @JvmOverloads
    constructor(
        private val listener: OnClickListener,
        private val viewType: Int,
        items: ArrayList<Episode> = ArrayList(),
    ) : BaseEpisodeAdapter<BaseEpisodeHolder>(items) {
        interface OnClickListener {
            fun onItemClick(item: Episode)

            fun onLongClick(item: Episode): Boolean = false
        }

        override fun getItemViewType(position: Int): Int = viewType

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): BaseEpisodeHolder =
            if (viewType ==
                ViewType.HORI
            ) {
                EpisodeHoriHolder(AdapterEpisodeHoriBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
            } else {
                EpisodeGridHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
            }

        override fun onBindViewHolder(
            holder: BaseEpisodeHolder,
            position: Int,
        ) {
            holder.initView(mItems[position])
        }
    }
