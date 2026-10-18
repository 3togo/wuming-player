package com.wuming.android.tv.api.provider

class ProviderException : Exception {
    internal constructor(message: String) : super(message)
    internal constructor(message: String, cause: Throwable) : super(message, cause)
}
