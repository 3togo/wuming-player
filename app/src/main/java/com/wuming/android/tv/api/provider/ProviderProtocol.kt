package com.wuming.android.tv.api.provider

internal object ProviderProtocol {
    const val DESCRIPTOR = "com.wuming.android.tv.api.provider"
    const val TRANSACTION_CALL = android.os.IBinder.FIRST_CALL_TRANSACTION

    const val OP = "op"
    const val KEY = "key"
    const val API = "api"
    const val EXT = "ext"
    const val JAR = "jar"
    const val ARGS = "args"
    const val PARAMS = "params"
    const val IDS = "ids"
    const val FLAGS = "flags"
    const val JSON = "json"
    const val PROXY_PORT = "proxyPort"
    const val ENABLED = "enabled"

    const val PING = 0
    const val PRELOAD = 1
    const val HOME = 2
    const val HOME_VIDEO = 3
    const val CATEGORY = 4
    const val DETAIL = 5
    const val SEARCH = 6
    const val SEARCH_PAGE = 7
    const val PLAYER = 8
    const val LIVE = 9
    const val MANUAL_VIDEO = 10
    const val VIDEO_FORMAT = 11
    const val ACTION = 12
    const val PROXY = 13
    const val JSON_EXT = 14
    const val JSON_EXT_MIX = 15
    const val TLS_POLICY = 16
    const val CRASH_FOR_TEST = 99

    const val RESULT_OK = 0
    const val RESULT_ERROR = 1
}
