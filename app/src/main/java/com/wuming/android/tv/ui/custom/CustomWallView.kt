package com.wuming.android.tv.ui.custom

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.wuming.android.tv.R
import com.wuming.android.tv.databinding.ViewWallBinding
import com.wuming.android.tv.event.ConfigEvent
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.utils.FileUtil
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import pl.droidsonroids.gif.GifDrawable
import java.io.File
import java.io.IOException

open class CustomWallView(
    context: Context,
    attrs: AttributeSet?,
) : FrameLayout(context, attrs),
    DefaultLifecycleObserver {
    private var binding: ViewWallBinding? = null
    private var drawable: GifDrawable? = null
    private var video: PlayerView? = null
    private var player: ExoPlayer? = null
    private var cache: Drawable? = null
    private var initialized = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode && !initialized) init()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (initialized) {
            (context as ComponentActivity).lifecycle.removeObserver(this)
            releaseResources()
            initialized = false
        }
    }

    private fun init() {
        binding = ViewWallBinding.inflate(LayoutInflater.from(context), this, true)
        (context as ComponentActivity).lifecycle.addObserver(this)
        initialized = true
        refresh()
    }

    private fun ensurePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(context).build()
        player!!.repeatMode = Player.REPEAT_MODE_ALL
        player!!.playWhenReady = true
        player!!.mute()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    open fun onConfigEvent(event: ConfigEvent) {
        if (event.type() == ConfigEvent.Type.WALL) refresh()
    }

    private fun refresh() {
        stop()
        load()
    }

    private fun stop() {
        player?.stop()
        player?.clearMediaItems()
        video?.player = null
        video?.visibility = GONE
        drawable?.stop()
        drawable?.recycle()
        drawable = null
        cache = null
    }

    private fun load() {
        val wall = DisplaySettings.getWall()
        val file = FileUtil.getWall(wall)
        cache = Drawable.createFromPath(FileUtil.getWallCache().absolutePath)
        if (DisplaySettings.getWallType() == 0 && wall != 0) {
            loadRes(wall)
        } else if (DisplaySettings.getWallType() == 2) {
            loadVideo(file)
        } else if (DisplaySettings.getWallType() == 1) {
            loadGif(file)
        } else {
            loadImage()
        }
    }

    private fun loadRes(wall: Int) {
        when (wall) {
            1 -> binding!!.image.setImageResource(R.drawable.wallpaper_1)
            2 -> binding!!.image.setImageResource(R.drawable.wallpaper_2)
            3 -> binding!!.image.setImageResource(R.drawable.wallpaper_3)
            4 -> binding!!.image.setImageResource(R.drawable.wallpaper_4)
        }
    }

    private fun loadVideo(file: File) {
        ensurePlayer()
        ensureVideoView()
        video!!.player = player
        video!!.visibility = VISIBLE
        binding!!.image.setImageDrawable(cache)
        player!!.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player!!.prepare()
    }

    private fun loadGif(file: File) {
        binding!!.image.setImageDrawable(cache)
        drawable = gif(file)
        binding!!.image.setImageDrawable(drawable)
    }

    private fun loadImage() {
        if (cache !=
            null
        ) {
            binding!!.image.setImageDrawable(cache)
        } else {
            binding!!.image.setImageResource(R.drawable.wallpaper_1)
        }
    }

    private fun gif(file: File): GifDrawable? =
        try {
            GifDrawable(file)
        } catch (e: IOException) {
            null
        }

    private fun ensureVideoView() {
        if (video != null) return
        video = LayoutInflater.from(context).inflate(R.layout.view_wall_video, this, false) as PlayerView
        // Keep animated wallpapers below the palette overlay just like still images.
        binding!!.root.addView(video, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onCreate(owner: LifecycleOwner) {
        if (!EventBus.getDefault().isRegistered(this)) EventBus.getDefault().register(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        drawable?.start()
        if (player == null || video == null || video!!.visibility != VISIBLE || player!!.mediaItemCount == 0) return
        video!!.player = player
        player!!.play()
    }

    override fun onPause(owner: LifecycleOwner) {
        drawable?.pause()
        if (player == null || video == null || video!!.visibility != VISIBLE || player!!.mediaItemCount == 0) return
        video!!.player = null
        player!!.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        releaseResources()
    }

    private fun releaseResources() {
        if (EventBus.getDefault().isRegistered(this)) EventBus.getDefault().unregister(this)
        drawable?.recycle()
        drawable = null
        if (video != null) {
            video!!.player = null
            binding?.root?.removeView(video)
            video = null
        }
        player?.release()
        player = null
        val b = binding
        if (b != null) {
            b.image.setImageDrawable(null)
            removeView(b.root)
            binding = null
        }
        cache = null
    }
}
