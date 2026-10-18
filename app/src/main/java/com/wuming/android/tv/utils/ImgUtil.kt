package com.wuming.android.tv.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.wuming.android.tv.App
import com.wuming.android.tv.R
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.impl.CustomTarget
import com.github.catvod.utils.Json
import com.google.common.net.HttpHeaders
import jahirfiquitiva.libs.textdrawable.TextDrawable

object ImgUtil {
    private const val TAG = "ImgUtil"

    private val retries = com.wuming.tv.core.catalog.ThumbnailRetryPolicy<Any> { SystemClock.elapsedRealtime() }

    private class Binding(private val view: ImageView) : View.OnAttachStateChangeListener,
        android.view.ViewTreeObserver.OnPreDrawListener {
        var startedAt = 0L
        var retry: Runnable? = null
        var start: (() -> Unit)? = null
        var pendingStart: Runnable? = null

        fun observeVisibility() {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnPreDrawListener(this)
                view.viewTreeObserver.addOnPreDrawListener(this)
            }
        }

        fun stopObserving() {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
        }

        override fun onPreDraw(): Boolean {
            val action = start ?: return true
            if (!isVisible(view)) return true
            start = null
            stopObserving()
            val task = Runnable {
                pendingStart = null
                if (view.getTag(R.id.tag_image_retry) !== this) return@Runnable
                if (isVisible(view)) action() else {
                    start = action
                    observeVisibility()
                }
            }
            pendingStart = task
            view.post(task)
            return true
        }

        override fun onViewAttachedToWindow(view: View) {
            if (start != null) observeVisibility()
        }

        override fun onViewDetachedFromWindow(view: View) {
            retry?.let(view::removeCallbacks)
            retry = null
            stopObserving()
        }
    }

    private fun isVisible(view: View): Boolean = view.isAttachedToWindow && view.isShown &&
        view.getGlobalVisibleRect(android.graphics.Rect())

    private fun cancelRetry(view: View) {
        val binding = view.getTag(R.id.tag_image_retry) as? Binding ?: return
        binding.retry?.let(view::removeCallbacks)
        binding.pendingStart?.let(view::removeCallbacks)
        binding.start = null
        binding.stopObserving()
        view.removeOnAttachStateChangeListener(binding)
        view.setTag(R.id.tag_image_retry, null)
    }

    private const val LOGO_SIZE_DP = 96
    // Full-screen artwork uses a CustomTarget and therefore cannot derive dimensions from a View.
    private const val MIN_THUMB_WIDTH = 900
    private const val MIN_THUMB_HEIGHT = 1200

    @JvmStatic
    fun logo(view: ImageView) {
        try {
            Glide.with(view)
                .load(UrlUtil.convert(VodConfig.get().getConfig().getLogo()))
                .circleCrop()
                .override(ResUtil.dp2px(LOGO_SIZE_DP), ResUtil.dp2px(LOGO_SIZE_DP))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .error(R.drawable.ic_logo)
                .into(view)
        } catch (e: Throwable) {
            AppLog.e(TAG, e)
        }
    }

    @JvmStatic
    fun clearCache() {
        clearMemory()
        try {
            Task.execute { Glide.get(App.get()).clearDiskCache() }
        } catch (e: Throwable) {
            AppLog.e(TAG, e)
        }
    }

    @JvmStatic
    fun clearMemory() {
        try {
            Glide.get(App.get()).clearMemory()
        } catch (e: Throwable) {
            AppLog.e(TAG, e)
        }
        retries.clear()
    }

    @JvmStatic
    fun clearView(view: View?) {
        if (view == null) return
        cancelRetry(view)
        try {
            Glide.with(view).clear(view)
        } catch (e: Exception) {
            // View may be detached or already cleared; no action needed.
        }
    }

    @JvmStatic
    fun clear(context: Context?, target: Target<*>?) {
        if (context == null) return
        try {
            Glide.with(context).clear(target)
        } catch (e: Exception) {
            // Clearing a target for an already-destroyed context is safe to skip.
        }
    }

    @JvmStatic
    fun load(url: String?, target: CustomTarget<Bitmap>?) {
        if (target == null) return
        try {
            val model = getUrl(url)
            Glide.with(App.get())
                .asBitmap()
                .load(model)
                .override(
                    ResUtil.getDimensionPixelSize(R.dimen.vs_96),
                    ResUtil.getDimensionPixelSize(R.dimen.vs_96)
                )
                .diskCacheStrategy(if (model is GlideUrl) DiskCacheStrategy.ALL else DiskCacheStrategy.RESOURCE)
                .error(R.drawable.artwork)
                .into(target)
        } catch (e: Throwable) {
            AppLog.e(TAG, e)
        }
    }

    @JvmStatic
    fun load(context: Context?, url: String?, target: CustomTarget<Drawable>?) {
        if (context == null || target == null) return
        try {
            val model = getUrl(url)
            Glide.with(context)
                .load(model)
                .override(
                    Math.max(ResUtil.getScreenWidth() / 2, MIN_THUMB_WIDTH),
                    Math.max(ResUtil.getScreenHeight() / 2, MIN_THUMB_HEIGHT)
                )
                .diskCacheStrategy(if (model is GlideUrl) DiskCacheStrategy.ALL else DiskCacheStrategy.RESOURCE)
                .error(R.drawable.artwork)
                .into(target)
        } catch (e: Throwable) {
            AppLog.e(TAG, e)
        }
    }

    @JvmStatic
    fun load(text: String?, url: String?, view: ImageView?) {
        load(text, url, view, true)
    }

    @JvmStatic
    fun load(text: String?, url: String?, view: ImageView?, vod: Boolean) {
        load(text, url, view, vod, false)
    }

    @JvmStatic
    fun loadFit(text: String?, url: String?, view: ImageView?) {
        load(text, url, view, true, true)
    }

    private fun listener(binding: Binding, retryLoad: () -> Unit, alreadyRetried: Boolean) = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            @Nullable e: GlideException?,
            model: Any?,
            @NonNull target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            if (target is com.bumptech.glide.request.target.ImageViewTarget<*>) {
                val view = target.view
                val tag = view.getTag(R.id.tag_image_text)
                val text = tag as? String ?: "！"
                val vod = view.getTag(R.id.tag_image_vod) == true
                view.setImageDrawable(getTextDrawable(text, vod))
            }
            // Glide nests transport failures in rootCauses, not necessarily in Throwable.cause.
            // Keep header sets independent. Retry only transient failures; every failed model
            // observes a cooldown so rebinding cannot create a request storm.
            val status = e?.rootCauses?.filterIsInstance<com.bumptech.glide.load.HttpException>()
                ?.firstOrNull()?.statusCode
            val transient = com.wuming.tv.core.catalog.ThumbnailRetryPolicy.isTransient(
                status, e?.rootCauses?.any { it is java.io.IOException && it !is javax.net.ssl.SSLException } == true
            )
            val imageView = (target as? com.bumptech.glide.request.target.ImageViewTarget<*>)?.view
            val eligible = imageView != null && isVisible(imageView) &&
                imageView.getTag(R.id.tag_image_retry) === binding
            val imageId = Integer.toHexString(model?.hashCode() ?: 0)
            // Offscreen/prefetched cards must not consume the limited visible-card retry slots.
            val delay = if (model is GlideUrl) retries.failed(model, transient && eligible, alreadyRetried) else null
            if (delay != null && imageView != null) {
                val view = imageView
                if (eligible) {
                    val request = target.request
                    val task = Runnable {
                        binding.retry = null
                        if (view.getTag(R.id.tag_image_retry) === binding && isVisible(view) &&
                            target.request === request && request?.isCleared == false) {
                            AppLog.d(TAG, "thumbnail retry started imageId=$imageId")
                            retryLoad()
                        } else {
                            AppLog.d(TAG, "thumbnail retry skipped imageId=$imageId reason=card-no-longer-active")
                        }
                    }
                    binding.retry = task
                    view.postDelayed(task, delay)
                    AppLog.d(TAG, "thumbnail retry scheduled imageId=$imageId delayMs=$delay")
                }
            }
            AppLog.w(TAG, "thumbnail failed imageId=$imageId retried=$alreadyRetried elapsedMs=${SystemClock.elapsedRealtime() - binding.startedAt} " +
                "httpStatus=${status ?: -1} cause=${e?.rootCauses?.firstOrNull()?.javaClass?.simpleName ?: "unknown"}")
            return true
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            @NonNull target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            retries.succeeded(model)
            AppLog.d(TAG, "thumbnail ready imageId=${Integer.toHexString(model.hashCode())} retried=$alreadyRetried " +
                "source=$dataSource elapsedMs=${SystemClock.elapsedRealtime() - binding.startedAt}")
            return false
        }
    }

    private fun load(text: String?, url: String?, view: ImageView?, vod: Boolean, fit: Boolean, alreadyRetried: Boolean = false) {
        if (view == null) return
        cancelRetry(view)
        view.scaleType = if (fit || !vod) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (!vod) view.visibility = if (BeanUtils.isEmpty(url)) View.GONE else View.VISIBLE
        try {
            val model = getUrl(url)
            if (model == null || (!alreadyRetried && retries.isBlocked(model))) {
                // Cancel the previous item's request before assigning a recycled card's fallback.
                Glide.with(view).clear(view)
                view.setImageDrawable(getTextDrawable(text, vod))
                return
            }
            // Equivalent Glide requests can retain the previous listener. A delayed retry
            // needs its own binding and attempt flag for cancellation and accurate tracing.
            if (alreadyRetried) Glide.with(view).clear(view)
            val binding = Binding(view)
            view.setTag(R.id.tag_image_retry, binding)
            view.addOnAttachStateChangeListener(binding)
            view.setTag(R.id.tag_image_text, text)
            view.setTag(R.id.tag_image_vod, vod)
            var builder: RequestBuilder<Drawable> = Glide.with(view)
                .load(model)
                // Preserve remote bytes across grid/detail sizes and the decoded thumbnail for fast revisits.
                .diskCacheStrategy(if (model is GlideUrl) DiskCacheStrategy.ALL else DiskCacheStrategy.RESOURCE)
                .placeholder(getTextDrawable(text, vod))
                .listener(listener(binding, {
                    load(text, url, view, vod, fit, alreadyRetried = true)
                }, alreadyRetried))
            // Before layout, let Glide's ViewTarget wait for valid dimensions rather than overriding with zero.
            if (view.width > 0 && view.height > 0) builder = builder.override(view.width, view.height)

            builder = if (fit || !vod) {
                builder.fitCenter()
            } else {
                builder.centerCrop()
            }

            val requestBuilder = builder
            if (isVisible(view)) {
                binding.startedAt = SystemClock.elapsedRealtime()
                requestBuilder.into(view)
            } else {
                // Cancel a recycled card's previous request; neither adjacent pager pages
                // nor RecyclerView prefetch should occupy thumbnail network slots.
                Glide.with(view).clear(view)
                view.setImageDrawable(getTextDrawable(text, vod))
                binding.start = {
                    binding.startedAt = SystemClock.elapsedRealtime()
                    requestBuilder.into(view)
                }
                binding.observeVisibility()
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, e)
            view.setImageDrawable(getTextDrawable(text, vod))
        }
    }

    @JvmStatic
    fun getUrl(url: String?): Any? {
        if (url == null || BeanUtils.isEmpty(url)) return null
        val localScheme = android.net.Uri.parse(url).scheme
        if ("file".equals(localScheme, ignoreCase = true) ||
            "content".equals(localScheme, ignoreCase = true) ||
            "android.resource".equals(localScheme, ignoreCase = true) ||
            "data".equals(localScheme, ignoreCase = true)
        ) {
            return url
        }
        if ("assets".equals(localScheme, ignoreCase = true)) {
            return "file:///android_asset/" + url.substring(url.indexOf("://") + 3)
        }
        var convertedUrl = UrlUtil.convert(url)
        // Glide's local loaders understand resource/content/file URIs and data models.
        // Only network models should receive HTTP headers or the OkHttp GlideUrl loader.
        val scheme = android.net.Uri.parse(convertedUrl).scheme
        if (!"http".equals(scheme, ignoreCase = true) && !"https".equals(scheme, ignoreCase = true)) {
            return convertedUrl
        }
        val builder = LazyHeaders.Builder()
        var baseUrl = convertedUrl
        val atIdx = convertedUrl.indexOf('@')
        if (atIdx >= 0) {
            baseUrl = convertedUrl.substring(0, atIdx)
            var pos = atIdx
            while (pos < convertedUrl.length) {
                val nextAt = convertedUrl.indexOf('@', pos + 1)
                val segment = if (nextAt < 0) convertedUrl.substring(pos + 1) else convertedUrl.substring(pos + 1, nextAt)
                val eqIdx = segment.indexOf('=')
                if (eqIdx > 0) {
                    val key = segment.substring(0, eqIdx)
                    val value = segment.substring(eqIdx + 1)
                    when (key) {
                        "Headers" -> addHeader(builder, value)
                        "Cookie" -> builder.addHeader(HttpHeaders.COOKIE, value)
                        "Referer" -> builder.addHeader(HttpHeaders.REFERER, value)
                        "User-Agent" -> builder.addHeader(HttpHeaders.USER_AGENT, value)
                    }
                }
                if (nextAt < 0) break
                pos = nextAt
            }
        }
        return if (BeanUtils.isEmpty(baseUrl)) null else GlideUrl(baseUrl, builder.build())
    }

    private fun addHeader(builder: LazyHeaders.Builder, header: String) {
        val map = Json.toMap(Json.parse(header))
        for ((key, value) in map) {
            builder.addHeader(UrlUtil.fixHeader(key), value)
        }
    }

    private fun getTextDrawable(text: String?, vod: Boolean): Drawable {
        val builder = TextDrawable.Builder()
        val firstChar = if (BeanUtils.isEmpty(text)) "！" else text?.substring(0, 1) ?: "！"
        return if (vod) {
            builder.buildRect(firstChar, ColorGenerator.get400(firstChar))
        } else {
            builder.buildRoundRect(
                firstChar,
                ColorGenerator.get400(firstChar),
                ResUtil.getDimensionPixelSize(R.dimen.vs_4)
            )
        }
    }
}