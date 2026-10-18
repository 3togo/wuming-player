package com.wuming.android.tv.utils

import android.content.Context
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.github.catvod.net.OkHttp
import com.wuming.tv.core.catalog.ThumbnailRequestBudget
import java.util.concurrent.TimeUnit
import java.io.InputStream

@GlideModule
open class OkGlideModule : AppGlideModule() {
    override fun applyOptions(
        context: Context,
        builder: GlideBuilder,
    ) {
        builder.setLogLevel(Log.ERROR)
        // Budget all image pools together, preserving room for playback buffers and surfaces.
        builder.setMemorySizeCalculator(
            MemorySizeCalculator
                .Builder(context)
                .setMaxSizeMultiplier(0.125f)
                .setLowMemoryMaxSizeMultiplier(0.0625f)
                .build(),
        )
        builder.setDefaultRequestOptions(
            RequestOptions()
                .format(DecodeFormat.PREFER_RGB_565)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .skipMemoryCache(false),
        )
    }

    override fun registerComponents(
        context: Context,
        glide: Glide,
        registry: Registry,
    ) {
        // Preserve the app's DNS, cookies, proxy and TLS handling, while isolating optional
        // artwork from catalog/player traffic and their much longer call timeout.
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = ThumbnailRequestBudget.MAX_CONCURRENT_REQUESTS
            maxRequestsPerHost = ThumbnailRequestBudget.MAX_CONCURRENT_REQUESTS
        }
        val client = OkHttp.newClientBuilder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(6, 2, TimeUnit.MINUTES))
            .connectTimeout(ThumbnailRequestBudget.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(ThumbnailRequestBudget.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(ThumbnailRequestBudget.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(client))
    }
}
