package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.databinding.AdapterQualityBinding

open class QualityAdapter(private val listener: OnClickListener) :
    RecyclerView.Adapter<QualityAdapter.ViewHolder>() {

    private var result = Result.empty()
    private var position = 0
    private var nextFocusUp = View.NO_ID
    private var nextFocusDown = View.NO_ID

    fun interface OnClickListener {
        fun onItemClick(result: Result)
    }

    open fun getPosition(): Int = position

    open fun setNextFocus(up: Int, down: Int) {
        nextFocusUp = up
        nextFocusDown = down
    }

    open fun addAll(result: Result) {
        this.result = result
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = result.url.getValues().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            AdapterQualityBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            this,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position)
        holder.itemView.nextFocusUpId = nextFocusUp
        holder.itemView.nextFocusDownId = nextFocusDown
    }

    private fun onItemClick(position: Int) {
        this.position = position
        result.url.set(position)
        listener.onItemClick(result)
        notifyItemRangeChanged(0, itemCount)
    }

    open class ViewHolder(
        private val binding: AdapterQualityBinding,
        private val adapter: QualityAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.text.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) adapter.onItemClick(position)
            }
        }

        open fun bind(position: Int) {
            binding.text.text = adapter.result.url.n(position)
            binding.text.isActivated = adapter.result.url.getPosition() == position
        }
    }
}
