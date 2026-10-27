package com.wuming.tv.desktop.catalog

import com.wuming.tv.core.libvio.LibvioCatalogSourceExecutor
import com.wuming.tv.core.libvio.LibvioHttpTransport
import com.wuming.tv.core.libvio.LibvioSiteSpec
import java.net.Proxy
import java.time.Duration
import okhttp3.OkHttpClient

/** Site traffic uses the same direct route as the previously verified Libvio provider. */
object DesktopLibvio {
    fun executor(spec: LibvioSiteSpec) = LibvioCatalogSourceExecutor(spec, LibvioHttpTransport(
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectTimeout(Duration.ofSeconds(15))
            .callTimeout(Duration.ofSeconds(30)).build(),
    ))
}
