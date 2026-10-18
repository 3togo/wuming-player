package com.wuming.tv.core.source.diagnostic

enum class ProbeFailureCode {
    NONE,
    INVALID_RESPONSE,
    TIMEOUT,
    NETWORK,
    PROVIDER_ERROR,
    MISSING_SAMPLE,
    UNSUPPORTED,
    CANCELLED
}
