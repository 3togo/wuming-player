package com.wuming.android.tv.ui.presenter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.databinding.AdapterProgressBinding

open class ProgressPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
        ViewHolder(AdapterProgressBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        viewHolder: Presenter.ViewHolder,
        item: Any?,
    ) {}

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {}

    open class ViewHolder(
        binding: AdapterProgressBinding,
    ) : Presenter.ViewHolder(binding.root)
}
