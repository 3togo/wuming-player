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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    override fun prepare(config: Config, request: Int): PreparedLoad {
        val target = FileUtil.getWall(0)
        val snapshot = FileUtil.getWallCache()
        val staging = Files.createTempDirectory(target.parentFile!!.toPath(), ".wallpaper-").toFile()
        val staged = File(staging, "source")
        try {
            checkUrl(config.url, staged, requestTag(request))
            val type = when {
                isGif(staged) -> 1
                isVideo(staged) -> 2
                else -> 0
            }
            val picture = File(staging, "snapshot.jpg")
            writeSnapshot(staged, picture)
            return PreparedLoad(config, {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(picture.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING)
                DisplaySettings.putWallType(type)
                wallSync = false
            }, {
                staging.deleteRecursively()
            })
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    @Synchronized
    fun installEmbedded(request: Int, embedded: Config) {
        if (!acceptsEmbedded(request, embedded.url)) return
        nextTaskId()
        embedded.ensurePersistedIdentity()
        config = embedded.update()
        wallSync = true
    }

    override fun isLoaded(): Boolean = false

    override fun setRules(rules: List<Rule>) {}

    override fun setAds(ads: List<String>) {}

    private fun checkUrl(url: String, file: File, tag: String) {
        if (url.startsWith("file")) Path.copy(Path.local(url), file)
        else Download.create(UrlUtil.convert(url), file).tag(tag).get()
        if (!Path.exists(file)) throw FileNotFoundException()
    }

    private fun writeSnapshot(file: File, output: File) {
        val manager = Glide.with(App.get())
        val request = manager.asBitmap().frame(0).load(file)
            .override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight())
            .skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE)
            .submit()
        try {
            val bitmap = request.get()
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)) { "Wallpaper snapshot could not be encoded" }
            }
        } finally {
            manager.clear(request)
        }
    }

    private fun isVideo(file: File): Boolean = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO), ignoreCase = true)
        }
    } catch (_: IllegalArgumentException) {
        // Still images are handled by Glide even when the platform retriever rejects them.
        false
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
            get().requestLoad(config, callback)
        }
    }
}
