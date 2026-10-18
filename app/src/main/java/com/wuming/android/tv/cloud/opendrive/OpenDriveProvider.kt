package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.*

/** Stable contract implemented only by cloud providers compiled into TVBox. */
interface OpenDriveProvider : CloudStorageProvider {
    override fun id(): String
    override fun displayName(): String
    override fun available(): Boolean
    override fun availabilityMessage(): String
    override fun authenticated(): Boolean

    /**
     * Performs a small, read-only request against the provider's own account API.
     * A local cookie/token is only a credential candidate; the UI must not call it
     * logged in until this check succeeds.
     */
    @Throws(Exception::class)
    override fun verifyAuthentication(): Boolean = authenticated()

    /** True only when beginLogin/pollLogin implement a first-party QR session. */
    override fun supportsQrLogin(): Boolean = false

    @Throws(Exception::class)
    override fun beginLogin(): OpenDriveLoginChallenge

    @Throws(Exception::class)
    override fun pollLogin(sessionId: String): OpenDriveLoginState

    @Throws(Exception::class)
    override fun list(parentId: String): List<OpenDriveItem>

    override fun supportsShare(url: String): Boolean = false

    @Throws(Exception::class)
    override fun listShare(url: String): List<OpenDriveItem> =
        throw UnsupportedOperationException("Shared links are not supported")

    /** Parses and validates external metadata once without creating a provider item handle. */
    fun prepareExternal(flag: String?, payload: String?): OpenDrivePreparedExternal? {
        val prepared = OpenDriveExternalPayload.create(flag, payload) ?: return null
        return prepareExternal(prepared)
    }

    /** Provider probe over the shared, one-time decoded payload. */
    fun prepareExternal(payload: OpenDriveExternalPayload?): OpenDrivePreparedExternal? = null

    /** Compatibility entry point for callers that require immediate materialization. */
    fun importExternal(flag: String?, payload: String?): String? =
        prepareExternal(flag, payload)?.materialize()

    @Throws(Exception::class)
    override fun resolve(itemId: String): OpenDrivePlayback

    /** Persists sensitive metadata for a successfully resolved opaque item handle. */
    override fun persistHandle(itemId: String): Boolean = false

    @Throws(Exception::class)
    fun proxy(params: Map<String, String>?): OpenDriveProxyResponse? = null

    /** Drop process-local account state after restore without deleting imported secrets. */
    fun credentialsRestored() {}

    @Throws(Exception::class)
    override fun logout()

    /** Release process-local resources when the provider is no longer needed. */
    fun shutdown() {}
}
