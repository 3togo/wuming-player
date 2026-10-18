package com.wuming.android.tv.ui.presenter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.bean.Value
import com.wuming.android.tv.databinding.AdapterFilterBinding

open class FilterPresenter(
    private val mKey: String,
) : Presenter() {
    private var listener: OnClickListener? = null

    fun interface OnClickListener {
        fun onItemClick(
            key: String,
            item: Value,
        )
    }

    open fun setOnClickListener(listener: OnClickListener?) {
        this.listener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
        ViewHolder(AdapterFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder,
        item: Any?,
    ) {
        val value = item as Value
        val holder = viewHolder as ViewHolder
        holder.binding.text.text = value.getN()
        holder.binding.text.isActivated = value.isActivated()
        setOnClickListener(holder) { listener!!.onItemClick(mKey, value) }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {}

    open class ViewHolder(
        val binding: AdapterFilterBinding,
    ) : Presenter.ViewHolder(binding.root)
}
