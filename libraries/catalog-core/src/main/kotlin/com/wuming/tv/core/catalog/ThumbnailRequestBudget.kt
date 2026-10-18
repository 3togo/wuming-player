package com.wuming.tv.core.catalog

/** Posters are optional UI decoration: keep their transport separate and short-lived. */
object ThumbnailRequestBudget {
    const val CONNECT_TIMEOUT_MS = 4_000L
    const val READ_TIMEOUT_MS = 6_000L
    const val CALL_TIMEOUT_MS = 10_000L
    const val MAX_CONCURRENT_REQUESTS = 6
}
