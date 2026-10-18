package com.wuming.android.tv.dlna

import android.os.Build
import com.wuming.tv.core.upnp.OkHttpStreamClient
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration
import org.fourthline.cling.model.ServerClientTokens
import org.fourthline.cling.transport.spi.NetworkAddressFactory
import org.fourthline.cling.transport.spi.StreamClient
import org.fourthline.cling.transport.spi.StreamServer

open class DLNAServiceConfiguration : AndroidUpnpServiceConfiguration() {

    override fun createStreamClient(): StreamClient<*> =
        OkHttpStreamClient(object : OkHttpStreamClient.Configuration(syncProtocolExecutorService) {
            override fun getUserAgentValue(majorVersion: Int, minorVersion: Int): String {
                val tokens = ServerClientTokens(majorVersion, minorVersion)
                tokens.osVersion = Build.VERSION.RELEASE
                tokens.osName = "Android"
                return tokens.toString()
            }
        })

    override fun createStreamServer(networkAddressFactory: NetworkAddressFactory): StreamServer<*> =
        SocketHttpStreamServer(SocketHttpStreamServer.Configuration(networkAddressFactory.streamListenPort))
}
