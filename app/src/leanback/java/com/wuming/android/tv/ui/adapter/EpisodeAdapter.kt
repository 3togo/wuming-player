package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.databinding.AdapterEpisodeBinding
import com.wuming.android.tv.databinding.AdapterEpisodeGridBinding
import com.wuming.android.tv.ui.base.BaseEpisodeHolder
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.utils.ResUtil

open class EpisodeAdapter
    @JvmOverloads
    constructor(
        private val listener: OnClickListener,
        private val viewType: Int = ViewType.HORI,
    ) : BaseEpisodeAdapter<BaseEpisodeHolder>() {
        private val maxWidth = ResUtil.getScreenWidth() - ResUtil.getDimensionPixelSize(R.dimen.vs_48)
        private var nextFocusDown = 0
        private var nextFocusUp = 0

        interface OnClickListener {
            fun onItemClick(item: Episode)

            fun onLongClick(item: Episode): Boolean = false
        }

        open fun setNextFocusDown(nextFocusDown: Int) {
            if (this.nextFocusDown == nextFocusDown) return
            this.nextFocusDown = nextFocusDown
        }

        open fun setNextFocusUp(nextFocusUp: Int) {
            if (this.nextFocusUp == nextFocusUp) return
            this.nextFocusUp = nextFocusUp
        }

        override fun getItemViewType(position: Int): Int = viewType

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): BaseEpisodeHolder =
            if (viewType ==
                ViewType.GRID
            ) {
                GridViewHolder(AdapterEpisodeGridBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
            } else {
                ViewHolder(AdapterEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)
            }

        override fun onBindViewHolder(
            holder: BaseEpisodeHolder,
            position: Int,
        ) {
            holder.initView(mItems[position])
            if (holder is ViewHolder) holder.setNextFocus(nextFocusUp, nextFocusDown)
        }

        open class ViewHolder(
            private val binding: AdapterEpisodeBinding,
            listener: OnClickListener,
        ) : BaseEpisodeHolder(binding.root) {
            private var item: Episode? = null

            init {
                binding.root.setOnClickListener { item?.let(listener::onItemClick) }
                binding.root.setOnLongClickListener {
                    item?.let(listener::onLongClick)
                        ?: false
                }
            }

            override fun initView(item: Episode) {
                this.item = item
                binding.text.maxWidth = ResUtil.getScreenWidth() - ResUtil.getDimensionPixelSize(R.dimen.vs_48)
                binding.text.isActivated = item.isActivated()
                binding.text.text = item.getDesc() + item.getDisplayName()
                binding.text.contentDescription = item.getDesc() + item.getDisplayName()
            }

            open fun setNextFocus(
                up: Int,
                down: Int,
            ) {
                binding.text.nextFocusUpId = up
                binding.text.nextFocusDownId = down
            }
        }

        open class GridViewHolder(
            private val binding: AdapterEpisodeGridBinding,
            listener: OnClickListener,
        ) : BaseEpisodeHolder(binding.root) {
            private var item: Episode? = null

            init {
                binding.text.setOnClickListener { item?.let(listener::onItemClick) }
                binding.text.setOnLongClickListener {
                    item?.let(listener::onLongClick)
                        ?: false
                }
            }

            override fun initView(item: Episode) {
                this.item = item
                binding.text.isActivated = item.isActivated()
                binding.text.text =
                    item.getDesc() + item.getDisplayName()
                binding.text.contentDescription = item.getDesc() + item.getDisplayName()
            }
        }
    }
