package com.github.catvod.net

import android.net.Uri
import com.github.catvod.bean.Proxy
import com.github.catvod.utils.Auth
import com.github.catvod.utils.Util
import com.google.common.net.HttpHeaders
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class OkAuthenticator : Authenticator {

    private val proxy = CopyOnWriteArrayList<Proxy>()

    fun addAll(items: List<Proxy>) {
        proxy.addAll(items)
    }

    fun clear() {
        proxy.clear()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (route == null || response.request.header(HttpHeaders.PROXY_AUTHORIZATION) != null) return null
        val proxyAddress = route.proxy.address() as? InetSocketAddress ?: return null
        val requestHost = response.request.url.host
        val proxyHost = proxyAddress.hostName
        for (item in proxy) {
            for (host in item.getHosts()) {
                if (Util.containOrMatch(requestHost, host)) {
                    for (url in item.getUrls()) {
                        if (url.contains(proxyHost)) {
                            val userInfo = Uri.parse(url).userInfo
                            if (userInfo != null) {
                                return response.request.newBuilder()
                                    .header(HttpHeaders.PROXY_AUTHORIZATION, Auth.basic(userInfo))
                                    .build()
                            }
                        }
                    }
                }
            }
        }
        return null
    }
}
