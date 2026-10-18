package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.databinding.AdapterRestoreBinding

open class RestoreAdapter(
    listener: OnClickListener?,
) : BaseFileAdapter<BaseFileAdapter.FileViewHolder>(listener) {
    init {
        addAll()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): FileViewHolder = FileViewHolder(AdapterRestoreBinding.inflate(LayoutInflater.from(parent.context), parent, false), this)

    override fun onBindViewHolder(
        holder: FileViewHolder,
        position: Int,
    ) {
        holder.bind(mItems[position])
    }
}
