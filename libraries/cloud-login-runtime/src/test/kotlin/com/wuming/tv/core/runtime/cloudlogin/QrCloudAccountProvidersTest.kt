package com.wuming.tv.core.runtime.cloudlogin

import com.wuming.tv.core.cloud.OpenDriveLoginState
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class QrCloudAccountProvidersTest {

    @Test
    fun `Baidu QR confirmation persists account cookies and verifies a root read`() {
        val store = MemoryCredentialStore()
        val transport = object : CloudLoginTransport {
            override fun get(url: String, query: Map<String, String>, headers: Map<String, String>): CloudLoginResponse =
                when {
                    url.endsWith("/v2/api/getqrcode") -> CloudLoginResponse(
                        json("""{"errno":0,"sign":"baidu-qr-sign"}"""),
                    )
                    url.endsWith("/channel/unicast") -> CloudLoginResponse(
                        json("""{"errno":0,"channel_v":"{\"status\":0,\"v\":\"baidu-confirm-value\"}"}"""),
                    )
                    url.endsWith("/v3/login/main/qrbdusslogin") ->
                        error("Baidu confirmation must use the raw response path")
                    url.endsWith("/api/list") -> CloudLoginResponse(json("""{"errno":0,"list":[]}"""))
                    else -> error("Unexpected Baidu URL: $url")
                }

            override fun getText(
                url: String,
                query: Map<String, String>,
                headers: Map<String, String>,
            ) = if (url.endsWith("/v3/login/main/qrbdusslogin")) {
                CloudLoginTextResponse(
                    """bd__cbs__opendrive({"errInfo":{"no":"0"},"data":{"session":{"stokenList":"broken\qescape"}}})""",
                    listOf("BDUSS=baidu-account-session; Domain=.baidu.com; Path=/; Secure; HttpOnly"),
                )
            } else {
                super<CloudLoginTransport>.getText(url, query, headers)
            }

            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
                error("Baidu QR login must not post form credentials")

            override fun postJson(url: String, json: String, headers: Map<String, String>) =
                error("Baidu QR login must not post JSON credentials")
        }
        val provider = BaiduCloudAccountProvider(store, transport)

        assertFalse(provider.authenticated())
        val challenge = provider.beginLogin()
        assertTrue(challenge.content.startsWith("https://wappass.baidu.com/"))
        assertEquals(OpenDriveLoginState.CONFIRMED, provider.pollLogin(challenge.id))
        assertTrue(provider.authenticated())
        assertTrue(provider.verifyAuthentication())
        provider.logout()
        assertFalse(provider.authenticated())
    }

    @Test
    fun `Ali QR confirmation persists a verified token`() {
        val store = MemoryCredentialStore()
        val refresh = "refresh-token-that-is-long-enough"
        val bizExt = Base64.getEncoder().encodeToString(
            """{"pds_login_result":{"refreshToken":"$refresh"}}""".toByteArray(StandardCharsets.UTF_8),
        )
        val transport = object : CloudLoginTransport {
            override fun get(url: String, query: Map<String, String>, headers: Map<String, String>) =
                CloudLoginResponse(json("""{"content":{"data":{"t":"12345678","ck":"abcdefgh","codeContent":"https://passport.aliyundrive.com/qr"}}}"""))

            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
                CloudLoginResponse(json("""{"content":{"data":{"qrCodeStatus":"CONFIRMED","bizExt":"$bizExt"}}}"""))

            override fun postJson(url: String, json: String, headers: Map<String, String>) =
                CloudLoginResponse(json("""{"token_type":"Bearer","access_token":"access-token-that-is-long-enough","refresh_token":"$refresh","default_drive_id":"drive"}"""))
        }
        val provider = AliPanCloudAccountProvider(store, transport)

        assertFalse(provider.authenticated())
        val challenge = provider.beginLogin()
        assertEquals(OpenDriveLoginState.CONFIRMED, provider.pollLogin(challenge.id))
        assertTrue(provider.authenticated())
        provider.logout()
        assertFalse(provider.authenticated())
    }

    @Test
    fun `stored Quark and UC account cookies are recognized locally`() {
        val store = MemoryCredentialStore().apply {
            write("quark", "__pus=quark-session")
            write("uc", "__pus=uc-session")
        }

        assertTrue(QuarkCloudAccountProvider(store).authenticated())
        assertTrue(UcCloudAccountProvider(store).authenticated())
    }

    @Test
    fun `Bilibili QR confirmation persists and verifies its account session`() {
        val store = MemoryCredentialStore()
        var pollCount = 0
        val transport = object : CloudLoginTransport {
            override fun get(url: String, query: Map<String, String>, headers: Map<String, String>) = when {
                url.endsWith("/qrcode/generate") -> CloudLoginResponse(json(
                    """{"code":0,"data":{"qrcode_key":"bilibili-qr-key","url":"https://passport.bilibili.com/qr"}}""",
                ))
                url.endsWith("/qrcode/poll") -> if (pollCount++ == 0) {
                    CloudLoginResponse(json("""{"code":0,"data":{"code":86090}}"""))
                } else {
                    CloudLoginResponse(
                        json("""{"code":0,"data":{"code":0}}"""),
                        listOf("SESSDATA=bilibili-session; Domain=.bilibili.com; Path=/; Secure; HttpOnly"),
                    )
                }
                url.endsWith("/x/web-interface/nav") -> CloudLoginResponse(
                    json("""{"code":0,"data":{"isLogin":true}}"""),
                )
                else -> error("Unexpected Bilibili URL: $url")
            }

            override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>) =
                error("Bilibili QR login must not post form credentials")

            override fun postJson(url: String, json: String, headers: Map<String, String>) =
                error("Bilibili QR login must not post JSON credentials")
        }
        val provider = BilibiliAccountProvider(store, transport)

        assertFalse(provider.authenticated())
        val challenge = provider.beginLogin()
        assertEquals(OpenDriveLoginState.SCANNED, provider.pollLogin(challenge.id))
        assertEquals(OpenDriveLoginState.CONFIRMED, provider.pollLogin(challenge.id))
        assertTrue(provider.authenticated())
        assertTrue(provider.verifyAuthentication())
        provider.logout()
        assertFalse(provider.authenticated())
    }

    @Test
    fun `desktop provider list includes cloud drives and Bilibili`() {
        assertEquals(
            listOf("alipan", "baidu", "quark", "uc", "bilibili"),
            desktopQrCloudProviders(MemoryCredentialStore()).map { it.id() },
        )
    }

    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    private class MemoryCredentialStore : CloudCredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(providerId: String) = values[providerId].orEmpty()
        override fun write(providerId: String, credential: String) { values[providerId] = credential }
        override fun remove(providerId: String) { values.remove(providerId) }
    }
}
