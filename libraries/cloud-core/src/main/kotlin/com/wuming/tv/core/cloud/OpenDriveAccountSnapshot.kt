package com.wuming.tv.core.cloud

/** Immutable account state consumed by Cloud Storage screens. */
class OpenDriveAccountSnapshot(
    val providerId: String,
    val displayName: String,
    val available: Boolean,
    val supportsQrLogin: Boolean,
    val tokenPresent: Boolean,
    val state: OpenDriveTokenState
) {
    @JvmName("providerId")
    fun providerIdAccessor(): String = providerId
    @JvmName("displayName")
    fun displayNameAccessor(): String = displayName
    @JvmName("available")
    fun availableAccessor(): Boolean = available
    @JvmName("supportsQrLogin")
    fun supportsQrLoginAccessor(): Boolean = supportsQrLogin
    @JvmName("tokenPresent")
    fun tokenPresentAccessor(): Boolean = tokenPresent
    @JvmName("state")
    fun stateAccessor(): OpenDriveTokenState = state

    fun verified(): Boolean = state == OpenDriveTokenState.VERIFIED

    fun canStartLogin(): Boolean {
        return available && supportsQrLogin
            && (state == OpenDriveTokenState.SIGNED_OUT || state == OpenDriveTokenState.INVALID)
    }
}
