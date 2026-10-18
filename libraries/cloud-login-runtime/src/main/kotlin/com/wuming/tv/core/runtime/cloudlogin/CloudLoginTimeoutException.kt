package com.wuming.tv.core.runtime.cloudlogin

/**
 * Thrown by a non-JDK login transport (for example Android's OkHttp bridge) when
 * a provider holds a poll connection open until the QR code is scanned.
 *
 * The shared providers already rely on the JDK [java.net.http.HttpTimeoutException]
 * from [JdkCloudLoginTransport] to mean "keep polling". Platforms whose HTTP stack
 * raises a different timeout type (OkHttp raises [java.net.SocketTimeoutException],
 * which is checked and cannot be rethrown through the non-throwing
 * [CloudLoginTransport] contract) translate it to this unchecked type so the same
 * WAITING handling applies everywhere.
 */
class CloudLoginTimeoutException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)
