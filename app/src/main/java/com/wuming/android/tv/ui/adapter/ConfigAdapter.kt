package com.wuming.android.tv.ui.adapter

import com.wuming.android.tv.utils.ControlSpacing
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.databinding.AdapterConfigBinding
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.DialogUtil
import com.wuming.android.tv.utils.Util

open class ConfigAdapter(
    private val listener: OnClickListener,
) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {
    private var items = ArrayList<Config>()
    private var readOnly = false

    interface OnClickListener {
        fun onTextClick(item: Config)

        fun onDeleteClick(item: Config)
    }

    open fun readOnly(readOnly: Boolean): ConfigAdapter {
        this.readOnly = readOnly
        return this
    }

    open fun addAll(type: Int): ConfigAdapter {
        items = ArrayList(Config.getAll(type))
        return this
    }

    open fun setItems(items: List<Config>): ConfigAdapter {
        this.items = ArrayList(items)
        return this
    }

    open fun remove(item: Config): Int {
        val position = items.indexOf(item)
        if (position == -1) return -1
        Task.execute { item.delete() }
        items.removeAt(position)
        notifyItemRemoved(position)
        return itemCount
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder {
        val binding = AdapterConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (!Util.isLeanback()) {
            val size = ControlSpacing.dimensionPixels(parent.context, R.dimen.ds_control_min_height)
            val padding = ControlSpacing.dimensionPixels(parent.context, R.dimen.ds_control_padding_horizontal)
            val vertical = ControlSpacing.dimensionPixels(parent.context, R.dimen.ds_control_padding_vertical)
            binding.delete.layoutParams = binding.delete.layoutParams.apply { width = size; height = size }
            binding.delete.setPadding(padding, padding, padding, padding)
            binding.text.setPadding(padding, vertical, padding, vertical)
        }
        return ViewHolder(binding, this)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    open class ViewHolder(
        private val binding: AdapterConfigBinding,
        private val adapter: ConfigAdapter,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Config? = null

        init {
            binding.text.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null) adapter.listener.onTextClick(current)
            }
            binding.delete.setOnClickListener {
                val pos = bindingAdapterPosition
                val current = item
                if (pos != RecyclerView.NO_POSITION && current != null) adapter.listener.onDeleteClick(current)
            }
        }

        open fun bind(item: Config) {
            this.item = item
            binding.text.text = item.desc
            binding.delete.visibility =
                if (adapter.readOnly) View.GONE else View.VISIBLE
        }
    }
}
