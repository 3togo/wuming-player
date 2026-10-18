package com.wuming.tv.desktop.server

/** Local telemetry only: never contains upstream URLs, headers or pairing/session tokens. */
data class RelayMonitorSnapshot(
    val enabled: Boolean,
    val pairedDevices: Int,
    val preparing: Int,
    val deliveredBytes: Long,
    val failures: Long,
    val sessions: List<RelaySessionSnapshot>,
)

data class RelaySessionSnapshot(
    val number: Long,
    val device: String,
    val activeRequests: Int,
    val requests: Long,
    val deliveredBytes: Long,
    val failures: Long,
    val idleMillis: Long,
)
