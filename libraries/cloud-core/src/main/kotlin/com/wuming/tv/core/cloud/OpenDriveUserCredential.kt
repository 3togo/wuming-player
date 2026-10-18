package com.wuming.tv.core.cloud

/**
 * How a non-netdisk service credential is replayed when consumed by another
 * subsystem (danmaku, VIP parse-site resolution, live OAuth, ...).
 */
enum class UserCredentialMethod {
    /** username + password posted to a login endpoint; the pair is the secret. */
    PASSWORD,

    /** A raw `Cookie` header string replayed across the service's own hosts. */
    COOKIE,

    /** An opaque bearer/token string sent as an `Authorization` header. */
    OAUTH_TOKEN
}

/**
 * Platform-neutral description of a user-registered service credential. The secret
 * itself is stored separately in the Android encrypted vault and never crosses this
 * model, keeping [OpenDriveUserCredential] safe to use from [com.wuming.tv.core].
 */
data class OpenDriveUserCredential(
    val serviceId: String,
    val displayName: String,
    val baseUrl: String,
    val method: UserCredentialMethod,
    val expiresAtEpochMillis: Long
) {
    init {
        require(serviceId.isNotBlank()) { "Service id is required" }
        require(displayName.isNotBlank()) { "Display name is required" }
    }
}
