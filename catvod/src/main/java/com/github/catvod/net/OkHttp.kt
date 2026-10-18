package com.github.catvod.net

import com.github.catvod.net.tls.OkHttpTlsManager
import com.github.catvod.net.url.OkHttpUrlBuilder

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.collection.ArrayMap
import com.github.catvod.net.interceptor.AuthInterceptor
import com.github.catvod.net.interceptor.RequestInterceptor
import com.github.catvod.net.interceptor.ResponseInterceptor
import com.github.catvod.net.interceptor.RedirectConnectionInterceptor
import com.github.catvod.utils.Path
import com.github.catvod.utils.Prefers
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Arrays
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.Volatile
import kotlin.math.max
import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor

open class OkHttp {
    private val timeoutClients = ConcurrentHashMap<String?, OkHttpClient>()

    @Volatile
    private var responseInterceptor: ResponseInterceptor? = null

    @Volatile
    private var requestInterceptor: RequestInterceptor? = null

    @Volatile
    private var authInterceptor: AuthInterceptor? = null

    @Volatile
    private var authenticator: OkAuthenticator? = null

    @Volatile
    private var selector: OkProxySelector? = null

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var player: OkHttpClient? = null

    @Volatile
    private var explicitCookiePlayer: OkHttpClient? = null

    @Volatile
    private var dns: OkDns? = null

    open fun clear() {
        OkHttpUrlBuilder.clearCache()
        resetClients()
        if (responseInterceptor != null) responseInterceptor!!.clear()
        if (requestInterceptor != null) requestInterceptor!!.clear()
        if (authInterceptor != null) authInterceptor!!.clear()
    }

    @Synchronized
    private fun resetClients() {
        timeoutClients.clear()
        client = null
        player = null
        explicitCookiePlayer = null
    }

    private object Loader {
        @Volatile
        var INSTANCE: OkHttp = OkHttp()
    }

    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic open fun noRedirect(): OkHttpClient = noRedirect(TIMEOUT)

        @JvmStatic open fun newCall(url: String?, headers: Map<String, String>, body: RequestBody): Call =
            newCall(url, headers, body, null)

        @JvmStatic open fun cancelAll() {
            cancelAll(client())
        }

        private const val LEGACY_TLS = "legacy_tls"
        private val TIMEOUT = TimeUnit.SECONDS.toMillis(15)
        private val CONNECT_TIMEOUT = TimeUnit.SECONDS.toMillis(10)


        // Hardware-relative connection pool and cache settings
        private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
        private val MAX_MEMORY_MB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        private val CONNECTION_POOL_MAX: Int = scale(32, 64, 128)
        private const val CONNECTION_POOL_KEEP_ALIVE_MINUTES = 15
        private val MAX_REQUESTS_PER_HOST = max(8, CPU_COUNT * 4)
        private val MAX_IDLE_CONNECTIONS = max(8, CPU_COUNT * 4)
        private val CACHE_MAX_SIZE: Long = scale(32, 64, 128) * 1024L * 1024L
        private val MAX_TIMEOUT_CLIENTS: Int = scale(6, 12, 24)

        private fun scale(low: Int, mid: Int, high: Int): Int {
            if (MAX_MEMORY_MB >= 2048) return high
            if (MAX_MEMORY_MB >= 1024) return mid
            return low
        }

        @JvmStatic
        open fun get(): OkHttp = Loader.INSTANCE

        /**
         * Applies the TLS policy to this process without writing SharedPreferences.
         *
         *
         * The app's provider service runs in a separate process, where Android's
         * SharedPreferences cache is not guaranteed to notice changes immediately.
         * The main process sends the selected value over IPC and calls this method
         * there so newly created clients use the same policy in both processes.
         */
        @JvmStatic
        open fun applyLegacyTlsPolicy(enabled: Boolean) {
            OkHttpTlsManager.setLegacyTlsOverride(enabled)
            get().resetClients()
        }

        @JvmStatic open fun isLegacyTlsEnabled(): Boolean = legacyTlsPolicy

        @JvmStatic open fun setLegacyTlsEnabled(enabled: Boolean) {
            legacyTlsPolicy = enabled
        }
        private var legacyTlsPolicy: Boolean
            get() = OkHttpTlsManager.isLegacyTlsEnabled()
            /**
             * Enables compatibility with providers whose HTTPS endpoints use invalid,
             * expired, self-signed, or hostname-mismatched certificates.
             *
             *
             * Changing the policy rebuilds all shared clients so no connection pool
             * created under the previous policy can be reused.
             */
            set(enabled) {
                OkHttpTlsManager.persistLegacyTls(enabled)
                applyLegacyTlsPolicy(enabled)
            }

        @JvmStatic
        open fun dns(): OkDns {
            if (get().dns != null) return get().dns!!
            synchronized(OkHttp::class.java) {
                if (get().dns != null) return get().dns!!
                return OkDns().also { get().dns = it }
            }
        }

        @JvmStatic
        open fun responseInterceptor(): ResponseInterceptor {
            if (get().responseInterceptor != null) return get().responseInterceptor!!
            synchronized(OkHttp::class.java) {
                if (get().responseInterceptor != null) return get().responseInterceptor!!
                return ResponseInterceptor().also { get().responseInterceptor = it }
            }
        }

        @JvmStatic
        open fun requestInterceptor(): RequestInterceptor {
            if (get().requestInterceptor != null) return get().requestInterceptor!!
            synchronized(OkHttp::class.java) {
                if (get().requestInterceptor != null) return get().requestInterceptor!!
                return RequestInterceptor().also { get().requestInterceptor = it }
            }
        }

        @JvmStatic
        open fun authInterceptor(): AuthInterceptor {
            if (get().authInterceptor != null) return get().authInterceptor!!
            synchronized(OkHttp::class.java) {
                if (get().authInterceptor != null) return get().authInterceptor!!
                return AuthInterceptor().also { get().authInterceptor = it }
            }
        }

        @JvmStatic
        open fun authenticator(): OkAuthenticator {
            if (get().authenticator != null) return get().authenticator!!
            synchronized(OkHttp::class.java) {
                if (get().authenticator != null) return get().authenticator!!
                return OkAuthenticator().also { get().authenticator = it }
            }
        }

        @JvmStatic
        open fun selector(): OkProxySelector {
            if (get().selector != null) return get().selector!!
            synchronized(OkHttp::class.java) {
                if (get().selector != null) return get().selector!!
                return OkProxySelector().also { get().selector = it }
            }
        }

        private fun client(): OkHttpClient {
            if (get().client != null) return get().client!!
            synchronized(OkHttp::class.java) {
                if (get().client != null) return get().client!!
                return builder.build().also { get().client = it }
            }
        }

        @JvmStatic
        open fun player(): OkHttpClient {
            if (get().player != null) return get().player!!
            synchronized(OkHttp::class.java) {
                if (get().player != null) return get().player!!
                // Player client: no HTTP cache (causes SocketTimeoutException with streaming),
                // longer read timeout for continuous streaming, no call timeout
                // Proxy is handled by OkProxySelector which only proxies github.com URLs
                return builder
                    .cache(null)
                    .connectTimeout(CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .build().also { get().player = it }
            }
        }

        /**
         * Player transport for reviewed providers that supply one explicit Cookie header.
         * The normal shared CookieJar would replace that header during OkHttp's bridge step.
         */
        @JvmStatic
        open fun explicitCookiePlayer(): OkHttpClient {
            if (get().explicitCookiePlayer != null) return get().explicitCookiePlayer!!
            synchronized(OkHttp::class.java) {
                if (get().explicitCookiePlayer != null) return get().explicitCookiePlayer!!
                return player().newBuilder()
                    .cookieJar(CookieJar.NO_COOKIES)
                    .build().also { get().explicitCookiePlayer = it }
            }
        }

        @JvmStatic
        open fun client(timeout: Long): OkHttpClient {
            val key: String = "t_" + timeout
            val tc: ConcurrentHashMap<String?, OkHttpClient> = get().timeoutClients
            if (tc.size >= MAX_TIMEOUT_CLIENTS) tc.clear()
            return tc.computeIfAbsent(key) { k: String? ->
                client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS).build()
            }
        }

        @JvmStatic
        open fun noRedirect(timeout: Long): OkHttpClient {
            val key: String = "nr_" + timeout
            val tc: ConcurrentHashMap<String?, OkHttpClient> = get().timeoutClients
            if (tc.size >= MAX_TIMEOUT_CLIENTS) tc.clear()
            return tc.computeIfAbsent(key) { k: String? ->
                client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false)
                    .followSslRedirects(false).build()
            }
        }

        @JvmStatic
        open fun client(redirect: Boolean, timeout: Long): OkHttpClient =
            if (redirect) client(timeout) else noRedirect(timeout)

        @JvmStatic
        open fun string(url: String): String {
            if (!url.startsWith("http")) return ""
            try {
                newCall(url).execute().use { res ->
                    val body: ResponseBody = res.body
                    return body.string()
                }
            } catch (e: Exception) {
                Log.e("OkHttp", "Request failed", e)
                return ""
            }
        }

        @JvmStatic
        open fun string(url: String, headers: Map<String, String>): String {
            if (!url.startsWith("http")) return ""
            try {
                newCall(url, headers).execute().use { res ->
                    val body: ResponseBody = res.body
                    return body.string()
                }
            } catch (e: Exception) {
                Log.e("OkHttp", "Request failed", e)
                return ""
            }
        }

        @JvmStatic
        open fun newCall(url: String?): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client().newCall(Request.Builder().url(httpUrl).build())
        }

        @JvmStatic
        open fun newCall(url: String?, tag: String?): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client().newCall(Request.Builder().url(httpUrl).tag(tag).build())
        }

        @JvmStatic
        open fun newCall(client: OkHttpClient, url: String?): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client.newCall(Request.Builder().url(httpUrl).build())
        }

        @JvmStatic
        open fun newCall(client: OkHttpClient, url: String?, tag: String?): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client.newCall(Request.Builder().url(httpUrl).tag(tag).build())
        }

        @JvmStatic
        open fun newCall(url: String?, headers: Map<String, String>): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client().newCall(
                Request.Builder().url(httpUrl).headers(headers.toHeaders()).build()
            )
        }

        /** GET with both request headers and a cancel tag (used by credential-aware callers).  */
        @JvmStatic
        open fun newCall(url: String?, headers: Map<String, String>, tag: String?): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client().newCall(
                Request.Builder().url(httpUrl).headers(headers.toHeaders()).tag(tag)
                    .build()
            )
        }

        @JvmStatic
        open fun newCall(
            url: String?,
            headers: Map<String, String>,
            params: ArrayMap<String, String>
        ): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client().newCall(
                Request.Builder().url(OkHttpUrlBuilder.buildUrl(httpUrl, params))
                    .headers(headers.toHeaders()).build()
            )
        }

        @JvmStatic
        open fun newCall(
            url: String?,
            headers: Map<String, String>,
            body: RequestBody,
            tag: String?
        ): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client().newCall(
                Request.Builder().url(httpUrl).headers(headers.toHeaders()).tag(tag)
                    .post(body).build()
            )
        }

        @JvmStatic
        open fun newCall(client: OkHttpClient, url: String?, body: RequestBody): Call {
            val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
            requireNotNull(httpUrl) { "Invalid URL: " + url }
            return client.newCall(Request.Builder().url(httpUrl).post(body).build())
        }

        @JvmStatic
        open fun cancel(tag: String) {
            Companion.cancel(client(), tag)
        }

        @JvmStatic
        open fun cancel(client: OkHttpClient, tag: String) {
            for (call: Call in client.dispatcher.queuedCalls()) {
                if (tag == call.request()
                        .tag()
                ) {
                    call.cancel()
                }
            }
            for (call: Call in client.dispatcher.runningCalls()) {
                if (tag == call.request()
                        .tag()
                ) {
                    call.cancel()
                }
            }
        }

        @JvmStatic
        open fun cancelAll(client: OkHttpClient) {
            client.dispatcher.cancelAll()
        }

        @JvmStatic
        open fun toBody(params: ArrayMap<String, String>): FormBody {
            val body: FormBody.Builder = FormBody.Builder()
            for (entry: Map.Entry<String, String> in params.entries) {
                body.add(
                    entry.key,
                    entry.value
                )
            }
            return body.build()
        }

        private fun createDispatcher(): Dispatcher {
            val dispatcher: Dispatcher = Dispatcher()
            dispatcher.maxRequestsPerHost = MAX_REQUESTS_PER_HOST
            dispatcher.maxRequests = CONNECTION_POOL_MAX * 2
            return dispatcher
        }

        private val cookieJar: CookieJar?
            get() {
                try {
                    return PersistentCookieJar.get()
                } catch (e: IllegalStateException) {
                    return CookieJar.NO_COOKIES
                }
            }

        /**
         * Returns the shared [CookieJar] (PersistentCookieJar) used by all app OkHttp clients.
         * Spiders that create their own OkHttpClient should use this to ensure cookies are shared.
         */
        @JvmStatic
        open fun cookieJar(): CookieJar = cookieJar!!

        /**
         * Returns a new [OkHttpClient.Builder] pre-configured with the shared CookieJar,
         * interceptors, DNS, SSL, and proxy settings. Spiders that need a customized client
         * (e.g., different timeouts) should use this instead of `new OkHttpClient.Builder()`
         * to ensure cookies from the PersistentCookieJar are included in requests.
         *
         * Example usage in a spider:
         * `OkHttpClient myClient = OkHttp.newClientBuilder().readTimeout(60, TimeUnit.SECONDS).build();`
         */
        @JvmStatic
        open fun newClientBuilder(): OkHttpClient.Builder = client().newBuilder()

        /**
         * Returns the Cookie header value for the given URL, read from the shared PersistentCookieJar.
         * This is useful for spiders that use their own HTTP client (not OkHttp) and need to
         * manually include cookies in request headers.
         *
         * @param url the request URL
         * @return the Cookie header value (e.g., "__pus=xxx; __puus=yyy"), or empty string if none
         */
        @JvmStatic
        open fun cookieHeader(url: String?): String {
            try {
                val httpUrl: HttpUrl? = OkHttpUrlBuilder.buildHttpUrl(url)
                if (httpUrl == null) return ""
                val cookies: List<Cookie> = cookieJar!!.loadForRequest(httpUrl)
                if (cookies.isEmpty()) return ""
                val sb: StringBuilder = StringBuilder()
                for (i: Int in cookies.indices) {
                    if (i > 0) sb.append("; ")
                    sb.append(cookies[i].name).append("=").append(cookies[i].value)
                }
                return sb.toString()
            } catch (e: Exception) {
                return ""
            }
        }

        private val builder: OkHttpClient.Builder
            get() {
                val builder: OkHttpClient.Builder = OkHttpClient.Builder()
                    .connectionPool(
                        ConnectionPool(
                            MAX_IDLE_CONNECTIONS,
                            CONNECTION_POOL_KEEP_ALIVE_MINUTES.toLong(),
                            TimeUnit.MINUTES
                        )
                    )
                    .addInterceptor(requestInterceptor())
                    .addInterceptor(authInterceptor())
                    .addNetworkInterceptor(responseInterceptor())
                    .addNetworkInterceptor(RedirectConnectionInterceptor())
                    .connectTimeout(
                        CONNECT_TIMEOUT,
                        TimeUnit.MILLISECONDS
                    )
                    .readTimeout(
                        TIMEOUT,
                        TimeUnit.MILLISECONDS
                    )
                    .writeTimeout(
                        TIMEOUT,
                        TimeUnit.MILLISECONDS
                    )
                    .dns(dns())
                    .protocols(
                        Arrays.asList<Protocol>(
                            Protocol.HTTP_2,
                            Protocol.HTTP_1_1
                        )
                    )
                    .retryOnConnectionFailure(true)
                    .cookieJar(cookieJar!!)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .pingInterval(
                        30,
                        TimeUnit.SECONDS
                    ) // HTTP/2 ping for connection health
                    .callTimeout(60, TimeUnit.SECONDS) // Overall call timeout
                    .dispatcher(createDispatcher())
                if (legacyTlsPolicy) {
                    val sslSocketFactory: SSLSocketFactory? =
                        OkHttpTlsManager.getCachedSslSocketFactory()
                    val trustManager: X509TrustManager? =
                        OkHttpTlsManager.getCachedTrustManager()
                    builder.hostnameVerifier(
                        HostnameVerifier {
                                hostname: String?,
                                session: SSLSession?
                            ->
                            true
                        }
                    )
                    if (sslSocketFactory != null && trustManager != null) {
                        builder.sslSocketFactory(sslSocketFactory, trustManager)
                    } else {
                        val context: SSLContext? = OkHttpTlsManager.getSSLContext()
                        if (context != null) {
                            builder.sslSocketFactory(
                                context.getSocketFactory(),
                                OkHttpTlsManager.trustAllCertificates()
                            )
                        }
                    }
                }
                val logging: HttpLoggingInterceptor =
                    HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY)
                builder.proxyAuthenticator(authenticator())
                // builder.addNetworkInterceptor(logging);
                builder.proxySelector(selector())
                // Configure HTTP cache for reduced network load
                val cacheDir: File =
                    File(Path.cache(), "http")
                builder.cache(
                    Cache(
                        cacheDir,
                        CACHE_MAX_SIZE
                    )
                )
                return builder
            }

    }
}
