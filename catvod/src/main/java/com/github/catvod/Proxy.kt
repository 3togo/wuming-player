package com.github.catvod

import com.github.catvod.utils.Util

object Proxy {

    private var port: Int = -1

    @JvmStatic
    fun set(port: Int) {
        Proxy.port = port
    }

    @JvmStatic
    fun getPort(): Int = port

    @JvmStatic
    fun getUrl(local: Boolean): String =
        "http://" + (if (local) "127.0.0.1" else Util.getIp()) + ":" + getPort() + "/proxy"
}
