package com.wuming.tv.core.runtime.cloudlogin

/** Credential persistence is supplied by the platform; protocol code never chooses a storage backend. */
interface CloudCredentialStore {
    fun read(providerId: String): String
    fun write(providerId: String, credential: String)
    fun remove(providerId: String)
}
