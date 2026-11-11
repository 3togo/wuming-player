package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.hongguo.HongGuoCatalogSourceExecutor
import com.wuming.tv.core.hongguo.HongGuoHttpTransport
import com.wuming.tv.core.hongguo.HongGuoSiteSpec
import java.net.Proxy
import java.time.Duration
import okhttp3.OkHttpClient

/** Desktop network configuration; provider grammar stays in the shared adapter. */
object DesktopHongGuo {
    fun executor(spec: HongGuoSiteSpec) = HongGuoCatalogSourceExecutor(spec, HongGuoHttpTransport(
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(Duration.ofSeconds(15))
            .callTimeout(Duration.ofSeconds(30)).build(),
    ))
}
