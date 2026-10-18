package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.cloud.opendrive.WebDavConnectorStore
import com.wuming.android.tv.databinding.ItemWebdavConnectorBinding

class WebDavConnectorAdapter(
    private var listener: Listener?,
) : RecyclerView.Adapter<WebDavConnectorAdapter.Holder>() {
    interface Listener {
        fun onEdit(connector: WebDavConnectorStore.Connector)

        fun onDelete(connector: WebDavConnectorStore.Connector)
    }

    private var items: List<WebDavConnectorStore.Connector> = emptyList()

    fun submit(values: List<WebDavConnectorStore.Connector>?) {
        items =
            if (values ==
                null
            ) {
                emptyList()
            } else {
                ArrayList(values)
            }
        ; notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(ItemWebdavConnectorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val item = items[position]
        holder.binding.name.text = item.name
        holder.binding.address.text = item.baseUrl
        holder.binding.path.text =
            if (item.initialPath!!.isEmpty()) {
                holder.itemView.context.getString(R.string.webdav_connector_root)
            } else {
                "/" +
                    item.initialPath
            }
        holder.binding.edit.setOnClickListener { listener?.onEdit(item) }
        holder.binding.delete.setOnClickListener { listener?.onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    fun release() {
        listener = null
        items = emptyList()
    }

    class Holder(
        @JvmField val binding: ItemWebdavConnectorBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
