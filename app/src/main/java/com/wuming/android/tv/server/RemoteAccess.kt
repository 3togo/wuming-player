package com.wuming.android.tv.server

import com.github.catvod.utils.Prefers
import com.wuming.android.tv.ProductCore
import com.wuming.tv.core.upnp.RemoteAccessPolicy
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class RemoteAccess private constructor() {
    companion object {
        private val policy = RemoteAccessPolicy(object : RemoteAccessPolicy.Store {
            override fun read(key: String): String? = Prefers.getString(key)
            override fun write(key: String, value: String) { Prefers.put(key, value) }
        }, { ProductCore.getDeviceType() == 0 })
        @JvmStatic fun secureWebAddress(address: String, tab: Int) = policy.secureWebAddress(address, tab)
        @JvmStatic fun openPairingWindow() = policy.openPairingWindow()
        @JvmStatic fun closePairingWindow() = policy.closePairingWindow()
        @JvmStatic fun claimPairing(code: String?, peer: String) = policy.claimPairing(code, peer)
        @JvmStatic fun rememberPairing(address: String, token: String) = policy.rememberPairing(address, token)
        @JvmStatic fun rotateServerToken() = policy.rotateServerToken()
        @JvmStatic fun newAuthorizedCall(client: OkHttpClient, url: String, body: RequestBody): Call {
            val request = Request.Builder().url(url).post(body)
            policy.authorizationFor(url)?.let { request.header("Authorization", it) }
            return client.newCall(request.build())
        }
        @JvmStatic fun allow(session: IHTTPSession, url: String, castMediaAuthorized: Boolean) =
            policy.allow(session.remoteIpAddress, url, castMediaAuthorized, session.headers["authorization"])
        @JvmStatic fun isProtected(url: String) = RemoteAccessPolicy.isProtected(url)
        @JvmStatic fun isSecretPreference(key: String) = RemoteAccessPolicy.isSecretPreference(key)
    }
}
