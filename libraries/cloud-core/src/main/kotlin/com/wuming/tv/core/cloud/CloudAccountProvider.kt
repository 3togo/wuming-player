package com.wuming.tv.core.cloud

/** Platform-neutral cloud-account authentication boundary. */
interface CloudAccountProvider {
    fun id(): String
    fun displayName(): String
    fun available(): Boolean
    fun availabilityMessage(): String
    fun authenticated(): Boolean

    @Throws(Exception::class)
    fun verifyAuthentication(): Boolean = authenticated()

    fun supportsQrLogin(): Boolean = false

    @Throws(Exception::class)
    fun beginLogin(): OpenDriveLoginChallenge

    @Throws(Exception::class)
    fun pollLogin(sessionId: String): OpenDriveLoginState

    @Throws(Exception::class)
    fun logout()
}
