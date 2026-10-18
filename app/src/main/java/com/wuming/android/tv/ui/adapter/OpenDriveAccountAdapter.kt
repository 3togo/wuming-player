package com.wuming.android.tv.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.wuming.android.tv.R
import com.wuming.android.tv.cloud.CloudStorageProvider
import com.wuming.android.tv.cloud.opendrive.OpenDriveTokenManager
import com.wuming.android.tv.databinding.ItemOpenDriveLoginBinding
import com.wuming.tv.core.cloud.OpenDriveAccountSnapshot
import com.wuming.tv.core.cloud.OpenDriveTokenState
import com.google.android.material.button.MaterialButton

/** Provider status and login controls used by the Cloud Storage account hub. */
class OpenDriveAccountAdapter(
    private val tokens: OpenDriveTokenManager,
    private val listener: Listener,
) : RecyclerView.Adapter<OpenDriveAccountAdapter.Holder>() {
    private val providers = providers(tokens)

    fun refresh() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder = Holder(ItemOpenDriveLoginBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val cloudProvider = providers[position]
        val account = tokens.snapshot(cloudProvider.id)
        val context = holder.itemView.context
        val name = context.getString(nameResource(cloudProvider))
        val status = statusResource(account.state)
        holder.binding.provider.text = context.getString(R.string.open_drive_provider_status, name, context.getString(status))
        applyFocusStyle(holder.binding.provider, providerTintResource(account))
        holder.binding.provider.isEnabled = account.available
        holder.binding.provider.setOnClickListener(
            if (account.available) View.OnClickListener { listener.onLogin(cloudProvider, false) } else null,
        )
        val assisted = showAssistedLogin(account)
        holder.binding.auto.visibility = if (assisted) View.VISIBLE else View.GONE
        applyFocusStyle(holder.binding.auto, R.color.blue_500)
        holder.binding.auto.isEnabled = assisted
        holder.binding.auto.setOnClickListener(if (assisted) View.OnClickListener { listener.onLogin(cloudProvider, true) } else null)
        holder.binding.state.setText(if (account.tokenPresent) R.string.open_drive_logout else R.string.open_drive_inactive)
        applyFocusStyle(holder.binding.state, if (account.tokenPresent) R.color.cloud_login_disconnected else R.color.white_20)
        holder.binding.state.isEnabled = account.tokenPresent
        holder.binding.state.setOnClickListener(
            if (account.tokenPresent) {
                View.OnClickListener {
                    listener.onLogout(account.providerId, name)
                }
            } else {
                null
            },
        )
    }

    override fun getItemCount(): Int = providers.size

    companion object {
        private fun providers(tokens: OpenDriveTokenManager): List<CloudStorageProvider> {
            val result = ArrayList<CloudStorageProvider>()
            for (account in tokens.accounts()) cloudProvider(account.providerId)?.let { result.add(it) }
            return result
        }

        private fun statusResource(state: OpenDriveTokenState): Int =
            when (state) {
                OpenDriveTokenState.UNAVAILABLE -> R.string.open_drive_status_unavailable
                OpenDriveTokenState.SIGNED_OUT -> R.string.open_drive_status_not_logged_in
                OpenDriveTokenState.PRESENT, OpenDriveTokenState.CHECKING -> R.string.open_drive_status_checking
                OpenDriveTokenState.VERIFIED -> R.string.open_drive_status_logged_in
                OpenDriveTokenState.INVALID -> R.string.open_drive_status_invalid
                OpenDriveTokenState.ERROR -> R.string.open_drive_status_verify_failed
            }

        @JvmStatic fun providerTintResource(account: OpenDriveAccountSnapshot): Int =
            if (account.verified()) R.color.cloud_login_connected else R.color.cloud_login_disconnected

        @JvmStatic fun showAssistedLogin(account: OpenDriveAccountSnapshot): Boolean = account.canStartLogin()

        @JvmStatic fun applyFocusStyle(
            button: MaterialButton,
            normalColorResource: Int,
        ) {
            val context = button.context
            val normal = ContextCompat.getColor(context, normalColorResource)
            val focused = ContextCompat.getColor(context, R.color.highlight)
            val disabled = ContextCompat.getColor(context, R.color.white_10)
            val white = ContextCompat.getColor(context, R.color.white)
            val black = ContextCompat.getColor(context, R.color.black)
            val transparent = ContextCompat.getColor(context, R.color.transparent)
            val states =
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(),
                )
            button.backgroundTintList = ColorStateList(states, intArrayOf(disabled, focused, focused, normal))
            button.setTextColor(ColorStateList(states, intArrayOf(ContextCompat.getColor(context, R.color.white_50), black, black, white)))
            button.strokeColor = ColorStateList(states, intArrayOf(transparent, white, white, transparent))
            button.strokeWidth = context.resources.getDimensionPixelSize(R.dimen.vs_3)
        }

        private fun cloudProvider(id: String): CloudStorageProvider? = CloudStorageProvider.entries.firstOrNull { it.id == id }

        private fun nameResource(provider: CloudStorageProvider): Int =
            when (provider) {
                CloudStorageProvider.ALIPAN -> R.string.open_drive_provider_alipan
                CloudStorageProvider.QUARK -> R.string.open_drive_provider_quark
                CloudStorageProvider.UC -> R.string.open_drive_provider_uc
                CloudStorageProvider.BAIDU -> R.string.open_drive_provider_baidu
                CloudStorageProvider.PAN123 -> R.string.open_drive_provider_123
                CloudStorageProvider.TIANYI -> R.string.open_drive_provider_tianyi
                CloudStorageProvider.DRIVE_115 -> R.string.open_drive_provider_115
            }
    }

    interface Listener {
        fun onLogin(
            provider: CloudStorageProvider,
            assisted: Boolean,
        )

        fun onLogout(
            providerId: String,
            displayName: String,
        )
    }

    class Holder(
        @JvmField val binding: ItemOpenDriveLoginBinding,
    ) : RecyclerView.ViewHolder(binding.root)
}
