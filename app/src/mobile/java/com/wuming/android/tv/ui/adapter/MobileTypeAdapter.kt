package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Class
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.databinding.AdapterTypeBinding
import com.wuming.android.tv.utils.LayoutConfig
import com.wuming.android.tv.utils.ResUtil

open class MobileTypeAdapter(
    @JvmField val listener: OnClickListener?,
) : BaseSelectAdapter<Class, MobileTypeAdapter.ViewHolder>() {
    fun interface OnClickListener {
        fun onItemClick(
            position: Int,
            item: Class,
        )
    }

    private fun home(): Class =
        Class().also {
            it.setTypeName(ResUtil.getString(R.string.vod_home))
            it.setTypeId("home")
        }

    open fun addAll(result: Result) {
        mItems.clear()
        mItems.addAll(result.getTypes())
        if (result.getList().isNotEmpty()) mItems.add(0, home())
        if (mItems.isNotEmpty()) mItems[0].setActivated(true)
        notifyDataSetChanged()
    }

    open fun setActivated(position: Int) {
        for (item in mItems) item.setActivated(false)
        mItems[position].setActivated(true)
        notifyItemRangeChanged(0, mItems.size)
    }

    override fun isActivated(item: Class): Boolean = item.isActivated()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterTypeBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = mItems[position]
        holder.binding.text.text =
            item.getTypeName()
        holder.binding.text.isActivated = item.isActivated()
        TextScale.bind(holder.itemView)
    }

    open class ViewHolder(
        @JvmField val binding: AdapterTypeBinding,
        adapter: MobileTypeAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            TextScale.bind(binding.root)
            if (ResUtil.isLand(binding.root.context)) {
                val height = LayoutConfig.getMobileHomeCategoryChipHeightPixels(binding.root.context)
                val params = binding.text.layoutParams as ViewGroup.MarginLayoutParams
                params.height = height
                val sideMargin = LayoutConfig.getTypeChipSideMarginPixels(height)
                params.marginStart = sideMargin
                params.marginEnd = sideMargin
                binding.text.layoutParams = params
                val padding = LayoutConfig.getTypeChipHorizontalPaddingPixels(height)
                binding.text.setPadding(padding, 0, padding, 0)
                TextScale.bind(binding.text)
            }
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) adapter.listener?.onItemClick(pos, adapter.mItems[pos])
            }
        }
    }
}
