package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.*

/** Android provider creation and diagnostic adapters for the shared storage engine. */
class OpenDriveEngine(providers: Collection<OpenDriveProvider>?) {
    constructor() : this(defaultProviders())

    private val shared = CloudStorageEngine(providers, OpenDriveFailures.STATUSES, OpenDriveFailures.DIAGNOSTICS,
        { provider -> IllegalStateException("${provider.displayName()} session expired. Sign in again in Settings > Cloud Storage.") },
        OpenDriveFailurePolicy.StatusPort { error ->
            if (error is OpenDriveHttp.StatusException) OpenDriveFailurePolicy.Status(error.code()) else null
        })

    fun providers(): List<OpenDriveProvider> = shared.providers()
    fun provider(id: String?): OpenDriveProvider = shared.provider(id)
    @Throws(Exception::class)
    fun list(providerId: String?, parentId: String?): List<OpenDriveItem> = shared.list(providerId, parentId)
    @Throws(Exception::class)
    fun resolve(providerId: String?, itemId: String): OpenDrivePlayback = shared.resolve(providerId, itemId)
    fun persistHandle(providerId: String?, itemId: String): Boolean = shared.persistHandle(providerId, itemId)
    fun providerForShare(url: String): OpenDriveProvider = shared.providerForShare(url)
    @Throws(Exception::class)
    fun listShare(providerId: String?, url: String): List<OpenDriveItem> = shared.listShare(providerId, url)
    @Throws(Exception::class)
    fun beginLogin(providerId: String?): OpenDriveLoginChallenge = shared.beginLogin(providerId)
    @Throws(Exception::class)
    fun pollLogin(providerId: String?, sessionId: String?): OpenDriveLoginState = shared.pollLogin(providerId, sessionId)
    @Throws(Exception::class)
    fun logout(providerId: String?) = shared.logout(providerId)
    fun shutdown() = shared.providers().forEach { it.shutdown() }
    @Throws(Exception::class)
    fun proxy(params: Map<String, String>?): OpenDriveProxyResponse? = shared.proxy(params) { provider, values -> provider.proxy(values) }

    private companion object {
        fun defaultProviders(): List<OpenDriveProvider> = listOf(
            AliPanOpenDriveProvider(),
            QuarkOpenDriveProvider(),
            UcOpenDriveProvider(),
            BaiduOpenDriveProvider(),
            Pan123OpenDriveProvider(),
            TianyiOpenDriveProvider(),
            WebDavOpenDriveProvider(),
        )

    }
}
