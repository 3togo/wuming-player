package com.wuming.android.tv.ui.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.viewbinding.ViewBinding
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ActivityNetworkInformationBinding
import com.wuming.android.tv.ui.base.BaseActivity
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Read-only details for the device's active network. */
class NetworkInformationActivity : BaseActivity() {
    private lateinit var binding: ActivityNetworkInformationBinding

    override fun getBinding(): ViewBinding =
        ActivityNetworkInformationBinding.inflate(layoutInflater).also { binding = it }

    override fun initView(savedInstanceState: Bundle?) {
        binding.refresh.requestFocus()
    }

    override fun initEvent() {
        binding.refresh.setOnClickListener { showNetwork() }
        binding.close.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        showNetwork()
    }

    private fun showNetwork() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val properties = network?.let { manager.getLinkProperties(it) }
        val unavailable = getString(R.string.network_information_unavailable)

        val status = when {
            network == null -> R.string.network_information_disconnected
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ->
                R.string.network_information_validated
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true ->
                R.string.network_information_unverified
            else -> R.string.network_information_local
        }
        val transport = when {
            capabilities == null -> unavailable
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> getString(R.string.network_information_vpn)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> getString(R.string.network_information_wifi)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> getString(R.string.network_information_ethernet)
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getString(R.string.network_information_cellular)
            else -> getString(R.string.network_information_other)
        }
        val addresses = properties?.linkAddresses.orEmpty().map { it.address }
        val ipv4 = formatAddresses(addresses.filterIsInstance<Inet4Address>(), unavailable)
        val ipv6 = formatAddresses(addresses.filterIsInstance<Inet6Address>(), unavailable)
        val gateways = properties?.routes.orEmpty().asSequence()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway?.hostAddress }
            .distinct().joinToString(", ").ifEmpty { unavailable }
        val dns = properties?.dnsServers.orEmpty().asSequence()
            .mapNotNull { it.hostAddress }.distinct().joinToString(", ").ifEmpty { unavailable }

        binding.status.text = getString(R.string.network_information_status, getString(status))
        binding.transport.text = getString(R.string.network_information_transport, transport)
        binding.ipv4.text = getString(R.string.network_information_ipv4, ipv4)
        binding.ipv6.text = getString(R.string.network_information_ipv6, ipv6)
        binding.gateway.text = getString(R.string.network_information_gateway, gateways)
        binding.dns.text = getString(R.string.network_information_dns, dns)
        binding.interfaceName.text = getString(
            R.string.network_information_interface, properties?.interfaceName ?: unavailable)
    }

    private fun formatAddresses(addresses: List<InetAddress>, unavailable: String): String =
        addresses.asSequence()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct().joinToString(", ").ifEmpty { unavailable }

    companion object {
        @JvmStatic
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, NetworkInformationActivity::class.java))
        }
    }
}
