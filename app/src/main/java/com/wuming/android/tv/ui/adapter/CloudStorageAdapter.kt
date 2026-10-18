package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.cloud.CloudStorageProvider
import com.wuming.android.tv.cloud.CloudStorageRepository
import com.wuming.android.tv.databinding.ItemCloudStorageBinding
import com.wuming.android.tv.utils.FileUtil
import java.util.EnumMap
import java.util.EnumSet

open class CloudStorageAdapter(
    private val providers: List<CloudStorageProvider>,
    private val listener: Listener,
) : RecyclerView.Adapter<CloudStorageAdapter.Holder>() {
    private val busy = EnumSet.noneOf(CloudStorageProvider::class.java)
    private val stats = EnumMap<CloudStorageProvider, CloudStorageRepository.Stats?>(CloudStorageProvider::class.java)

    open fun setBusy(
        provider: CloudStorageProvider,
        value: Boolean,
    ) {
        if (value) busy.add(provider) else busy.remove(provider)
        val position = providers.indexOf(provider)
        if (position >= 0) notifyItemChanged(position)
    }

    open fun setStats(
        provider: CloudStorageProvider,
        value: CloudStorageRepository.Stats?,
    ) {
        stats[provider] = value
        val position = providers.indexOf(provider)
        if (position >= 0) notifyItemChanged(position)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(ItemCloudStorageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val provider = providers[position]
        val isBusy = busy.contains(provider)
        holder.binding.name.text = provider.displayName
        holder.binding.address.text = provider.managementUrl
        val value = stats[provider]
        holder.binding.tracked.text =
            if (value == null || value.count == 0) {
                holder.itemView.context.getString(R.string.cloud_storage_tracked_empty)
            } else {
                holder.itemView.context.getString(R.string.cloud_storage_tracked, value.count, FileUtil.byteCountToDisplaySize(value.bytes))
            }
        holder.binding.clear.isEnabled = !isBusy
        holder.binding.manage.isEnabled = !isBusy
        holder.binding.clear.setText(if (isBusy) R.string.cloud_storage_clearing else R.string.cloud_storage_clear)
        holder.binding.manage.setOnClickListener { listener.onManage(provider) }
        holder.binding.clear.setOnClickListener { listener.onClear(provider) }
    }

    override fun getItemCount(): Int = providers.size

    interface Listener {
        fun onManage(provider: CloudStorageProvider)

        fun onClear(provider: CloudStorageProvider)
    }

    class Holder(
        @JvmField val binding: ItemCloudStorageBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
