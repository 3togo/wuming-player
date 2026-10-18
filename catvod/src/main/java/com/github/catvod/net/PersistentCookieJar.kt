package com.github.catvod.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import java.util.ArrayList
import java.util.Locale
import kotlin.concurrent.Volatile
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A persistent [CookieJar] backed by SharedPreferences.
 * Stores cookies across app restarts and provides bidirectional sync
 * with Android's WebView CookieManager via
 * [.syncFromWebView] and [.syncToWebView].
 *
 * Key design: cookies synced from WebView are stored under the TOP-LEVEL domain
 * (e.g., uc.cn, not drive.uc.cn) so that sibling subdomains like pc-api.uc.cn
 * can also access them via subdomain matching.
 */
open class PersistentCookieJar private constructor(context: Context) : CookieJar {
    private val prefs: SharedPreferences

    init {
        prefs =
            context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val editor: SharedPreferences.Editor = prefs.edit()
        for (cookie: Cookie in cookies) {
            val storageDomain: String = getStorageDomain(cookie.domain)
            editor.putString(cookieKey(storageDomain, cookie.name), encodeCookie(cookie))
        }
        editor.apply()
    }

    override fun loadForRequest(url: HttpUrl): MutableList<Cookie> {
        val cookies: MutableList<Cookie> = ArrayList<Cookie>()
        val now: Long = System.currentTimeMillis()
        val expired: MutableList<String?> = ArrayList<String?>()
        val host: String = url.host.lowercase(java.util.Locale.US)

        for (key: String in prefs.getAll().keys) {
            val pipe: Int = key.indexOf('|')
            if (pipe < 0) continue
            val cookieHost: String = key.substring(0, pipe)

            // Subdomain matching: exact match or request host is a subdomain of cookie domain
            if (host != cookieHost && !host.endsWith("." + cookieHost)) continue

            val cookie: Cookie? = decodeCookie(prefs.getString(key, ""))
            if (cookie == null) continue
            if (cookie.expiresAt < now) {
                expired.add(key)
                continue
            }
            if (cookie.matches(url)) {
                cookies.add(cookie)
            }
        }

        if (!expired.isEmpty()) {
            val editor: SharedPreferences.Editor = prefs.edit()
            for (key: String? in expired) editor.remove(key)
            editor.apply()
        }
        return cookies
    }

    /**
     * Returns every unexpired cookie captured for one cloud provider's storage domain.
     *
     *
     * This intentionally does not apply normal request-host matching. A small number of
     * reviewed provider protocols (currently Baidu QR login) treat all `Set-Cookie`
     * values returned by a sibling login host as one opaque account token and replay that
     * token to the drive host. Callers must scope this to the provider and must not use it as
     * a general replacement for [.loadForRequest].
     */
    open fun loadForStorageDomain(domain: String?): MutableList<Cookie> {
        if (domain == null ||
            domain.codePoints().allMatch { Character.isWhitespace(it) }
        ) {
            return java.util.Collections.emptyList<Cookie>()
        }
        val storageDomain: String = getStorageDomain(domain)
        val prefix: String = storageDomain + "|"
        val now: Long = System.currentTimeMillis()
        val cookies: MutableList<Cookie> = ArrayList<Cookie>()
        val expired: MutableList<String?> = ArrayList<String?>()
        for (key: String in prefs.getAll().keys) {
            if (!key.startsWith(prefix)) continue
            val cookie: Cookie? = decodeCookie(prefs.getString(key, ""))
            if (cookie == null) continue
            if (cookie.expiresAt < now) {
                expired.add(key)
                continue
            }
            cookies.add(cookie)
        }
        if (!expired.isEmpty()) {
            val editor: SharedPreferences.Editor = prefs.edit()
            for (key: String? in expired) editor.remove(key)
            editor.apply()
        }
        return java.util.List.copyOf<Cookie>(cookies)
    }

    /**
     * Sync cookies from Android's WebView CookieManager into this jar.
     * Call this after a user logs in via a WebView so that subsequent
     * OkHttp requests (e.g., from spiders) include the session cookies.
     *
     * Cookies are stored under the top-level domain so that sibling
     * subdomains (e.g., pc-api.uc.cn) can access cookies set on
     * drive.uc.cn or broccoli.uc.cn.
     */
    open fun syncFromWebView(context: Context?, url: String) {
        try {
            val cm: CookieManager = CookieManager.getInstance()

            val urlsToSync: MutableSet<String> = LinkedHashSet<String>()
            urlsToSync.add(url)

            val httpUrl: HttpUrl? = url.toHttpUrlOrNull()
            if (httpUrl != null) {
                val host: String = httpUrl.host
                urlsToSync.add(httpUrl.scheme + "://" + host + "/")
                // For UC, also check broccoli.uc.cn (the app shell domain)
                if (host.contains("uc.cn")) {
                    urlsToSync.add("https://broccoli.uc.cn/")
                }
            }

            val editor: SharedPreferences.Editor = prefs.edit()
            var count: Int = 0
            for (syncUrl: String in urlsToSync) {
                val cookieString: String? = cm.getCookie(syncUrl)
                if (cookieString == null || cookieString.isEmpty()) continue

                val syncHttpUrl: HttpUrl? = syncUrl.toHttpUrlOrNull()
                if (syncHttpUrl == null) continue

                // Store under top-level domain so sibling subdomains can access
                val storageDomain: String = getStorageDomain(syncHttpUrl.host)

                for (cookie in parseCookieHeader(cookieString, storageDomain)) {
                    editor.putString(cookieKey(storageDomain, cookie.name), encodeCookie(cookie))
                    count++
                }
            }
            editor.apply()
            Log.d(
                "PersistentCookieJar",
                "syncFromWebView: synced " + count + " cookies from " + urlsToSync
            )
        } catch (e: Exception) {
            Log.e("PersistentCookieJar", "syncFromWebView failed", e)
        }
    }

    /**
     * Sync cookies from this jar into Android's WebView CookieManager.
     * Call this before loading a URL in a WebView so that the WebView
     * has the same session as OkHttp.
     */
    open fun syncToWebView(url: String) {
        try {
            val httpUrl: HttpUrl? = url.toHttpUrlOrNull()
            if (httpUrl == null) return

            val cookies: MutableList<Cookie> = loadForRequest(httpUrl)
            if (cookies.isEmpty()) return

            val cm: CookieManager = CookieManager.getInstance()
            for (cookie: Cookie in cookies) {
                // Preserve Domain/Path/Secure/HttpOnly attributes. Writing only
                // name=value creates a host-only WebView cookie, which prevents
                // authenticated pages such as pan.quark.cn from authorizing XHRs
                // to sibling API hosts such as drive-pc.quark.cn.
                cm.setCookie(url, cookie.toString())
            }
            cm.flush()
        } catch (e: Exception) {
            Log.e("PersistentCookieJar", "syncToWebView failed", e)
        }
    }

    /**
     * Check if there are login session cookies stored for the given URL.
     * Unlike checking for any cookie, this ignores tracking/analytics cookies
     * (like __sdid) that are set by API calls even without a user login.
     */
    open fun hasLoginCookies(url: String): Boolean {
        val httpUrl: HttpUrl? = url.toHttpUrlOrNull()
        if (httpUrl == null) return false

        val cookies: MutableList<Cookie> = loadForRequest(httpUrl)
        for (cookie: Cookie in cookies) {
            val name: String = cookie.name
            for (loginName: String? in LOGIN_COOKIE_NAMES) {
                if (name == loginName) return true
            }
        }
        return false
    }

    /** Check one provider-specific session cookie without accepting another
     * provider's generic cookie names as proof of authentication.  */
    open fun hasCookie(url: String, name: String?): Boolean {
        if (name == null || name.codePoints().allMatch { Character.isWhitespace(it) }) return false
        val httpUrl: HttpUrl? = url.toHttpUrlOrNull()
        if (httpUrl == null) return false
        for (cookie: Cookie in loadForRequest(httpUrl)) {
            if (name == cookie.name &&
                !cookie.value.codePoints().allMatch { Character.isWhitespace(it) }
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Remove all cookies for a given host.
     */
    open fun clear(host: String) {
        val prefix: String = host.lowercase(java.util.Locale.US) + "|"
        val editor: SharedPreferences.Editor = prefs.edit()
        for (key: String in prefs.getAll().keys) {
            if (key.startsWith(prefix)) editor.remove(key)
        }
        editor.apply()
    }

    /** Expire cookies visible to one WebView origin without clearing unrelated sites.  */
    open fun clearWebView(url: String) {
        try {
            val manager: CookieManager = CookieManager.getInstance()
            val values: String? = manager.getCookie(url)
            if (values == null ||
                values.codePoints().allMatch { Character.isWhitespace(it) }
            ) {
                return
            }
            val httpUrl: HttpUrl? = url.toHttpUrlOrNull()
            val storageDomain: String? =
                if (httpUrl == null) null else getStorageDomain(httpUrl.host)
            for (pair: String in values.split(";".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()) {
                val separator: Int = pair.indexOf('=')
                val name: String =
                    (if (separator < 0) pair else pair.substring(0, separator)).trim { it <= ' ' }
                if (name.isEmpty()) continue
                manager.setCookie(url, name + "=; Max-Age=0; Path=/")
                // Cloud sessions are commonly scoped to a parent domain (for
                // example .uc.cn), while the management page is hosted on a
                // subdomain. Expiring only a host cookie leaves the parent
                // session alive and can trap the provider SPA in a login loop.
                if (storageDomain != null && storageDomain != httpUrl!!.host) {
                    manager.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=." + storageDomain)
                    manager.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=" + storageDomain)
                }
            }
            manager.flush()
        } catch (e: Exception) {
            Log.e("PersistentCookieJar", "clearWebView failed", e)
        }
    }

    /** Snapshot only the explicitly requested domains for a password-encrypted backup.  */
    open fun exportCookies(domains: Set<String>): MutableMap<String, String> {
        val result: MutableMap<String, String> = LinkedHashMap<String, String>()
        for (domain: String in domains) {
            for (cookie: Cookie in loadForStorageDomain(domain)) {
                result.put(cookieKey(domain, cookie.name), encodeCookie(cookie))
            }
        }
        return result
    }

    /** Validate the entire snapshot before changing any saved cookies.  */
    open fun validateCookies(domains: Set<String>, values: Map<String, String>?) {
        requireNotNull(values) { "Missing cookies" }
        for (entry in values.entries) {
            val cookie: Cookie? = decodeCookie(entry.value)
            require(
                !(
                    cookie == null || !domains.contains(getStorageDomain(cookie.domain)) ||
                        (
                            cookieKey(
                                getStorageDomain(cookie.domain),
                                cookie.name
                            ) != entry.key
                            )
                    )
            ) { "Invalid backup cookie" }
        }
    }

    /** Replaces only the selected providers; unrelated website logins are untouched.  */
    open fun replaceCookies(domains: Set<String>, values: Map<String, String>?): Boolean {
        validateCookies(domains, values)
        val validatedValues = requireNotNull(values) { "Missing cookies" }
        val editor: SharedPreferences.Editor = prefs.edit()
        for (key: String in prefs.getAll().keys) {
            val pipe: Int = key.indexOf('|')
            if (pipe > 0 && domains.contains(key.substring(0, pipe))) editor.remove(key)
        }
        for (entry in validatedValues.entries) {
            editor.putString(entry.key, entry.value)
        }
        return editor.commit()
    }

    /**
     * Remove all cookies.
     */
    open fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * Get the top-level domain for cookie storage.
     * For cloud drive providers, returns the top-level domain so that
     * sibling subdomains can access the cookies:
     * - drive.uc.cn -> uc.cn (so pc-api.uc.cn can also get the cookies)
     * - broccoli.uc.cn -> uc.cn
     * - drive-pc.quark.cn -> quark.cn
     * - pan.baidu.com -> baidu.com
     */
    private fun getStorageDomain(host: String): String {
        val lower: String = host.lowercase(java.util.Locale.US)
        for (domain: String in CLOUD_DOMAINS) {
            if (lower == domain || lower.endsWith("." + domain)) {
                return domain
            }
        }
        return lower
    }

    private fun cookieKey(host: String, name: String?): String =
        host.lowercase(java.util.Locale.US) + "|" + name

    private fun encodeCookie(cookie: Cookie): String = (
        cookie.name + "=" + cookie.value +
            ";domain=" + cookie.domain +
            ";path=" + cookie.path +
            ";expiresAt=" + cookie.expiresAt +
            ";secure=" + cookie.secure +
            ";httpOnly=" + cookie.httpOnly +
            ";hostOnly=" + cookie.hostOnly
        )

    private fun decodeCookie(encoded: String?): Cookie? {
        if (encoded == null || encoded.isEmpty()) return null
        try {
            val parts: Array<String> =
                encoded.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val nameValue: Array<String> = parts[0].split("=".toRegex(), limit = 2).toTypedArray()
            if (nameValue.size != 2) return null

            val name: String = nameValue[0]
            val value: String = nameValue[1]
            var domain: String = ""
            var path: String = "/"
            var expiresAt: Long = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
            var secure: Boolean = false
            var httpOnly: Boolean = false
            var hostOnly: Boolean = false

            for (i: Int in 1..<parts.size) {
                val kv: Array<String> = parts[i].split("=".toRegex(), limit = 2).toTypedArray()
                if (kv.size != 2) continue
                when (kv[0].trim { it <= ' ' }) {
                    "domain" -> domain = kv[1].trim { it <= ' ' }
                    "path" -> path = kv[1].trim { it <= ' ' }
                    "expiresAt" -> expiresAt = kv[1].trim { it <= ' ' }.toLong()
                    "secure" -> secure = kv[1].trim { it <= ' ' }.toBoolean()
                    "hostOnly" -> hostOnly = kv[1].trim { it <= ' ' }.toBoolean()
                    "httpOnly" -> httpOnly = kv[1].trim { it <= ' ' }.toBoolean()
                }
            }

            val builder: Cookie.Builder = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path)
                .expiresAt(expiresAt)
            if (hostOnly) builder.hostOnlyDomain(domain)
            if (secure) builder.secure()
            if (httpOnly) builder.httpOnly()
            return builder.build()
        } catch (e: Exception) {
            Log.w("PersistentCookieJar", "cookie decode failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        fun parseCookieHeader(cookieHeader: String?, domain: String): List<Cookie> {
            val cookies = ArrayList<Cookie>()
            if (cookieHeader.isNullOrEmpty()) return cookies
            val expiresAt = System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L
            for (part in cookieHeader.split(';')) {
                val pair = part.trim { it <= ' ' }
                val separator = pair.indexOf('=')
                if (separator <= 0) continue
                val name = pair.substring(0, separator).trim { it <= ' ' }
                val value = pair.substring(separator + 1).trim { it <= ' ' }
                if (name.isEmpty() || value.isEmpty()) continue
                try {
                    cookies.add(Cookie.Builder().name(name).value(value)
                        .domain(domain).path("/").expiresAt(expiresAt).build())
                } catch (e: Exception) {
                    Log.w("PersistentCookieJar", "cookie parse failed for name=$name domain=$domain: ${e.message}")
                }
            }
            return cookies
        }

        private const val PREFS_NAME = "okhttp_cookies"

        @Volatile
        private var instance: PersistentCookieJar? = null

        /** Cloud drive top-level domains for cookie storage.  */
        private val CLOUD_DOMAINS = arrayOf<String>(
            "uc.cn", "quark.cn", "baidu.com", "alipan.com", "aliyundrive.com",
            "123pan.com", "123pan.cn", "123684.com", "115.com",
            "189.cn", "bilibili.com"
        )

        /** Known login session cookie names per provider.  */
        private val LOGIN_COOKIE_NAMES = arrayOf<String?>(
            "__pus", "__puus", "BDUSS", "token", "CID", "SSON", "COOKIE_LOGIN_USER",
            "SESSDATA", "bili_jct", "DedeUserID"
        )

        @JvmStatic
        open fun get(): PersistentCookieJar {
            if (instance != null) return instance!!
            throw IllegalStateException("PersistentCookieJar not initialized. Call create() first.")
        }

        @JvmStatic
        open fun create(context: Context): PersistentCookieJar {
            if (instance == null) {
                synchronized(PersistentCookieJar::class.java) {
                    if (instance == null) {
                        instance = PersistentCookieJar(context)
                    }
                }
            }
            return instance!!
        }
    }
}
