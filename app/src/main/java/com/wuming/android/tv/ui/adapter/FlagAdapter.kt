package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.bean.Flag
import com.wuming.android.tv.databinding.AdapterFlagBinding
import java.util.Collections

open class FlagAdapter(
    private val listener: OnClickListener,
) : BaseSelectAdapter<Flag, FlagAdapter.ViewHolder>() {
    private var nextFocusDown = View.NO_ID

    fun interface OnClickListener {
        fun onItemClick(item: Flag)
    }

    open fun add(item: Flag) {
        mItems.add(item)
        notifyItemInserted(mItems.size - 1)
    }

    override fun getActivated(): Flag = mItems[getPosition()]

    open fun setActivated(flag: Flag) {
        if (!mItems.contains(flag)) flag.setFlag(mItems[0].getFlag())
        for (item in mItems) item.setActivated(flag)
        notifyItemRangeChanged(0, itemCount)
    }

    open fun toggle(episode: Episode) {
        for (item in mItems) item.toggle(item.isActivated(), episode)
        notifyDataSetChanged()
    }

    open fun reverse() {
        for (item in mItems) Collections.reverse(item.getEpisodes())
        notifyDataSetChanged()
    }

    open fun setNextFocusDown(nextFocusDown: Int) {
        this.nextFocusDown = nextFocusDown
    }

    override fun isActivated(item: Flag): Boolean = item.isActivated()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterFlagBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.text.text = item.getShow()
        holder.binding.text.isActivated = item.isActivated()
        holder.binding.text.isSelected = item.isActivated()
        holder.setItem(item)
        holder.binding.text.nextFocusDownId = nextFocusDown
    }

    open class ViewHolder(
        val binding: AdapterFlagBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Flag? = null

        init {
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
        }

        open fun setItem(item: Flag?) {
            this.item = item
        }
    }
}
