package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.AdapterPartBinding
import com.wuming.android.tv.utils.ResUtil
import java.util.ArrayList

open class PartAdapter(
    private val mListener: OnClickListener?,
) : RecyclerView.Adapter<PartAdapter.ViewHolder>() {
    private val mItems = ArrayList<String>()
    private val maxWidth = ResUtil.getScreenWidth() - ResUtil.getDimensionPixelSize(R.dimen.vs_48)
    private var nextFocusUp = 0
    private var nextFocusDown = View.NO_ID

    open fun addAll(items: List<String>) {
        mItems.clear()
        mItems.addAll(items)
        notifyDataSetChanged()
    }

    open fun clear() {
        mItems.clear()
        notifyDataSetChanged()
    }

    open fun setNextFocusUp(nextFocusUp: Int) {
        this.nextFocusUp = nextFocusUp
    }

    open fun setNextFocusDown(nextFocusDown: Int) {
        this.nextFocusDown = nextFocusDown
    }

    override fun getItemCount(): Int = mItems.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterPartBinding.inflate(LayoutInflater.from(parent.context), parent, false), mListener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val text = mItems[position]
        holder.binding.text.text = text
        holder.binding.text.maxWidth = maxWidth
        holder.binding.text.nextFocusUpId = nextFocusUp
        holder.binding.text.nextFocusDownId = nextFocusDown
        holder.bind(text)
    }

    fun interface OnClickListener {
        fun onItemClick(item: String)
    }

    open class ViewHolder(
        val binding: AdapterPartBinding,
        listener: OnClickListener?,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: String? = null

        init {
            binding.root.setOnClickListener { item?.let { listener?.onItemClick(it) } }
        }

        open fun bind(text: String) {
            item = text
        }
    }
}
