package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.bean.Device
import com.wuming.android.tv.databinding.AdapterDeviceBinding

open class DeviceAdapter(
    private val listener: OnClickListener,
) : BaseDiffAdapter<Device, DeviceAdapter.ViewHolder>() {
    // Discovery callbacks can arrive before AsyncListDiffer commits the previous diff.
    // Mutations must use the latest submitted list, not the last rendered snapshot.
    private var submittedItems: List<Device> = emptyList()

    override fun getItems(): MutableList<Device> = ArrayList(submittedItems)

    override fun setItems(items: List<Device>?, runnable: Runnable?) {
        submittedItems = items?.toList() ?: emptyList()
        super.setItems(submittedItems, runnable)
    }

    override fun setItems(items: List<Device>?, callback: BaseDiffAdapter.Callback) {
        setItems(items, Runnable { callback.onUpdateFinished(true) })
    }

    interface OnClickListener {
        fun onItemClick(item: Device)

        fun onLongClick(item: Device): Boolean
    }

    open fun getIps(): List<String> {
        val ips = ArrayList<String>()
        for (item in getItems()) if (item.isApp()) ips.add(item.getIp())
        return ips
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(AdapterDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false), listener)

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = getItem(position)
        holder.binding.name.text = item.getName()
        holder.binding.host.text = (if (item.isDLNA()) "DLNA · " else "TVBox · ") + item.getHost()
        holder.binding.type.setImageResource(getIcon(item))
        holder.binding.type.contentDescription = if (item.isDLNA()) "DLNA Device" else item.getName()
        holder.binding.capabilities.visibility = if (item.isDLNA()) View.VISIBLE else View.GONE
        holder.setItem(item)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.setItem(null)
    }

    private fun getIcon(item: Device): Int = if (item.isMobile()) R.drawable.ic_cast_mobile else R.drawable.ic_cast_tv

    open class ViewHolder(
        val binding: AdapterDeviceBinding,
        listener: OnClickListener,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var item: Device? = null

        init {
            binding.capabilities.setOnClickListener { item?.let(listener::onLongClick) }
            binding.root.setOnClickListener { item?.let(listener::onItemClick) }
            binding.root.setOnLongClickListener { item?.let(listener::onLongClick) ?: false }
        }

        open fun setItem(item: Device?) {
            this.item = item
        }
    }
}
