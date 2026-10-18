package com.github.catvod.net.interceptor

import com.github.catvod.utils.Auth
import com.google.common.net.HttpHeaders
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

class AuthInterceptor : Interceptor {

    private val userMap = ConcurrentHashMap<String, String>()

    fun clear() {
        userMap.clear()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = check(chain.request())
        val response = chain.proceed(request)
        if (response.code != 401) return response
        val host = request.url.host
        var user: String? = request.url.toUri().userInfo
        if (user == null) user = userMap[host]
        if (user == null) return response
        response.close()
        val header = response.header(HttpHeaders.WWW_AUTHENTICATE)
        val auth = if (digest(header)) Auth.digest(user, header!!, request) else Auth.basic(user)
        return chain.proceed(request.newBuilder().header(HttpHeaders.AUTHORIZATION, auth).build())
    }

    private fun digest(header: String?): Boolean = header != null && header.startsWith("Digest")

    private fun check(request: Request): Request {
        val uri = request.url.toUri()
        if (uri.userInfo == null) return request
        userMap[request.url.host] = uri.userInfo
        return request.newBuilder().header(HttpHeaders.AUTHORIZATION, Auth.basic(uri.userInfo)).build()
    }
}
