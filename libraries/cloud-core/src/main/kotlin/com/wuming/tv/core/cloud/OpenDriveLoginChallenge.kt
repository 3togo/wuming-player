package com.wuming.tv.core.cloud

/** A provider-issued login challenge; zero expiry means the provider did not publish one. */
class OpenDriveLoginChallenge(
    val id: String,
    val content: String,
    expiresAtEpochMillis: Long
) {
    val expiresAtEpochMillis: Long = maxOf(0L, expiresAtEpochMillis)

    init {
        if (id.isBlank()) throw IllegalArgumentException("Login session ID is required")
        if (content.isBlank()) throw IllegalArgumentException("Login challenge is required")
    }

    @JvmName("id")
    fun idAccessor(): String = id
    @JvmName("content")
    fun contentAccessor(): String = content
    @JvmName("expiresAtEpochMillis")
    fun expiresAtEpochMillisAccessor(): Long = expiresAtEpochMillis
}
