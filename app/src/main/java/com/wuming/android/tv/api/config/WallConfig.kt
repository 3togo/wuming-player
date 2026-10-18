package com.wuming.android.tv.api.config

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.wuming.android.tv.App
import com.wuming.android.tv.bean.Config
import com.wuming.android.tv.bean.Rule
import com.wuming.android.tv.event.ConfigEvent
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.utils.Download
import com.wuming.android.tv.utils.FileUtil
import com.wuming.android.tv.utils.ResUtil
import com.wuming.android.tv.utils.UrlUtil
import com.github.catvod.utils.Path
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

internal class WallConfig : BaseConfig() {

    @Volatile
    private var wallSync = false

    fun init(): WallConfig = this

    fun config(config: Config): WallConfig {
        this.config = config
        if (config.isEmpty()) return this
        this.wallSync = config.url == VodConfig.get().wall
        return this
    }

    fun load() {
        if (wallSync) return
        load(Callback())
    }

    override fun getTag(): String = TAG

    override fun defaultConfig(): Config = Config.wall()

    override fun postEvent() {
        super.postEvent()
        ConfigEvent.wall()
    }

    override fun load(config: Config) {
        val file = FileUtil.getWall(0)
        checkUrl(config.url, file)
        setWallType(file)
        setSnapshot(file)
    }

    override fun isLoaded(): Boolean = false

    override fun setRules(rules: List<Rule>) {}

    override fun setAds(ads: List<String>) {}

    private fun checkUrl(url: String, file: File) {
        if (url.startsWith("file")) Path.copy(Path.local(url), file)
        else Download.create(UrlUtil.convert(url), file).tag(TAG).get()
        if (!Path.exists(file)) throw FileNotFoundException()
    }

    private fun setWallType(file: File) {
        DisplaySettings.putWallType(0)
        if (isGif(file)) DisplaySettings.putWallType(1)
        else if (isVideo(file)) DisplaySettings.putWallType(2)
    }

    private fun setSnapshot(file: File) {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) return
        val bitmap = Glide.with(App.get()).asBitmap().frame(0).load(file)
            .override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight())
            .skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE)
            .submit().get()
        FileOutputStream(FileUtil.getWallCache()).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
        }
        bitmap.recycle()
    }

    private fun isVideo(file: File): Boolean {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            return "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO), ignoreCase = true)
        }
    }

    private fun isGif(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            "image/gif" == options.outMimeType
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "WallConfig"

        @Volatile
        private var INSTANCE: WallConfig = WallConfig()

        @JvmStatic
        fun get(): WallConfig = INSTANCE

        @JvmStatic
        fun getUrl(): String = get().getConfig().url.orEmpty()

        @JvmStatic
        fun getDesc(): String = get().getConfig().desc.orEmpty()

        @JvmStatic
        fun load(config: Config, callback: Callback) {
            get().config(config).load(callback)
        }
    }
}
