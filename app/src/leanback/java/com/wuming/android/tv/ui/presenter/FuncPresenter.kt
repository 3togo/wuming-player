package com.wuming.android.tv.ui.presenter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.bean.Func
import com.wuming.android.tv.databinding.AdapterFuncBinding
import com.wuming.android.tv.utils.ResUtil

open class FuncPresenter(
    private val listener: OnClickListener,
) : Presenter() {
    fun interface OnClickListener {
        fun onItemClick(item: Func)
    }

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val binding = AdapterFuncBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val root = binding.root
        val params = root.layoutParams
        val maxHeight = ResUtil.getScreenHeight() / 6
        if (params.height > maxHeight) {
            val scale = maxHeight.toFloat() / params.height
            params.height = maxHeight
            params.width = (params.width * scale).toInt()
            root.layoutParams = params
            val iconParams = binding.icon.layoutParams
            iconParams.width = (iconParams.width * scale).toInt()
            iconParams.height = (iconParams.height * scale).toInt()
            binding.icon.layoutParams =
                iconParams
            TextScale.bind(binding.text)
        }
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder,
        item: Any?,
    ) {
        val func = item as Func
        val holder = viewHolder as ViewHolder
        holder.binding.text.setText(func.getText())
        holder.binding.icon.setImageResource(func.getDrawable())
        setOnClickListener(holder) { listener.onItemClick(func) }
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {}

    open class ViewHolder(
        val binding: AdapterFuncBinding,
    ) : Presenter.ViewHolder(binding.root)
}
