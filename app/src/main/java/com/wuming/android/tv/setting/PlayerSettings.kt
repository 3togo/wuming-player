package com.wuming.android.tv.setting

import com.github.catvod.utils.Prefers

open class PlayerSettings {
    companion object {
        @JvmStatic
        fun getRender(): Int = Prefers.getInt(SettingsSchema.RENDER, 0)

        @JvmStatic
        fun putRender(render: Int) {
            Prefers.put(SettingsSchema.RENDER, render)
        }

        @JvmStatic
        fun getBuffer(): Int = Math.min(Math.max(Prefers.getInt(SettingsSchema.BUFFER), 1), 10)

        @JvmStatic
        fun putBuffer(buffer: Int) {
            Prefers.put(SettingsSchema.BUFFER, buffer)
        }

        @JvmStatic
        fun getSpeed(): Float = Math.min(Math.max(Prefers.getFloat(SettingsSchema.SPEED, 3f), 2f), 5f)

        @JvmStatic
        fun putSpeed(speed: Float) {
            Prefers.put(SettingsSchema.SPEED, speed)
        }

        @JvmStatic
        fun getUa(): String? = Prefers.getString(SettingsSchema.UA)

        @JvmStatic
        fun putUa(ua: String?) {
            Prefers.put(SettingsSchema.UA, ua)
        }

        @JvmStatic
        fun isTunnel(): Boolean = Prefers.getBoolean(SettingsSchema.TUNNEL)

        @JvmStatic
        fun putTunnel(tunnel: Boolean) {
            Prefers.put(SettingsSchema.TUNNEL, tunnel)
        }

        @JvmStatic
        fun isAudioPrefer(): Boolean = Prefers.getBoolean(SettingsSchema.AUDIO_PREFER)

        @JvmStatic
        fun putAudioPrefer(audioPrefer: Boolean) {
            Prefers.put(SettingsSchema.AUDIO_PREFER, audioPrefer)
        }

        @JvmStatic
        fun isVideoPrefer(): Boolean = Prefers.getBoolean(SettingsSchema.VIDEO_PREFER)

        @JvmStatic
        fun putVideoPrefer(videoPrefer: Boolean) {
            Prefers.put(SettingsSchema.VIDEO_PREFER, videoPrefer)
        }

        @JvmStatic
        fun isPreferAAC(): Boolean = Prefers.getBoolean(SettingsSchema.PREFER_AAC)

        @JvmStatic
        fun putPreferAAC(preferAAC: Boolean) {
            Prefers.put(SettingsSchema.PREFER_AAC, preferAAC)
        }

        @JvmStatic
        fun isDanmakuLoad(): Boolean = DanmakuSetting.isLoad()

        @JvmStatic
        fun putDanmakuLoad(danmakuLoad: Boolean) {
            DanmakuSetting.putLoad(danmakuLoad)
        }

        @JvmStatic
        fun isDanmakuShow(): Boolean = DanmakuSetting.isShow()

        @JvmStatic
        fun putDanmakuShow(danmakuShow: Boolean) {
            DanmakuSetting.putShow(danmakuShow)
        }

        @JvmStatic
        fun getSubtitleTextSize(): Float = Prefers.getFloat(SettingsSchema.SUBTITLE_TEXT_SIZE)

        @JvmStatic
        fun putSubtitleTextSize(value: Float) {
            Prefers.put(SettingsSchema.SUBTITLE_TEXT_SIZE, value)
        }

        @JvmStatic
        fun getSubtitlePosition(): Float = Prefers.getFloat(SettingsSchema.SUBTITLE_POSITION)

        @JvmStatic
        fun putSubtitlePosition(value: Float) {
            Prefers.put(SettingsSchema.SUBTITLE_POSITION, value)
        }

        @JvmStatic
        fun isCaption(): Boolean = Prefers.getBoolean(SettingsSchema.CAPTION)

        @JvmStatic
        fun putCaption(caption: Boolean) {
            Prefers.put(SettingsSchema.CAPTION, caption)
        }

        @JvmStatic
        fun getBackground(): Int = Prefers.getInt(SettingsSchema.BACKGROUND, 2)

        @JvmStatic
        fun putBackground(background: Int) {
            Prefers.put(SettingsSchema.BACKGROUND, background)
        }

        @JvmStatic
        fun isBackgroundOff(): Boolean = getBackground() == 0

        @JvmStatic
        fun isBackgroundOn(): Boolean = getBackground() == 1 || getBackground() == 2

        @JvmStatic
        fun isBackgroundPiP(): Boolean = getBackground() == 2

        @JvmStatic
        fun getVideoInfo(): Int = Prefers.getInt(SettingsSchema.VIDEO_INFO, 0)

        @JvmStatic
        fun putVideoInfo(mode: Int) {
            Prefers.put(SettingsSchema.VIDEO_INFO, mode)
        }

        @JvmStatic
        fun showVideoInfo(): Boolean = videoInfoEnabledFor(getVideoInfo())

        @JvmStatic
        fun videoInfoEnabledFor(mode: Int): Boolean = mode == 0 || mode == 1

        @JvmStatic
        fun showVideoClock(): Boolean {
            val mode = getVideoInfo()
            return mode == 0 || mode == 2
        }
    }
}
