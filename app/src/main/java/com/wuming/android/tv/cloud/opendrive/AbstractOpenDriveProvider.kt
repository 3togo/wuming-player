package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.CredentialShapePolicy
import com.wuming.tv.core.cloud.CredentialShapePolicy.isLoginValue
import com.wuming.tv.core.cloud.CredentialShapePolicy.isSharePasscode
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy.httpsUri
import com.wuming.tv.core.cloud.FirstPartyOriginPolicy.isWithinDomain
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Shared protocol helpers for built-in cloud drive providers.
 *
 *
 * Only stateless or generically parameterizable logic lives here: RFC 7233
 * range patterns, credential shape validation, share URL canonicalization, and
 * stream prefix reading. Provider-specific transport, cookie, QR login session
 * shapes, and playback strategies stay in each concrete class.
 *
 *
 * The share-link grammars are deliberately not unified here. Quark/Uc use
 * `/s/<id>`, Baidu `/s/1<suffix>`, AliPan `/s/<id>[/<folder>]`,
 * Pan123 `/s/<key>` and Tianyi `/web/share?code=`, so each keeps its own
 * canonicalizer; only the passcode rule they genuinely agree on is shared.
 */
abstract class AbstractOpenDriveProvider : OpenDriveProvider {
    // Declare these delegates explicitly: Kotlin's generated compatibility bridges omit
    // checked exceptions, which would reject Java provider overrides during javac.
    @Throws(Exception::class)
    override fun verifyAuthentication(): Boolean = super<OpenDriveProvider>.verifyAuthentication()

    @Throws(Exception::class)
    override fun listShare(url: String): List<com.wuming.tv.core.cloud.OpenDriveItem> =
        super<OpenDriveProvider>.listShare(url)

    @Throws(Exception::class)
    override fun proxy(params: Map<String, String>?): OpenDriveProxyResponse? =
        super<OpenDriveProvider>.proxy(params)

    // Explicit delegates keep Java subclasses overriding ordinary methods rather
    // than synthetic compatibility bridges for Kotlin interface defaults.
    override fun supportsQrLogin(): Boolean = super<OpenDriveProvider>.supportsQrLogin()
    override fun supportsShare(url: String): Boolean = super<OpenDriveProvider>.supportsShare(url)
    override fun prepareExternal(payload: OpenDriveExternalPayload?): OpenDrivePreparedExternal? =
        super<OpenDriveProvider>.prepareExternal(payload)
    override fun credentialsRestored() = super<OpenDriveProvider>.credentialsRestored()
    override fun shutdown() = super<OpenDriveProvider>.shutdown()
    override fun persistHandle(itemId: String): Boolean = super<OpenDriveProvider>.persistHandle(itemId)

    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmField val BYTE_RANGE: Pattern = com.wuming.tv.core.cloud.CloudProtocol.BYTE_RANGE
        @JvmField val SUFFIX_RANGE: Pattern = com.wuming.tv.core.cloud.CloudProtocol.SUFFIX_RANGE
        @JvmField val CONTENT_RANGE: Pattern = com.wuming.tv.core.cloud.CloudProtocol.CONTENT_RANGE
        @JvmStatic open fun validLoginValue(value: String?): Boolean = com.wuming.tv.core.cloud.CloudProtocol.validLoginValue(value)
        @JvmStatic open fun canonicalShareUrl(value: String?, host: String): String? = com.wuming.tv.core.cloud.CloudProtocol.canonicalShareUrl(value, host)
        @JvmStatic open fun sharePasscode(value: String?, providerName: String?): String = com.wuming.tv.core.cloud.CloudProtocol.sharePasscode(value, providerName)
        @JvmStatic open fun shareUrlWithPasscode(canonical: String, original: String?, provider: String?): String? =
            com.wuming.tv.core.cloud.CloudProtocol.shareUrlWithPasscode(canonical, original, provider)
        @Throws(IOException::class)
        @JvmStatic open fun readPrefix(input: InputStream?, limit: Int): ByteArray = com.wuming.tv.core.cloud.CloudProtocol.readPrefix(input, limit)
        @JvmStatic open fun urlLooksLikeHls(value: String?): Boolean = com.wuming.tv.core.cloud.CloudProtocol.urlLooksLikeHls(value)
    }
}
