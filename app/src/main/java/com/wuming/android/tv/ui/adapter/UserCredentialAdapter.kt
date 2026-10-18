package com.wuming.android.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ItemUserCredentialBinding
import com.wuming.tv.core.cloud.OpenDriveUserCredential
import com.wuming.tv.core.cloud.UserCredentialMethod

class UserCredentialAdapter(
    private var listener: Listener?,
) : RecyclerView.Adapter<UserCredentialAdapter.Holder>() {
    interface Listener {
        fun onEdit(credential: OpenDriveUserCredential)

        fun onDelete(credential: OpenDriveUserCredential)
    }

    private var items: List<OpenDriveUserCredential> = emptyList()

    fun submit(values: List<OpenDriveUserCredential>?) {
        items = values?.let { ArrayList(it) } ?: emptyList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(ItemUserCredentialBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val item = items[position]
        holder.binding.name.text = item.displayName
        holder.binding.address.text = item.baseUrl
        holder.binding.path.text = methodLabel(holder, item.method)
        holder.binding.edit.setOnClickListener { listener?.onEdit(item) }
        holder.binding.delete.setOnClickListener { listener?.onDelete(item) }
    }

    private fun methodLabel(holder: Holder, method: UserCredentialMethod): String {
        val res = holder.itemView.context.resources
        return when (method) {
            UserCredentialMethod.COOKIE -> res.getString(R.string.credential_method_cookie)
            UserCredentialMethod.OAUTH_TOKEN -> res.getString(R.string.credential_method_token)
            UserCredentialMethod.PASSWORD -> res.getString(R.string.credential_method_password)
        }
    }

    override fun getItemCount(): Int = items.size

    fun release() {
        listener = null
        items = emptyList()
    }

    class Holder(
        @JvmField val binding: ItemUserCredentialBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
