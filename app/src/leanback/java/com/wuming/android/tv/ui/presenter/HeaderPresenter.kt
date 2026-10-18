package com.wuming.android.tv.ui.presenter

import com.wuming.android.tv.utils.TextScale

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.wuming.android.tv.databinding.AdapterHeaderBinding
import com.wuming.android.tv.utils.ResUtil

open class HeaderPresenter
    @JvmOverloads
    constructor(
        @Suppress("UNUSED_PARAMETER") textSizeSp: Float = 0f,
    ) : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
            val binding = AdapterHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            TextScale.bind(binding.root)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(
            viewHolder: Presenter.ViewHolder,
            item: Any?,
        ) {
            (viewHolder as ViewHolder).binding.text.text = if (item is String) item else ResUtil.getString(item as Int)
        }

        override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {}

        open class ViewHolder(
            val binding: AdapterHeaderBinding,
        ) : Presenter.ViewHolder(binding.root)
    }
