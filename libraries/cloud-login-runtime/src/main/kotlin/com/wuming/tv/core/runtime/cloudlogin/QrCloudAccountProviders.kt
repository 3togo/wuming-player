package com.wuming.tv.core.runtime.cloudlogin

import com.wuming.tv.core.cloud.CloudAccountProvider
import com.wuming.tv.core.cloud.OpenDriveLoginChallenge
import com.wuming.tv.core.cloud.OpenDriveLoginState
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginTimeoutException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

fun desktopQrCloudProviders(
    credentials: CloudCredentialStore,
    transport: CloudLoginTransport = JdkCloudLoginTransport(),
): List<CloudAccountProvider> = listOf(
    AliPanCloudAccountProvider(credentials, transport),
    BaiduCloudAccountProvider(credentials, transport),
    QuarkCloudAccountProvider(credentials, transport),
    UcCloudAccountProvider(credentials, transport),
    BilibiliAccountProvider(credentials, transport),
)

abstract class QrCloudAccountProvider : CloudAccountProvider {
    override fun available() = true
    override fun availabilityMessage() = ""
    override fun supportsQrLogin() = true
}

class BilibiliAccountProvider(
    private val credentials: CloudCredentialStore,
    private val transport: CloudLoginTransport = JdkCloudLoginTransport(),
) : QrCloudAccountProvider() {
    private data class Session(
        val key: String,
        val expiresAt: Long,
        val cookies: LinkedHashMap<String, String>,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    override fun id() = "bilibili"
    override fun displayName() = "Bilibili"
    override fun authenticated() = hasAccountCookie(parseCookieHeader(credentials.read(id())))

    override fun beginLogin(): OpenDriveLoginChallenge {
        val now = System.currentTimeMillis()
        val response = transport.get(QR_GENERATE, headers = headers())
        if (response.body.longValue("code") != 0L) {
            throw IOException("Bilibili did not create a QR login session")
        }
        val data = response.body.objectValue("data")
        val key = data.stringValue("qrcode_key")
        val content = data.stringValue("url")
        if (!validKey(key) || !content.startsWith("https://")) {
            throw IOException("Bilibili returned an invalid QR login session")
        }
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = now + TimeUnit.MINUTES.toMillis(5)
        sessions.clear()
        sessions[sessionId] = Session(key, expiresAt, LinkedHashMap(cookiePairs(response.setCookies)))
        return OpenDriveLoginChallenge(sessionId, content, expiresAt)
    }

    override fun pollLogin(sessionId: String): OpenDriveLoginState {
        val session = sessions[sessionId] ?: return OpenDriveLoginState.EXPIRED
        if (System.currentTimeMillis() >= session.expiresAt) {
            sessions.remove(sessionId)
            return OpenDriveLoginState.EXPIRED
        }
        val response = transport.get(
            QR_POLL,
            mapOf("qrcode_key" to session.key),
            headers(session.cookies),
        )
        session.cookies.putAll(cookiePairs(response.setCookies))
        if (response.body.longValue("code") != 0L) return OpenDriveLoginState.FAILED
        return when (response.body.objectValue("data").longValue("code")) {
            86_101L -> OpenDriveLoginState.WAITING
            86_090L -> OpenDriveLoginState.SCANNED
            86_038L -> OpenDriveLoginState.EXPIRED.also { sessions.remove(sessionId) }
            0L -> confirm(sessionId, session.cookies)
            else -> OpenDriveLoginState.FAILED
        }
    }

    private fun confirm(sessionId: String, cookies: Map<String, String>): OpenDriveLoginState {
        if (!hasAccountCookie(cookies)) return OpenDriveLoginState.FAILED
        credentials.write(id(), cookies.cookieHeader())
        sessions.remove(sessionId)
        return OpenDriveLoginState.CONFIRMED
    }

    override fun verifyAuthentication(): Boolean {
        val cookies = parseCookieHeader(credentials.read(id()))
        if (!hasAccountCookie(cookies)) return false
        val response = transport.get(NAV_API, headers = headers(cookies))
        cookies.putAll(cookiePairs(response.setCookies))
        val valid = response.body.longValue("code") == 0L &&
            response.body.objectValue("data").booleanValue("isLogin")
        if (valid) credentials.write(id(), cookies.cookieHeader())
        return valid
    }

    override fun logout() {
        sessions.clear()
        credentials.remove(id())
    }

    private fun headers(cookies: Map<String, String> = emptyMap()) = buildMap {
        put("Referer", "https://www.bilibili.com/")
        put("User-Agent", USER_AGENT)
        if (cookies.isNotEmpty()) put("Cookie", cookies.cookieHeader())
    }

    private fun hasAccountCookie(cookies: Map<String, String>) = cookies.keys.any {
        it.equals("SESSDATA", true)
    }

    private fun validKey(value: String) = value.length in 8..512 && value.all { it.code in 0x21..0x7e }

    private companion object {
        const val QR_GENERATE = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
        const val QR_POLL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
        const val NAV_API = "https://api.bilibili.com/x/web-interface/nav"
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

class BaiduCloudAccountProvider(
    private val credentials: CloudCredentialStore,
    private val transport: CloudLoginTransport = JdkCloudLoginTransport(),
) : QrCloudAccountProvider() {
    private data class Session(
        val sign: String,
        val expiresAt: Long,
        val cookies: LinkedHashMap<String, String>,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    override fun id() = "baidu"
    override fun displayName() = "Baidu Netdisk"
    override fun authenticated() = hasAccountCookie(parseCookieHeader(credentials.read(id())))

    override fun beginLogin(): OpenDriveLoginChallenge {
        val now = System.currentTimeMillis()
        val response = transport.get(
            QR_GENERATE,
            mapOf("lp" to "pc", "_" to now.toString()),
            headers(),
        )
        if (response.body.longValue("errno") != 0L) {
            throw IOException("Baidu Netdisk did not create a QR login session")
        }
        val sign = response.body.stringValue("sign")
        if (!validLoginValue(sign)) throw IOException("Baidu returned an invalid QR login session")
        val content = "https://wappass.baidu.com/wp/?qrlogin&t=$now" +
            "&error=0&sign=$sign&cmd=login&lp=pc&tpl=netdisk&uaonly=&client_id=" +
            "&adapter=3&client=&qrloginfrom=pc&wechat=0&traceid="
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = now + TimeUnit.MINUTES.toMillis(5)
        sessions.clear()
        sessions[sessionId] = Session(sign, expiresAt, LinkedHashMap(cookiePairs(response.setCookies)))
        return OpenDriveLoginChallenge(sessionId, content, expiresAt)
    }

    override fun pollLogin(sessionId: String): OpenDriveLoginState {
        val session = sessions[sessionId] ?: return OpenDriveLoginState.EXPIRED
        val now = System.currentTimeMillis()
        if (now >= session.expiresAt) {
            sessions.remove(sessionId)
            return OpenDriveLoginState.EXPIRED
        }
        val response = try {
            transport.get(
                QR_POLL,
                mapOf(
                    "channel_id" to session.sign,
                    "tpl" to "netdisk",
                    "apiver" to "v3",
                    "callback" to CALLBACK,
                    "tt" to now.toString(),
                    "_" to now.toString(),
                ),
                headers(session.cookies),
            )
        } catch (error: Exception) {
            // Baidu holds this endpoint open while no phone has scanned the QR code.
            if (error is HttpTimeoutException || error is CloudLoginTimeoutException) {
                return OpenDriveLoginState.WAITING
            }
            throw error
        }
        session.cookies.putAll(cookiePairs(response.setCookies))
        if (response.body.longValue("errno") != 0L) return OpenDriveLoginState.WAITING
        val channel = runCatching {
            JsonParser.parseString(response.body.stringValue("channel_v")).asJsonObject
        }.getOrNull() ?: return OpenDriveLoginState.WAITING
        val status = channel.longValue("status")
        if (status != 0L) return if (status == 1L) OpenDriveLoginState.SCANNED else OpenDriveLoginState.WAITING
        val bduss = channel.stringValue("v")
        if (!validLoginValue(bduss)) return OpenDriveLoginState.FAILED
        val confirmation = transport.getText(
            QR_CONFIRM,
            mapOf(
                "v" to now.toString(),
                "bduss" to bduss,
                "u" to "",
                "loginVersion" to "v4",
                "qrcode" to "1",
                "tpl" to "netdisk",
                "apiver" to "v3",
                "tt" to now.toString(),
                "traceid" to "",
                "callback" to CALLBACK,
            ),
            headers(session.cookies),
        )
        session.cookies.putAll(cookiePairs(confirmation.setCookies))
        if (!validConfirmation(confirmation.body)) {
            return OpenDriveLoginState.FAILED
        }
        val credential = normalizedAccountCookies(session.cookies)
        if (credential.isBlank()) return OpenDriveLoginState.FAILED
        credentials.write(id(), credential)
        sessions.remove(sessionId)
        return OpenDriveLoginState.CONFIRMED
    }

    override fun verifyAuthentication(): Boolean {
        val cookies = parseCookieHeader(credentials.read(id()))
        if (!hasAccountCookie(cookies)) return false
        val response = transport.get(
            "$HOST/api/list",
            mapOf(
                "dir" to "/", "order" to "name", "desc" to "0", "showempty" to "0",
                "page" to "1", "num" to "1", "web" to "1", "app_id" to "250528",
            ),
            headers(cookies),
        )
        cookies.putAll(cookiePairs(response.setCookies))
        val valid = response.body.longValue("errno") == 0L &&
            response.body.get("list")?.isJsonArray == true
        if (valid) credentials.write(id(), normalizedAccountCookies(cookies))
        return valid
    }

    override fun logout() {
        sessions.clear()
        credentials.remove(id())
    }

    private fun headers(cookies: Map<String, String> = emptyMap()) = buildMap {
        put("Referer", "$HOST/")
        put("User-Agent", USER_AGENT)
        if (cookies.isNotEmpty()) put("Cookie", cookies.cookieHeader())
    }

    private fun normalizedAccountCookies(cookies: Map<String, String>): String {
        if (!hasAccountCookie(cookies)) return ""
        return cookies.filterKeys { !it.equals("BDCLND", true) }.cookieHeader()
    }

    private fun hasAccountCookie(cookies: Map<String, String>) = cookies.keys.any {
        it.equals("BDUSS", true) || it.equals("BDUSS_BFESS", true)
    }

    private fun validLoginValue(value: String) = value.length in 8..4096 && value.all {
        it.code in 0x21..0x7e && it != '&'
    }

    private fun validConfirmation(value: String): Boolean {
        val normalized = value.replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
        return CONFIRMATION_SUCCESS.containsMatchIn(normalized)
    }

    private companion object {
        const val HOST = "https://pan.baidu.com"
        const val QR_GENERATE = "https://passport.baidu.com/v2/api/getqrcode"
        const val QR_POLL = "https://passport.baidu.com/channel/unicast"
        const val QR_CONFIRM = "https://passport.baidu.com/v3/login/main/qrbdusslogin"
        const val CALLBACK = "bd__cbs__opendrive"
        val CONFIRMATION_SUCCESS = Regex(
            "\\\"errInfo\\\"\\s*:\\s*\\{[^{}]{0,1024}\\\"no\\\"\\s*:\\s*(?:\\\"0\\\"|0)(?=\\s*[,}])",
        )
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

class AliPanCloudAccountProvider(
    private val credentials: CloudCredentialStore,
    private val transport: CloudLoginTransport = JdkCloudLoginTransport(),
) : QrCloudAccountProvider() {
    private data class Session(val t: String, val ck: String, val expiresAt: Long)
    private val sessions = ConcurrentHashMap<String, Session>()

    override fun id() = "alipan"
    override fun displayName() = "Aliyun Drive"
    override fun authenticated() = validToken(credentials.read(id()))

    override fun beginLogin(): OpenDriveLoginChallenge {
        val now = System.currentTimeMillis()
        val root = transport.get(QR_GENERATE, ALI_QUERY, PUBLIC_HEADERS).body
        val data = root.objectValue("content").objectValue("data")
        val t = data.stringValue("t")
        val ck = data.stringValue("ck")
        val content = data.stringValue("codeContent")
        if (!validValue(t) || !validValue(ck) || !content.startsWith("https://")) {
            throw IOException("Aliyun Drive did not create a valid QR login session")
        }
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = now + TimeUnit.MINUTES.toMillis(10)
        sessions.clear()
        sessions[sessionId] = Session(t, ck, expiresAt)
        return OpenDriveLoginChallenge(sessionId, content, expiresAt)
    }

    override fun pollLogin(sessionId: String): OpenDriveLoginState {
        val session = sessions[sessionId] ?: return OpenDriveLoginState.EXPIRED
        if (System.currentTimeMillis() >= session.expiresAt) {
            sessions.remove(sessionId)
            return OpenDriveLoginState.EXPIRED
        }
        val form = LinkedHashMap(ALI_QUERY).apply {
            put("t", session.t)
            put("ck", session.ck)
            put("navlanguage", "zh-CN")
            put("navPlatform", "Linux")
        }
        val data = transport.postForm(
            "$QR_QUERY?appName=aliyun_drive&fromSite=52&_bx-v=2.2.3",
            form,
            PUBLIC_HEADERS,
        ).body.objectValue("content").objectValue("data")
        return when (data.stringValue("qrCodeStatus").uppercase()) {
            "NEW" -> OpenDriveLoginState.WAITING
            "SCANED", "SCANNED" -> OpenDriveLoginState.SCANNED
            "EXPIRED" -> OpenDriveLoginState.EXPIRED.also { sessions.remove(sessionId) }
            "CONFIRMED" -> confirm(sessionId, data.stringValue("bizExt"))
            else -> OpenDriveLoginState.FAILED
        }
    }

    private fun confirm(sessionId: String, encoded: String): OpenDriveLoginState {
        val refreshToken = runCatching {
            val decoded = Base64.getDecoder().decode(encoded)
            JsonParser.parseString(String(decoded, StandardCharsets.UTF_8)).asJsonObject
                .objectValue("pds_login_result").stringValue("refreshToken")
        }.getOrDefault("")
        if (!validValue(refreshToken)) return OpenDriveLoginState.FAILED
        val body = JsonObject().apply {
            addProperty("refresh_token", refreshToken)
            addProperty("grant_type", "refresh_token")
        }
        val token = transport.postJson(AUTH, body.toString(), PUBLIC_HEADERS).body.toString()
        if (!validToken(token)) return OpenDriveLoginState.FAILED
        credentials.write(id(), token)
        sessions.remove(sessionId)
        return OpenDriveLoginState.CONFIRMED
    }

    override fun verifyAuthentication(): Boolean = authenticated()

    override fun logout() {
        sessions.clear()
        credentials.remove(id())
    }

    private fun validToken(raw: String): Boolean = runCatching {
        val value = JsonParser.parseString(raw).asJsonObject
        value.stringValue("access_token").length >= 20 &&
            value.stringValue("refresh_token").length >= 20 &&
            (value.stringValue("default_drive_id").ifBlank { value.stringValue("resource_drive_id") }).isNotBlank()
    }.getOrDefault(false)

    private fun validValue(value: String) = value.length in 8..4096 && value.all { it.code in 0x21..0x7e }

    private companion object {
        const val AUTH = "https://auth.aliyundrive.com/v2/account/token"
        const val QR_GENERATE = "https://passport.aliyundrive.com/newlogin/qrcode/generate.do"
        const val QR_QUERY = "https://passport.aliyundrive.com/newlogin/qrcode/query.do"
        val PUBLIC_HEADERS = mapOf("Referer" to "https://www.alipan.com/")
        val ALI_QUERY = mapOf(
            "appName" to "aliyun_drive", "fromSite" to "52", "appEntrance" to "web",
            "isMobile" to "false", "lang" to "zh_CN", "returnUrl" to "", "bizParams" to "",
            "_bx-v" to "2.2.3",
        )
    }
}

class QuarkCloudAccountProvider(
    private val credentials: CloudCredentialStore,
    private val transport: CloudLoginTransport = JdkCloudLoginTransport(),
) : QrCloudAccountProvider() {
    private data class Session(val token: String, val expiresAt: Long, val cookies: LinkedHashMap<String, String>)
    private val sessions = ConcurrentHashMap<String, Session>()

    override fun id() = "quark"
    override fun displayName() = "Quark Drive"
    override fun authenticated() = hasAccountCookie(parseCookieHeader(credentials.read(id())))

    override fun beginLogin(): OpenDriveLoginChallenge {
        val now = System.currentTimeMillis()
        val response = transport.get(
            QR_TOKEN_API,
            mapOf("client_id" to "386", "v" to VERSION, "request_id" to UUID.randomUUID().toString()),
            headers(),
        )
        val token = response.body.objectValue("data").objectValue("members").stringValue("token")
        if (!validValue(token)) throw IOException("Quark did not create a valid QR login session")
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = now + TimeUnit.MINUTES.toMillis(10)
        sessions.clear()
        sessions[sessionId] = Session(token, expiresAt, LinkedHashMap(cookiePairs(response.setCookies)))
        val content = "https://su.quark.cn/4_eMHBJ?uc_param_str=&token=$token&client_id=532" +
            "&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400"
        return OpenDriveLoginChallenge(sessionId, content, expiresAt)
    }

    override fun pollLogin(sessionId: String): OpenDriveLoginState {
        val session = sessions[sessionId] ?: return OpenDriveLoginState.EXPIRED
        if (System.currentTimeMillis() >= session.expiresAt) {
            sessions.remove(sessionId)
            return OpenDriveLoginState.EXPIRED
        }
        val response = transport.get(
            QR_POLL_API,
            mapOf("token" to session.token, "client_id" to "532", "v" to VERSION,
                "request_id" to UUID.randomUUID().toString()),
            headers(session.cookies),
        )
        session.cookies.putAll(cookiePairs(response.setCookies))
        val status = response.body.longValue("status")
        if (status == 50_004_001L) return OpenDriveLoginState.WAITING
        if (status != 2_000_000L) return OpenDriveLoginState.FAILED
        val ticket = response.body.objectValue("data").objectValue("members").stringValue("service_ticket")
        if (!validValue(ticket)) return OpenDriveLoginState.FAILED
        val account = transport.get(
            "https://pan.quark.cn/account/info",
            mapOf("st" to ticket, "lw" to "scan"),
            headers(session.cookies),
        )
        session.cookies.putAll(cookiePairs(account.setCookies))
        if (!account.body.booleanValue("success") || !hasAccountCookie(session.cookies)) {
            return OpenDriveLoginState.FAILED
        }
        credentials.write(id(), session.cookies.cookieHeader())
        sessions.remove(sessionId)
        return OpenDriveLoginState.CONFIRMED
    }

    override fun verifyAuthentication(): Boolean {
        val cookies = parseCookieHeader(credentials.read(id()))
        if (!hasAccountCookie(cookies)) return false
        val response = transport.get(
            "$API/file/sort",
            mapOf("pr" to "ucpro", "fr" to "pc", "pdir_fid" to "0", "_page" to "1", "_size" to "1",
                "_sort" to "file_type:asc,updated_at:desc"),
            headers(cookies),
        )
        cookies.putAll(cookiePairs(response.setCookies))
        if (hasAccountCookie(cookies)) credentials.write(id(), cookies.cookieHeader())
        return response.body.objectValue("data").size() > 0
    }

    override fun logout() {
        sessions.clear()
        credentials.remove(id())
    }

    private fun headers(cookies: Map<String, String> = emptyMap()) = buildMap {
        put("Referer", "https://pan.quark.cn/")
        put("User-Agent", USER_AGENT)
        if (cookies.isNotEmpty()) put("Cookie", cookies.cookieHeader())
    }

    private fun hasAccountCookie(cookies: Map<String, String>) = "__pus" in cookies || "__puus" in cookies
    private fun validValue(value: String) = value.length in 8..2048 && value.matches(Regex("[A-Za-z0-9._~-]+"))

    private companion object {
        const val API = "https://drive-pc.quark.cn/1/clouddrive"
        const val QR_TOKEN_API = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
        const val QR_POLL_API = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
        const val VERSION = "1.2"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) quark-cloud-drive/3.20.0 Chrome/112.0.5615.165 " +
            "Electron/24.1.3.8 Safari/537.36 Channel/pckk_other_ch"
    }
}

class UcCloudAccountProvider(
    private val credentials: CloudCredentialStore,
    private val transport: CloudLoginTransport = JdkCloudLoginTransport(),
) : QrCloudAccountProvider() {
    private data class Session(
        val token: String,
        val requestId: String,
        val expiresAt: Long,
        val cookies: LinkedHashMap<String, String>,
    )
    private val sessions = ConcurrentHashMap<String, Session>()

    override fun id() = "uc"
    override fun displayName() = "UC Drive"
    override fun authenticated() = "__pus" in parseCookieHeader(credentials.read(id()))

    override fun beginLogin(): OpenDriveLoginChallenge {
        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val response = transport.postForm(
            "$QR_TOKEN_API?__dt=${ThreadLocalRandom.current().nextInt(10_000, 100_000)}&__t=$now",
            mapOf("client_id" to CLIENT_ID, "v" to VERSION, "request_id" to requestId),
            headers(),
        )
        if (!response.body.stringValue("message").equals("ok", true)) {
            throw IOException("UC did not create a QR login session")
        }
        val token = response.body.objectValue("data").objectValue("members").stringValue("token")
        if (!validValue(token)) throw IOException("UC returned an invalid QR login session")
        val sessionId = UUID.randomUUID().toString()
        val expiresAt = now + TimeUnit.MINUTES.toMillis(10)
        sessions.clear()
        sessions[sessionId] = Session(token, UUID.randomUUID().toString(), expiresAt,
            LinkedHashMap(cookiePairs(response.setCookies)))
        val content = "https://su.uc.cn/1_n0ZCv" +
            "?uc_param_str=dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt" +
            "&token=$token&client_id=$CLIENT_ID&uc_biz_str=S%3Acustom%7CC%3Atitlebar_fix"
        return OpenDriveLoginChallenge(sessionId, content, expiresAt)
    }

    override fun pollLogin(sessionId: String): OpenDriveLoginState {
        val session = sessions[sessionId] ?: return OpenDriveLoginState.EXPIRED
        val now = System.currentTimeMillis()
        if (now >= session.expiresAt) {
            sessions.remove(sessionId)
            return OpenDriveLoginState.EXPIRED
        }
        val response = transport.get(
            QR_POLL_API,
            mapOf("__dt" to ThreadLocalRandom.current().nextInt(1_000, 100_000).toString(), "__t" to now.toString(),
                "token" to session.token, "client_id" to CLIENT_ID, "v" to VERSION,
                "request_id" to session.requestId),
            headers(session.cookies),
        )
        session.cookies.putAll(cookiePairs(response.setCookies))
        if (response.body.longValue("status") != 2_000_000L) return OpenDriveLoginState.WAITING
        val ticket = response.body.objectValue("data").objectValue("members").stringValue("service_ticket")
        if (!validValue(ticket)) return OpenDriveLoginState.FAILED
        val account = transport.get(
            "https://drive.uc.cn/account/info",
            mapOf("st" to ticket),
            headers(session.cookies),
        )
        session.cookies.putAll(cookiePairs(account.setCookies))
        if (!account.body.booleanValue("success") || "__pus" !in session.cookies) {
            return OpenDriveLoginState.FAILED
        }
        credentials.write(id(), session.cookies.cookieHeader())
        sessions.remove(sessionId)
        return OpenDriveLoginState.CONFIRMED
    }

    override fun verifyAuthentication(): Boolean {
        val cookies = parseCookieHeader(credentials.read(id()))
        if ("__pus" !in cookies) return false
        val response = transport.get(
            "$API/file/sort?$API_QUERY",
            mapOf("pdir_fid" to "0", "_page" to "1", "_size" to "1",
                "_sort" to "file_type:asc,updated_at:desc"),
            headers(cookies),
        )
        cookies.putAll(cookiePairs(response.setCookies))
        if ("__pus" in cookies) credentials.write(id(), cookies.cookieHeader())
        return response.body.objectValue("data").size() > 0
    }

    override fun logout() {
        sessions.clear()
        credentials.remove(id())
    }

    private fun headers(cookies: Map<String, String> = emptyMap()) = buildMap {
        put("Referer", "https://drive.uc.cn/")
        put("User-Agent", USER_AGENT)
        if (cookies.isNotEmpty()) put("Cookie", cookies.cookieHeader())
    }

    private fun validValue(value: String) = value.length in 8..512 && value.matches(Regex("[A-Za-z0-9_-]+"))

    private companion object {
        const val API = "https://pc-api.uc.cn/1/clouddrive"
        const val QR_TOKEN_API = "https://api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin"
        const val QR_POLL_API = "https://api.open.uc.cn/cas/ajax/getServiceTicketByQrcodeToken"
        const val CLIENT_ID = "381"
        const val VERSION = "1.2"
        const val API_QUERY = "pr=UCBrowser&fr=pc&sys=darwin&ve=1.8.6&ut=Nk27FcCv6q1eo6rXz8QHR/nIG6qLA3jh7KdL+agFgcOvww=="
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 8.0.0; SM-G955U Build/R16NW) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    }
}
