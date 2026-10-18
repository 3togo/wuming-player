package com.wuming.tv.core.cloud

/** User-visible lifecycle of a provider token. */
enum class OpenDriveTokenState { UNAVAILABLE, SIGNED_OUT, PRESENT, CHECKING, VERIFIED, INVALID, ERROR }
