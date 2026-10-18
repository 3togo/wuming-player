package com.wuming.tv.core.source

/** Work around the measured Migu resolver's zero-length redirects with trailing HTML. */
object HttpConnectionPolicy {
    @JvmStatic fun shouldClose(scheme: String, host: String): Boolean =
        scheme.equals("http", ignoreCase = true) &&
            host.equals("gslbmgsplive.miguvideo.com", ignoreCase = true)
}
